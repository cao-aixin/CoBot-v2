package com.cobot.dto;

import lombok.Data;

/**
 * 标记已读请求体
 */
@Data
public class ChatReadRequest {

    /** 团队（聊天室） ID */
    private Long teamId;

    /** 已读到的最大消息 ID */
    private Long lastReadId;
}
