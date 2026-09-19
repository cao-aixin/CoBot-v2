package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待确认任务暂存实体（对应表 sys_pending_task）
 *
 * <p>AI / 正则识别出的任务先写这张表暂存，用户回复"确认"后再转正到 sys_task。
 * 落库版相对内存版的价值：<b>服务重启后待审批任务不会凭空消失</b>。
 *
 * <p>唯一键 (team_id, requester_name) 保证"同团队同发起人只保留最近一条"，
 * 与内存版语义一致，同时天然实现多团队隔离。
 */
@Data
@TableName("sys_pending_task")
public class SysPendingTask {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID */
    private Long teamId;

    /** 发起人用户名 */
    private String requesterName;

    /** 任务负责人 */
    private String ownerName;

    /** 任务内容 */
    private String taskContent;

    /** 截止日期（字符串原样保留，确认时再解析成日期） */
    private String deadline;

    /** 优先级 */
    private String priority;

    /** 暂存时间（超过 30 分钟视为过期） */
    private LocalDateTime createTime;
}
