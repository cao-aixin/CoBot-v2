package com.cobot.dto;

/**
 * 任务指派请求（仅团队负责人可用）
 *
 * @param taskId    任务 ID
 * @param ownerName 被指派成员的用户名（须为本团队成员）
 */
public record TaskAssignRequest(Long taskId, String ownerName) {
}
