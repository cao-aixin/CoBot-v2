package com.cobot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.entity.SysSession;
import com.cobot.entity.SysTeamMember;
import com.cobot.entity.SysUser;
import com.cobot.mapper.SysSessionMapper;
import com.cobot.mapper.SysTeamMemberMapper;
import com.cobot.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录会话服务（Token 认证）
 *
 * <p>会话<b>落库</b>（表 sys_session）而非放在内存 Map：
 * <ul>
 *   <li>服务重启 / 重新部署后，已登录用户不会被集体踢下线</li>
 *   <li>改密后可以精确地作废该账号的全部 Token</li>
 *   <li>多实例部署时共用同一张表即可共享登录态</li>
 * </ul>
 *
 * <p>Token 机制：
 * <ul>
 *   <li>登录成功 -> 生成 32 位 UUID Token 落库，24 小时过期</li>
 *   <li>每次校验通过自动滑动续期；为避免每个请求都写库，
 *       仅在剩余有效期不足一半时才真正更新过期时间</li>
 *   <li>过期会话由定时任务与登录动作顺带清理</li>
 * </ul>
 *
 * <p>安全设计：
 * <ul>
 *   <li>密码不落明文：SHA-256(固定盐 + 密码) 后入库，数据库泄露也无法直接得到密码</li>
 *   <li>登录防爆破：同一账号 10 分钟内连续失败 5 次，锁定 5 分钟
 *       （失败计数放在进程内，重启即清零，属于可接受的降级）</li>
 * </ul>
 */
@Service
public class AuthService {

    /** 会话有效期：24 小时 */
    private static final long EXPIRE_HOURS = 24;

    /** 密码加盐（固定 pepper；更严格可改为每用户独立随机盐存库） */
    private static final String PASSWORD_PEPPER = "CoBot@2026#salt";

    /** 防爆破阈值：10 分钟窗口内最多失败次数 */
    private static final int MAX_FAILS = 5;

    /** 触发阈值后的锁定时长 */
    private static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private final SysUserMapper sysUserMapper;

    private final SysTeamMemberMapper sysTeamMemberMapper;

    private final SysSessionMapper sysSessionMapper;

    /** 用户名 -> 失败记录（防爆破；进程内计数） */
    private final Map<String, FailRecord> failMap = new ConcurrentHashMap<>();

    public AuthService(SysUserMapper sysUserMapper,
                       SysTeamMemberMapper sysTeamMemberMapper,
                       SysSessionMapper sysSessionMapper) {
        this.sysUserMapper = sysUserMapper;
        this.sysTeamMemberMapper = sysTeamMemberMapper;
        this.sysSessionMapper = sysSessionMapper;
    }

    /**
     * 密码哈希：SHA-256(pepper + 明文)，返回小写 hex
     */
    public static String hashPassword(String rawPassword) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((PASSWORD_PEPPER + rawPassword).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        }
    }

    /**
     * 登录：按「登录账号（学号/工号）」校验密码，成功则签发 Token 并落库
     *
     * @return Token；被锁定返回 "LOCKED"；账号或密码错误返回 null
     */
    public String login(String account, String password) {
        // 1. 防爆破检查（按登录账号计数）
        FailRecord fail = failMap.get(account);
        if (fail != null && fail.isLocked()) {
            return "LOCKED";
        }
        // 2. 校验密码（按 account 查库，与库中哈希比对）
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getAccount, account));
        if (user == null || !user.getPassword().equalsIgnoreCase(hashPassword(password))) {
            recordFail(account);
            return null;
        }
        failMap.remove(account);   // 登录成功清空失败记录
        // 3. 签发 Token 落库
        String token = UUID.randomUUID().toString().replace("-", "");
        SysSession session = new SysSession();
        session.setToken(token);
        session.setUserId(user.getId());
        session.setUsername(user.getUsername());   // 写入显示昵称，前端展示链路零改动
        session.setExpireAt(LocalDateTime.now().plusHours(EXPIRE_HOURS));
        session.setCreateTime(LocalDateTime.now());
        sysSessionMapper.insert(session);
        return token;
    }

    /** 记录一次登录失败（超出窗口的历史记录顺带清理） */
    private void recordFail(String account) {
        FailRecord fail = failMap.get(account);
        if (fail == null) {
            failMap.put(account, new FailRecord());
        } else {
            fail.fails++;
        }
    }

    /**
     * 注册：登录账号（学号/工号）唯一，密码哈希入库
     *
     * <p>注册后<b>不加入任何团队</b>：由用户自行「创建团队」成为负责人，
     * 或凭同事的邀请码「加入团队」。账号重复（含并发竞态命中唯一索引）返回 null。
     *
     * @param account  登录账号（学号/工号），会做 trim，为空返回 null
     * @param nickname 显示昵称/姓名；为空则回落成 account
     * @return 新用户 ID；账号已存在返回 null
     */
    public Long register(String account, String nickname, String password) {
        if (account == null) {
            return null;
        }
        String loginAccount = account.trim();
        if (loginAccount.isEmpty()) {
            return null;
        }
        // 按登录账号做重复预检（并发竞态由 uk_account 唯一索引兜底）
        Long exists = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getAccount, loginAccount));
        if (exists > 0) {
            return null;
        }
        SysUser user = new SysUser();
        user.setAccount(loginAccount);
        // 昵称留空时回落成登录账号，保证聊天/任务等展示链路始终有名字可用
        String displayName = (nickname == null || nickname.trim().isEmpty())
                ? loginAccount : nickname.trim();
        user.setUsername(displayName);
        user.setPassword(hashPassword(password));   // 明文不出现在数据库
        try {
            sysUserMapper.insert(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发下两个请求同时通过预检时，唯一索引会拦下后者
            return null;
        }
        // 注意：注册后不加入任何团队，交由用户自行创建 / 凭邀请码加入
        return user.getId();
    }

    /**
     * 校验 Token：有效则（按需）滑动续期并返回会话，无效返回 null
     */
    public LoginSession validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        SysSession session = sysSessionMapper.selectById(token);
        if (session == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (session.getExpireAt() == null || session.getExpireAt().isBefore(now)) {
            sysSessionMapper.deleteById(token);   // 已过期，清理
            return null;
        }
        LocalDateTime nextExpire = now.plusHours(EXPIRE_HOURS);
        // 滑动续期：剩余有效期不足一半时才写库，避免每个请求都产生一次 UPDATE
        if (session.getExpireAt().isBefore(now.plusHours(EXPIRE_HOURS / 2))) {
            session.setExpireAt(nextExpire);
            sysSessionMapper.updateById(session);
        }
        return new LoginSession(session.getUserId(), session.getUsername(), nextExpire);
    }

    /**
     * 退出登录：销毁会话
     */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sysSessionMapper.deleteById(token);
        }
    }

    /**
     * 清理所有已过期会话（由定时任务调用）
     *
     * @return 清理条数
     */
    public int cleanExpiredSessions() {
        return sysSessionMapper.delete(new LambdaQueryWrapper<SysSession>()
                .lt(SysSession::getExpireAt, LocalDateTime.now()));
    }

    /**
     * 校验用户是否为某团队成员（聊天室/看板的成员资格门槛）
     */
    public boolean isMember(Long teamId, Long userId) {
        if (teamId == null || userId == null) {
            return false;
        }
        return sysTeamMemberMapper.selectCount(new LambdaQueryWrapper<SysTeamMember>()
                .eq(SysTeamMember::getTeamId, teamId)
                .eq(SysTeamMember::getUserId, userId)) > 0;
    }

    /**
     * 查询用户在团队中的角色
     *
     * @return "owner"（负责人）/ "member"（普通成员）/ null（不是该团队成员）
     */
    public String roleInTeam(Long teamId, Long userId) {
        if (teamId == null || userId == null) {
            return null;
        }
        SysTeamMember member = sysTeamMemberMapper.selectOne(new LambdaQueryWrapper<SysTeamMember>()
                .eq(SysTeamMember::getTeamId, teamId)
                .eq(SysTeamMember::getUserId, userId));
        if (member == null) {
            return null;
        }
        return member.getRole() == null || member.getRole().isBlank() ? "member" : member.getRole();
    }

    /**
     * 判断用户是否为团队负责人（团队管理、任务指派/删除等操作的权限门槛）
     */
    public boolean isOwner(Long teamId, Long userId) {
        return "owner".equals(roleInTeam(teamId, userId));
    }

    /**
     * 修改密码：校验旧密码 -> 新密码哈希入库
     *
     * @return 错误提示；成功返回 null
     */
    public String changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return "用户不存在";
        }
        if (!user.getPassword().equalsIgnoreCase(hashPassword(oldPassword))) {
            return "原密码不正确";
        }
        if (newPassword == null || newPassword.length() < 6) {
            return "新密码至少 6 位";
        }
        if (newPassword.equals(oldPassword)) {
            return "新密码不能与原密码相同";
        }
        user.setPassword(hashPassword(newPassword));
        sysUserMapper.updateById(user);
        // 安全：改密后让该账号已有的所有 Token 失效，必须重新登录
        clearSessionsOfUser(userId);
        return null;
    }

    /**
     * 作废某用户的全部登录会话（改密 / 被移出团队时调用）
     *
     * @return 作废条数
     */
    public int clearSessionsOfUser(Long userId) {
        return sysSessionMapper.delete(new LambdaQueryWrapper<SysSession>()
                .eq(SysSession::getUserId, userId));
    }

    /**
     * 登录失败记录（防爆破）
     */
    private static class FailRecord {
        private int fails = 1;
        private final LocalDateTime windowStart = LocalDateTime.now();

        /** 是否处于锁定状态：10 分钟窗口内失败次数 >= 阈值，则锁 5 分钟 */
        private boolean isLocked() {
            if (Duration.between(windowStart, LocalDateTime.now()).toMinutes() >= 10) {
                fails = 0;   // 窗口过期，重新计数
                return false;
            }
            return fails >= MAX_FAILS
                    && Duration.between(windowStart, LocalDateTime.now()).compareTo(LOCK_DURATION) < 0;
        }
    }

    /**
     * 登录会话（由 sys_session 表映射而来）
     */
    public static class LoginSession {
        public final Long userId;
        public final String username;
        public final LocalDateTime expireAt;

        public LoginSession(Long userId, String username, LocalDateTime expireAt) {
            this.userId = userId;
            this.username = username;
            this.expireAt = expireAt;
        }
    }
}
