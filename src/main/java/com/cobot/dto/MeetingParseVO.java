package com.cobot.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 会议纪要拆任务结果
 *
 * <p>把一段纪要文本变成结构化任务清单，用户可以一键导入任务看板。
 */
@Data
public class MeetingParseVO {

    /** 本次解析落库的产物 ID（用于后续"导入看板"） */
    private Long artifactId;

    /** 解析主题（取纪要首行或用户填写） */
    private String topic;

    /** 解析出的任务清单 */
    private List<AiTaskParseDTO> tasks = new ArrayList<>();

    /** 是否由大模型解析（false 表示降级为本地按行切分） */
    private boolean aiGenerated;

    /** 因大模型失败而降级时的提示 */
    private String fallbackNote;
}
