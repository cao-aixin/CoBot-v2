package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 团队成员关联实体（对应表 sys_team_member）
 *
 * <p>用户与团队的多对多关系。
 */
@Data
@TableName("sys_team_member")
public class SysTeamMember {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID */
    private Long teamId;

    /** 用户 ID */
    private Long userId;

    /** 成员角色：owner=队长（创建者）/ member=普通成员 */
    private String role;

    /** 加入时间 */
    private java.time.LocalDateTime joinTime;
}
