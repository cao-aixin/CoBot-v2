package com.cobot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.dto.AiTaskParseDTO;
import com.cobot.dto.MeetingParseVO;
import com.cobot.dto.TeamInsightVO;
import com.cobot.dto.WeeklyReportVO;
import com.cobot.entity.AiArtifact;
import com.cobot.entity.ChatMessage;
import com.cobot.entity.SysTask;
import com.cobot.entity.SysTeam;
import com.cobot.entity.SysTeamMember;
import com.cobot.entity.SysUser;
import com.cobot.mapper.AiArtifactMapper;
import com.cobot.mapper.ChatMessageMapper;
import com.cobot.mapper.SysTaskMapper;
import com.cobot.mapper.SysTeamMapper;
import com.cobot.mapper.SysTeamMemberMapper;
import com.cobot.mapper.SysUserMapper;
import com.cobot.util.PlainTextCleaner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 扩展能力服务（AI 工作台第二期）
 *
 * <p>三个高性价比能力：
 * <ol>
 *   <li>{@link #parseMeeting}    会议纪要 -> 结构化任务清单（复用现有任务体系，一键导入看板）</li>
 *   <li>{@link #generateWeekly}  AI 周报（数字来自数据库真实聚合，文字由大模型撰写，可推送到聊天室）</li>
 *   <li>{@link #askTeam}         团队数据问答（"谁手上活最多""还有哪些逾期任务"）</li>
 * </ol>
 *
 * <p>贯穿始终的原则（与团队洞察一致）：<b>数字只来自数据库，大模型只负责表达</b>。
 * 大模型调用失败时一律降级到本地规则，保证任何环境下都能演示。
 */
@Slf4j
@Service
public class AiExtendService {

    /** 生成类型：会议纪要拆任务 */
    public static final String TYPE_MEETING = "meeting";

    /** 生成类型：AI 周报 */
    public static final String TYPE_WEEKLY = "weekly";

    /** 生成类型：团队问答 */
    public static final String TYPE_QA = "qa";

    /** 单次纪要最多拆出的任务数（防止大模型过度发散） */
    private static final int MAX_MEETING_TASKS = 15;

    private final ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Value("${agent.mode:llm}")
    private String agentMode;

    private final TeamStatsService teamStatsService;

    private final AiArtifactMapper aiArtifactMapper;

    private final SysTaskMapper sysTaskMapper;

    private final SysTeamMapper sysTeamMapper;

    private final SysTeamMemberMapper sysTeamMemberMapper;

    private final SysUserMapper sysUserMapper;

    private final ChatMessageMapper chatMessageMapper;

    public AiExtendService(ChatClient.Builder builder,
                           TeamStatsService teamStatsService,
                           AiArtifactMapper aiArtifactMapper,
                           SysTaskMapper sysTaskMapper,
                           SysTeamMapper sysTeamMapper,
                           SysTeamMemberMapper sysTeamMemberMapper,
                           SysUserMapper sysUserMapper,
                           ChatMessageMapper chatMessageMapper) {
        this.chatClient = builder.build();
        this.teamStatsService = teamStatsService;
        this.aiArtifactMapper = aiArtifactMapper;
        this.sysTaskMapper = sysTaskMapper;
        this.sysTeamMapper = sysTeamMapper;
        this.sysTeamMemberMapper = sysTeamMemberMapper;
        this.sysUserMapper = sysUserMapper;
        this.chatMessageMapper = chatMessageMapper;
    }

    // ==================== 一、会议纪要 -> 任务清单 ====================

    /**
     * 解析会议纪要，抽取可执行任务
     *
     * @param teamId 团队 ID
     * @param userId 发起人
     * @param notes  纪要原文
     */
    public MeetingParseVO parseMeeting(Long teamId, Long userId, String notes) {
        MeetingParseVO vo = new MeetingParseVO();
        vo.setTopic(firstLineTopic(notes));
        List<String> memberNames = teamMemberNames(teamId);

        List<AiTaskParseDTO> tasks = "local".equalsIgnoreCase(agentMode)
                ? null : parseTasksByLlm(notes, memberNames);
        if (tasks != null && !tasks.isEmpty()) {
            vo.setAiGenerated(true);
        } else {
            tasks = parseTasksLocal(notes, memberNames);
            vo.setAiGenerated(false);
            if (!"local".equalsIgnoreCase(agentMode)) {
                vo.setFallbackNote("大模型解析失败，已按行拆分为任务草稿，请人工复核负责人与截止时间");
            }
        }
        if (tasks.size() > MAX_MEETING_TASKS) {
            tasks = tasks.subList(0, MAX_MEETING_TASKS);
        }
        // 任务内容展示前统一清洗为纯文本（大模型 / 本地两条路径都覆盖）
        for (AiTaskParseDTO dto : tasks) {
            if (dto != null) {
                dto.setTaskContent(PlainTextCleaner.clean(dto.getTaskContent()));
            }
        }
        vo.setTasks(tasks);

        // 落库：content 存任务数组 JSON，方便"导入看板"时再读出来
        String content = toJson(tasks);
        Long artifactId = saveArtifact(teamId, userId, TYPE_MEETING, vo.getTopic(), content, null);
        vo.setArtifactId(artifactId);
        return vo;
    }

    /**
     * 把纪要里解析出的任务批量导入任务看板
     *
     * @param isOwner 发起人是否为负责人：负责人导入直接"进行中"，成员导入进"待确认"待审批
     * @return 结果提示
     */
    public String importMeetingTasks(Long teamId, Long artifactId, boolean isOwner) {
        AiArtifact artifact = aiArtifactMapper.selectById(artifactId);
        if (artifact == null || !TYPE_MEETING.equals(artifact.getGenType())) {
            return "❌ 找不到该纪要解析记录";
        }
        if (!teamId.equals(artifact.getTeamId())) {
            return "❌ 该记录不属于当前团队";
        }
        List<AiTaskParseDTO> tasks = fromJsonList(artifact.getContent());
        if (tasks.isEmpty()) {
            return "❌ 该纪要没有可导入的任务";
        }
        String status = isOwner ? "进行中" : "待确认";
        int inserted = 0;
        for (AiTaskParseDTO dto : tasks) {
            if (dto.getTaskContent() == null || dto.getTaskContent().isBlank()) {
                continue;
            }
            SysTask task = new SysTask();
            task.setTeamId(teamId);
            task.setOwnerName(dto.getOwnerName() == null || dto.getOwnerName().isBlank()
                    ? "待指派" : dto.getOwnerName());
            task.setTaskContent(dto.getTaskContent());
            task.setPriority(dto.getPriority() == null || dto.getPriority().isBlank() ? "普通" : dto.getPriority());
            task.setTaskStatus(status);
            if (dto.getDeadline() != null && dto.getDeadline().matches("\\d{4}-\\d{2}-\\d{2}")) {
                task.setDeadline(LocalDate.parse(dto.getDeadline()));
            }
            task.setCreateTime(LocalDateTime.now());
            sysTaskMapper.insert(task);
            inserted++;
        }
        if (inserted == 0) {
            return "❌ 没有可导入的有效任务";
        }
        return "✅ 已从纪要导入 " + inserted + " 条任务"
                + ("进行中".equals(status) ? "（直接进入进行中）" : "（状态为待确认，等待负责人审批）");
    }

    /** 大模型解析纪要 -> 任务数组 */
    private List<AiTaskParseDTO> parseTasksByLlm(String notes, List<String> memberNames) {
        String system = """
                你是团队任务管理助手。请从会议纪要中抽取「可执行的任务」，严格输出 JSON 数组，不要任何解释文字。
                数组每一项的字段：
                - taskContent：任务内容，简洁明确，不超过 25 个字
                - ownerName：负责人姓名；只有纪要中明确指定了负责人才填，否则填空字符串
                - deadline：截止日期，格式 yyyy-MM-dd；纪要中没提到就填空字符串
                - priority：优先级，只能是 高 / 普通 / 低 三者之一，默认 普通
                所有字段的值必须是纯文本：禁止包含任何 Markdown 语法符号（如 #、*、_、`、>、|、~），禁止包含 emoji 与装饰性符号。
                今天日期是 %s。
                团队成员名单（ownerName 只能从这个名单里选，选不到就留空）：%s
                """.formatted(LocalDate.now(), String.join("、", memberNames));
        String raw = callLlm(system, "会议纪要如下：\n" + notes);
        if (raw == null) {
            return null;
        }
        return extractJsonArray(raw);
    }

    /** 本地兜底：按行拆分纪要，用成员名单与日期正则补全字段 */
    private List<AiTaskParseDTO> parseTasksLocal(String notes, List<String> memberNames) {
        List<AiTaskParseDTO> tasks = new ArrayList<>();
        if (notes == null || notes.isBlank()) {
            return tasks;
        }
        Pattern datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})|(\\d{1,2})[月/](\\d{1,2})[日号]?");
        for (String rawLine : notes.split("\\r?\\n")) {
            String line = rawLine.replaceAll("^[\\s\\-*•·>#0-9.、)）]+", "").trim();
            if (line.length() < 4 || line.length() > 120) {
                continue;
            }
            AiTaskParseDTO dto = new AiTaskParseDTO();
            dto.setTaskContent(line);
            dto.setPriority(line.contains("紧急") || line.contains("尽快") || line.contains("重要") ? "高" : "普通");
            // 负责人：取行内第一个命中的团队成员名
            for (String name : memberNames) {
                if (name != null && !name.isBlank() && line.contains(name)) {
                    dto.setOwnerName(name);
                    break;
                }
            }
            Matcher m = datePattern.matcher(line);
            if (m.find()) {
                if (m.group(1) != null) {
                    dto.setDeadline(m.group(1));
                } else {
                    int month = Integer.parseInt(m.group(2));
                    int day = Integer.parseInt(m.group(3));
                    dto.setDeadline(LocalDate.now().withMonth(month).withDayOfMonth(day)
                            .format(DateTimeFormatter.ISO_LOCAL_DATE));
                }
            }
            tasks.add(dto);
        }
        return tasks;
    }

    // ==================== 二、AI 周报 ====================

    /**
     * 生成团队周报（数字来自真实聚合，文字由大模型撰写）
     */
    public WeeklyReportVO generateWeekly(Long teamId, Long userId) {
        TeamInsightVO stats = teamStatsService.buildInsight(teamId);
        if (stats == null) {
            return null;
        }
        WeeklyReportVO vo = new WeeklyReportVO();
        vo.setTeamName(stats.getTeamName());
        vo.setPeriodEnd(LocalDate.now());
        vo.setPeriodStart(LocalDate.now().minusDays(6));
        vo.setStats(stats);

        String report = null;
        if (!"local".equalsIgnoreCase(agentMode)) {
            String system = """
                    你是团队管理助理。请基于下面提供的「真实统计数据」撰写一份团队周报。
                    结构要求（使用纯文本，小节标题用中文序号）：
                    一、本周进展
                    二、风险与问题
                    三、下周建议
                    硬性要求：必须使用纯文本，禁止使用任何 Markdown 语法符号（如 #、*、_、`、>、|、~），
                    禁止使用 emoji 与装饰性符号（如 ✅、❌、📊、▍、●）；不要使用表格，
                    指标请用「指标：数值」逐行陈述，列表直接换行或用「1、」这类中文序号；
                    只能使用给定数据，禁止编造任何数字或成员；语气专业简洁，总长度控制在 500 字以内。
                    """;
            report = callLlm(system, "统计数据：\n" + teamStatsService.toPromptText(stats));
        }
        boolean aiGenerated = report != null;
        if (!aiGenerated) {
            report = localWeeklyReport(stats, vo.getPeriodStart(), vo.getPeriodEnd());
        }
        // 落库 / 展示 / 推送前统一清洗为纯文本
        report = PlainTextCleaner.clean(report);
        vo.setReport(report);
        vo.setAiGenerated(aiGenerated);

        Long artifactId = saveArtifact(teamId, userId, TYPE_WEEKLY,
                stats.getTeamName() + " 周报（" + vo.getPeriodStart() + " 至 " + vo.getPeriodEnd() + "）",
                report, null);
        vo.setArtifactId(artifactId);
        return vo;
    }

    /**
     * 把周报推送到团队聊天室（负责人动作）
     *
     * @param teamId     团队 ID
     * @param artifactId 周报产物 ID（从生成历史里挑一条推送）
     */
    public String pushWeeklyToChat(Long teamId, Long artifactId) {
        AiArtifact artifact = aiArtifactMapper.selectById(artifactId);
        if (artifact == null || !TYPE_WEEKLY.equals(artifact.getGenType())) {
            return "❌ 找不到该周报记录";
        }
        if (!teamId.equals(artifact.getTeamId())) {
            return "❌ 该周报不属于当前团队";
        }
        SysTeam team = sysTeamMapper.selectById(teamId);
        if (team == null) {
            return "❌ 团队不存在";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【团队周报】").append(artifact.getTopic() == null ? "本周" : artifact.getTopic()).append("\n");
        // 聊天室气泡里不适合放大段 Markdown，统一调用纯文本清洗工具，彻底去掉 ▍ 之类的替代符号
        sb.append(PlainTextCleaner.clean(artifact.getContent()));
        ChatMessage msg = new ChatMessage();
        msg.setTeamId(teamId);
        msg.setSenderId(null);
        msg.setSenderType(1);
        msg.setMsgContent(sb.toString());
        msg.setMsgType("text");
        msg.setRevoked(0);
        msg.setCreateTime(LocalDateTime.now());
        chatMessageMapper.insert(msg);
        return "✅ 周报已推送到团队聊天室";
    }

    /** 本地规则周报（大模型不可用时；纯文本模板，不含任何 Markdown 语法符号） */
    private String localWeeklyReport(TeamInsightVO s, LocalDate start, LocalDate end) {
        StringBuilder sb = new StringBuilder();
        sb.append("一、本周进展\n");
        sb.append("统计周期：").append(start).append(" 至 ").append(end).append("\n");
        sb.append("任务总数：").append(s.getTotalTasks()).append(" 条，已完成 ")
                .append(s.getDoneTasks()).append(" 条，完成率 ").append(s.getCompletionRate()).append("%\n");
        sb.append("近 7 天新增任务：").append(s.getNewLast7Days()).append(" 条，团队消息 ")
                .append(s.getMsgLast7Days()).append(" 条\n\n");
        sb.append("二、风险与问题\n");
        sb.append("整体风险等级：").append(s.getRiskLevel()).append("\n");
        sb.append("逾期未完成：").append(s.getOverdueTasks()).append(" 条，待审批 ")
                .append(s.getWaitingTasks()).append(" 条\n");
        List<TeamInsightVO.MemberLoad> heavy = s.getMemberLoads().stream()
                .filter(l -> "超载".equals(l.getLoadLevel()) || "偏重".equals(l.getLoadLevel())).toList();
        if (heavy.isEmpty()) {
            sb.append("成员负荷均衡，无超载人员\n\n");
        } else {
            sb.append("负荷偏重成员：")
                    .append(heavy.stream().map(TeamInsightVO.MemberLoad::getUsername)
                            .reduce((a, b) -> a + "、" + b).orElse(""))
                    .append("\n\n");
        }
        sb.append("三、下周建议\n");
        sb.append(s.getOverdueTasks() > 0
                        ? "1、优先清理 " + s.getOverdueTasks() + " 条逾期任务，重新评估截止时间。\n"
                        : "1、无逾期任务，保持当前节奏。\n")
                .append(s.getWaitingTasks() > 0
                        ? "2、负责人尽快审批 " + s.getWaitingTasks() + " 条待确认任务，避免阻塞执行。\n"
                        : "2、任务流转顺畅，无需特别处理。\n")
                .append(heavy.isEmpty()
                        ? "3、分工均衡，可按现有计划推进。\n"
                        : "3、考虑将部分任务转派给空闲成员，缓解负荷。\n");
        sb.append("\n本报告为本地规则生成（大模型不可用），数字均来自数据库真实统计。");
        return sb.toString();
    }

    // ==================== 三、团队数据问答 ====================

    /**
     * 团队数据问答：基于真实统计快照回答用户提问
     *
     * @return { artifactId, answer, aiGenerated }
     */
    public Map<String, Object> askTeam(Long teamId, Long userId, String question) {
        TeamInsightVO stats = teamStatsService.buildInsight(teamId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (stats == null) {
            return null;
        }
        String answer = null;
        if (!"local".equalsIgnoreCase(agentMode)) {
            String system = """
                    你是团队数据助手。只能依据下面给出的真实统计数据回答问题，数据里没有的信息必须明确说"数据中没有相关信息"。
                    回答要简洁（200 字以内），可以用短列表。禁止编造数字或成员姓名。
                    必须使用纯文本：禁止使用任何 Markdown 语法符号（如 #、*、_、`、>、|、~），禁止使用 emoji 与装饰性符号；列表直接换行或用「1、」这类中文序号。
                    """;
            answer = callLlm(system, "统计数据：\n" + teamStatsService.toPromptText(stats)
                    + "\n用户问题：" + question);
        }
        boolean aiGenerated = answer != null;
        if (!aiGenerated) {
            answer = localAnswer(stats, question);
        }
        // 落库 / 展示前统一清洗为纯文本
        answer = PlainTextCleaner.clean(answer);
        Long artifactId = saveArtifact(teamId, userId, TYPE_QA, question, answer, null);
        result.put("artifactId", artifactId);
        result.put("answer", answer);
        result.put("aiGenerated", aiGenerated);
        result.put("stats", stats);
        return result;
    }

    /**
     * 本地兜底问答：覆盖最高频的几个管理问题，其余给出统计摘要
     */
    private String localAnswer(TeamInsightVO s, String question) {
        String q = question == null ? "" : question;
        if (q.contains("逾期") || q.contains("延期") || q.contains("到期")) {
            return s.getOverdueTasks() == 0
                    ? "当前没有逾期未完成的任务。"
                    : "当前有 " + s.getOverdueTasks() + " 条任务逾期未完成，整体风险等级为「" + s.getRiskLevel() + "」，建议优先处理。";
        }
        if (q.contains("谁") && (q.contains("忙") || q.contains("活") || q.contains("负荷") || q.contains("任务最多"))) {
            if (s.getMemberLoads().isEmpty()) {
                return "团队暂无成员负荷数据。";
            }
            TeamInsightVO.MemberLoad top = s.getMemberLoads().get(0);
            return "当前任务最重的是 " + top.getUsername() + "：名下任务 " + top.getTotal()
                    + " 条（进行中 " + top.getDoing() + "，逾期 " + top.getOverdue() + "），负荷等级「" + top.getLoadLevel() + "」。";
        }
        if (q.contains("完成率") || q.contains("进度") || q.contains("多少完成")) {
            return "团队任务完成率为 " + s.getCompletionRate() + "%（已完成 " + s.getDoneTasks()
                    + " / 总数 " + s.getTotalTasks() + "）。";
        }
        if (q.contains("待审批") || q.contains("待确认")) {
            return s.getWaitingTasks() == 0 ? "当前没有待审批任务。"
                    : "当前有 " + s.getWaitingTasks() + " 条任务待负责人审批。";
        }
        if (q.contains("成员") || q.contains("几个人")) {
            return "团队共 " + s.getMemberCount() + " 名成员。";
        }
        return "团队共 " + s.getMemberCount() + " 人，" + s.getTotalTasks() + " 条任务（进行中 "
                + s.getDoingTasks() + "，已完成 " + s.getDoneTasks() + "，待审批 " + s.getWaitingTasks()
                + "），逾期 " + s.getOverdueTasks() + " 条，完成率 " + s.getCompletionRate()
                + "%，整体风险等级「" + s.getRiskLevel() + "」。";
    }

    // ==================== 通用工具 ====================

    /** 调用大模型；失败返回 null，由调用方降级 */
    private String callLlm(String systemPrompt, String userPrompt) {
        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            return (content == null || content.isBlank()) ? null : content.trim();
        } catch (Exception e) {
            log.warn("[CoBot] AI 扩展能力调用大模型失败，将降级处理：{}", e.getMessage());
            return null;
        }
    }

    /** 从大模型回复里抠出 JSON 数组（兼容 ```json 包裹与前后废话） */
    private List<AiTaskParseDTO> extractJsonArray(String raw) {
        try {
            String text = raw.trim();
            int fence = text.indexOf("```");
            if (fence >= 0) {
                int start = text.indexOf('\n', fence);
                int end = text.indexOf("```", start);
                if (start > 0 && end > start) {
                    text = text.substring(start + 1, end).trim();
                }
            }
            int left = text.indexOf('[');
            int right = text.lastIndexOf(']');
            if (left >= 0 && right > left) {
                text = text.substring(left, right + 1);
            }
            List<AiTaskParseDTO> tasks = objectMapper.readValue(text, new TypeReference<>() {});
            tasks.removeIf(t -> t.getTaskContent() == null || t.getTaskContent().isBlank());
            return tasks;
        } catch (Exception e) {
            log.warn("[CoBot] 纪要任务 JSON 解析失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<AiTaskParseDTO> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[CoBot] 产出的任务 JSON 反序列化失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 纪要主题：取第一行非空内容，最长 40 字 */
    private String firstLineTopic(String notes) {
        if (notes == null) {
            return "会议纪要";
        }
        for (String line : notes.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                return t.length() > 40 ? t.substring(0, 40) : t;
            }
        }
        return "会议纪要";
    }

    /** 团队成员用户名列表 */
    private List<String> teamMemberNames(Long teamId) {
        List<Long> userIds = sysTeamMemberMapper.selectList(new LambdaQueryWrapper<SysTeamMember>()
                        .eq(SysTeamMember::getTeamId, teamId))
                .stream().map(SysTeamMember::getUserId).toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(userIds.size());
        for (SysUser u : sysUserMapper.selectBatchIds(userIds)) {
            names.add(u.getUsername());
        }
        return names;
    }

    /** 落库产物；PPT 类才有文件 */
    private Long saveArtifact(Long teamId, Long userId, String genType, String topic,
                              String content, String filePath) {
        AiArtifact artifact = new AiArtifact();
        artifact.setTeamId(teamId);
        artifact.setUserId(userId);
        artifact.setGenType(genType);
        artifact.setTopic(topic == null ? null : (topic.length() > 190 ? topic.substring(0, 190) : topic));
        artifact.setContent(content);
        artifact.setFilePath(filePath);
        artifact.setCreateTime(LocalDateTime.now());
        aiArtifactMapper.insert(artifact);
        return artifact.getId();
    }
}
