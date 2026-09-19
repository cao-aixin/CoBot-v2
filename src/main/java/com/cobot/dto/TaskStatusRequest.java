package com.cobot.dto;

import lombok.Data;

/**
 * 更新任务状态请求体
 */
@Data
public class TaskStatusRequest {

    /** 任务 ID */
    private Long taskId;

    /** 目标状态：待确认 / 进行中 / 已完成 / 归档 */
    private String status;
}
