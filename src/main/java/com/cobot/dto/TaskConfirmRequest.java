package com.cobot.dto;

import lombok.Data;

/**
 * 确认创建任务请求体
 *
 * <p>AI 识别出的任务先暂存在 PendingTaskStore，
 * 用户回复"确认"（聊天消息）或点击前端"确认创建"按钮（本接口）后正式入库。
 */
@Data
public class TaskConfirmRequest {

    /** 团队（聊天室） ID */
    private Long teamId;
}
