package com.cobot.skill;

import com.cobot.entity.SysTask;
import com.cobot.entity.SysUser;
import com.cobot.mapper.SysTaskMapper;
import com.cobot.mapper.SysUserMapper;
import com.cobot.util.PlainTextCleaner;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Skill 二：任务查询工具
 *
 * <p>查询团队任务清单，支持：我的任务 / 未完成任务 / 全部任务。
 * 查询结果以多行文本返回，llm 模式下交给大模型整理措辞，
 * local 模式下直接作为 bot 回复内容。
 */
@Component
public class TaskQuerySkill {

    @Resource
    private SysTaskMapper sysTaskMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    /**
     * 查询任务清单（注册为大模型可调用的 Tool）
     *
     * @param queryType 查询类型：myTask=我的任务 / unfinished=未完成任务 / all=全部任务
     * @param userId    当前用户 ID（查询"我的任务"时用于匹配负责人）
     * @param teamId    团队 ID
     * @return 格式化的任务清单文本；无任务时返回提示
     */
    @Tool(description = "查询用户团队任务清单，支持查询我的任务(myTask)、未完成任务(unfinished)、全部任务(all)")
    public String queryTask(
            @ToolParam(description = "查询类型：myTask/unfinished/all") String queryType,
            @ToolParam(description = "当前用户ID") Long userId,
            @ToolParam(description = "团队ID") Long teamId) {

        LambdaQueryWrapper<SysTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysTask::getTeamId, teamId);

        if ("myTask".equals(queryType)) {
            // "我的任务"：负责人 = 当前用户名（任务表冗余了姓名，直接匹配）
            String username = getUsername(userId);
            wrapper.eq(SysTask::getOwnerName, username == null ? "" : username);
        } else if ("unfinished".equals(queryType)) {
            // 未完成 = 排除已完成与已归档
            wrapper.notIn(SysTask::getTaskStatus, "已完成", "归档");
        }
        wrapper.orderByAsc(SysTask::getDeadline);

        List<SysTask> taskList = sysTaskMapper.selectList(wrapper);
        if (taskList.isEmpty()) {
            return "当前团队暂无" + desc(queryType) + "任务。";
        }

        StringBuilder sb = new StringBuilder("任务清单（").append(desc(queryType)).append("）共 ").append(taskList.size()).append(" 条：\n");
        taskList.forEach(task -> sb
                .append("负责人：").append(task.getOwnerName())
                .append("，任务：").append(task.getTaskContent())
                .append("，状态：").append(task.getTaskStatus())
                .append("，截止：").append(task.getDeadline() == null ? "未设置" : task.getDeadline())
                .append("，优先级：").append(task.getPriority())
                .append("\n"));
        // 输出为纯文本：去掉可能残留的 Markdown / 装饰符号
        return PlainTextCleaner.clean(sb.toString());
    }

    /**
     * 查询类型转中文描述
     */
    private String desc(String queryType) {
        return switch (queryType == null ? "all" : queryType) {
            case "myTask" -> "我的";
            case "unfinished" -> "未完成";
            default -> "全部";
        };
    }

    /**
     * 用户 ID -> 用户名
     */
    private String getUsername(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.getUsername();
    }
}
