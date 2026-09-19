package com.cobot.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * AI 周报结果
 *
 * <p>数字部分（stats）来自数据库真实聚合，文字部分（report）由大模型基于这些数字撰写。
 */
@Data
public class WeeklyReportVO {

    /** 本次生成落库的产物 ID */
    private Long artifactId;

    /** 团队名称 */
    private String teamName;

    /** 统计周期起止（近 7 天） */
    private LocalDate periodStart;

    /** 统计周期结束（今天） */
    private LocalDate periodEnd;

    /** 周报正文（Markdown） */
    private String report;

    /** 是否由大模型生成 */
    private boolean aiGenerated;

    /** 客观统计快照，供前端图表化展示 */
    private TeamInsightVO stats;

    /** 是否已推送到聊天室 */
    private boolean pushed;
}
