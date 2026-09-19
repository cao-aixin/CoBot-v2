package com.cobot.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目计划视图对象（AI 工作台 · 一键生成项目计划）
 *
 * <p>结构：项目 -> 阶段 -> 任务 三层。任务默认负责人由大模型参考团队真实成员给出，
 * 前端可一键把这些任务批量导入任务看板（sys_task）。
 */
@Data
public class ProjectPlanVO {

    /** 项目主题 */
    private String topic;

    /** 项目目标（一句话） */
    private String goal;

    /** 计划周期（周） */
    private Integer weeks;

    /** 阶段列表 */
    private List<PlanStage> stages = new ArrayList<>();

    /** 计划任务总数（各阶段合计） */
    private int taskCount;

    /** 计划来源：true=大模型生成 / false=本地规则模板 */
    private boolean aiGenerated;

    /** 生成建议（风险提示 / 落地要点，Markdown 文本） */
    private String advice;

    /** 本次计划落库的产物 ID（导入看板、导出时使用） */
    private Long artifactId;

    /**
     * 计划阶段
     */
    @Data
    public static class PlanStage {

        /** 阶段名称，如"第一阶段：需求与设计" */
        private String stageName;

        /** 阶段目标 */
        private String goal;

        /** 阶段周期描述，如"第 1 周" */
        private String period;

        /** 阶段下的任务 */
        private List<PlanTaskItem> tasks = new ArrayList<>();
    }

    /**
     * 计划任务项
     */
    @Data
    public static class PlanTaskItem {

        /** 任务内容 */
        private String taskContent;

        /** 建议负责人（团队成员用户名；无合适人选时为空） */
        private String ownerName;

        /** 优先级：高 / 普通 / 低 */
        private String priority;

        /** 建议截止日期 yyyy-MM-dd（可空） */
        private String deadline;
    }
}
