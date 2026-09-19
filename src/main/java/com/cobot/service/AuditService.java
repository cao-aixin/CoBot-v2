package com.cobot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.entity.SysAuditLog;
import com.cobot.mapper.SysAuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作审计服务
 *
 * <p>统一入口写入 sys_audit_log；查询侧仅供团队负责人查看本团队的操作记录。
 *
 * <p>设计取舍：审计写入<b>绝不影响主流程</b> —— 任何异常都被吞掉并降级为日志，
 * 不能因为记审计失败导致用户操作失败。
 */
@Slf4j
@Service
public class AuditService {

    private final SysAuditLogMapper auditLogMapper;

    public AuditService(SysAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记一条审计
     *
     * @param teamId   所属团队（可为 null）
     * @param userId   操作人 ID
     * @param username 操作人用户名
     * @param action   动作标识
     * @param target   操作对象
     * @param detail   详情
     * @param ip       来源 IP
     */
    public void record(Long teamId, Long userId, String username,
                       String action, String target, String detail, String ip) {
        try {
            SysAuditLog logRow = new SysAuditLog();
            logRow.setTeamId(teamId);
            logRow.setUserId(userId);
            logRow.setUsername(username);
            logRow.setAction(action);
            logRow.setTarget(truncate(target, 120));
            logRow.setDetail(truncate(detail, 500));
            logRow.setIp(ip);
            logRow.setCreateTime(LocalDateTime.now());
            auditLogMapper.insert(logRow);
        } catch (Exception e) {
            // 审计失败不影响业务，只留一条 warn
            log.warn("[CoBot] 审计日志写入失败 action={} target={}", action, target, e);
        }
    }

    /**
     * 某团队最近的操作记录（负责人视角）
     *
     * @param teamId 团队 ID
     * @param limit  最多返回条数
     */
    public List<SysAuditLog> recent(Long teamId, int limit) {
        int size = (limit <= 0 || limit > 200) ? 50 : limit;
        return auditLogMapper.selectList(new LambdaQueryWrapper<SysAuditLog>()
                .eq(SysAuditLog::getTeamId, teamId)
                .orderByDesc(SysAuditLog::getId)
                .last("LIMIT " + size));
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
