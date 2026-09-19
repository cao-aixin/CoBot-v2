package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 消息已读状态实体（对应表 sys_read_state）
 *
 * <p>每个用户在每个聊天室记录"已读到的最大消息 ID"，
 * 前端据此计算未读红点数量（未读数 = 该室最新消息 ID - last_read_id）。
 */
@Data
@TableName("sys_read_state")
public class SysReadState {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队 ID */
    private Long teamId;

    /** 用户 ID */
    private Long userId;

    /** 已读到的最大消息 ID（0 表示一条未读） */
    private Long lastReadId;
}
