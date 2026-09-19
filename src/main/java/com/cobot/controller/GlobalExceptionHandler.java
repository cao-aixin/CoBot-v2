package com.cobot.controller;

import com.cobot.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 *
 * <p>捕获大模型 API 异常、数据库异常等，统一返回 Result 结构，
 * 前端聊天室把 msg 以 bot 气泡形式展示，避免白屏/静默失败。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 大模型调用异常（Key 未配置 / 网络 / 限流）
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("[CoBot] 全局异常：", e);
        String msg;
        if (e instanceof NonTransientAiException
                || (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("api key")))) {
            msg = "大模型调用失败：请检查 AI_API_KEY 配置，或将 application.yml 中 agent.mode 切换为 local（本地规则模式，无需 Key）。";
        } else {
            msg = "服务异常：" + e.getClass().getSimpleName() + "，请查看后端日志。";
        }
        return Result.fail(msg);
    }
}
