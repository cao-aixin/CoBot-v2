package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（对应表 sys_user）
 *
 * <p>唯一登录凭据是 {@code account}（学号 / 工号）；{@code username} 只作显示昵称使用
 * （可重名）。{@code password} 与 {@code account} 均加 @JsonIgnore，
 * 保证任何接口返回的用户 JSON 都不泄露密码与登录账号。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 用户 ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号（学号/工号，唯一登录凭据；注册后不可改；JSON 序列化时永远隐藏） */
    @JsonIgnore
    private String account;

    /** 显示昵称/姓名（可重名，仅用于展示：聊天、任务、看板等） */
    private String username;

    /** 密码（登录用；JSON 序列化时永远隐藏） */
    @JsonIgnore
    private String password;

    /** 创建时间 */
    private LocalDateTime createTime;
}
