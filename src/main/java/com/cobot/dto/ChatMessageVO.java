package com.cobot.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天消息视图对象（返回给前端）
 *
 * <p>在实体基础上补充：
 * <ul>
 *   <li>发送人昵称（用户消息带 username，bot 消息固定为 CoBot）</li>
 *   <li>撤回 / 附件 / @提及 等展示所需字段</li>
 *   <li>{@code mentionMe}：这条消息是否 @ 了当前登录用户（前端据此高亮提醒）</li>
 * </ul>
 */
@Data
public class ChatMessageVO {

    /** 消息 ID */
    private Long id;

    /** 所属团队 ID */
    private Long teamId;

    /** 发送人用户 ID，bot 为 null */
    private Long senderId;

    /** 发送人昵称（用户名 / CoBot） */
    private String senderName;

    /** 发送者类型：0 用户，1 智能体 */
    private Integer senderType;

    /** 消息内容；已撤回时内容被替换为提示文案 */
    private String msgContent;

    /** 是否已撤回：0 否 / 1 是 */
    private Integer revoked;

    /** 消息类型：text / image / file */
    private String msgType;

    /** 附件原始文件名 */
    private String fileName;

    /** 附件字节数 */
    private Long fileSize;

    /** @提及的用户名列表 */
    private List<String> mentions;

    /** 这条消息是否 @ 了当前登录用户 */
    private Boolean mentionMe;

    /** 发送时间 */
    private LocalDateTime createTime;
}
