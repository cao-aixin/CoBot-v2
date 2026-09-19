package com.cobot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cobot.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper（继承 MyBatis-Plus BaseMapper，零 SQL 完成基础 CRUD）
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
