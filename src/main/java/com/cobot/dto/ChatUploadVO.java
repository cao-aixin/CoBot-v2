package com.cobot.dto;

import lombok.Data;

/**
 * 附件上传响应体
 *
 * <p>前端拿到这些字段后，再调 {@code /api/chat/send} 把它作为一条附件消息发出去。
 * filePath 由服务端签发，发送时会校验归属，前端不能伪造。
 */
@Data
public class ChatUploadVO {

    /** 原始文件名 */
    private String fileName;

    /** 服务端存储相对路径（发消息时回传） */
    private String filePath;

    /** 字节数 */
    private Long fileSize;

    /** 推断出的消息类型：image 图片 / file 文件 */
    private String msgType;
}
