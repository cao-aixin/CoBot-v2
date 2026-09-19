package com.cobot.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 产物历史记录视图对象（AI 工作台 · 生成历史列表）
 *
 * <p>只携带列表展示需要的字段，大字段（正文 / 文件字节）不在此处返回，
 * 避免历史列表接口把几百 KB 的 PPT 二进制都搬到前端。
 */
@Data
public class ArtifactVO {

    /** 产物 ID */
    private Long id;

    /** 生成类型：insight / plan / ppt */
    private String genType;

    /** 生成类型中文名，如"团队洞察" */
    private String genTypeName;

    /** 生成主题 */
    private String topic;

    /** PPT 配色主题标识（blue/green/...；非 PPT 产物为空） */
    private String theme;

    /** PPT 配色主题中文名，如"星云紫" */
    private String themeName;

    /** PPT 配色主色的 hex 值，历史列表画色点用 */
    private String themeColor;

    /** 发起人用户名 */
    private String username;

    /** 可下载文件名（PPT 才有） */
    private String fileName;

    /** 生成时间 */
    private LocalDateTime createTime;
}
