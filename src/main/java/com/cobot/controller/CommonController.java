package com.cobot.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.dto.Result;
import com.cobot.dto.TeamVO;
import com.cobot.entity.ChatMessage;
import com.cobot.entity.SysAuditLog;
import com.cobot.entity.SysReadState;
import com.cobot.entity.SysTask;
import com.cobot.entity.SysTeam;
import com.cobot.entity.SysTeamMember;
import com.cobot.entity.SysUser;
import com.cobot.mapper.ChatMessageMapper;
import com.cobot.mapper.SysReadStateMapper;
import com.cobot.mapper.SysTaskMapper;
import com.cobot.mapper.SysTeamMapper;
import com.cobot.mapper.SysTeamMemberMapper;
import com.cobot.mapper.SysUserMapper;
import com.cobot.service.AuditService;
import com.cobot.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 基础数据接口（需登录）
 *
 * <p>GET /api/team/my          我的团队列表（登录后进入聊天室的入口；含角色、未读数、待办数）
 * <br>GET /api/user/list?teamId= 某团队成员列表（需为该团队成员；密码已隐藏）
 * <br>GET /api/audit/list?teamId= 团队操作审计记录（仅团队负责人可查）
 */
@RestController
@RequestMapping("/api")
public class CommonController {

    private final SysTeamMapper sysTeamMapper;

    private final SysUserMapper sysUserMapper;

    private final SysTeamMemberMapper sysTeamMemberMapper;

    private final ChatMessageMapper chatMessageMapper;

    private final SysTaskMapper sysTaskMapper;

    private final SysReadStateMapper sysReadStateMapper;

    private final AuditService auditService;

    private final AuthService authService;

    public CommonController(SysTeamMapper sysTeamMapper,
                            SysUserMapper sysUserMapper,
                            SysTeamMemberMapper sysTeamMemberMapper,
                            ChatMessageMapper chatMessageMapper,
                            SysTaskMapper sysTaskMapper,
                            SysReadStateMapper sysReadStateMapper,
                            AuditService auditService,
                            AuthService authService) {
        this.sysTeamMapper = sysTeamMapper;
        this.sysUserMapper = sysUserMapper;
        this.sysTeamMemberMapper = sysTeamMemberMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.sysTaskMapper = sysTaskMapper;
        this.sysReadStateMapper = sysReadStateMapper;
        this.auditService = auditService;
        this.authService = authService;
    }

    /**
     * 我的团队列表：登录用户所属的团队 + 我在该团队的角色（前端权限渲染依据）
     *
     * <p>顺带带上"未读消息数"与"未完成任务数"，让团队切换下拉本身就能当简易仪表盘用。
     */
    @GetMapping("/team/my")
    public Result<List<TeamVO>> myTeams(HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        List<SysTeamMember> relations = sysTeamMemberMapper.selectList(new LambdaQueryWrapper<SysTeamMember>()
                .eq(SysTeamMember::getUserId, loginUserId));
        if (relations.isEmpty()) {
            return Result.ok(List.of());
        }
        List<Long> teamIds = relations.stream().map(SysTeamMember::getTeamId).toList();
        List<TeamVO> list = new ArrayList<>();
        for (SysTeam team : sysTeamMapper.selectBatchIds(teamIds)) {
            SysTeamMember relation = relations.stream()
                    .filter(r -> r.getTeamId().equals(team.getId())).findFirst().orElse(null);
            TeamVO vo = new TeamVO();
            vo.setId(team.getId());
            vo.setTeamName(team.getTeamName());
            vo.setInviteCode(team.getInviteCode());
            vo.setRole(relation == null || relation.getRole() == null ? "member" : relation.getRole());
            vo.setMemberCount(Math.toIntExact(sysTeamMemberMapper.selectCount(
                    new LambdaQueryWrapper<SysTeamMember>().eq(SysTeamMember::getTeamId, team.getId()))));
            vo.setNotice(team.getNotice());
            vo.setInviteExpireAt(team.getInviteExpireAt());
            vo.setInviteMaxUse(team.getInviteMaxUse());
            vo.setInviteUsedCount(team.getInviteUsedCount());
            vo.setUnreadCount(unreadCount(team.getId(), loginUserId));
            vo.setPendingTaskCount(Math.toIntExact(sysTaskMapper.selectCount(new LambdaQueryWrapper<SysTask>()
                    .eq(SysTask::getTeamId, team.getId())
                    .notIn(SysTask::getTaskStatus, "已完成", "归档"))));
            list.add(vo);
        }
        list.sort(java.util.Comparator.comparing(TeamVO::getId));
        return Result.ok(list);
    }

    /**
     * 某团队成员列表（teamId 必传，且需为该团队成员；密码已通过 @JsonIgnore 隐藏）
     */
    @GetMapping("/user/list")
    public Result<List<SysUser>> userList(@RequestParam Long teamId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        // 先查团队成员关联，再按 id 批量查用户
        List<Long> userIds = sysTeamMemberMapper.selectList(new LambdaQueryWrapper<SysTeamMember>()
                        .eq(SysTeamMember::getTeamId, teamId))
                .stream().map(SysTeamMember::getUserId).toList();
        if (userIds.isEmpty()) {
            return Result.ok(List.of());
        }
        return Result.ok(sysUserMapper.selectBatchIds(userIds));
    }

    /**
     * 团队操作审计记录（仅团队负责人可查）
     *
     * <p>回答"谁改了我的角色""任务是谁删的"这类管理追问，是管理类系统的标配。
     */
    @GetMapping("/audit/list")
    public Result<List<SysAuditLog>> auditList(@RequestParam Long teamId,
                                               @RequestParam(defaultValue = "50") Integer limit,
                                               HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        if (!authService.isOwner(teamId, loginUserId)) {
            return Result.fail("操作审计记录仅团队负责人可查看");
        }
        return Result.ok(auditService.recent(teamId, limit));
    }

    /**
     * 计算某用户在某团队的未读消息数
     *
     * <p>口径 = 本团队内 id 大于"已读到的最大 id"的消息条数。
     * 消息 id 是全库自增的，所以不能用"本团队最新消息 id - 已读 id"，
     * 否则其它团队的消息量会把这个数字顶得很高。
     */
    private Integer unreadCount(Long teamId, Long userId) {
        SysReadState state = sysReadStateMapper.selectOne(new LambdaQueryWrapper<SysReadState>()
                .eq(SysReadState::getTeamId, teamId)
                .eq(SysReadState::getUserId, userId));
        long readId = state == null || state.getLastReadId() == null ? 0L : state.getLastReadId();
        Long unread = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getTeamId, teamId)
                .gt(ChatMessage::getId, readId));
        return unread == null ? 0 : unread.intValue();
    }
}
