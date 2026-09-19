package com.cobot.dto;

import lombok.Data;

/**
 * 发送聊天消息请求体
 *
 * <p>发送人身份以登录会话为准，{@code userId} 字段仅作兼容保留，服务端不采信。
 *
 * <p>附件类消息（图片 / 文件）的流程：先调 {@code POST /api/chat/upload} 拿到服务端签发的
 * {@code filePath}，再带上下面的附件字段调 {@code /send}；服务端会校验 filePath 归属本团队。
 */
@Data
public class ChatSendRequest {

    /** 团队（聊天室） ID */
    private Long teamId;

    /** 当前发送人用户 ID（服务端不采信，仅向后兼容） */
    private Long userId;

    /** 消息内容（附件消息可为空，此时用文件名兜底） */
    private String content;

    /** 消息类型：text 文本 / image 图片 / file 文件；缺省按 text 处理 */
    private String msgType;

    /** 附件原始文件名（上传接口返回） */
    private String fileName;

    /** 附件相对存储路径（上传接口返回） */
    private String filePath;

    /** 附件字节数（上传接口返回） */
    private Long fileSize;
}
