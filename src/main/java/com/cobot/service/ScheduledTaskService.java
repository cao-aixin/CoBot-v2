package com.cobot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.entity.ChatMessage;
import com.cobot.entity.SysRemindLog;
import com.cobot.entity.SysTask;
import com.cobot.mapper.ChatMessageMapper;
import com.cobot.mapper.SysRemindLogMapper;
import com.cobot.mapper.SysTaskMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时提醒服务
 *
 * <p>职责（按修改文档开发任务清单第 9 条）：
 * <ol>
 *   <li>项目启动时加载未到期任务（@PostConstruct，预热 + 日志确认）</li>
 *   <li>每分钟扫描：任务到期/即将到期 -> 推送提醒到聊天室；独立提醒到时 -> 推送</li>
 *   <li>is_push 标记防重复推送（单实例部署，等价于旧版 Redis 锁的防重复语义）</li>
 * </ol>
 */
@Slf4j
@Service
public class ScheduledTaskService {

    @Resource
    private SysTaskMapper sysTaskMapper;

    @Resource
    private SysRemindLogMapper sysRemindLogMapper;

    @Resource
    private ChatMessageMapper chatMessageMapper;

    @Resource
    private AuthService authService;

    @Resource
    private PendingTaskStore pendingTaskStore;

    /** 扫描周期（yml 配置，默认每分钟） */
    @Value("${cobot.remind.cron:0 * * * * ?}")
    private String remindCron;

    /**
     * 项目启动：加载所有未到期任务，日志确认数量（按文档要求实现）
     */
    @PostConstruct
    public void loadPendingTasks() {
        Long count = sysTaskMapper.selectCount(new LambdaQueryWrapper<SysTask>()
                .notIn(SysTask::getTaskStatus, "已完成", "归档"));
        log.info("[CoBot] 启动加载未完成/未到期任务，数量：{}", count);
    }

    /**
     * 创建一条独立定时提醒（RemindSkill 调用）
     *
     * @param remindContent 提醒内容（用户原话）
     * @param remindTime    提醒时间字符串（yyyy-MM-dd HH:mm；为空/模糊时按内容解析，再兜底+1小时）
     * @param teamId        推送目标聊天室
     */
    public String createRemind(String remindContent, String remindTime, Long teamId) {
        LocalDateTime triggerTime = parseRemindTime(remindTime, remindContent);
        SysRemindLog remindLog = new SysRemindLog();
        remindLog.setTaskId(0L);   // 独立提醒不挂任务
        remindLog.setTeamId(teamId == null ? 1L : teamId);
        remindLog.setRemindContent(remindContent);
        remindLog.setRemindTime(triggerTime);
        remindLog.setIsPush(0);
        sysRemindLogMapper.insert(remindLog);
        return "⏰ 提醒创建成功：" + remindContent
                + "，将在 " + triggerTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " 推送到本聊天室。";
    }

    /**
     * 定时扫描：每分钟执行一次
     */
    @Scheduled(cron = "${cobot.remind.cron:0 * * * * ?}")
    public void scanAndRemind() {
        LocalDateTime now = LocalDateTime.now();
        scanTaskDeadline(now);      // 任务到期/今日到期提醒
        scanStandaloneRemind(now);  // 独立定时提醒
    }

    /**
     * 定时清理：过期登录会话 + 过期待确认任务
     *
     * <p>会话与待确认任务如今都落库了，需要有人把「过期垃圾」扫走：
     * <ul>
     *   <li>逾期会话：安全上越早清越好（避免 Token 表无限膨胀）</li>
     *   <li>过期待确认任务：30 分钟没确认的识别结果属于脏数据，清掉避免误创建</li>
     * </ul>
     * 每 30 分钟跑一次，避开整点提醒高峰。
     */
    @Scheduled(cron = "0 7,37 * * * ?")
    public void cleanExpiredData() {
        int sessions = authService.cleanExpiredSessions();
        int pendings = pendingTaskStore.cleanExpired();
        if (sessions > 0 || pendings > 0) {
            log.info("[CoBot] 清理完成：过期会话 {} 条，过期待确认任务 {} 条", sessions, pendings);
        }
    }

    /**
     * 任务提醒：今天到期 或 已过期未完成的任务，每天只提醒一次
     */
    private void scanTaskDeadline(LocalDateTime now) {
        LocalDate today = LocalDate.now();
        // 查询所有任务，逐条按各自的 teamId / deadline 判断并推送
        List<SysTask> tasks = sysTaskMapper.selectList(null);
        for (SysTask task : tasks) {
            // 已完成/归档/待审批（尚未开始执行）的任务不提醒
            if ("已完成".equals(task.getTaskStatus()) || "归档".equals(task.getTaskStatus())
                    || "待确认".equals(task.getTaskStatus())) {
                continue;
            }
            boolean dueToday = task.getDeadline() != null && task.getDeadline().isEqual(today);
            boolean overdue = task.getDeadline() != null && task.getDeadline().isBefore(today);
            if (!dueToday && !overdue) {
                continue;   // 未到期
            }
            // 当天已提醒过则跳过（is_push 防重复）
            boolean reminded = sysRemindLogMapper.selectCount(new LambdaQueryWrapper<SysRemindLog>()
                    .eq(SysRemindLog::getTaskId, task.getId())
                    .ge(SysRemindLog::getRemindTime, today.atStartOfDay())) > 0;
            if (reminded) {
                continue;
            }
            // 记录提醒日志
            SysRemindLog remindLog = new SysRemindLog();
            remindLog.setTaskId(task.getId());
            remindLog.setTeamId(task.getTeamId());
            remindLog.setRemindContent(task.getTaskContent());
            remindLog.setRemindTime(now);
            remindLog.setIsPush(1);
            sysRemindLogMapper.insert(remindLog);
            // 推送到对应团队聊天室
            String text = (overdue ? "⏰【逾期提醒】" : "⏰【今日到期】")
                    + "任务 #" + task.getId() + " " + task.getTaskContent()
                    + "（负责人：" + task.getOwnerName()
                    + "，截止：" + task.getDeadline() + "）请及时处理！";
            pushBotMessage(task.getTeamId(), text);
        }
    }

    /**
     * 独立提醒：remind_time 已到且未推送 -> 推送并置 is_push=1
     */
    private void scanStandaloneRemind(LocalDateTime now) {
        List<SysRemindLog> logs = sysRemindLogMapper.selectList(new LambdaQueryWrapper<SysRemindLog>()
                .eq(SysRemindLog::getIsPush, 0)
                .le(SysRemindLog::getRemindTime, now));
        for (SysRemindLog remindLog : logs) {
            remindLog.setIsPush(1);
            sysRemindLogMapper.updateById(remindLog);
            if (remindLog.getTaskId() == 0L) {
                // 独立提醒：推送到创建时指定的团队聊天室
                pushBotMessage(remindLog.getTeamId(), "⏰【定时提醒】" + remindLog.getRemindContent());
            }
        }
    }

    /**
     * 推送一条 bot 消息到指定聊天室
     */
    public void pushBotMessage(Long teamId, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setTeamId(teamId);
        msg.setSenderId(null);
        msg.setSenderType(1);
        msg.setMsgContent(content);
        msg.setCreateTime(LocalDateTime.now());
        chatMessageMapper.insert(msg);
        log.info("[CoBot] 提醒已推送 teamId={} : {}", teamId, content);
    }

    /**
     * 解析提醒时间（优先级从高到低）：
     * 1) 内容中的相对时间（"N分钟后 / N小时后 / 半小时后"）——最可靠，直接以当前时间推算
     * 2) LLM 传入的标准格式 yyyy-MM-dd HH:mm
     * 3) 内容中的绝对时刻（"明天9点 / 今天18:30"等）
     * 4) 都失败 -> 默认 1 小时后
     */
    private LocalDateTime parseRemindTime(String remindTime, String content) {
        String text = (content == null ? "" : content);
        // 1. 相对时间优先：防止大模型把"半小时后"换算错
        if (text.contains("半小时后") || text.contains("半个小时后")) {
            return LocalDateTime.now().plusMinutes(30);
        }
        Matcher rel = Pattern.compile("(\\d{1,3})\\s*个?(?:小时|分钟|分)后").matcher(text);
        if (rel.find()) {
            long n = Long.parseLong(rel.group(1));
            return text.contains("小时")
                    ? LocalDateTime.now().plusHours(n)
                    : LocalDateTime.now().plusMinutes(n);
        }
        // 2. 标准格式 yyyy-MM-dd HH:mm（llm 换算后的绝对时间）
        if (remindTime != null && remindTime.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
            return LocalDateTime.parse(remindTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        // 3. 从文本解析绝对时刻：(今天|明天)? hh[点:：]mm分?
        String merged = (remindTime == null ? "" : remindTime) + " " + text;
        Matcher m = Pattern.compile("(今天|明天)?\\s*(\\d{1,2})[点:：]((\\d{1,2})分?)?").matcher(merged);
        if (m.find()) {
            LocalDateTime base = "明天".equals(m.group(1))
                    ? LocalDate.now().plusDays(1).atStartOfDay()
                    : LocalDate.now().atStartOfDay();
            int hour = Integer.parseInt(m.group(2));
            int minute = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
            LocalDateTime t = base.withHour(hour).withMinute(minute);
            // 今天的时间已过 -> 顺延到明天
            return t.isBefore(LocalDateTime.now()) ? t.plusDays(1) : t;
        }
        // 4. 兜底：1 小时后
        return LocalDateTime.now().plusHours(1);
    }
}
