package com.cobot.dto;

import lombok.Data;

/**
 * 聊天消息通用操作请求体（撤回 / 删除用）
 */
@Data
public class ChatMsgRequest {

    /** 消息 ID */
    private Long messageId;
}
