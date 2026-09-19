package com.cobot.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * PPT 大纲视图对象（AI 工作台 · 一键生成演示文稿）
 *
 * <p>大模型只负责产出"大纲"（每页标题 + 要点），真正的 .pptx 文件由
 * {@code PptxBuildService} 用 Apache POI 渲染，保证下载下来就是能直接打开播放的文件。
 */
@Data
public class PptOutlineVO {

    /** 演示主题 */
    private String topic;

    /** 副标题（封面用，如"XX团队 · 2026 年度项目汇报"） */
    private String subtitle;

    /** 页面列表（含封面 / 目录 / 内容 / 结尾） */
    private List<PptSlide> slides = new ArrayList<>();

    /** 生成来源：true=大模型生成 / false=本地规则模板 */
    private boolean aiGenerated;

    /** 落库产物 ID（下载路径 /api/ai/file/{artifactId}） */
    private Long artifactId;

    /** 配色主题标识（blue/green/purple/orange/teal/red/dark） */
    private String theme;

    /** 配色主题中文名（前端结果卡片展示，如"星云紫"） */
    private String themeName;

    /** 可下载文件名 */
    private String fileName;

    /** 文件大小（字节），前端展示用 */
    private long fileSize;

    /**
     * 单页幻灯片
     */
    @Data
    public static class PptSlide {

        /** 页面类型：cover=封面 / agenda=目录 / content=内容页 / end=结束页 */
        private String type;

        /** 页面标题 */
        private String title;

        /** 页面要点（每行一条，渲染成项目符号） */
        private List<String> bullets = new ArrayList<>();
    }
}
