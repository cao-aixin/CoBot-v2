package com.cobot.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 团队视图对象（"我的团队"列表用）
 *
 * <p>比实体多一个 {@code role} 字段：前端据此渲染"负责人 / 成员"身份，
 * 并决定是否显示团队管理、任务指派/删除等负责人专属入口。
 *
 * <p>另附带未读消息数与任务概览，让团队切换列表本身就能当"仪表盘"用。
 */
@Data
public class TeamVO {

    /** 团队 ID */
    private Long id;

    /** 团队名称 */
    private String teamName;

    /** 邀请码（成员可分享） */
    private String inviteCode;

    /** 我在该团队的角色：owner=负责人 / member=普通成员 */
    private String role;

    /** 团队成员数 */
    private Integer memberCount;

    /** 团队公告（可能为空） */
    private String notice;

    /** 邀请码过期时间；null 表示永久有效 */
    private LocalDateTime inviteExpireAt;

    /** 邀请码最大使用次数；0 表示不限 */
    private Integer inviteMaxUse;

    /** 邀请码已使用次数 */
    private Integer inviteUsedCount;

    /** 我在此团队的未读消息数 */
    private Integer unreadCount;

    /** 该团队待办（未完成）任务数 */
    private Integer pendingTaskCount;
}
