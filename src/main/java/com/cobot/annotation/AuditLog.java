package com.cobot.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解
 *
 * <p>打在 Controller 方法上，由 {@code AuditAspect} 自动把这次操作写入 sys_audit_log：
 * 谁（登录会话）、在哪个团队（参数中的 teamId）、做了什么（action + value）、结果如何（返回消息）。
 *
 * <p>用法：
 * <pre>
 *   &#64;AuditLog(action = "TASK_DELETE", value = "删除任务")
 *   public Result&lt;String&gt; delete(...)
 * </pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 动作标识（常量风格，便于检索统计），如 TEAM_RENAME / TASK_DELETE */
    String action();

    /** 动作中文描述，写进日志详情的开头 */
    String value() default "";
}
