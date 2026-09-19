package com.cobot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cobot.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天消息表 Mapper
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
