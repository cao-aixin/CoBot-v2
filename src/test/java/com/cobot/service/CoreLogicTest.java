package com.cobot.service;

import com.cobot.dto.AiTaskParseDTO;
import com.cobot.dto.PendingTask;
import com.cobot.entity.SysTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 核心逻辑单元测试（不依赖 Spring 上下文与数据库）
 *
 * <p>覆盖两块最容易被改坏的纯逻辑：
 * <ul>
 *   <li>密码哈希：同一个密码哈希必须稳定（否则所有人登不上），不同密码必须不同</li>
 *   <li>待确认任务转实体：状态与截止日期解析正确（负责人/成员分流的关键）</li>
 * </ul>
 */
class CoreLogicTest {

    @Test
    @DisplayName("密码哈希稳定且与明文不同")
    void hashPasswordStable() {
        String expected = AuthService.hashPassword("123456");
        assertEquals(expected, AuthService.hashPassword("123456"), "同一密码两次哈希必须一致");
        assertEquals(64, expected.length(), "SHA-256 hex 应为 64 位");
        assertNotEquals(expected, AuthService.hashPassword("1234567"), "不同密码哈希必须不同");
        assertNotEquals("123456", expected, "库里不能出现明文密码");
    }

    @Test
    @DisplayName("演示账号密码哈希与库中种子数据一致")
    void seedPasswordMatches() {
        // sql/cobot_db.sql 中演示密码 123456 的哈希值
        assertEquals("34768e01b823efa01239e1ffc47ea0370ff9d4d764305c80a889ba54b09e0490",
                AuthService.hashPassword("123456"),
                "若本断言失败，说明改了加盐规则，会导致所有历史账号无法登录");
    }

    @Test
    @DisplayName("负责人创建任务：直接进行中")
    void ownerTaskGoesDoing() {
        PendingTask pending = buildPending("2026-12-31");
        SysTask task = pending.toEntity("进行中");
        assertEquals("进行中", task.getTaskStatus());
        assertEquals("张三", task.getOwnerName());
        assertEquals(LocalDate.of(2026, 12, 31), task.getDeadline());
        assertEquals("普通", task.getPriority());
    }

    @Test
    @DisplayName("普通成员创建任务：进入待确认等待审批")
    void memberTaskGoesWaiting() {
        SysTask task = buildPending("2026-12-31").toEntity("待确认");
        assertEquals("待确认", task.getTaskStatus());
    }

    @Test
    @DisplayName("非法或缺失截止日期不抛异常，置空处理")
    void invalidDeadlineIsNull() {
        assertNull(buildPending("下周五").toEntity("进行中").getDeadline());
        assertNull(buildPending(null).toEntity("进行中").getDeadline());
    }

    @Test
    @DisplayName("缺省优先级回落为普通")
    void defaultPriority() {
        PendingTask pending = buildPending("2026-12-31");
        pending.getParseDTO().setPriority(null);
        assertEquals("普通", pending.toEntity("进行中").getPriority());
        assertNotNull(pending.toEntity().getTaskStatus());
    }

    private PendingTask buildPending(String deadline) {
        AiTaskParseDTO dto = new AiTaskParseDTO();
        dto.setOwnerName("张三");
        dto.setTaskContent("完成登录接口开发");
        dto.setDeadline(deadline);
        dto.setPriority("普通");
        PendingTask pending = new PendingTask();
        pending.setTeamId(1L);
        pending.setRequesterName("张三");
        pending.setParseDTO(dto);
        return pending;
    }
}
