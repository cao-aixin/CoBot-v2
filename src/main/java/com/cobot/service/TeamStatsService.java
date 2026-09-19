package com.cobot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.dto.TeamInsightVO;
import com.cobot.entity.ChatMessage;
import com.cobot.entity.SysTask;
import com.cobot.entity.SysTeam;
import com.cobot.entity.SysTeamMember;
import com.cobot.entity.SysUser;
import com.cobot.mapper.ChatMessageMapper;
import com.cobot.mapper.SysTaskMapper;
import com.cobot.mapper.SysTeamMapper;
import com.cobot.mapper.SysTeamMemberMapper;
import com.cobot.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队数据统计聚合服务（AI 工作台 · 团队洞察的数据底座）
 *
 * <p>这里只做一件事：把散落在 sys_task / sys_team_member / chat_message 里的原始数据，
 * 聚合成一份结构化的客观统计（{@link TeamInsightVO}）。
 *
 * <p>设计原则：<b>数字永远来自数据库真实查询，不经过大模型</b>。
 * 大模型只负责在拿到真实数字之后写"分析建议"，绝不参与算数，
 * 从根上避免 AI 编造统计口径的情况。
 */
@Service
public class TeamStatsService {

    /** 近期趋势统计窗口（天） */
    private static final int RECENT_DAYS = 7;

    private final SysTeamMapper sysTeamMapper;

    private final SysTeamMemberMapper sysTeamMemberMapper;

    private final SysUserMapper sysUserMapper;

    private final SysTaskMapper sysTaskMapper;

    private final ChatMessageMapper chatMessageMapper;

    public TeamStatsService(SysTeamMapper sysTeamMapper,
                            SysTeamMemberMapper sysTeamMemberMapper,
                            SysUserMapper sysUserMapper,
                            SysTaskMapper sysTaskMapper,
                            ChatMessageMapper chatMessageMapper) {
        this.sysTeamMapper = sysTeamMapper;
        this.sysTeamMemberMapper = sysTeamMemberMapper;
        this.sysUserMapper = sysUserMapper;
        this.sysTaskMapper = sysTaskMapper;
        this.chatMessageMapper = chatMessageMapper;
    }

    /**
     * 聚合某团队的完整统计快照
     *
     * @param teamId 团队 ID
     * @return 统计快照；团队不存在返回 null
     */
    public TeamInsightVO buildInsight(Long teamId) {
        SysTeam team = sysTeamMapper.selectById(teamId);
        if (team == null) {
            return null;
        }
        TeamInsightVO vo = new TeamInsightVO();
        vo.setTeamId(teamId);
        vo.setTeamName(team.getTeamName());

        // ---------- 1. 团队成员（userId -> 用户名 / 角色） ----------
        List<SysTeamMember> relations = sysTeamMemberMapper.selectList(
                new LambdaQueryWrapper<SysTeamMember>().eq(SysTeamMember::getTeamId, teamId));
        Map<Long, String> nameMap = new LinkedHashMap<>();
        Map<String, String> roleMap = new LinkedHashMap<>();
        for (SysTeamMember relation : relations) {
            SysUser user = sysUserMapper.selectById(relation.getUserId());
            if (user == null) {
                continue;
            }
            nameMap.put(relation.getUserId(), user.getUsername());
            roleMap.put(user.getUsername(), relation.getRole() == null ? "member" : relation.getRole());
        }
        vo.setMemberCount(relations.size());

        // ---------- 2. 任务总量与状态分布 ----------
        List<SysTask> tasks = sysTaskMapper.selectList(
                new LambdaQueryWrapper<SysTask>().eq(SysTask::getTeamId, teamId));
        LocalDate today = LocalDate.now();
        int doing = 0;
        int done = 0;
        int waiting = 0;
        int archived = 0;
        int overdue = 0;
        for (SysTask task : tasks) {
            String status = task.getTaskStatus() == null ? "" : task.getTaskStatus();
            switch (status) {
                case "进行中" -> doing++;
                case "已完成" -> done++;
                case "待确认" -> waiting++;
                case "归档" -> archived++;
                default -> { /* 未知状态不归类，仅计入总数 */ }
            }
            if (isOverdue(task, today)) {
                overdue++;
            }
        }
        vo.setTotalTasks(tasks.size());
        vo.setDoingTasks(doing);
        vo.setDoneTasks(done);
        vo.setWaitingTasks(waiting);
        vo.setArchivedTasks(archived);
        vo.setOverdueTasks(overdue);
        vo.setCompletionRate(tasks.isEmpty()
                ? 0.0
                : Math.round(done * 1000.0 / tasks.size()) / 10.0);

        // ---------- 3. 近 7 天趋势 ----------
        LocalDateTime since = LocalDateTime.now().minusDays(RECENT_DAYS);
        vo.setNewLast7Days((int) tasks.stream()
                .filter(t -> t.getCreateTime() != null && t.getCreateTime().isAfter(since))
                .count());
        // 说明：sys_task 未记录"完成时间"，此处以"截止日在近 7 天内且已完成"近似表示近期交付量
        vo.setDoneLast7Days((int) tasks.stream()
                .filter(t -> "已完成".equals(t.getTaskStatus()) && t.getDeadline() != null
                        && !t.getDeadline().isBefore(today.minusDays(RECENT_DAYS)))
                .count());
        vo.setMsgLast7Days(Math.toIntExact(chatMessageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getTeamId, teamId)
                        .eq(ChatMessage::getSenderType, 0)
                        .ge(ChatMessage::getCreateTime, since))));

        // ---------- 4. 成员负荷排行 ----------
        List<TeamInsightVO.MemberLoad> loads = new ArrayList<>();
        int overloaded = 0;
        for (Map.Entry<Long, String> entry : nameMap.entrySet()) {
            String username = entry.getValue();
            TeamInsightVO.MemberLoad load = new TeamInsightVO.MemberLoad();
            load.setUsername(username);
            load.setRole(roleMap.getOrDefault(username, "member"));
            for (SysTask task : tasks) {
                if (!username.equals(task.getOwnerName())) {
                    continue;
                }
                load.setTotal(load.getTotal() + 1);
                if ("进行中".equals(task.getTaskStatus())) {
                    load.setDoing(load.getDoing() + 1);
                }
                if ("已完成".equals(task.getTaskStatus())) {
                    load.setDone(load.getDone() + 1);
                }
                if (isOverdue(task, today)) {
                    load.setOverdue(load.getOverdue() + 1);
                }
            }
            load.setLoadLevel(judgeLoadLevel(load));
            if ("超载".equals(load.getLoadLevel())) {
                overloaded++;
            }
            loads.add(load);
        }
        // 负荷高的排前面，便于一眼看出谁最忙
        loads.sort(Comparator.comparingInt(TeamInsightVO.MemberLoad::getOverdue)
                .thenComparingInt(TeamInsightVO.MemberLoad::getDoing).reversed());
        vo.setMemberLoads(loads);

        // ---------- 5. 风险等级（规则判定，非大模型拍脑袋） ----------
        vo.setRiskLevel(judgeRiskLevel(overdue, vo.getTotalTasks(), overloaded));
        return vo;
    }

    /**
     * 任务是否已逾期：有截止日、已过期、且尚未完成/归档
     */
    public boolean isOverdue(SysTask task, LocalDate today) {
        if (task.getDeadline() == null) {
            return false;
        }
        String status = task.getTaskStatus();
        if ("已完成".equals(status) || "归档".equals(status)) {
            return false;
        }
        return task.getDeadline().isBefore(today);
    }

    /**
     * 成员负荷等级判定
     */
    private String judgeLoadLevel(TeamInsightVO.MemberLoad load) {
        if (load.getOverdue() >= 2 || load.getDoing() >= 4) {
            return "超载";
        }
        if (load.getOverdue() >= 1 || load.getDoing() >= 3) {
            return "偏重";
        }
        if (load.getDoing() >= 1) {
            return "正常";
        }
        return "空闲";
    }

    /**
     * 团队整体风险等级判定
     *
     * @param overdue  逾期任务数
     * @param total    任务总数
     * @param overloaded 超载成员数
     */
    private String judgeRiskLevel(int overdue, int total, int overloaded) {
        if (overdue >= 3 || overloaded >= 2 || (total > 0 && overdue * 100.0 / total >= 30)) {
            return "高";
        }
        if (overdue >= 1 || overloaded >= 1) {
            return "中";
        }
        return "低";
    }

    /**
     * 把统计快照压缩成一段纯文本，作为大模型的输入上下文
     *
     * <p>只喂数字和事实，不给大模型留任何"自由发挥"的空间。
     */
    public String toPromptText(TeamInsightVO vo) {
        StringBuilder sb = new StringBuilder();
        sb.append("团队名称：").append(vo.getTeamName()).append("\n");
        sb.append("成员人数：").append(vo.getMemberCount()).append("\n");
        sb.append("任务总数：").append(vo.getTotalTasks())
                .append("（进行中 ").append(vo.getDoingTasks())
                .append("、已完成 ").append(vo.getDoneTasks())
                .append("、待确认 ").append(vo.getWaitingTasks())
                .append("、已归档 ").append(vo.getArchivedTasks()).append("）\n");
        sb.append("逾期未完成：").append(vo.getOverdueTasks()).append("\n");
        sb.append("完成率：").append(vo.getCompletionRate()).append("%\n");
        sb.append("近 7 天新增任务：").append(vo.getNewLast7Days())
                .append("，近 7 天完成：").append(vo.getDoneLast7Days())
                .append("，近 7 天团队消息数：").append(vo.getMsgLast7Days()).append("\n");
        sb.append("规则判定的整体风险等级：").append(vo.getRiskLevel()).append("\n");
        sb.append("成员负荷：\n");
        for (TeamInsightVO.MemberLoad load : vo.getMemberLoads()) {
            sb.append("  - ").append(load.getUsername())
                    .append("（").append("owner".equals(load.getRole()) ? "负责人" : "成员").append("）")
                    .append(" 任务 ").append(load.getTotal())
                    .append(" 条，进行中 ").append(load.getDoing())
                    .append("，已完成 ").append(load.getDone())
                    .append("，逾期 ").append(load.getOverdue())
                    .append("，负荷：").append(load.getLoadLevel()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 本地规则兜底：不使用大模型时，直接把统计事实组织成一份可读的纯文本报告
     *
     * <p>刻意不输出任何 Markdown 语法符号（#、|、**、- 等），因为报告会原样展示在
     * 「AI 工作台」与聊天室里，用户分不清哪条是大模型生成的，所以源头就要写干净。
     */
    public String buildLocalReport(TeamInsightVO vo) {
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(vo.getTeamName()).append("」团队数据分析\n\n");
        sb.append("整体风险等级：").append(vo.getRiskLevel()).append("\n\n");

        sb.append("一、核心指标\n");
        sb.append("团队成员：").append(vo.getMemberCount()).append(" 人\n");
        sb.append("任务总数：").append(vo.getTotalTasks()).append(" 条\n");
        sb.append("进行中：").append(vo.getDoingTasks()).append(" 条\n");
        sb.append("已完成：").append(vo.getDoneTasks()).append(" 条\n");
        sb.append("待审批：").append(vo.getWaitingTasks()).append(" 条\n");
        sb.append("逾期未完成：").append(vo.getOverdueTasks()).append(" 条\n");
        sb.append("完成率：").append(vo.getCompletionRate()).append("%\n\n");

        sb.append("二、近期活跃度（近 7 天）\n");
        sb.append("新增任务：").append(vo.getNewLast7Days()).append(" 条\n");
        sb.append("完成任务：").append(vo.getDoneLast7Days()).append(" 条\n");
        sb.append("团队消息：").append(vo.getMsgLast7Days()).append(" 条\n\n");

        sb.append("三、成员负荷分布\n");
        for (TeamInsightVO.MemberLoad load : vo.getMemberLoads()) {
            sb.append(load.getUsername())
                    .append("（").append("owner".equals(load.getRole()) ? "负责人" : "成员").append("）：")
                    .append("任务 ").append(load.getTotal())
                    .append(" 条，进行中 ").append(load.getDoing())
                    .append("，已完成 ").append(load.getDone())
                    .append("，逾期 ").append(load.getOverdue())
                    .append("，负荷 ").append(load.getLoadLevel())
                    .append("\n");
        }

        sb.append("\n四、管理建议\n");
        sb.append("1、");
        if (vo.getOverdueTasks() > 0) {
            sb.append("当前有 ").append(vo.getOverdueTasks())
                    .append(" 条任务已逾期，建议优先排期或重新评估截止时间。\n");
        } else {
            sb.append("当前没有逾期任务，进度健康，建议保持现有节奏。\n");
        }
        sb.append("2、");
        if (vo.getWaitingTasks() > 0) {
            sb.append("有 ").append(vo.getWaitingTasks())
                    .append(" 条成员提交的任务待审批，建议尽快流转以免阻塞执行。\n");
        } else {
            sb.append("暂无待审批任务，任务流转顺畅。\n");
        }
        sb.append("3、");
        List<TeamInsightVO.MemberLoad> heavy = vo.getMemberLoads().stream()
                .filter(l -> "超载".equals(l.getLoadLevel()) || "偏重".equals(l.getLoadLevel()))
                .toList();
        List<TeamInsightVO.MemberLoad> idle = vo.getMemberLoads().stream()
                .filter(l -> "空闲".equals(l.getLoadLevel()))
                .toList();
        if (heavy.isEmpty()) {
            sb.append("成员负荷均衡，无需调整分工。\n");
        } else if (idle.isEmpty()) {
            sb.append("成员 ").append(heavy.stream().map(TeamInsightVO.MemberLoad::getUsername)
                    .reduce((a, b) -> a + "、" + b).orElse(""))
                    .append(" 负荷偏重且无人空闲，建议考虑延期低优先级任务。\n");
        } else {
            sb.append("成员 ").append(heavy.stream().map(TeamInsightVO.MemberLoad::getUsername)
                            .reduce((a, b) -> a + "、" + b).orElse(""))
                    .append(" 负荷偏重，可把部分任务转派给相对空闲的 ")
                    .append(idle.stream().map(TeamInsightVO.MemberLoad::getUsername)
                            .reduce((a, b) -> a + "、" + b).orElse(""))
                    .append("。\n");
        }
        sb.append("4、近 7 天团队消息 ").append(vo.getMsgLast7Days()).append(" 条，");
        sb.append(vo.getMsgLast7Days() >= 20 ? "协作活跃。\n" : "协作频率偏低，建议在聊天室同步进度。\n");
        return sb.toString();
    }
}
