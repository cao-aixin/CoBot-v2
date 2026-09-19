package com.cobot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cobot.entity.SysSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录会话 Mapper（对应表 sys_session）
 */
@Mapper
public interface SysSessionMapper extends BaseMapper<SysSession> {
}
