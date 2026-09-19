package com.cobot.dto;

/**
 * 仅含任务 ID 的请求（删除任务等操作用）
 *
 * @param taskId 任务 ID
 */
public record TaskIdRequest(Long taskId) {
}
