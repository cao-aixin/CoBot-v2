package com.cobot.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.annotation.AuditLog;
import com.cobot.dto.Result;
import com.cobot.entity.ChatMessage;
import com.cobot.entity.SysPendingTask;
import com.cobot.entity.SysReadState;
import com.cobot.entity.SysTask;
import com.cobot.entity.SysTeam;
import com.cobot.entity.SysTeamMember;
import com.cobot.entity.SysUser;
import com.cobot.mapper.ChatMessageMapper;
import com.cobot.mapper.SysPendingTaskMapper;
import com.cobot.mapper.SysReadStateMapper;
import com.cobot.mapper.SysTaskMapper;
import com.cobot.mapper.SysTeamMapper;
import com.cobot.mapper.SysTeamMemberMapper;
import com.cobot.mapper.SysUserMapper;
import com.cobot.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队自助管理接口（多团队场景核心）
 *
 * <p>通用能力：
 * <ul>
 *   <li>POST /api/team/create   创建团队（创建者为 owner，自动生成 6 位邀请码）</li>
 *   <li>POST /api/team/join     凭邀请码加入团队（校验有效期与使用次数上限）</li>
 *   <li>GET  /api/team/members  查看团队成员列表（含角色与入团时间；仅成员可查）</li>
 *   <li>GET  /api/team/info     团队详情（公告 / 邀请码策略），团队成员可见</li>
 *   <li>POST /api/team/leave    主动退出团队（负责人需先转让或解散）</li>
 * </ul>
 *
 * <p>负责人（owner）专属能力：
 * <ul>
 *   <li>POST /api/team/rename         修改团队名称</li>
 *   <li>POST /api/team/notice         设置团队公告</li>
 *   <li>POST /api/team/reset-invite   重置邀请码（可同时设定有效期与使用次数）</li>
 *   <li>POST /api/team/remove-member  移除成员</li>
 *   <li>POST /api/team/set-role       设置 / 取消成员负责人身份</li>
 *   <li>POST /api/team/dismiss        解散团队（清理成员、任务、消息等全部关联数据）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/team")
public class TeamController {

    /** 邀请码字符集（去掉易混淆的 0/O、1/I） */
    private static final String CODE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    private final SysTeamMapper sysTeamMapper;

    private final SysTeamMemberMapper sysTeamMemberMapper;

    private final SysUserMapper sysUserMapper;

    private final SysTaskMapper sysTaskMapper;

    private final ChatMessageMapper chatMessageMapper;

    private final SysPendingTaskMapper sysPendingTaskMapper;

    private final SysReadStateMapper sysReadStateMapper;

    private final AuthService authService;

    public TeamController(SysTeamMapper sysTeamMapper,
                          SysTeamMemberMapper sysTeamMemberMapper,
                          SysUserMapper sysUserMapper,
                          SysTaskMapper sysTaskMapper,
                          ChatMessageMapper chatMessageMapper,
                          SysPendingTaskMapper sysPendingTaskMapper,
                          SysReadStateMapper sysReadStateMapper,
                          AuthService authService) {
        this.sysTeamMapper = sysTeamMapper;
        this.sysTeamMemberMapper = sysTeamMemberMapper;
        this.sysUserMapper = sysUserMapper;
        this.sysTaskMapper = sysTaskMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.sysPendingTaskMapper = sysPendingTaskMapper;
        this.sysReadStateMapper = sysReadStateMapper;
        this.authService = authService;
    }

    /**
     * 团队操作请求体（按接口取用对应字段）
     *
     * @param teamName          团队名称
     * @param inviteCode        邀请码
     * @param teamId            团队 ID
     * @param userId            目标用户 ID
     * @param role              角色：owner / member
     * @param notice            团队公告
     * @param inviteExpireHours 邀请码有效小时数；null 或 <=0 表示永久有效
     * @param inviteMaxUse      邀请码最大使用次数；null 或 0 表示不限
     */
    public record TeamOpRequest(String teamName, String inviteCode, Long teamId, Long userId, String role,
                                String notice, Integer inviteExpireHours, Integer inviteMaxUse) {}

    /**
     * 创建团队：邀请码自动生成且唯一，创建者以 owner 身份入团
     */
    @PostMapping("/create")
    @AuditLog(action = "TEAM_CREATE", value = "创建团队")
    public Result<SysTeam> create(@RequestBody TeamOpRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.teamName() == null || request.teamName().isBlank()) {
            return Result.fail("团队名称不能为空");
        }
        String teamName = request.teamName().trim();
        if (teamName.length() > 20) {
            return Result.fail("团队名称最长 20 个字");
        }
        // 生成全局唯一邀请码
        String code = uniqueCode();

        SysTeam team = new SysTeam();
        team.setTeamName(teamName);
        team.setInviteCode(code);
        team.setInviteUsedCount(0);
        team.setInviteMaxUse(0);          // 默认不限次数
        team.setCreateTime(LocalDateTime.now());
        sysTeamMapper.insert(team);

        SysTeamMember member = new SysTeamMember();
        member.setTeamId(team.getId());
        member.setUserId(loginUserId);
        member.setRole("owner");
        member.setJoinTime(LocalDateTime.now());
        sysTeamMemberMapper.insert(member);
        return Result.ok(team);
    }

    /**
     * 凭邀请码加入团队
     *
     * <p>依次校验：码存在 -> 未过期 -> 未超使用次数上限；已在团队内则幂等返回。
     */
    @PostMapping("/join")
    @AuditLog(action = "TEAM_JOIN", value = "加入团队")
    public Result<SysTeam> join(@RequestBody TeamOpRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.inviteCode() == null || request.inviteCode().isBlank()) {
            return Result.fail("请输入邀请码");
        }
        SysTeam team = sysTeamMapper.selectOne(new LambdaQueryWrapper<SysTeam>()
                .eq(SysTeam::getInviteCode, request.inviteCode().trim().toUpperCase()));
        if (team == null) {
            return Result.fail("邀请码无效，请核对后重试");
        }
        // 已在团队内：幂等返回，不再消耗邀请码次数
        if (authService.isMember(team.getId(), loginUserId)) {
            return Result.ok(team);
        }
        // 有效期校验
        if (team.getInviteExpireAt() != null && team.getInviteExpireAt().isBefore(LocalDateTime.now())) {
            return Result.fail("该邀请码已于 " + team.getInviteExpireAt().toLocalDate()
                    + " 过期，请联系团队负责人重新生成");
        }
        // 使用次数上限校验
        int maxUse = team.getInviteMaxUse() == null ? 0 : team.getInviteMaxUse();
        int used = team.getInviteUsedCount() == null ? 0 : team.getInviteUsedCount();
        if (maxUse > 0 && used >= maxUse) {
            return Result.fail("该邀请码使用次数已达上限（" + maxUse + " 次），请联系团队负责人重新生成");
        }

        SysTeamMember member = new SysTeamMember();
        member.setTeamId(team.getId());
        member.setUserId(loginUserId);
        member.setRole("member");
        member.setJoinTime(LocalDateTime.now());
        sysTeamMemberMapper.insert(member);

        // 记录一次使用
        team.setInviteUsedCount(used + 1);
        sysTeamMapper.updateById(team);
        return Result.ok(team);
    }

    /**
     * 团队成员列表（含角色与入团时间；仅团队成员可查）
     */
    @GetMapping("/members")
    public Result<List<Map<String, Object>>> members(@RequestParam Long teamId, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        List<SysTeamMember> relations = sysTeamMemberMapper.selectList(
                new LambdaQueryWrapper<SysTeamMember>().eq(SysTeamMember::getTeamId, teamId));
        List<Long> userIds = relations.stream().map(SysTeamMember::getUserId).toList();
        Map<Long, String> nameMap = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser u : sysUserMapper.selectBatchIds(userIds)) {
                nameMap.put(u.getId(), u.getUsername());
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysTeamMember r : relations) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", r.getUserId());
            item.put("username", nameMap.getOrDefault(r.getUserId(), "未知用户"));
            item.put("role", r.getRole() == null ? "member" : r.getRole());
            item.put("joinTime", r.getJoinTime());
            list.add(item);
        }
        return Result.ok(list);
    }

    /**
     * 团队详情（公告 / 邀请码策略；团队成员可见）
     */
    @GetMapping("/info")
    public Result<Map<String, Object>> info(@RequestParam Long teamId, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        SysTeam team = sysTeamMapper.selectById(teamId);
        if (team == null) {
            return Result.fail("团队不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", team.getId());
        data.put("teamName", team.getTeamName());
        data.put("inviteCode", team.getInviteCode());
        data.put("inviteExpireAt", team.getInviteExpireAt());
        data.put("inviteMaxUse", team.getInviteMaxUse());
        data.put("inviteUsedCount", team.getInviteUsedCount());
        data.put("notice", team.getNotice());
        data.put("myRole", authService.roleInTeam(teamId, loginUserId));
        return Result.ok(data);
    }

    /**
     * 主动退出团队
     *
     * <p>负责人不能直接退（否则团队会变成无主状态）：需先把负责人身份转给别人，或直接解散团队。
     * 退出后该账号在本团队的登录会话会被作废，前端回到团队选择页。
     */
    @PostMapping("/leave")
    @AuditLog(action = "TEAM_LEAVE", value = "退出团队")
    public Result<String> leave(@RequestBody TeamOpRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        String loginUsername = (String) httpRequest.getAttribute("loginUsername");
        if (request.teamId() == null) {
            return Result.fail("请指定团队");
        }
        String role = authService.roleInTeam(request.teamId(), loginUserId);
        if (role == null) {
            return Result.fail("你不在该团队中");
        }
        if ("owner".equals(role)) {
            // 团队里还有别的负责人时允许退出（交接已完成）
            boolean hasOtherOwner = sysTeamMemberMapper.selectList(new LambdaQueryWrapper<SysTeamMember>()
                            .eq(SysTeamMember::getTeamId, request.teamId())
                            .eq(SysTeamMember::getRole, "owner"))
                    .stream().anyMatch(m -> !m.getUserId().equals(loginUserId));
            if (!hasOtherOwner) {
                return Result.fail("你是该团队唯一的负责人，请先转让负责人身份或解散团队");
            }
        }
        sysTeamMemberMapper.delete(new LambdaQueryWrapper<SysTeamMember>()
                .eq(SysTeamMember::getTeamId, request.teamId())
                .eq(SysTeamMember::getUserId, loginUserId));
        // 清掉该用户在此团队的已读状态，避免残留
        sysReadStateMapper.delete(new LambdaQueryWrapper<SysReadState>()
                .eq(SysReadState::getTeamId, request.teamId())
                .eq(SysReadState::getUserId, loginUserId));
        return Result.ok("已退出团队「" + teamName(request.teamId()) + "」，欢迎随时用邀请码回来：" + loginUsername);
    }

    /** 生成 6 位随机邀请码 */
    private String randomCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /** 生成全局唯一邀请码（DB 里查重，避免撞码） */
    private String uniqueCode() {
        String code;
        do {
            code = randomCode();
        } while (sysTeamMapper.selectCount(new LambdaQueryWrapper<SysTeam>()
                .eq(SysTeam::getInviteCode, code)) > 0);
        return code;
    }

    private String teamName(Long teamId) {
        SysTeam team = sysTeamMapper.selectById(teamId);
        return team == null ? String.valueOf(teamId) : team.getTeamName();
    }

    // ==================== 以下为「负责人(owner)专属」团队管理功能 ====================

    /**
     * 修改团队名称（仅负责人）
     */
    @PostMapping("/rename")
    @AuditLog(action = "TEAM_RENAME", value = "修改团队名称")
    public Result<String> rename(@RequestBody TeamOpRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (!authService.isOwner(request.teamId(), loginUserId)) {
            return Result.fail("仅团队负责人可修改团队信息");
        }
        if (request.teamName() == null || request.teamName().isBlank()) {
            return Result.fail("团队名称不能为空");
        }
        SysTeam team = sysTeamMapper.selectById(request.teamId());
        if (team == null) {
            return Result.fail("团队不存在");
        }
        team.setTeamName(request.teamName().trim());
        sysTeamMapper.updateById(team);
        return Result.ok("团队名称已改为「" + team.getTeamName() + "」");
    }

    /**
     * 设置团队公告（仅负责人；传空串表示清空公告）
     */
    @PostMapping("/notice")
    @AuditLog(action = "TEAM_NOTICE", value = "设置团队公告")
    public Result<String> notice(@RequestBody TeamOpRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (!authService.isOwner(request.teamId(), loginUserId)) {
            return Result.fail("仅团队负责人可设置公告");
        }
        SysTeam team = sysTeamMapper.selectById(request.teamId());
        if (team == null) {
            return Result.fail("团队不存在");
        }
        String notice = request.notice() == null ? "" : request.notice().trim();
        if (notice.length() > 200) {
            return Result.fail("公告最长 200 个字");
        }
        team.setNotice(notice);
        sysTeamMapper.updateById(team);
        return Result.ok(notice.isEmpty() ? "已清空团队公告" : "公告已更新");
    }

    /**
     * 重置邀请码（仅负责人；旧码立即失效，防止外泄后被随意加入）
     *
     * <p>可同时设定有效期与使用次数上限：
     * <ul>
     *   <li>inviteExpireHours：多少小时后失效，null/&lt;=0 表示永久有效</li>
     *   <li>inviteMaxUse：最多可用几次，null/0 表示不限</li>
     * </ul>
     */
    @PostMapping("/reset-invite")
    @AuditLog(action = "TEAM_RESET_INVITE", value = "重置邀请码")
    public Result<Map<String, Object>> resetInvite(@RequestBody TeamOpRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (!authService.isOwner(request.teamId(), loginUserId)) {
            return Result.fail("仅团队负责人可重置邀请码");
        }
        SysTeam team = sysTeamMapper.selectById(request.teamId());
        if (team == null) {
            return Result.fail("团队不存在");
        }
        team.setInviteCode(uniqueCode());
        team.setInviteUsedCount(0);   // 新码重新计数
        int hours = request.inviteExpireHours() == null ? 0 : request.inviteExpireHours();
        team.setInviteExpireAt(hours > 0 ? LocalDateTime.now().plusHours(hours) : null);
        team.setInviteMaxUse(request.inviteMaxUse() == null ? 0 : Math.max(0, request.inviteMaxUse()));
        sysTeamMapper.updateById(team);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inviteCode", team.getInviteCode());
        data.put("inviteExpireAt", team.getInviteExpireAt());
        data.put("inviteMaxUse", team.getInviteMaxUse());
        return Result.ok(data);
    }

    /**
     * 移除团队成员（仅负责人；不能移除自己，也不能移除其他负责人）
     */
    @PostMapping("/remove-member")
    @AuditLog(action = "TEAM_REMOVE_MEMBER", value = "移除团队成员")
    public Result<String> removeMember(@RequestBody TeamOpRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (!authService.isOwner(request.teamId(), loginUserId)) {
            return Result.fail("仅团队负责人可移除成员");
        }
        if (request.userId() == null) {
            return Result.fail("请指定要移除的成员");
        }
        if (request.userId().equals(loginUserId)) {
            return Result.fail("不能移除自己");
        }
        String targetRole = authService.roleInTeam(request.teamId(), request.userId());
        if (targetRole == null) {
            return Result.fail("该用户不在团队中");
        }
        if ("owner".equals(targetRole)) {
            return Result.fail("不能移除其他负责人");
        }
        sysTeamMemberMapper.delete(new LambdaQueryWrapper<SysTeamMember>()
                .eq(SysTeamMember::getTeamId, request.teamId())
                .eq(SysTeamMember::getUserId, request.userId()));
        sysReadStateMapper.delete(new LambdaQueryWrapper<SysReadState>()
                .eq(SysReadState::getTeamId, request.teamId())
                .eq(SysReadState::getUserId, request.userId()));
        // 被移出后强制下线（安全：避免被移除的人继续用旧 Token 访问团队数据）
        authService.clearSessionsOfUser(request.userId());
        SysUser removed = sysUserMapper.selectById(request.userId());
        return Result.ok("已移除成员：" + (removed == null ? request.userId() : removed.getUsername()));
    }

    /**
     * 设置成员角色（仅负责人；可设/取消其他成员的负责人身份，实现队长交接）
     */
    @PostMapping("/set-role")
    @AuditLog(action = "TEAM_SET_ROLE", value = "调整成员角色")
    public Result<String> setRole(@RequestBody TeamOpRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (!authService.isOwner(request.teamId(), loginUserId)) {
            return Result.fail("仅团队负责人可调整成员角色");
        }
        SysTeamMember member = sysTeamMemberMapper.selectOne(new LambdaQueryWrapper<SysTeamMember>()
                .eq(SysTeamMember::getTeamId, request.teamId())
                .eq(SysTeamMember::getUserId, request.userId()));
        if (member == null) {
            return Result.fail("该用户不在团队中");
        }
        if (request.role() == null || request.role().isBlank()) {
            return Result.fail("请指定角色");
        }
        String role = request.role().trim().toLowerCase();
        if (!"owner".equals(role) && !"member".equals(role)) {
            return Result.fail("角色只能是 owner 或 member");
        }
        // 不允许把最后一个负责人降级，否则团队将无主
        if (!"owner".equals(role) && "owner".equals(member.getRole())) {
            long ownerCount = sysTeamMemberMapper.selectCount(new LambdaQueryWrapper<SysTeamMember>()
                    .eq(SysTeamMember::getTeamId, request.teamId())
                    .eq(SysTeamMember::getRole, "owner"));
            if (ownerCount <= 1) {
                return Result.fail("团队至少需要一位负责人，请先指定新的负责人");
            }
        }
        member.setRole(role);
        sysTeamMemberMapper.updateById(member);
        return Result.ok("角色已更新为：" + ("owner".equals(role) ? "负责人" : "普通成员"));
    }

    /**
     * 解散团队（仅负责人）
     *
     * <p>级联清理该团队的全部关联数据：成员关系、任务、聊天消息、待确认任务、已读状态、团队记录。
     * 全员强制下线（会话按用户清空），避免有人拿着旧 Token 继续访问已解散的团队。
     */
    @PostMapping("/dismiss")
    @Transactional(rollbackFor = Exception.class)
    @AuditLog(action = "TEAM_DISMISS", value = "解散团队")
    public Result<String> dismiss(@RequestBody TeamOpRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (!authService.isOwner(request.teamId(), loginUserId)) {
            return Result.fail("仅团队负责人可解散团队");
        }
        SysTeam team = sysTeamMapper.selectById(request.teamId());
        if (team == null) {
            return Result.fail("团队不存在");
        }
        Long teamId = team.getId();
        String name = team.getTeamName();
        // 先收集成员，用于事后强制下线
        List<Long> memberIds = sysTeamMemberMapper.selectList(new LambdaQueryWrapper<SysTeamMember>()
                        .eq(SysTeamMember::getTeamId, teamId))
                .stream().map(SysTeamMember::getUserId).toList();

        sysTeamMemberMapper.delete(new LambdaQueryWrapper<SysTeamMember>().eq(SysTeamMember::getTeamId, teamId));
        sysTaskMapper.delete(new LambdaQueryWrapper<SysTask>().eq(SysTask::getTeamId, teamId));
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getTeamId, teamId));
        sysPendingTaskMapper.delete(new LambdaQueryWrapper<SysPendingTask>().eq(SysPendingTask::getTeamId, teamId));
        sysReadStateMapper.delete(new LambdaQueryWrapper<SysReadState>().eq(SysReadState::getTeamId, teamId));
        sysTeamMapper.deleteById(teamId);

        memberIds.forEach(authService::clearSessionsOfUser);
        return Result.ok("团队「" + name + "」已解散，相关任务与消息已清理，全部成员已下线");
    }
}
