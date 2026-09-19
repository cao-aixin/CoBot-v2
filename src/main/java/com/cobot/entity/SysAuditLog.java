package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志实体（对应表 sys_audit_log）
 *
 * <p>记录"谁在什么时候对哪个团队做了什么" —— 管理类系统的标配能力。
 * 覆盖范围：团队设置变更、成员角色变动、任务增删改审批、AI 产物生成等。
 */
@Data
@TableName("sys_audit_log")
public class SysAuditLog {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属团队（登录等全局动作可为空） */
    private Long teamId;

    /** 操作人用户 ID */
    private Long userId;

    /** 操作人用户名 */
    private String username;

    /** 动作标识，如 TEAM_RENAME / TASK_DELETE / AI_PPT */
    private String action;

    /** 操作对象（任务 ID、被操作用户名等） */
    private String target;

    /** 补充说明 */
    private String detail;

    /** 来源 IP */
    private String ip;

    /** 操作时间 */
    private LocalDateTime createTime;
}
