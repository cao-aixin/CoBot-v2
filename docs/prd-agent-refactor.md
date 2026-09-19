# PRD：CoBot-v2 AI 层架构重构 —— "一个功能一个小智能体 + Skill 优先"

- **文档版本**：v1.0（增量 PRD，只描述变更，不重复既有功能规格）
- **作者**：Alice（产品经理）
- **状态**：待架构师评审
- **上游需求**（用户原话）：「能修改一下，修改为先调用自己的skill，一个功能分成一个小智能体项目吗」

---

## 0. 项目信息与现状基线

- **工程**：`D:\workbuddy\CoBot智能体\CoBot-v2`（Spring Boot 3.4.5 + SpringAI 1.0.0 / DeepSeek `deepseek-chat` + MyBatis-Plus + MySQL `cobot_db`，单 jar，端口 8093，前端为单文件 Vue3 CDN `src/main/resources/static/index.html`）
- **AI 层现状**：
  - `AiController`（354 行，13 个 `/api/ai/**` 端点，其中 6 个走大模型：insight / plan / ppt / meeting / weekly / ask）
  - `AgentDispatchService`（62 行，聊天室双模式分发 llm/local）
  - `AiContentService`（816 行，洞察/计划/PPT + 历史/详情/文件，持有 ChatClient，已有 local 降级模板）
  - `AiExtendService`（550 行，纪要/周报/问答，持有 ChatClient）
  - `AiTaskParseService`（137 行）/ `LocalAgentService`（81 行）：聊天智能体 llm / local 两路
  - `skill/`：TaskExtractSkill、TaskQuerySkill、RemindSkill（3 个 @Tool）
  - `TeamStatsService`（337 行，真实聚合统计，是洞察/周报/问答的数据源）
  - `PptxBuildService`（279 行，POI 渲染真实 .pptx）
- **硬约束（不可破坏）**：
  1. 对外 HTTP 接口路径与响应结构**完全不变**，前端 `index.html` **零改动**；
  2. `PlainTextCleaner` 的 AI 输出去符号清洗收口**必须保留**，任何智能体输出不得绕过；
  3. `TeamStatsService` 的"**数字真实、文字才由 LLM 写**"原则不变。

---

## 1. 产品目标

将 CoBot-v2 现有 7 个 AI 能力（聊天室智能体 + AI 工作台的洞察、计划、PPT、纪要、周报、问答）从"集中在 3~4 个大 Service 里直接调大模型"重构为"**每个 AI 能力一个独立小智能体类**"，并统一确立"**Skill 优先、模型兜底**"的调用链：每个智能体先尝试用自己挂载的 Skill（本地规则/模板，零成本、确定性强、离线可用）完成请求，Skill 无法覆盖时才回退调用大模型。目标是在**不改动对外接口与前端**的前提下，降低大模型调用量与响应延迟、提升输出确定性、让每个 AI 能力可独立维护与独立开关。

---

## 2. 用户故事

**团队成员（使用 AI 工作台 / 聊天室）**
- US-1：As a 团队成员, I want 生成周报/洞察时优先走本地 Skill 秒级返回, so that 网络或大模型服务异常时我依然能拿到基于真实统计数据的结果，而不是报错。
- US-2：As a 团队成员, I want 每次生成结果里能看出"这是本地模板算的还是大模型写的", so that 我对结果的可信度和成本有预期（Skill 结果确定性高、LLM 结果更丰富）。

**负责人（团队管理员）**
- US-3：As a 负责人, I want PPT/计划等耗模型的生成在 Skill 命中时响应更快、失败时自动降级到模板兜底, so that 汇报场景不因 DeepSeek 超时而卡壳。
- US-4：As a 负责人, I want 数据类数字始终来自 TeamStatsService 的真实统计、只有叙述文字由大模型润色, so that 我敢直接把 AI 产出用于管理汇报。

**开发者（维护视角）**
- US-5：As a 开发者, I want 每个AI 能力是一个独立的小智能体类（如 InsightAgent、WeeklyAgent）, so that 修改某一个能力时不用在 800+ 行的大 Service 里定位，也不会影响其他能力。
- US-6：As a 开发者, I want 智能体的"Skill 优先 → 模型兜底 → local 模板降级"调用链是统一模式而非各写各的, so that 新增第 8 个 AI 能力时照抄模板即可，且 PlainTextCleaner 清洗天然收口。

---

## 3. 需求池

### P0 —— Must have（本期范围）

**P0-1：7 个智能体类拆分（同工程内独立类，不拆 Maven 模块）**

| # | 智能体类（建议名） | 承接现状 | 覆盖端点 |
|---|---|---|---|
| 1 | `ChatAgent` | `AiTaskParseService`(llm 路) + `LocalAgentService`(local 路)，经 `AgentDispatchService` 分发 | 聊天室消息链路 |
| 2 | `InsightAgent` | 从 `AiContentService` 迁出洞察生成 | `/api/ai/insight` 相关 |
| 3 | `PlanAgent` | 迁出项目计划生成 | `/api/ai/plan` 相关 |
| 4 | `PptAgent` | 迁出 PPT 大纲/生成（POI 渲染继续走 `PptxBuildService`） | `/api/ai/ppt` 相关 |
| 5 | `MeetingAgent` | 从 `AiExtendService` 迁出会议纪要 | `/api/ai/meeting` 相关 |
| 6 | `WeeklyAgent` | 迁出周报 | `/api/ai/weekly` 相关 |
| 7 | `AskAgent` | 迁出自由问答 | `/api/ai/ask` 相关 |

- 验收标准：
  - `AiController` 13 个端点的 URL、请求/响应 JSON 结构**逐字段不变**；Controller 只做参数校验与编排，不再直接持有业务生成逻辑；
  - 每个智能体类单文件 ≤ 300 行，只依赖自己的 Skill + ChatClient（兜底时）+ 所属数据服务（`TeamStatsService` / `PptxBuildService` / Mapper 等）；
  - `AiContentService` / `AiExtendService` 拆分后仅保留历史、详情、文件管理等非生成逻辑（或由各智能体与 Controller 直接复用其查询方法），生成方法迁空。

**P0-2：Skill 优先、模型兜底的统一调用链**

- 每个智能体内部固定三段式顺序：
  1. **Skill 尝试**：调用本智能体挂载的 Skill（现有 3 个 @Tool Skill 优先复用，不足则新增轻量规则/模板 Skill，如 `InsightSkill` 基于 `TeamStatsService` 聚合结果直接拼装结论）；
  2. **模型兜底**：Skill 判定"覆盖不了"（输入超出规则范围、置信度不足、Skill 抛出业务性无法处理）时，才调用 ChatClient（DeepSeek）；
  3. **local 模板降级**：模型调用异常/超时时，回落到现状已有的 local 降级模板，保证请求不失败。
- 硬性规则：
  - 所有 LLM 输出（兜底与降级除外——local 模板本身已是干净文本）必须过 `PlainTextCleaner` 后再返回，清洗收口不绕过；
  - 数字类内容一律来自 `TeamStatsService` 真实统计，Skill 与 LLM 都不得编造数字；
  - `AiController` 对外响应结构不变的前提下，Service 层内部传递需可区分"本次结果来自 skill / llm / fallback"。
- 验收标准：切断 DeepSeek 配置（模拟不可用）时，7 个能力全部仍能返回合法响应（走 Skill 或 fallback）；恢复后 LLM 链路自动生效。

### P1 —— Should have（本期尽量完成）

**P1-1：统一 Agent 基类与注册表**
- 抽象 `AbstractAgent` 基类：固化三段式调用链骨架（`trySkill → fallbackLlm → fallbackLocal`），子类只实现"Skill 能否处理""如何调模型""local 模板长什么样"三个钩子；
- `AgentRegistry`：按能力名注册/查找 7 个智能体，`AiController` 与 `AgentDispatchService` 通过注册表路由，替代散落的 if/else；
- 验收标准：新增一个演示性智能体只需继承基类 + 注册一行，无需改调用链代码。

**P1-2：可观测的"本次是否调用了大模型"标记**
- 每次生成在响应元数据中携带来源标记（skill / llm / fallback）与耗时（内部字段，随现有响应结构透出即可，不新增破坏性字段；若响应结构不允许新增字段，则记录到日志/审计表）；
- 日志按智能体维度输出 Skill 命中情况，为后续 P2 的配置开关提供数据依据。

### P2 —— Nice to have（下期或按需）

**P2-1：智能体级配置开关**：每个智能体支持配置项（如 `cobot.agent.insight.mode = skill-first | llm-only | skill-only`），负责人可按能力关闭大模型调用以控成本。

**P2-2：超时与降级策略细化**：按智能体差异化设置 LLM 超时（如问答 30s、PPT 大纲 60s）、重试次数、以及降级模板的差异化文案。

---

## 4. 待确认问题

| # | 问题 | 给谁确认 | 我的倾向 |
|---|---|---|---|
| Q1 | **Skill 命中率的判定标准**：Skill "处理不了"如何判定？建议两条硬规则——① 输入为空/超长/含 Skill 规则无法解析的意图时返回"未覆盖"；② Skill 输出缺少必需字段（如洞察无任何统计数字）视为未命中。是否还需要模型参与判定（不推荐，会反向引入模型调用）？ | 架构师 | 纯规则判定，不引入模型判定 |
| Q2 | **PPT 大纲是否也 Skill 优先**：PPT 大纲个性化强，Skill 模板只能覆盖"按主题+周数生成通用大纲"。建议 PPT 走"Skill 出骨架、LLM 润色每页要点"，还是 PPT 保持 llm 优先仅降级？ | 架构师 + 主理人 | PPT 例外处理，其余 6 个能力严格 Skill 优先 |
| Q3 | **聊天室智能体是否并入同一套 Agent 框架**：`ChatAgent` 的分发逻辑（`AgentDispatchService` 双模式）已有自己的 llm/local 路径，并入 `AbstractAgent` 三段式可统一但要动 `AgentDispatchService`（62 行，改动可控）。并入还是保持独立？ | 架构师 | 并入，但保留 `AgentDispatchService` 作为聊天室专用入口 |
| Q4 | **响应结构内是否允许新增来源标记字段**：P1-2 的 skill/llm/fallback 标记若放响应体，前端虽"零改动也能跑"但严格说 JSON 多了字段；若只进日志则前端用户不可见（US-2 落空）。取舍？ | 主理人 | 日志+审计表记录，响应不加字段，绝对保零改动 |
| Q5 | **拆分后 `AiContentService`/`AiExtendService` 的去留**：生成逻辑迁空后，历史/详情/文件管理是留在原 Service，还是随之分散？ | 架构师 | 保留原 Service 只做查询与文件，智能体只管生成 |
| Q6 | **现有 3 个 @Tool Skill 的定位**：它们目前是作为 ChatClient 的 function-calling 工具。重构后是继续给 LLM 兜底时挂载，还是也作为各智能体的"Skill 优先"第一段？ | 架构师 | 两者兼顾：兜底时挂载，ChatAgent 同时以其为第一段 |

---

## 5. 验收总则（红线）

1. `index.html` 一行不改，13 个 `/api/ai/**` 端点请求/响应逐字段兼容；
2. 任意 AI 输出（除 local 模板直出）必经 `PlainTextCleaner`；
3. 数字仅出自 `TeamStatsService` 真实统计；
4. DeepSeek 完全不可用时，7 个能力零报错（Skill/fallback 兜底）。

---
*（完）*



