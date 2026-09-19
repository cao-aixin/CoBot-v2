package com.cobot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 生成产物实体（对应表 ai_artifact）
 *
 * <p>AI 工作台每次生成的内容都会记录一行：
 * <ul>
 *   <li>{@code insight} —— 团队洞察报告（负责人专属的智能数据分析）</li>
 *   <li>{@code plan}    —— 项目计划（阶段 + 任务清单，可一键导入任务看板）</li>
 *   <li>{@code ppt}     —— 演示文稿（大纲 + 真实 .pptx 文件）</li>
 * </ul>
 *
 * <p>注意：{@code fileData} 是 LONGBLOB 大字段，列表查询时会被 {@link TableField}(select=false) 排除，
 * 只有下载接口才会按 ID 单独把文件字节取出来，避免列表接口白白搬运几百 KB 的二进制。
 */
@Data
@TableName("ai_artifact")
public class AiArtifact {

    /** 产物 ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属团队 ID */
    private Long teamId;

    /** 发起生成的用户 ID */
    private Long userId;

    /** 生成类型：insight / plan / ppt */
    private String genType;

    /** 生成主题（如"校园二手交易平台"） */
    private String topic;

    /** PPT 配色主题标识（blue/green/purple/orange/teal/red/dark；非 PPT 产物为空） */
    private String theme;

    /** 下载文件名（PPT 产物才有） */
    private String fileName;

    /** 生成的正文内容（Markdown 文本，用于回显与导出） */
    private String content;

    /** 文件二进制（.pptx）；列表查询不加载，避免大字段拖慢接口 */
    @JsonIgnore
    @TableField(select = false)
    private byte[] fileData;

    /**
     * 文件外置存储相对路径（新产物落磁盘，库里只留路径）
     *
     * <p>读取优先级：先看 filePath（磁盘），没有再回落 fileData（历史库内数据），
     * 这样老产物的下载能力不会被破坏。
     */
    private String filePath;

    /** 生成时间 */
    private LocalDateTime createTime;
}
