package com.cobot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cobot.entity.SysTeamMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 团队成员表 Mapper
 */
@Mapper
public interface SysTeamMemberMapper extends BaseMapper<SysTeamMember> {
}
