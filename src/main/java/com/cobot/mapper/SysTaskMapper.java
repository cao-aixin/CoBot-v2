package com.cobot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cobot.entity.SysTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务表 Mapper
 */
@Mapper
public interface SysTaskMapper extends BaseMapper<SysTask> {
}
