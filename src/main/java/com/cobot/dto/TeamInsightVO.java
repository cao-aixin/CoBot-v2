package com.cobot.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 团队洞察视图对象（AI 工作台 · 负责人数据统计）
 *
 * <p>由 {@code TeamStatsService} 从数据库真实聚合出客观数字（stats），
 * 再把数字喂给大模型生成主观分析（report），两者一起返回给前端：
 * <ul>
 *   <li>stats 部分永远真实可核验，不依赖大模型</li>
 *   <li>report 部分是指挥 AI 基于 stats 写出的管理建议，local 模式下退化为规则模板</li>
 * </ul>
 */
@Data
public class TeamInsightVO {

    /** 团队 ID */
    private Long teamId;

    /** 团队名称 */
    private String teamName;

    /** 团队成员数 */
    private int memberCount;

    // ---------- 任务总量维度 ----------

    /** 任务总数 */
    private int totalTasks;

    /** 进行中 */
    private int doingTasks;

    /** 已完成 */
    private int doneTasks;

    /** 待确认（待负责人审批） */
    private int waitingTasks;

    /** 已归档 */
    private int archivedTasks;

    /** 已逾期未完成的任务数 */
    private int overdueTasks;

    /** 完成率（已完成 / 总数，百分比，保留一位小数） */
    private double completionRate;

    // ---------- 近期趋势维度 ----------

    /** 近 7 天新增任务数 */
    private int newLast7Days;

    /** 近 7 天完成任务数 */
    private int doneLast7Days;

    /** 近 7 天团队消息数（活跃度） */
    private int msgLast7Days;

    /** 风险等级：低 / 中 / 高（由逾期率与超载人数规则判定） */
    private String riskLevel = "低";

    /** 成员负荷明细（按负荷从高到低排序） */
    private List<MemberLoad> memberLoads = new ArrayList<>();

    /** AI 生成的管理分析报告（Markdown 文本） */
    private String report;

    /** 报告来源：true=大模型生成 / false=本地规则模板 */
    private boolean aiGenerated;

    /** 本次洞察落库的产物 ID（前端导出 / 历史回看用） */
    private Long artifactId;

    /**
     * 成员负荷明细
     */
    @Data
    public static class MemberLoad {

        /** 成员用户名 */
        private String username;

        /** 团队角色：owner / member */
        private String role;

        /** 名下任务总数 */
        private int total;

        /** 进行中 */
        private int doing;

        /** 已完成 */
        private int done;

        /** 逾期未完成 */
        private int overdue;

        /** 负荷等级：空闲 / 正常 / 偏重 / 超载 */
        private String loadLevel;
    }
}
