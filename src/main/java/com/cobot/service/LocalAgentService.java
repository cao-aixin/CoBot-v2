package com.cobot.service;

import com.cobot.dto.AiTaskParseDTO;
import com.cobot.skill.RemindSkill;
import com.cobot.skill.TaskExtractSkill;
import com.cobot.skill.TaskQuerySkill;
import com.cobot.util.PlainTextCleaner;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 本地规则智能体（无大模型，可完全离线运行）
 *
 * <p>按关键词 + 正则做意图路由：
 * <ul>
 *   <li>"我的任务" / "未完成任务" / "全部任务" -> 任务查询工具</li>
 *   <li>"提醒我..." -> 定时提醒工具</li>
 *   <li>"@CoBot 负责人 时间 事项" -> 任务提取工具</li>
 *   <li>其他 -> 返回 null（上层不回复，保持聊天室安静）</li>
 * </ul>
 */
@Service
public class LocalAgentService {

    @Resource
    public TaskExtractSkill taskExtractSkill;

    @Resource
    private TaskQuerySkill taskQuerySkill;

    @Resource
    private RemindSkill remindSkill;

    /**
     * 结构化任务解析（local 模式：直接走提取工具的正则）
     */
    public AiTaskParseDTO parseTaskFromText(String userText, Long teamId, String requesterName) {
        return taskExtractSkill.extractFromText(userText, teamId, requesterName);
    }

    /**
     * 关键词意图分发
     */
    public String dispatchSkill(String userText, Long userId, Long teamId, String requesterName) {
        if (userText == null || userText.isBlank()) {
            return null;
        }
        // 1. 任务查询类（放在 @CoBot 之前：自然语言查询如"@CoBot 查一下有哪些任务"也要命中）
        if (userText.contains("我的任务")) {
            return PlainTextCleaner.clean(taskQuerySkill.queryTask("myTask", userId, teamId));
        }
        if (userText.contains("未完成任务")) {
            return PlainTextCleaner.clean(taskQuerySkill.queryTask("unfinished", userId, teamId));
        }
        // 查询意图：提到任务 + 查/哪些/列表/几个 等疑问词（如"查一下团队现在有哪些任务"）
        boolean askQuery = userText.contains("全部任务")
                || (userText.contains("任务") && (userText.contains("查") || userText.contains("哪些")
                        || userText.contains("列表") || userText.contains("几个")));
        if (askQuery) {
            return PlainTextCleaner.clean(taskQuerySkill.queryTask("all", userId, teamId));
        }
        // 2. 定时提醒类
        if (userText.contains("提醒我") || userText.contains("提醒大家")) {
            return PlainTextCleaner.clean(remindSkill.createRemind(userText, null, teamId));
        }
        // 3. 任务提取类（@CoBot 唤醒，正则解析）
        if (userText.contains("@CoBot")) {
            AiTaskParseDTO dto = taskExtractSkill.extractFromText(userText, teamId, requesterName);
            if (dto.getTaskContent() != null) {
                String reply = "识别到任务：负责人：" + dto.getOwnerName()
                        + "，截止：" + dto.getDeadline()
                        + "，内容：" + dto.getTaskContent()
                        + "。回复【确认】创建任务，或到任务看板点击\"确认创建\"。";
                return PlainTextCleaner.clean(reply);
            }
            return "无法识别任务格式，请按格式输入：@CoBot 张三 周五 完成登录接口";
        }
        // 4. 兜底：不响应
        return null;
    }
}
