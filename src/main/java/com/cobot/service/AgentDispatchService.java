package com.cobot.service;

import com.cobot.dto.AiTaskParseDTO;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Agent 调度代理层（双模式分发核心）
 *
 * <p>按 yml 中 {@code agent.mode} 配置把请求分发到两种实现：
 * <ul>
 *   <li><b>llm</b> —— {@link AiTaskParseService}：SpringAI ChatClient + Tool Calling 调用大模型</li>
 *   <li><b>local</b> —— {@link LocalAgentService}：本地正则 + 关键词 Skill，无需大模型 Key，可离线运行</li>
 * </ul>
 * 上层业务（TaskBusinessService / Controller）只依赖本类，不感知具体模式。
 */
@Service
public class AgentDispatchService {

    /** 当前 Agent 模式：llm / local（application.yml 配置） */
    @Value("${agent.mode}")
    private String agentMode;

    @Resource
    private AiTaskParseService llmAgentService;

    @Resource
    private LocalAgentService localAgentService;

    /**
     * 是否为 llm 模式
     */
    public boolean isLlmMode() {
        return "llm".equalsIgnoreCase(agentMode);
    }

    /**
     * 从文本解析任务（结构化 DTO）
     */
    public AiTaskParseDTO parseTaskFromText(String userText, Long teamId, String requesterName) {
        if (isLlmMode()) {
            return llmAgentService.parseTaskFromText(userText, teamId, requesterName);
        }
        return localAgentService.parseTaskFromText(userText, teamId, requesterName);
    }

    /**
     * 意图分发：任务创建 / 任务查询 / 定时提醒 / 兜底闲聊
     *
     * @param userText 用户输入
     * @param userId   当前用户 ID
     * @param teamId   当前团队 ID
     * @return bot 回复文本；无法理解时返回 null（由上层决定是否回复）
     */
    public String dispatchSkill(String userText, Long userId, Long teamId, String requesterName) {
        if (isLlmMode()) {
            return llmAgentService.chatWithTool(userText, userId, teamId, requesterName);
        }
        return localAgentService.dispatchSkill(userText, userId, teamId, requesterName);
    }
}
