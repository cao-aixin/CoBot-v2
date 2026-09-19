package com.cobot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.dto.AiTaskParseDTO;
import com.cobot.dto.PendingTask;
import com.cobot.entity.SysPendingTask;
import com.cobot.mapper.SysPendingTaskMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 待确认任务暂存器（<b>落库版</b>）
 *
 * <p>设计说明：
 * <ul>
 *   <li>AI / 正则识别出任务后【先暂存不落 sys_task】，回复用户"回复【确认】创建任务"</li>
 *   <li>用户在聊天里回复"确认"，或前端点击"确认创建"按钮，才真正写入 sys_task</li>
 *   <li>多团队隔离：唯一键 (team_id, requester_name) 保证不同团队、同团队不同成员互不干扰，
 *       杜绝 A 团队的待确认任务被 B 团队确认的越权问题</li>
 *   <li>条目 30 分钟自动过期（读取时判定），防止误识别的脏数据长期滞留</li>
 *   <li>相比内存版的最大收益：<b>服务重启 / 重新部署后待确认任务不会丢</b></li>
 * </ul>
 */
@Component
public class PendingTaskStore {

    /** 待确认任务有效期：30 分钟，过期视为不存在 */
    private static final int EXPIRE_MINUTES = 30;

    private final SysPendingTaskMapper pendingTaskMapper;

    public PendingTaskStore(SysPendingTaskMapper pendingTaskMapper) {
        this.pendingTaskMapper = pendingTaskMapper;
    }

    /**
     * 暂存一条解析出的任务（同团队同发起人覆盖旧记录，与内存版语义一致）
     *
     * @param teamId        团队 ID
     * @param requesterName 发起人用户名
     * @param dto           解析结果
     */
    public void put(Long teamId, String requesterName, AiTaskParseDTO dto) {
        // 覆盖式写入：先删同键旧记录，再插新记录（避免依赖 ON DUPLICATE KEY 的方言写法）
        pendingTaskMapper.delete(new LambdaQueryWrapper<SysPendingTask>()
                .eq(SysPendingTask::getTeamId, teamId)
                .eq(SysPendingTask::getRequesterName, requesterName));

        SysPendingTask row = new SysPendingTask();
        row.setTeamId(teamId);
        row.setRequesterName(requesterName);
        row.setOwnerName(dto.getOwnerName());
        row.setTaskContent(dto.getTaskContent());
        row.setDeadline(dto.getDeadline());
        row.setPriority(dto.getPriority());
        row.setCreateTime(LocalDateTime.now());
        pendingTaskMapper.insert(row);
    }

    /**
     * 取出并删除指定团队+发起人的待确认任务（确认后即消费掉）
     *
     * @return 不存在或已过期返回 null
     */
    public PendingTask poll(Long teamId, String requesterName) {
        SysPendingTask row = selectOne(teamId, requesterName);
        if (row == null) {
            return null;
        }
        pendingTaskMapper.deleteById(row.getId());
        return isExpired(row) ? null : toPendingTask(row);
    }

    /**
     * 只查看不移除（前端提示"有待确认任务"时用）
     */
    public PendingTask peek(Long teamId, String requesterName) {
        SysPendingTask row = selectOne(teamId, requesterName);
        return (row == null || isExpired(row)) ? null : toPendingTask(row);
    }

    /**
     * 清理所有已过期的暂存记录（由定时任务调用）
     *
     * @return 清理条数
     */
    public int cleanExpired() {
        return pendingTaskMapper.delete(new LambdaQueryWrapper<SysPendingTask>()
                .lt(SysPendingTask::getCreateTime, LocalDateTime.now().minusMinutes(EXPIRE_MINUTES)));
    }

    private SysPendingTask selectOne(Long teamId, String requesterName) {
        return pendingTaskMapper.selectOne(new LambdaQueryWrapper<SysPendingTask>()
                .eq(SysPendingTask::getTeamId, teamId == null ? 0L : teamId)
                .eq(SysPendingTask::getRequesterName, requesterName == null ? "" : requesterName));
    }

    private boolean isExpired(SysPendingTask row) {
        return row.getCreateTime() != null
                && row.getCreateTime().isBefore(LocalDateTime.now().minusMinutes(EXPIRE_MINUTES));
    }

    /** 数据库行 -> 业务 DTO */
    private PendingTask toPendingTask(SysPendingTask row) {
        AiTaskParseDTO dto = new AiTaskParseDTO();
        dto.setOwnerName(row.getOwnerName());
        dto.setTaskContent(row.getTaskContent());
        dto.setDeadline(row.getDeadline());
        dto.setPriority(row.getPriority());
        PendingTask task = new PendingTask();
        task.setTeamId(row.getTeamId());
        task.setRequesterName(row.getRequesterName());
        task.setParseDTO(dto);
        task.setCreateTime(row.getCreateTime());
        return task;
    }
}
