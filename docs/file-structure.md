# CoBot-v2 文件结构说明（代码地图）

> 适用对象：项目作者本人。打开工程时，按本文可立刻知道每个文件是干什么的。
> 所有行数均为真实 `wc -l` 统计（已随最近改动复核）；每个 java 文件的内容均取自其首部 javadoc 与关键方法，非猜测。

---

## 一、项目概览

| 项 | 说明 |
|----|------|
| 工程坐标 | `com.cobot:cobot-web-agent:2.0.0`（单 jar，打出来是 `target/cobot-web-agent-2.0.0.jar`） |
| 技术栈 | Spring Boot **3.4.5** + **Spring AI 1.0.0**（OpenAI 兼容协议，当前对接 **DeepSeek** `deepseek-chat`）+ MyBatis-Plus 3.5.7 + MySQL（库名 `cobot_db`，驱动 `mysql-connector-j`）+ Apache POI 5.2.5 |
| 部署形态 | 单可执行 **fat jar**，监听端口 **8093**（避旧项目 8091）；默认激活 `local` profile，生产用 `SPRING_PROFILES_ACTIVE=prod` |
| 前端形态 | **单文件 Vue3（CDN 引入，无构建步骤）**：`src/main/resources/static/index.html`（2165 行）；UI 用 Element Plus（CDN）；后端直接托管静态页 |
| 大模型接入 | `application.yml` 的 `spring.ai.openai`，密钥走环境变量 `AI_API_KEY`（本地放 `application-local.yml`，已被 .gitignore 排除，**绝不入库**）；`temperature=0.1` |
| 双模式 Agent | `agent.mode`：`llm`（SpringAI ToolCalling 调大模型）/ `local`（本地正则+关键词 Skill，可离线） |
| 附加能力 | 引入了 `springdoc-openapi` 2.7.0 → 自带 **Swagger UI**（通常 `/swagger-ui.html`）自动生成接口文档 |
| 登录态 | Token 落库（`sys_session`），服务重启不踢人；请求头 `Authorization: Bearer <token>`；拦截器注入 `loginUserId`/`loginUsername` |
| 鉴权字段 | 登录凭据是 `account`（学号/工号），`username` 仅为显示昵称；登录载荷字段为 `account`（**不是 username**） |

---

## 二、完整目录树（每个文件标注行数）

```
CoBot-v2/
├── pom.xml                                  (125)  Maven 构建定义（父工程 / 依赖 / 打 jar）
├── .gitignore                               (25)   忽略规则（见 §2.1）
├── qa_symbol_raw.txt                        (357)  QA 原始语料（AI 输出符号核查样本，被 test/qa_symbol_check.py 引用）
├── sql/                                     (6 个 .sql，见 §2.2)
├── src/
│   ├── main/
│   │   ├── java/com/cobot/                  (73 个 .java，见 §3)
│   │   │   ├── CoBotApplication.java        (33)
│   │   │   ├── annotation/AuditLog.java     (31)
│   │   │   ├── aspect/AuditAspect.java      (168)
│   │   │   ├── config/WebMvcConfig.java     (74)
│   │   │   ├── controller/                  (7 个)
│   │   │   ├── dto/                         (21 个)
│   │   │   ├── entity/                      (11 个)
│   │   │   ├── mapper/                      (11 个)
│   │   │   ├── service/                     (15 个)
│   │   │   ├── skill/                       (3 个)
│   │   │   └── util/PlainTextCleaner.java   (187)
│   │   └── resources/
│   │       ├── application.yml               (52)   主配置（端口/数据源/AI/agent.mode/提醒cron/存储根）
│   │       ├── application-local.yml         (13)   本地环境密钥（**被 .gitignore 排除，不入库**）
│   │       ├── application-local.yml.example (15)   本地配置模板（复制为 application-local.yml 后填密钥）
│   │       ├── application-prod.yml          (30)   生产环境配置
│   │       └── static/index.html             (2165) 单文件前端（模板+脚本+样式一体）
│   └── test/java/com/cobot/
│       ├── service/CoreLogicTest.java       (92)
│       ├── service/ExportServiceTest.java   (68)
│       ├── service/MentionParserTest.java   (60)
│       └── util/PlainTextCleanerTest.java   (178)
├── test/                                    (7 个脚本 + output/，见 §7)
└── （被 gitignore 的运行/产物目录，不展开内部文件）
    ├── target/         构建产物（编译类、jar、*.jar 等）
    ├── data/           运行期落盘根（附件 attachments/ 与 AI 产物 artifacts/），cobot.storage.root=./data
    ├── .idea/          IntelliJ IDE 配置
    ├── .verify-cache/  前端自检脚本下载的 Vue 编译器缓存
    └── test/output/    各测试脚本产出的截图与 pptx 产物（被 .gitignore 排除）
```

### 2.1 .gitignore 忽略内容
构建产物 `target/`、`*.jar`、`*.war`、`*.log`、`logs/`；运行期数据 `data/`；IDE `.idea/` `*.iml` `*.ipr` `*.iws` `.vscode/`；**敏感配置 `src/main/resources/application-local.yml`**（含 DB 口令与 AI Key，绝不提交）；`*.tmp`、`test/output/`、`.verify-cache/`。

### 2.2 sql/ 目录（6 个迁移脚本）
| 文件 | 行数 | 作用 |
|------|------|------|
| `cobot_db.sql` | 120 | 建库建表 + 演示种子数据（含 `account` 字段、`123456` 密码哈希）；首次部署执行 |
| `upgrade-account.sql` | 79 | `sys_user` 新增 `account` 列并回填（登录凭据与显示昵称 `username` 拆分） |
| `upgrade-ai-feature.sql` | 23 | AI 工作台所需表/字段（如 `ai_artifact` 产物表） |
| `upgrade-hardening.sql` | 130 | 权限/越权加固（邀请码策略、团队隔离、审计字段等） |
| `upgrade-ppt-theme.sql` | 11 | PPT 配色主题表初始化 |
| `upgrade-role-permission.sql` | 25 | 角色与权限相关字段调整 |

---

## 三、逐文件功能表（按包分组）

> 列：相对路径 · 行数 · 做什么 · 关键点（核心方法 / 对外接口 / 被谁调用 / 坑与约定）

### 3.1 启动与横切

| 相对路径 | 行数 | 做什么 | 关键点 |
|----------|------|--------|--------|
| `CoBotApplication.java` | 33 | 启动类（`@SpringBootApplication` + `@EnableScheduling`） | 双模式 Agent（llm/local）；3 个 Skill；Web 聊天室形态，不依赖飞书/钉钉/企微 |
| `annotation/AuditLog.java` | 31 | 操作审计注解 `@AuditLog(action, value)` | 打在 Controller 方法上，由 `AuditAspect` 落库 `sys_audit_log` |
| `aspect/AuditAspect.java` | 168 | 审计切面（`@AfterReturning` 拦截 `@AuditLog`） | 操作人取拦截器塞的 `loginUserId/Username`；按参数名自动抽 `teamId/taskId/artifactId/userId`；结果取 `Result.msg`，失败加 `[失败]` 前缀；切面异常全程吞掉，不影响主流程；兼容 record 访问器 |
| `config/WebMvcConfig.java` | 74 | 注册登录拦截器 | 拦截 `/api/**`，放行 `/api/auth/login`、`/api/auth/register`；无效 Token 返回 401；通过则把 `loginUserId`、`loginUsername` 放入 request 属性（业务层只信这个） |

### 3.2 controller/（7 个，全部 `@RestController`）

| 相对路径 | 行数 | 做什么 | 关键点（对外接口） |
|----------|------|--------|--------------------|
| `AuthController.java` | 141 | 认证：登录/注册/改密/退出/当前用户 | `POST /api/auth/login`(返回 token+userId+username+account)、`POST /api/auth/register`、`POST /api/auth/password`、`POST /api/auth/logout`、`GET /api/auth/me`；内层 `record LoginRequest(account,password,nickname)`、`PasswordRequest(old,new)`；改密后该账号所有会话失效；token 格式 `Bearer xxx`（静态 `extractToken`） |
| `ChatController.java` | 407 | 聊天室：发消息/附件上传下载/撤回/删除/已读/未读/列表 | `POST /api/chat/send`(文本触发智能体；附件只存不触发)、`POST /upload`、`GET /file/{messageId}`、`POST /revoke`(本人2分钟或负责人)、`POST /delete`(仅负责人,物理删)、`POST /read`、`GET /unread`、`GET /list`(2s 轮询增量)；发送人身份一律取登录态；附件路径强制 `attachments/{teamId}/` 前缀防越权；未读口径=本团队 id>已读id 的条数（非差值，避免跨团队虚高） |
| `AiController.java` | 354 | AI 工作台全部接口（需登录+团队成员；洞察/周报/问答限负责人） | 见 §5；含内层 `record` 请求体 `PlanRequest/PptRequest/ImportRequest/MeetingRequest/AskRequest` |
| `TaskController.java` | 324 | 任务看板 CRUD：列表/确认创建/改状态/指派/删除/审批/待确认/导出 | `GET /api/task/list`、`POST /confirm`(审计 TASK_CONFIRM)、`POST /status`、`POST /assign`(仅负责人)、`POST /delete`(审计)、`POST /approve`(审批)、`GET /pending`、`GET /export`(Excel) |
| `TeamController.java` | 501 | 团队生命周期：创建/加入/成员/信息/退出/改名/公告/重置邀请码/移除成员/改角色/解散 | `POST /api/team/create`、`/join`、`GET /members`、`GET /info`、`POST /leave`、`POST /rename`、`POST /notice`、`POST /reset-invite`、`POST /remove-member`、`POST /set-role`、`POST /dismiss`(事务+审计)；内层 `TeamOpRequest`；多写操作带 `@AuditLog` |
| `CommonController.java` | 169 | 通用只读接口（基类 `/api`） | `GET /api/team/my`(我的团队+角色+未读+任务概览)、`GET /api/user/list`(团队成员)、`GET /api/audit/list`(审计日志) |
| `GlobalExceptionHandler.java` | 34 | 全局异常→`Result` | 捕获 `NonTransientAiException`(大模型API异常)及数据库异常，统一返回，前端以 bot 气泡展示，避免白屏 |

### 3.3 dto/（21 个，传输对象 / 视图对象）

| 相对路径 | 行数 | 做什么 | 关键点 |
|----------|------|--------|--------|
| `Result.java` | 40 | 统一响应 `{code,msg,data}`，`code==200` 成功 | 全局约定 |
| `AiTaskParseDTO.java` | 26 | 智能体解析出的任务结构（任务内容/负责人/截止/优先级） | `PendingTask` 与 `SysTask` 的中间态 |
| `PendingTask.java` | 57 | 待确认任务暂存对象（团队维度只留最近一条） | `toEntity(status)` 负责"负责人→进行中 / 成员→待确认"分流；缺省优先级回落"普通"；非法截止日置空不抛异常 |
| `ArtifactVO.java` | 45 | AI 产物历史列表 VO（不含正文/字节大字段） | 历史列表接口用，避免搬几百 KB PPT |
| `MeetingParseVO.java` | 30 | 纪要拆任务结果 | 一键导入看板 |
| `PptOutlineVO.java` | 59 | PPT 大纲（每页标题+要点） | 真正文件由 `PptxBuildService` 用 POI 渲染 |
| `PptTheme.java` | 117 | PPT 配色主题（主色/强调色/底色三基准色，中间色按比例混白推导） | `ALL` 列表 + `toVO()`；新增主题往 `ALL` 加一行；内部 `ThemeVO` |
| `ProjectPlanVO.java`  | 78 | 项目计划（项目→阶段→任务三层） | 任务默认负责人由大模型参考真实成员给出 |
| `TeamInsightVO.java` | 106 | 团队洞察（真实 stats + AI report） | stats 永远真实可核验，report 由大模型基于 stats 生成 |
| `WeeklyReportVO.java` | 38 | 周报（真实 stats + 文字 report） | 数字来自库聚合，文字由大模型撰写 |
| `TeamVO.java` | 50 | 团队 VO（多 `role` 字段 + 未读/任务概览） | 前端据此渲染负责人/成员入口 |
| `ChatMessageVO.java` | 59 | 聊天消息 VO（发件人昵称/bot固定CoBot/撤回/附件/@提及） | 列表接口返回 |
| `ChatSendRequest.java` | 36 | 发消息请求体 | `userId` 仅兼容保留，服务端不采信；附件先 `/upload` 拿 `filePath` 再 `/send` |
| `ChatMsgRequest.java` | 13 | 撤回/删除通用请求（messageId） | — |
| `ChatReadRequest.java` | 16 | 标记已读（teamId + lastReadId） | — |
| `ChatUploadVO.java` | 25 | 附件上传响应（fileName/filePath/fileSize/msgType） | filePath 服务端签发，发送时校验归属 |
| `TaskApproveRequest.java` | 10 | 审批请求 record(taskId, approved) | 仅负责人 |
| `TaskAssignRequest.java` | 10 | 指派请求 record(taskId, ownerName) | ownerName 须本团队成员 |
| `TaskConfirmRequest.java` | 16 | 确认创建请求（团队ID + 暂存任务） | — |
| `TaskIdRequest.java` | 9 | 仅含 taskId 的请求 record | 删除等 |
| `TaskStatusRequest.java` | 16 | 更新状态（taskId + 目标状态） | 待确认/进行中/已完成/归档 |

### 3.4 entity/（11 个，MyBatis-Plus 实体，`@TableName` 对应表）

| 相对路径 | 行数 | 对应表 | 关键点 |
|----------|------|--------|--------|
| `SysUser.java` | 39 | `sys_user` | **`account` 唯一登录凭据（学号/工号）；`username` 仅显示昵称**；密码 SHA-256 入库，无明文 |
| `SysTeam.java` | 43 | `sys_team` | 一个团队=一个聊天室 |
| `SysTeamMember.java` | 32 | `sys_team_member` | 用户↔团队多对多（含角色） |
| `SysSession.java` | 37 | `sys_session` | 登录 Token 落库，重启不踢人 |
| `SysTask.java` | 44 | `sys_task` | 任务（内容/负责人/截止/状态/优先级） |
| `ChatMessage.java` | 59 | `chat_message` | 用户与 bot 消息统一存；含撤回/附件/@提及字段 |
| `SysReadState.java` | 30 | `sys_read_state` | 每用户每室"已读到的最大 id" |
| `SysAuditLog.java` | 47 | `sys_audit_log` | 谁/何时/哪团队/做了什么/结果 |
| `SysPendingTask.java` | 47 | `sys_pending_task` | 待确认任务落库版（重启不丢） |
| `SysRemindLog.java` | 41 | `sys_remind_log` | 两类提醒共用（定时提醒/任务到期） |
| `AiArtifact.java` | 69 | `ai_artifact` | AI 产物（洞察/计划/PPT/纪要/周报/问答），存正文+可选 .pptx 字节(`@JsonIgnore`) |

### 3.5 mapper/（11 个，MyBatis-Plus `BaseMapper` 零 SQL 基础 CRUD）

| 相对路径 | 行数 | 对应表 | 关键点 |
|----------|------|--------|--------|
| `SysUserMapper.java` | 12 | `sys_user` | 继承 `BaseMapper`，零 SQL |
| `SysTeamMapper.java` | 12 | `sys_team` | — |
| `SysTeamMemberMapper.java` | 12 | `sys_team_member` | — |
| `SysSessionMapper.java` | 12 | `sys_session` | — |
| `SysTaskMapper.java` | 12 | `sys_task` | — |
| `ChatMessageMapper.java` | 12 | `chat_message` | — |
| `SysReadStateMapper.java` | 12 | `sys_read_state` | — |
| `SysAuditLogMapper.java` | 12 | `sys_audit_log` | — |
| `SysPendingTaskMapper.java` | 12 | `sys_pending_task` | — |
| `SysRemindLogMapper.java` | 12 | `sys_remind_log` | — |
| `AiArtifactMapper.java` | 38 | `ai_artifact` | 唯一带手写 SQL（含 `ByteArrayTypeHandler` 读写 pptx 字节） |

### 3.6 service/（15 个）

| 相对路径 | 行数 | 做什么 | 关键点 |
|----------|------|--------|--------|
| `AuthService.java` | 325 | 登录/注册/校验/改密/退出/角色判断 | `hashPassword`(SHA-256,64位,稳定)、`login`、`register`(**不自动加入任何团队**)、`validate(token)`→`LoginSession`、`changePassword`(旧会话全部失效)、`isMember/isOwner`；`@Component` |
| `Chat`链路→`TaskBusinessService`(见下) | — | — | — |
| `TaskBusinessService.java` | 172 | 聊天消息业务闭环 | `handleChatMessage(teamId,userId,content)`：存用户消息→解析@提及→生成回复；"确认"指令调 `confirmPendingTask`(暂存任务落库,负责人→进行中/成员→待确认)；其余交 `AgentDispatchService.dispatchSkill`；bot 回复入库 |
| `AgentDispatchService.java` | 62 | **双模式分发核心** | 按 `agent.mode`：`llm`→`AiTaskParseService`(ChatClient+3 Skill)，`local`→`LocalAgentService`(正则+关键词)；方法 `dispatchSkill` |
| `AiTaskParseService.java` | 137 | llm 模式聊天智能体 | `ChatClient` + 三个 `@Tool` Skill（TaskExtract/TaskQuery/Remind）做 Tool Calling；SpringAI 1.0.0 直接 `.tools(组件)` 注册 |
| `LocalAgentService.java` | 81 | local 模式聊天智能体 | 关键词+正则意图路由，无需大模型 Key，可离线 |
| `AiContentService.java` | 816 | **AI 工作台核心（洞察/计划/PPT/历史）** | `generateInsight`(真实聚合+AI报告,local降级模板)、`generatePlan`、`importPlanTasks`(角色决定初始状态)、`generatePpt`(调 ChatClient 出大纲→`PptxBuildService` 渲染)、`history`、`detail`、`fileBytes`；持有 `ChatClient`，双模式兼容 |
| `AiExtendService.java` | 550 | **AI 工作台二期（纪要/周报/问答）** | `parseMeeting`(LLM拆任务)、`importMeetingTasks`、`generateWeekly`(LLM周报,落库)、`pushWeeklyToChat`(推聊天室)、`askTeam`(基于真实统计问答,不编造)；持有 `ChatClient` |
| `PptxBuildService.java` | 279 | 用 Apache POI 渲染真实 .pptx | 封面/内容/结束页；按 `PptTheme` 上色（主色封面底、内容页左竖条） |
| `TeamStatsService.java` | 337 | **真实聚合统计** | 从库算任务总数/成员数/逾期/负荷等，喂给 `TeamInsightVO.stats`，不依赖大模型 |
| `ExportService.java` | 160 | 任务看板导出 Excel | POI 生成 xlsx；测试用 POI 反向解析校验 |
| `FileStorageService.java` | 172 | 附件/产物落盘到 `./data` | `saveAttachment`(返回服务端签发路径)、`read`、`delete`；`@PostConstruct` 建目录 |
| `ScheduledTaskService.java` | 229 | 定时任务（`@Scheduled`） | 每分钟(`cron 0 * * * * ?`)扫到期提醒/到期任务，推送 bot 消息；依赖 `SysRemindLog` |
| `PendingTaskStore.java` | 118 | 待确认任务**落库版**暂存器 | `poll(teamId,requester)` 取最近一条；服务重启不丢 |
| `MentionParser.java` | 73 | @提及解析器 | 按用户名长度倒序匹配，避免"张三丰"被"张三"抢中 |
| `AuditService.java` | 80 | 审计落库 | `record(...)` 写 `sys_audit_log`；切面调用 |

### 3.7 skill/（3 个 `@Tool` 本地技能，供 llm 模式 Tool Calling 调用）

| 相对路径 | 行数 | 做什么 | 关键点 |
|----------|------|--------|--------|
| `TaskExtractSkill.java` | 183 | 任务提取工具 | 从聊天文本抽出任务（负责人/截止/优先级），写入 `PendingTaskStore`；`@Tool` |
| `TaskQuerySkill.java` | 97 | 任务查询工具 | 按条件查 `sys_task`，返回给大模型组织回答；`@Tool` |
| `RemindSkill.java` | 39 | 定时提醒工具 | 创建提醒→`ScheduledTaskService` 到期推送；V2 直接推聊天室；`@Tool` |

### 3.8 util/（1 个）

| 相对路径 | 行数 | 做什么 | 关键点 |
|----------|------|--------|--------|
| `PlainTextCleaner.java` | 187 | AI 输出纯文本清洗（去 Markdown/装饰符号） | 幂等、null 安全；落库/返前端前统一收口；正文中的 `#`/`-`/`.` 不被误删，中文不破坏 |

### 3.9 测试（src/test/java，4 个 JUnit5，纯逻辑不依赖 Spring 上下文）

| 相对路径 | 行数 | 测什么 |
|----------|------|--------|
| `service/CoreLogicTest.java` | 92 | 密码哈希稳定且与明文不同、种子密码与 `cobot_db.sql` 一致、负责人任务→进行中/成员→待确认、非法截止日置空、缺省优先级回落"普通" |
| `service/ExportServiceTest.java` | 68 | 导出 xlsx 能被 POI 重新解析（表头+数据行正确），而非仅"有字节" |
| `service/MentionParserTest.java` | 60 | @提及解析（普通/长度倒序/边界） |
| `util/PlainTextCleanerTest.java` | 178 | 各清洗规则、幂等性、空值安全、清洗后为空兜底、中文不破坏、正文 `#`/`-`/`.` 不误删 |

---

## 四、分层与调用链路

### 4.1 请求主链路（聊天室发消息触发智能体）
```
浏览器 index.html
  → 统一请求封装（带 Authorization: Bearer <token>）
  → WebMvcConfig.AuthInterceptor.preHandle
        ├─ 无效 token → 401（前端跳登录页）
        └─ 有效 → request.setAttribute("loginUserId","loginUsername")
  → Controller（取登录态身份，不采信前端 userId）
  → Service
  → Mapper（MyBatis-Plus）→ MySQL（cobot_db）
  → 返回 Result → 前端渲染
```
**约定**：所有写操作身份以 `loginUserId` 为准；成员资格用 `AuthService.isMember/isOwner` 校验；越权返回 `code≠200`。

### 4.2 `@AuditLog` + `AuditAspect` 如何工作
1. Controller 方法标 `@AuditLog(action="TASK_CONFIRM", value="确认创建任务")`。
2. 方法**正常返回后**，`AuditAspect.afterReturning`（`@AfterReturning`）触发。
3. 切面从 request 属性取 `loginUserId/loginUsername`（=拦截器注入的值，前端不可伪造）。
4. 按参数名自动抽取 `teamId`（团队维度）与 `taskId/artifactId/userId/id`（精确对象）。
5. 取返回值 `Result`：成功用 `value + " → " + msg`，失败加 `[失败]` 前缀。
6. 调 `AuditService.record(...)` 写 `sys_audit_log`；切面任何异常都被吞掉，**绝不打断业务**。

### 4.3 聊天智能体的"双模式"
```
ChatController.send
 → TaskBusinessService.handleChatMessage
     ├─ 存用户消息 + 解析@提及
     ├─ 若消息=="确认" → PendingTaskStore.poll → sys_task（负责人→进行中 / 成员→待确认）
     └─ 否则 → AgentDispatchService.dispatchSkill
                 ├─ agent.mode=llm  → AiTaskParseService（ChatClient + 3 个 @Tool Skill）
                 └─ agent.mode=local → LocalAgentService（正则+关键词，离线）
     → bot 回复入库 chat_message
```
> 注意：上面这套是**聊天室里的 CoBot**（识别任务、提醒、问答）。AI 工作台（洞察/计划/PPT/纪要/周报/问答）是另一条路，见 §5，二者都最终可能调大模型。

---

## 五、AI 能力清单（AiController 暴露的接口）

全部前缀 `/api/ai`，需登录且为目标团队成员；`insight`/`weekly`/`ask` **仅团队负责人**。

| 接口 | 实现 Service·方法 | 走大模型？ | 说明 |
|------|------------------|-----------|------|
| `GET  /insight` | `AiContentService.generateInsight` | **是**（report；local 模式降级规则模板） | 真实聚合 stats（`TeamStatsService`）+ AI 管理分析；负责人专属 |
| `POST /plan` | `AiContentService.generatePlan` | **是** | 一键生成"阶段→任务"项目计划 |
| `POST /plan/import` | `AiContentService.importPlanTasks` | 否（纯 DB） | 计划批量入看板；负责人→进行中 / 成员→待确认 |
| `POST /ppt` | `AiContentService.generatePpt` | **是**（大纲） | 大纲 + 真实 .pptx（经 `PptxBuildService`/POI 渲染）；可选 `theme` |
| `GET  /themes` | `PptTheme.ALL` | 否（配置） | 配色主题列表（key/中文名/hex） |
| `GET  /history` | `AiContentService.history` | 否（DB） | 当前团队生成历史（倒序） |
| `GET  /detail/{id}` | `AiContentService.detail` | 否（DB） | 产物详情回看（正文/可下载标记） |
| `GET  /file/{id}` | `AiContentService.fileBytes` | 否（文件） | 下载 .pptx（带成员资格校验，防公开链接） |
| `POST /meeting` | `AiExtendService.parseMeeting` | **是** | 纪要拆任务 |
| `POST /meeting/import` | `AiExtendService.importMeetingTasks` | 否（纯 DB） | 纪要任务入看板 |
| `POST /weekly` | `AiExtendService.generateWeekly` | **是** | 周报（数字真实聚合+LLM 撰文），落库；负责人专属 |
| `POST /weekly/push` | `AiExtendService.pushWeeklyToChat` | 否（DB+聊天） | 把已生成周报推送到聊天室 |
| `POST /ask` | `AiExtendService.askTeam` | **是** | 基于真实统计的团队问答，数据没有就说不知道，不编造；负责人专属 |

> 走大模型的 6 个端点（`insight`/`plan`/`ppt`/`meeting`/`weekly`/`ask`）需要有效的 `AI_API_KEY`；其余为纯数据库/配置/文件操作。

---

## 六、前后端划分（单文件前端）

`src/main/resources/static/index.html`（2165 行）= 模板 + `<script>`(Vue3 CDN) + `<style>` 一体，无构建步骤。

### 6.1 三大视图
1. **登录视图**（`<login>`）：登录/注册两个 `el-tab`；注册表单字段 `regForm.account`（学号/工号，注册后不可改）、`regForm.nickname`（显示昵称）。演示账号：`20230001`(张三,研发一组负责人)、`20230002`(李四,产品二组负责人)、`20230003`(王五)、`20230004`(赵六)，密码均 `123456`；账号是唯一登录凭据，界面显示的始终是昵称。
2. **团队引导页**（`needTeam=true`）：已登录但**未加入任何团队**时显示——创建团队 / 凭 6 位邀请码加入；否则不渲染主界面外壳。
3. **主应用视图**（`v-else-if !needTeam`）：顶栏（团队切换/成员/退出）+ 成员侧边栏 + **三页签**：
   - 💬 **聊天室**（`activeTab='chat'`）：消息列表（2s 轮询 `/api/chat/list`）、发消息（@CoBot 触发智能体）、附件、撤回、未读红点、@提及高亮。
   - 📋 **任务看板**（`activeTab='board'`）：任务卡片、筛选（状态/负责人/关键词）、分页、状态流转、指派、审批、删除、导出 Excel。
   - 🤖 **AI 工作台**（`activeTab='ai'`）：团队洞察 / 项目计划(→导入看板) / 一键PPT(配色主题+下载) / 纪要拆任务(→导入) / 周报(→推送聊天室) / 团队问答；含历史列表与详情回看。

### 6.2 `setup()` 暴露的主要方法（节选，供二次开发检索）
- 登录/团队：`doLogin` `doRegister` `doLogout` `createTeam` `joinTeam` `leaveTeam` `onTeamChange` `showInvite`
- 聊天：`sendMsg` `confirmTask` `revokeMsg` `deleteMsg` `markRead` `markAllRead` `pickFile` `onFilePicked` `downloadAttachment` `canRevoke`
- 看板：`changeStatus` `approveTask` `deleteTask` `openAssign` `doAssign` `exportTasks` `isOwner` `roleOf` `canEditStatus` `isOverdue` `onPageChange`
- 成员栏：`memberSearch` `memberGroups` `avatarStyle`
- 账号：`changePassword` `teamManage` `memberCmd`
- AI 工作台：`runInsight` `runPlan` `importPlan` `runPpt` `downloadPpt` `loadAiHistory` `viewArtifact` `runMeeting` `importMeeting` `runWeekly` `pushWeekly` `runAsk` `exportText` `planToMarkdown` `pptToMarkdown`
- 工具：`fmtTime` `fmtSize` `fmtDateTime` `slideTypeName`
- 主要状态：`loggedIn` `needTeam` `me` `myTeams` `currentTeamId` `members` `activeTab` `messages` `tasks` `pendingTask` `aiBusy` `insightData` `planData` `pptData` `aiHistory` `themes` `meetingData` `weeklyData` `askData`

---

## 七、测试与脚本清单

### 7.1 JUnit（src/test/java，4 个，见 §3.9）
跑法：`mvn test`（或 IDEA 运行对应类）。均为纯逻辑单测，不依赖数据库/Spring 上下文。

### 7.2 端到端 / QA 脚本（test/，7 个，仅标准库或 node 内置，无需额外安装）

| 脚本 | 行数 | 作用 | 怎么跑 | 关键约定 |
|------|------|------|--------|----------|
| `api_smoke_test.py` | 265 | 主链路冒烟（登录/团队/@提及/已读/附件/任务/Excel导出/纪要/周报/问答/邀请码/越权） | `python test/api_smoke_test.py [port]`（默认 8093）；需 MySQL 已执行 `cobot_db.sql`+`upgrade-hardening.sql`、服务已起；自建"冒烟测试团队"结束自动解散 | 登录载荷字段是 **`account`**（如 `{"account":"20230001","password":"123456"}`） |
| `ai_feature_test.py` | 211 | AI 工作台端到端（洞察权限/计划/导入/PPT生成下载/历史/跨团队隔离） | `python test/ai_feature_test.py`；仅标准库(urllib) | 用 `20230003`王五(普通成员)验权限边界；`account` 登录 |
| `ppt_theme_test.py` | 131 | PPT 配色主题验证（themes 列表/同主题不同配色/解压校验颜色生效/非法 key 回落默认蓝） | `python test/ppt_theme_test.py` | 登录用 `20230001`(张三,负责人) |
| `qa_account_regression.py` | 213 | 账号体系改造独立回归（唯一性/并发/空格/大小写/nickname回落/无团队隔离/防爆破锁定/user不泄露密码） | `python test/qa_account_regression.py`；测试账号前缀 `qa_` 便于清理 | 登录载荷字段 **`account`** |
| `qa_symbol_check.py` | 305 | "AI 生成内容不含特殊符号"独立验证（自建团队/自造数据/符号黑名单反向断言） | `python test/qa_symbol_check.py [port]` | 引用根目录 `qa_symbol_raw.txt` 作语料；`account` 登录 |
| `dbtool.py` | 47 | 本地 MySQL 小工具（避免 shell 重定向/编码问题） | `python test/dbtool.py exec <sql文件>` 或 `python test/dbtool.py query "<SQL>"` | 硬编码 `mysql.exe` 路径与 `root/123456` |
| `check_frontend.js` | 238 | 前端静态自检（无构建，防白屏） | `node test/check_frontend.js` | 校验：内联 `<script>` JS 语法(vm.Script)、Vue 模板可编译、setup 导出；自动下载 Vue 编译器到 `.verify-cache/` |

> **统一提醒**：所有 Python 登录载荷字段是 `account`（不是 `username`）；服务需先启动并监听 8093；`test/output/` 存放截图与 pptx 产物，已被 .gitignore 排除。
