package com.cobot.dto;

import lombok.Data;

/**
 * AI 任务解析结果 DTO
 *
 * <p>统一承载两种 Agent 模式的任务抽取结果：
 * llm 模式由大模型输出 JSON 反序列化而来；local 模式由正则解析而来。
 * 字段名与大模型约定的 JSON key 严格一致。
 */
@Data
public class AiTaskParseDTO {

    /** 任务内容 */
    private String taskContent;

    /** 负责人姓名 */
    private String ownerName;

    /** 截止日期，统一格式 yyyy-MM-dd（大模型/正则归一化后输出） */
    private String deadline;

    /** 优先级：高 / 普通 / 低 */
    private String priority;
}
