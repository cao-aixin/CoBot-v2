package com.cobot.config;

import com.cobot.controller.AuthController;
import com.cobot.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

/**
 * Web MVC 配置：注册登录拦截器
 *
 * <p>拦截规则：
 * <ul>
 *   <li>拦截 /api/** 下所有业务接口</li>
 *   <li>放行 /api/auth/login、/api/auth/register（登录注册本身无需 Token）</li>
 *   <li>未携带有效 Token -> 401，前端收到后跳回登录页</li>
 *   <li>校验通过 -> 把登录用户 ID 放入 request 属性 "loginUserId"，业务接口直接取用，
 *       前端传什么 userId 都不作数，杜绝冒充他人发言</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 登录校验拦截器（通过构造器注入 AuthService，保证依赖可用）
     */
    static class AuthInterceptor implements HandlerInterceptor {

        private final AuthService authService;

        private final ObjectMapper objectMapper = new ObjectMapper();

        AuthInterceptor(AuthService authService) {
            this.authService = authService;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                 Object handler) throws Exception {
            String token = AuthController.extractToken(request.getHeader("Authorization"));
            AuthService.LoginSession session = authService.validate(token);
            if (session == null) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        objectMapper.writeValueAsString(Map.of("code", 401, "msg", "未登录或登录已过期")));
                return false;
            }
            // 登录身份注入请求属性，业务层只认这里，不认前端参数
            request.setAttribute("loginUserId", session.userId);
            request.setAttribute("loginUsername", session.username);
            return true;
        }
    }

    private final AuthService authService;

    public WebMvcConfig(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(authService))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register");
    }
}
