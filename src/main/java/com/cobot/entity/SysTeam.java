package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 团队实体（对应表 sys_team）
 *
 * <p>一个团队相当于一个"聊天室"，聊天消息与任务都挂在团队维度下。
 */
@Data
@TableName("sys_team")
public class SysTeam {

    /** 团队 ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队名称 */
    private String teamName;

    /** 邀请码（6 位，成员分享后他人凭码加入团队） */
    private String inviteCode;

    /** 邀请码过期时间；null 表示永久有效 */
    private LocalDateTime inviteExpireAt;

    /** 邀请码最大使用次数；0 表示不限 */
    private Integer inviteMaxUse;

    /** 邀请码已使用次数 */
    private Integer inviteUsedCount;

    /** 团队公告（展示在聊天室顶部） */
    private String notice;

    /** 创建时间 */
    private LocalDateTime createTime;
}
