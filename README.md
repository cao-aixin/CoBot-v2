# CoBot —— AI 团队协作智能体 v2

> Web 网页聊天室 + 双模式 Agent 的团队协作机器人（不依赖第三方 IM）

在自建网页聊天室里 **@ 机器人** 即可驱动团队协作：自然语言派发任务、一键生成 PPT、自动写周报、会议纪要解析、定时提醒。

## 技术栈

| 层 | 技术 |
|---|---|
| 框架 | Spring Boot 3.4.5 + Java 17 |
| AI | Spring AI 1.0.0（OpenAI 兼容协议，可对接豆包 Ark / 通义 DashScope / DeepSeek 等任意兼容网关） |
| 持久层 | MyBatis-Plus 3.5.7 + MySQL |
| 文档生成 | Apache POI（服务端生成真实 `.pptx` / Excel） |
| 接口文档 | SpringDoc OpenAPI + Swagger UI |
| 其他 | AOP 操作审计、Lombok |

## 核心功能

- **双模式 Agent**：本地技能（任务提取 / 任务查询 / 定时提醒）+ AI 派发，`AgentDispatchService` 统一调度
- **AI 任务解析**：自然语言描述 → 结构化任务卡，经"待确认任务流"二次确认后落库
- **一键生成 PPT**：`PptxBuildService` 按主题模板在服务端生成真实 `.pptx` 文件
- **周报 / 团队洞察**：聚合任务与聊天数据自动生成
- **会议纪要解析**：文本 → 会议要点 + 行动项
- **群聊与已读状态**：多人聊天室、@ 提及解析（`MentionParser`）、消息已读回执
- **安全与审计**：登录鉴权、角色权限、AOP 审计日志切面

## 快速开始

```bash
# 1. 初始化数据库（依次执行升级脚本）
mysql -u root -p < sql/cobot_db.sql
# 按需执行 sql/upgrade-*.sql

# 2. 配置 src/main/resources/application.yml
#    将 CHANGE_ME 替换为本地值：数据库密码、AI 网关 base-url / api-key
#    可参考 application-local.yml.example

# 3. 启动
mvn spring-boot:run

# 4. 打开静态聊天页（地址见启动日志，Swagger 文档 /swagger-ui.html）
```

## 测试

- `mvn test`：核心逻辑单元测试（任务解析、@ 解析、导出等）
- `test/`：Python API 冒烟测试、账号权限回归测试、UI 截图

## 设计文档

- [docs/prd-agent-refactor.md](docs/prd-agent-refactor.md) —— Agent 重构 PRD
- [docs/design-agent-refactor.md](docs/design-agent-refactor.md) —— 设计文档
- [docs/file-structure.md](docs/file-structure.md) —— 文件结构说明
- [docs/class-diagram.mermaid](docs/class-diagram.mermaid) / [sequence-diagram.mermaid](docs/sequence-diagram.mermaid) —— 类图 / 时序图

## 安全说明

仓库中所有真实口令 / API Key 均已替换为 `CHANGE_ME` 占位符，本地运行前请替换为自己的值；`application-local.yml`（本地真实配置）已被 `.gitignore` 排除。
