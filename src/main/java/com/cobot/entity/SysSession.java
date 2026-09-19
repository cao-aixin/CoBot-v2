package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录会话实体（对应表 sys_session）
 *
 * <p>相比内存 Map 方案，Token 落库后 <b>服务重启不会把所有人踢下线</b>，
 * 也为后续多实例部署（共用一个库）留好了路子。
 *
 * <p>token 用 {@link IdType#INPUT}：Token 由业务侧生成（UUID 去横线，32 位），不使用自增。
 */
@Data
@TableName("sys_session")
public class SysSession {

    /** 登录令牌（32 位 hex，业务侧生成） */
    @TableId(type = IdType.INPUT)
    private String token;

    /** 用户 ID */
    private Long userId;

    /** 用户名（冗余存储，校验时免联表） */
    private String username;

    /** 过期时间（每次校验通过后滑动续期） */
    private LocalDateTime expireAt;

    /** 签发时间 */
    private LocalDateTime createTime;
}
