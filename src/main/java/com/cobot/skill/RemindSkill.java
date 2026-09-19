package com.cobot.skill;

import com.cobot.service.ScheduledTaskService;
import com.cobot.util.PlainTextCleaner;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Skill 三：定时提醒工具
 *
 * <p>创建定时提醒任务，到期后在团队聊天室推送 bot 消息
 * （V2 为网页聊天室形态，提醒直接推送到聊天消息流）。
 */
@Component
public class RemindSkill {

    @Resource
    private ScheduledTaskService scheduledTaskService;

    /**
     * 创建定时提醒（注册为大模型可调用的 Tool）
     *
     * @param remindContent 提醒内容
     * @param remindTime    提醒时间，格式 yyyy-MM-dd HH:mm；表述模糊时由 ScheduledTaskService 兜底解析
     * @param teamId        团队 ID（提醒消息推送到该聊天室）
     * @return 创建结果描述
     */
    @Tool(description = "创建定时提醒任务，到期在群聊推送消息")
    public String createRemind(
            @ToolParam(description = "提醒内容") String remindContent,
            @ToolParam(description = "提醒时间，格式yyyy-MM-dd HH:mm，如2026-09-18 09:00") String remindTime,
            @ToolParam(description = "团队ID") Long teamId) {

        // 返回给聊天室的文本统一清洗为纯文本（去掉 ⏰ 之类的装饰符号）
        return PlainTextCleaner.clean(scheduledTaskService.createRemind(remindContent, remindTime, teamId));
    }
}
