package com.cobot.dto;

import com.cobot.entity.SysTask;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 待确认任务暂存对象
 *
 * <p>AI/正则识别出任务后先暂存（每个团队只保留最近一条），
 * 等用户回复"确认"后再落库，避免误识别产生脏数据。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PendingTask {

    /** 团队 ID */
    private Long teamId;

    /** 提出任务的用户名（发起人，落库备注用） */
    private String requesterName;

    /** 解析出的任务数据 */
    private AiTaskParseDTO parseDTO;

    /** 暂存时间（超过一定时间未确认可清理） */
    private LocalDateTime createTime;

    /**
     * 转换为任务实体（deadline 由 yyyy-MM-dd 字符串解析，失败则为 null）
     *
     * @param initialStatus 入库初始状态：负责人创建 -> "进行中"；普通成员创建 -> "待确认"（待负责人审批）
     */
    public SysTask toEntity(String initialStatus) {
        SysTask task = new SysTask();
        task.setTeamId(this.teamId);
        task.setOwnerName(parseDTO.getOwnerName());
        task.setTaskContent(parseDTO.getTaskContent());
        task.setPriority(parseDTO.getPriority() == null ? "普通" : parseDTO.getPriority());
        task.setTaskStatus(initialStatus == null ? "进行中" : initialStatus);
        if (parseDTO.getDeadline() != null && parseDTO.getDeadline().matches("\\d{4}-\\d{2}-\\d{2}")) {
            task.setDeadline(java.time.LocalDate.parse(parseDTO.getDeadline()));
        }
        return task;
    }

    /**
     * 转换为任务实体（默认入库为"进行中"状态）
     */
    public SysTask toEntity() {
        return toEntity("进行中");
    }
}
