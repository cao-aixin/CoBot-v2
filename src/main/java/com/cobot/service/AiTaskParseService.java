package com.cobot.service;

import com.cobot.dto.AiTaskParseDTO;
import com.cobot.dto.PendingTask;
import com.cobot.skill.RemindSkill;
import com.cobot.skill.TaskExtractSkill;
import com.cobot.skill.TaskQuerySkill;
import com.cobot.util.PlainTextCleaner;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 模式智能体（SpringAI Tool Calling）
 *
 * <p>核心流程：用户输入 -> ChatClient 携带 System Prompt + 三个 Skill 工具
 * -> 大模型自主决定调用哪个工具（任务提取/查询/提醒）-> 汇总工具结果生成回复。
 *
 * <p>模型接入：OpenAI 兼容协议（可对接豆包 Ark / 通义 DashScope，见 application.yml）。
 * SpringAI 1.0.0 中，@Tool 注解组件直接传给 ChatClient 的 .tools(Object...) 即可完成方法级注册。
 */
@Service
public class AiTaskParseService {

    private final ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private TaskExtractSkill taskExtractSkillRef;

    @Resource
    private TaskQuerySkill taskQuerySkillRef;

    @Resource
    private RemindSkill remindSkillRef;

    @Resource
    private PendingTaskStore pendingTaskStore;

    /**
     * System Prompt：约束大模型只能基于工具结果回答，禁止编造
     */
    private final String systemPrompt = """
            你是CoBot团队协作智能体，负责协助团队进行任务管理。你拥有调用工具的能力，请严格遵守规则：
            1. 分析用户输入，判断用户意图，自动选择调用对应的工具。
            2. 用户需要创建任务，调用【任务提取工具 extractTask】：先由你从文本中抽取负责人、截止时间（原文表述，如"这周五"）、任务内容，再作为参数传入；用户查询任务，调用【任务查询工具 queryTask】；用户设置提醒，调用【定时提醒工具 createRemind】。
            3. 调用任务提取工具获取任务信息之后，整理信息返回给用户，提示用户回复【确认】完成创建；或点击任务看板的"确认创建"按钮。
            4. 如果任务信息缺失（缺少负责人或任务内容），主动询问用户补齐信息，不要调用工具。
            5. 禁止编造不存在的任务、人员、时间，不随意猜测信息；工具返回什么就用什么。
            6. 设置提醒时，createRemind 的 remindTime 参数必须基于下方上下文中给出的当前时间换算相对时间表述（如"半小时后"、"2分钟后"），格式 yyyy-MM-dd HH:mm，禁止凭空猜测。
            7. 回答简洁，适合聊天窗口展示，不要多余的长篇描述。
            8. 回复必须是纯文本：禁止使用任何 Markdown 语法符号（如 #、*、_、`、>、|、~），禁止使用 emoji 与装饰性符号（如 ✅、❌、📊、▍、●）。
            """;

    public AiTaskParseService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 组装工具组件（SpringAI 1.0.0：@Tool 组件直接传入 .tools(...)）
     */
    private Object[] tools() {
        return new Object[]{ taskExtractSkillRef, taskQuerySkillRef, remindSkillRef };
    }

    /**
     * 结构化任务解析：要求大模型只输出 JSON，再反序列化为 DTO
     *
     * <p>注意：大模型回复可能带 ```json 代码围栏，已做剥离处理。
     */
    public AiTaskParseDTO parseTaskFromText(String userText, Long teamId, String requesterName) {
        String jsonPrompt = """
                请从下面的团队聊天文本中提取任务信息，只输出一个JSON对象，不要输出任何解释：
                {"taskContent":"任务内容","ownerName":"负责人","deadline":"yyyy-MM-dd","priority":"高|普通|低"}
                识别不到任务时输出：{}。今天是%s。
                文本：%s
                """.formatted(java.time.LocalDate.now(), userText);
        String jsonRes = chatClient.prompt()
                .system(systemPrompt)
                .user(jsonPrompt)
                .call()
                .content();
        return parseJson(jsonRes, teamId, requesterName);
    }

    /**
     * 带 Tool Calling 的聊天：大模型自主选择工具并生成最终回复
     *
     * @param userText 用户输入
     * @param userId   当前用户 ID（拼入上下文，供工具调用使用）
     * @param teamId   当前团队 ID
     * @param requesterName 当前用户名
     */
    public String chatWithTool(String userText, Long userId, Long teamId, String requesterName) {
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String contextPrompt = "[上下文：当前用户ID=%s，用户名=%s，团队ID=%s，当前时间=%s] %s"
                .formatted(userId, requesterName, teamId, now, userText);
        String reply = chatClient.prompt()
                .system(systemPrompt)
                .user(contextPrompt)
                .tools(tools())
                .call()
                .content();
        // 保持"无法理解返回 null"的契约：空回复交由上层决定是否回复
        if (reply == null || reply.isBlank()) {
            return null;
        }
        // 大模型不一定听话，返回前统一清洗为纯文本
        return PlainTextCleaner.clean(reply);
    }

    /**
     * 解析大模型返回的 JSON（剥离代码围栏 + 异常容错）
     */
    private AiTaskParseDTO parseJson(String raw, Long teamId, String requesterName) {
        try {
            // 剥离 ```json ... ``` 围栏
            Matcher m = Pattern.compile("\\{[\\s\\S]*\\}").matcher(raw);
            String json = m.find() ? m.group() : raw;
            AiTaskParseDTO dto = objectMapper.readValue(json, AiTaskParseDTO.class);
            // 识别到任务则按"团队+发起人"隔离暂存，等待用户确认
            if (dto.getTaskContent() != null) {
                pendingTaskStore.put(teamId, requesterName, dto);
            }
            return dto;
        } catch (Exception e) {
            // JSON 解析失败不抛异常，返回空 DTO（上层按"识别不到"处理）
            return new AiTaskParseDTO();
        }
    }
}
