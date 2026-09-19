package com.cobot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cobot.entity.SysAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计日志 Mapper（对应表 sys_audit_log）
 */
@Mapper
public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {
}
