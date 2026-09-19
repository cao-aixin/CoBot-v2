package com.cobot.skill;

import com.cobot.dto.AiTaskParseDTO;
import com.cobot.service.PendingTaskStore;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 一：任务提取工具
 *
 * <p>两条解析路径：
 * <ul>
 *   <li>llm 模式：大模型先从文本中抽取「负责人 / 截止时间 / 任务内容」，再调用
 *       {@link #extractTask} 传结构化参数（Tool Calling 主路径）</li>
 *   <li>local 模式：{@link #extractFromText} 用正则直接解析固定格式
 *       "@CoBot 负责人 时间 事项"（离线兜底路径）</li>
 * </ul>
 * 两条路径识别成功后都会暂存到 {@link PendingTaskStore}，等待用户回复"确认"落库。
 */
@Component
public class TaskExtractSkill {

    /**
     * local 模式正则：匹配格式 "@CoBot 负责人 截止时间 任务内容"
     * 例：@CoBot 张三 周五 完成登录接口
     */
    private static final Pattern PATTERN = Pattern.compile(
            "@CoBot\\s*(?<owner>.+?)\\s*(?<deadline>今天|明天|后天|下?周[一二三四五六日天])\\s*(?<content>.+)");

    /** 文本中可提取的相对天数："3天后" */
    private static final Pattern DAYS_LATER = Pattern.compile("(\\d{1,3})\\s*天[之]?后");
    /** 文本中可提取的绝对日期："9月20日" / "09-20" */
    private static final Pattern MD_DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]");

    @Resource
    private PendingTaskStore pendingTaskStore;

    /**
     * 提取任务信息（注册为大模型可调用的 Tool，llm 模式主路径）
     *
     * <p>注意：参数由大模型从文本中抽取后传入，本方法不做正则二次解析，
     * 只负责把时间表述规范化为日期并暂存待确认任务。
     *
     * @param ownerName     负责人姓名（未指明则传发起人用户名）
     * @param deadlineText  截止时间原始表述（如：这周五 / 明天下午3点 / 3天后 / 2026-09-20；未提及传"未设置"）
     * @param taskContent   任务内容
     * @param priority      优先级：高/普通/低
     * @param teamId        团队 ID（未知传 null）
     * @param requesterName 发起人用户名（未知传 null）
     * @return 解析结果；参数缺失时 taskContent 为 null
     */
    @Tool(description = "提取任务信息并进入待确认状态。调用前请先从文本中抽取负责人、截止时间、任务内容再传参")
    public AiTaskParseDTO extractTask(
            @ToolParam(description = "负责人姓名，从文本中抽取；未指明则传发起人用户名") String ownerName,
            @ToolParam(description = "截止时间的原文表述（如：这周五、明天下午3点、3天后、2026-09-20），未提及传'未设置'") String deadlineText,
            @ToolParam(description = "任务内容") String taskContent,
            @ToolParam(description = "优先级：高/普通/低，未提及传普通", required = false) String priority,
            @ToolParam(description = "当前团队ID，未知传null", required = false) Long teamId,
            @ToolParam(description = "发起任务的用户名，未知传null", required = false) String requesterName) {

        AiTaskParseDTO dto = new AiTaskParseDTO();
        // 大模型必须抽出内容与负责人，缺一则返回空 DTO（上层按"识别不到"处理）
        if (taskContent == null || taskContent.isBlank()
                || ownerName == null || ownerName.isBlank()) {
            return dto;
        }
        dto.setOwnerName(ownerName.trim());
        dto.setTaskContent(taskContent.trim());
        dto.setDeadline(convertDate(deadlineText));
        dto.setPriority(priority == null || priority.isBlank() ? "普通" : priority.trim());
        pendingTaskStore.put(teamId, requesterName, dto);
        return dto;
    }

    /**
     * 文本正则解析（local 模式主路径；llm 模式不经过此方法）
     *
     * @param text 用户输入文本（需含 @CoBot 唤醒词）
     */
    public AiTaskParseDTO extractFromText(String text, Long teamId, String requesterName) {
        AiTaskParseDTO dto = new AiTaskParseDTO();
        Matcher matcher = PATTERN.matcher(text == null ? "" : text);
        if (matcher.find()) {
            // 清洗口语化前缀："@CoBot 帮我安排明天..." -> owner 不能是"帮我安排"
            String owner = matcher.group("owner").trim()
                    .replaceAll("^(帮我安排|麻烦|帮忙|帮我|请|安排|让)+", "").trim();
            if (owner.isEmpty()) {
                // 没写负责人 -> 默认发起人本人
                owner = (requesterName == null || requesterName.isBlank()) ? "未指定" : requesterName;
            }
            dto.setOwnerName(owner);
            dto.setDeadline(convertDate(matcher.group("deadline").trim()));
            dto.setTaskContent(matcher.group("content").trim());
            dto.setPriority("普通");
            // 暂存，等待用户确认
            pendingTaskStore.put(teamId, requesterName, dto);
        }
        return dto;
    }

    /**
     * 时间表述转 yyyy-MM-dd（宽松匹配，支持混合表述如"这周五之前"、"3天后"、"9月20日"）
     * 无法解析时返回 null（任务仍可创建，截止时间留空）
     */
    public String convertDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        LocalDate today = LocalDate.now();

        // 1. 标准日期 yyyy-MM-dd
        if (text.matches(".*\\d{4}-\\d{2}-\\d{2}.*")) {
            return text.replaceAll(".*(\\d{4}-\\d{2}-\\d{2}).*", "$1");
        }
        // 2. 相对天数："3天后"
        Matcher days = DAYS_LATER.matcher(text);
        if (days.find()) {
            return today.plusDays(Integer.parseInt(days.group(1)))
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        // 3. 绝对日期："9月20日"（年份取当年，若已过则顺延一年）
        Matcher md = MD_DATE.matcher(text);
        if (md.find()) {
            LocalDate date = today.withMonth(Integer.parseInt(md.group(1)))
                    .withDayOfMonth(Integer.parseInt(md.group(2)));
            return date.isBefore(today) ? date.plusYears(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                    : date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        // 4. 今天/明天/后天
        if (text.contains("明天")) {
            return today.plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (text.contains("后天")) {
            return today.plusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (text.contains("今天")) {
            return today.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        // 5. 周/星期："周五" / "这周五" / "下周三"（含"周"字即匹配）
        Matcher week = Pattern.compile("(下?)周[一二三四五六日天]").matcher(text);
        if (week.find()) {
            char dayChar = week.group().charAt(week.group().length() - 1);
            LocalDate base = nextWeekday(today, dayChar);
            if ("下".equals(week.group(1))) {
                base = base.plusDays(7);
            }
            return base.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        // 6. 兜底：无法解析 -> null（截止时间留空，不阻塞建任务）
        return null;
    }

    /**
     * 计算今天（不含）之后第一个星期 targetDay 的日期
     *
     * @param c 目标日的简称字：一二三四五六日/天
     */
    private LocalDate nextWeekday(LocalDate today, char c) {
        int target = switch (c) {
            case '一' -> DayOfWeek.MONDAY.getValue();
            case '二' -> DayOfWeek.TUESDAY.getValue();
            case '三' -> DayOfWeek.WEDNESDAY.getValue();
            case '四' -> DayOfWeek.THURSDAY.getValue();
            case '五' -> DayOfWeek.FRIDAY.getValue();
            case '六' -> DayOfWeek.SATURDAY.getValue();
            default -> DayOfWeek.SUNDAY.getValue();
        };
        int diff = target - today.getDayOfWeek().getValue();
        if (diff <= 0) {
            diff += 7;   // 今天或本周已过 -> 下周（"周五"指最近未到的周五）
        }
        return today.plusDays(diff);
    }
}
