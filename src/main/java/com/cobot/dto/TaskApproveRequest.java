package com.cobot.dto;

/**
 * 任务审批请求（仅团队负责人可用，用于审批普通成员提交的"待确认"任务）
 *
 * @param taskId   任务 ID
 * @param approved true=批准（转进行中）/ false=驳回（转归档）
 */
public record TaskApproveRequest(Long taskId, Boolean approved) {
}
