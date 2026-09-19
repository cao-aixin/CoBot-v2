package com.cobot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cobot.entity.SysPendingTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 待确认任务暂存 Mapper（对应表 sys_pending_task）
 */
@Mapper
public interface SysPendingTaskMapper extends BaseMapper<SysPendingTask> {
}
