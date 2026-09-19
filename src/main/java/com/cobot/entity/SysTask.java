package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 任务实体（对应表 sys_task）
 *
 * <p>由智能体从聊天文本中识别（或用户手动创建）的团队任务。
 */
@Data
@TableName("sys_task")
public class SysTask {

    /** 任务 ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属团队 ID */
    private Long teamId;

    /** 负责人姓名 */
    private String ownerName;

    /** 任务内容 */
    private String taskContent;

    /** 截止日期 */
    private LocalDate deadline;

    /** 优先级：高 / 普通 / 低 */
    private String priority;

    /** 任务状态：待确认、进行中、已完成、归档 */
    private String taskStatus;

    /** 创建时间 */
    private LocalDateTime createTime;
}
