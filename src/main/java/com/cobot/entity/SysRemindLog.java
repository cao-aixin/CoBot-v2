package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提醒日志实体（对应表 sys_remind_log）
 *
 * <p>两类提醒共用本表：
 * <ul>
 *   <li>任务到期提醒：task_id 为真实任务 ID，到期扫描后置 is_push=1</li>
 *   <li>独立定时提醒（"提醒我..."）：task_id = 0，到达 remind_time 后推送并置 is_push=1</li>
 * </ul>
 */
@Data
@TableName("sys_remind_log")
public class SysRemindLog {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联任务 ID；独立提醒（不挂任务）用 0 占位 */
    private Long taskId;

    /** 推送目标团队（聊天室） ID */
    private Long teamId;

    /** 提醒内容（独立提醒保存原话；任务提醒可复用任务内容） */
    private String remindContent;

    /** 提醒触发时间 */
    private LocalDateTime remindTime;

    /** 推送状态：0 未推送，1 已推送 */
    private Integer isPush;
}
