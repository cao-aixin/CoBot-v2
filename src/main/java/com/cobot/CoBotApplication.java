package com.cobot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CoBot AI 团队协作智能体 V2 启动类
 *
 * <p>本版本按《修改》文档要求，从"飞书群机器人"改造为【Web 网页聊天室】形态：
 * <ul>
 *   <li>双模式 Agent：llm（SpringAI ToolCalling 调用大模型）/ local（本地正则+关键词 Skill），yml 配置切换</li>
 *   <li>不依赖任何第三方 IM（飞书/钉钉/企微），聊天室直接跑在浏览器里</li>
 *   <li>三个 Skill 工具：任务提取、任务查询、定时提醒</li>
 * </ul>
 *
 * <p>@EnableScheduling：开启定时任务（到期任务扫描提醒）
 */
@EnableScheduling
@SpringBootApplication
public class CoBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoBotApplication.class, args);
        System.out.println("""
                ==========================================================
                  CoBot-v2 启动成功！
                  聊天室/看板入口: http://localhost:8093
                  Agent 模式: agent.mode = llm(大模型) / local(本地规则)
                ==========================================================
                """);
    }
}
