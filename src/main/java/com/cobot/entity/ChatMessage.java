package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息实体（对应表 chat_message）
 *
 * <p>网页聊天室的消息记录，用户消息与智能体（bot）消息统一存储：
 * <ul>
 *   <li>senderType = 0：用户消息，senderId 为发送人用户 ID</li>
 *   <li>senderType = 1：智能体消息，senderId 为 null</li>
 * </ul>
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    /** 消息 ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属团队（聊天室） ID */
    private Long teamId;

    /** 发送人用户 ID，bot 消息为 null */
    private Long senderId;

    /** 发送者类型：0 用户，1 智能体 */
    private Integer senderType;

    /** 消息内容（撤回后前端展示"消息已撤回"，原文仍保留在库中便于追溯） */
    private String msgContent;

    /** 是否已撤回：0 否 / 1 是 */
    private Integer revoked;

    /** 消息类型：text 文本 / image 图片 / file 文件 */
    private String msgType;

    /** 附件原始文件名（image / file 类型才有） */
    private String fileName;

    /** 附件存储相对路径（文件走磁盘，库中只存路径，避免大字段拖慢查询） */
    private String filePath;

    /** 附件字节数 */
    private Long fileSize;

    /** @提及的用户名，逗号分隔（前端据此高亮 + 触发提醒） */
    private String mentions;

    /** 发送时间 */
    private LocalDateTime createTime;
}
