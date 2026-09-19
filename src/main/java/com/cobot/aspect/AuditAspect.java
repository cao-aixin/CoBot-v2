package com.cobot.aspect;

import com.cobot.annotation.AuditLog;
import com.cobot.dto.Result;
import com.cobot.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作审计切面
 *
 * <p>拦截打了 {@code @AuditLog} 的 Controller 方法，方法正常返回后自动落一条审计：
 * <ul>
 *   <li>操作人：从拦截器塞进 request 的 loginUserId / loginUsername 取（前端传参不作数）</li>
 *   <li>团队：按参数名 {@code teamId} 自动提取（团队维度检索审计的常用姿势）</li>
 *   <li>对象：按参数名 {@code taskId / artifactId / userId} 自动提取，便于精确追责</li>
 *   <li>结果：取返回值 Result 的 msg；失败（code≠200）会加 [失败] 前缀</li>
 * </ul>
 *
 * <p>切面内任何异常都被吞掉，绝不影响主流程。
 */
@Aspect
@Component
public class AuditAspect {

    private final AuditService auditService;

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @AfterReturning(pointcut = "@annotation(auditLog)", returning = "result")
    public void afterReturning(JoinPoint joinPoint, AuditLog auditLog, Object result) {
        try {
            HttpServletRequest request = currentRequest();
            Long userId = null;
            String username = null;
            String ip = null;
            if (request != null) {
                Object uid = request.getAttribute("loginUserId");
                if (uid instanceof Long id) {
                    userId = id;
                }
                Object uname = request.getAttribute("loginUsername");
                if (uname instanceof String name) {
                    username = name;
                }
                ip = clientIp(request);
            }

            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String[] names = signature.getParameterNames();
            Object[] args = joinPoint.getArgs();

            Long teamId = extractLong(names, args, "teamId");
            String target = buildTarget(names, args);

            String detail = auditLog.value();
            boolean ok = true;
            if (result instanceof Result<?> r) {
                ok = r.getCode() == 200;
                String msg = r.getMsg() == null ? "" : r.getMsg();
                detail = (detail == null || detail.isBlank()) ? msg : detail + " → " + msg;
            }
            if (!ok) {
                detail = "[失败] " + (detail == null ? "" : detail);
            }

            auditService.record(teamId, userId, username, auditLog.action(), target, detail, ip);
        } catch (Exception ignored) {
            // 审计只为留痕，绝不打断业务
        }
    }

    /**
     * 按参数名提取 Long 值（如 teamId）
     */
    private Long extractLong(String[] names, Object[] args, String paramName) {
        if (names == null) {
            return null;
        }
        for (int i = 0; i < names.length; i++) {
            if (paramName.equals(names[i]) && args[i] instanceof Number n) {
                return n.longValue();
            }
        }
        // 兜底：record 类型 DTO 里含 teamId() 访问器时也尝试取（如 PlanRequest）
        for (Object arg : args) {
            Long v = tryRecordAccessor(arg, "teamId");
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * 组装操作对象描述：优先取任务/产物/目标用户，其次退化为方法名
     */
    private String buildTarget(String[] names, Object[] args) {
        String[] keys = {"taskId", "artifactId", "userId", "id"};
        if (names != null) {
            for (String key : keys) {
                for (int i = 0; i < names.length; i++) {
                    if (key.equals(names[i]) && args[i] instanceof Number n) {
                        return key + "=" + n.longValue();
                    }
                }
            }
        }
        for (Object arg : args) {
            for (String key : keys) {
                Long v = tryRecordAccessor(arg, key);
                if (v != null) {
                    return key + "=" + v;
                }
            }
        }
        return null;
    }

    /**
     * 反射读取 record / POJO 的访问器（兼容 Java record 的 teamId() 与 getTeamId()）
     */
    private Long tryRecordAccessor(Object arg, String field) {
        if (arg == null || arg instanceof String || arg instanceof Number) {
            return null;
        }
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (String methodName : new String[]{field, "get" + cap}) {
            try {
                var m = arg.getClass().getMethod(methodName);
                Object v = m.invoke(arg);
                if (v instanceof Number n) {
                    return n.longValue();
                }
            } catch (Exception ignored) {
                // 该访问器不存在，继续尝试下一个
            }
        }
        return null;
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    /** 取真实客户端 IP（兼容反向代理场景） */
    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            int comma = ip.indexOf(',');
            return comma > 0 ? ip.substring(0, comma).trim() : ip.trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }
}
