package com.cobot.controller;

import com.cobot.dto.Result;
import com.cobot.service.AuthService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口（登录 / 注册 / 退出 / 当前用户信息）
 *
 * <p>POST /api/auth/login    登录，返回 Token
 * <br>POST /api/auth/register 注册（注册后不加入任何团队，由用户自行创建或凭邀请码加入）
 * <br>POST /api/auth/logout  退出登录（销毁 Token）
 * <br>GET  /api/auth/me      当前登录用户信息（前端刷新后恢复会话用）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Resource
    private AuthService authService;

    /**
     * 登录 / 注册请求体
     *
     * @param account  登录账号（学号/工号，唯一凭据）
     * @param password 密码
     * @param nickname 显示昵称/姓名，仅注册时使用（留空则回落成 account）
     */
    public record LoginRequest(String account, String password, String nickname) {}

    /** 修改密码请求体 */
    public record PasswordRequest(String oldPassword, String newPassword) {}

    /**
     * 登录
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest request) {
        if (request.account() == null || request.account().isBlank()
                || request.password() == null || request.password().isBlank()) {
            return Result.fail("账号和密码不能为空");
        }
        String token = authService.login(request.account().trim(), request.password());
        if ("LOCKED".equals(token)) {
            return Result.fail("失败次数过多，账号已临时锁定，请 5 分钟后再试");
        }
        if (token == null) {
            return Result.fail("账号或密码错误");
        }
        AuthService.LoginSession session = authService.validate(token);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("userId", session.userId);
        // username = 显示昵称（前端据此做「我的任务 / 指派」等身份比对），保持不变
        data.put("username", session.username);
        // account = 登录账号（学号/工号），新增字段
        data.put("account", request.account().trim());
        return Result.ok(data);
    }

    /**
     * 注册（登录账号唯一；注册后不加入任何团队）
     */
    @PostMapping("/register")
    public Result<String> register(@RequestBody LoginRequest request) {
        if (request.account() == null || request.account().isBlank()
                || request.password() == null || request.password().isBlank()) {
            return Result.fail("账号和密码不能为空");
        }
        if (request.password().length() < 4) {
            return Result.fail("密码至少 4 位");
        }
        String nickname = request.nickname() == null ? null : request.nickname().trim();
        if (nickname != null && nickname.length() > 50) {
            return Result.fail("昵称最长 50 个字");
        }
        Long userId = authService.register(request.account(), nickname, request.password());
        if (userId == null) {
            return Result.fail("该账号已被注册，请直接登录或更换账号");
        }
        return Result.ok("注册成功，请创建自己的团队，或凭邀请码加入同事的团队");
    }

    /**
     * 修改密码（需登录；修改成功后该账号所有会话失效，需重新登录）
     */
    @PostMapping("/password")
    public Result<String> changePassword(@RequestBody PasswordRequest request,
                                         HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        String err = authService.changePassword(loginUserId, request.oldPassword(), request.newPassword());
        if (err != null) {
            return Result.fail(err);
        }
        return Result.ok("密码修改成功，请重新登录");
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Result<String> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.logout(extractToken(authHeader));
        return Result.ok("已退出登录");
    }

    /**
     * 当前登录用户信息
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        AuthService.LoginSession session = authService.validate(extractToken(authHeader));
        if (session == null) {
            return Result.fail("未登录或登录已过期");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", session.userId);
        data.put("username", session.username);
        return Result.ok(data);
    }

    /**
     * 从 Authorization 头提取 Token（格式：Bearer xxx）
     */
    public static String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return authHeader.substring(7).trim();
    }
}
