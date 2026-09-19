package com.cobot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cobot.entity.SysReadState;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息已读状态 Mapper（对应表 sys_read_state）
 */
@Mapper
public interface SysReadStateMapper extends BaseMapper<SysReadState> {
}
