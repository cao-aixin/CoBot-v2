package com.cobot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.dto.ArtifactVO;
import com.cobot.dto.PptOutlineVO;
import com.cobot.dto.PptTheme;
import com.cobot.dto.ProjectPlanVO;
import com.cobot.dto.TeamInsightVO;
import com.cobot.entity.AiArtifact;
import com.cobot.entity.SysTask;
import com.cobot.entity.SysTeamMember;
import com.cobot.entity.SysUser;
import com.cobot.mapper.AiArtifactMapper;
import com.cobot.mapper.SysTaskMapper;
import com.cobot.mapper.SysTeamMemberMapper;
import com.cobot.mapper.SysUserMapper;
import com.cobot.util.PlainTextCleaner;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 内容生成服务（AI 工作台核心）
 *
 * <p>统一承载三个"一键生成"能力，并负责把产物落库（{@code ai_artifact}）：
 * <ol>
 *   <li>{@link #generateInsight}  —— 团队洞察：负责人视角的数据分析报告</li>
 *   <li>{@link #generatePlan}     —— 项目计划：阶段 + 任务清单，可一键导入任务看板</li>
 *   <li>{@link #generatePpt}      —— 演示文稿：大纲 + 真实 .pptx 文件</li>
 * </ol>
 *
 * <p>双模式兼容：{@code agent.mode=llm} 时调用大模型（SpringAI ChatClient）；
 * {@code agent.mode=local} 或大模型调用失败时，自动降级为本地规则模板，
 * 保证功能在任何环境下都能演示，不会因为网络/额度问题直接不可用。
 *
 * <p>安全边界：所有数字统计均由 {@link TeamStatsService} 从数据库真实聚合，
 * 大模型只做"文字表达"，不参与算数，杜绝 AI 编造统计口径。
 */
@Slf4j
@Service
public class AiContentService {

    /** 生成类型：团队洞察 */
    public static final String TYPE_INSIGHT = "insight";

    /** 生成类型：项目计划 */
    public static final String TYPE_PLAN = "plan";

    /** 生成类型：演示文稿 */
    public static final String TYPE_PPT = "ppt";

    /** 大模型调用失败时统一提示（前端据此提示用户） */
    private static final String LLM_FALLBACK_NOTE = "（大模型调用失败，已自动降级为本地模板生成）";

    private final ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Resource
    private TeamStatsService teamStatsService;

    @Resource
    private PptxBuildService pptxBuildService;

    @Resource
    private AiArtifactMapper aiArtifactMapper;

    @Resource
    private SysTaskMapper sysTaskMapper;

    @Resource
    private SysTeamMemberMapper sysTeamMemberMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private AgentDispatchService agentDispatchService;

    @Resource
    private FileStorageService fileStorageService;

    public AiContentService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    // ==================== 一、团队洞察（负责人） ====================

    /**
     * 生成团队数据洞察报告
     *
     * @param teamId 团队 ID
     * @param userId 发起人用户 ID
     * @return 统计 + AI 分析报告；团队不存在返回 null
     */
    public TeamInsightVO generateInsight(Long teamId, Long userId) {
        TeamInsightVO insight = teamStatsService.buildInsight(teamId);
        if (insight == null) {
            return null;
        }
        // 1. 统计事实已由数据库算出，这里只让大模型"读数字写分析"
        String report = null;
        boolean aiGenerated = false;
        if (agentDispatchService.isLlmMode()) {
            report = callLlm(systemPromptAnalyst(), userPromptInsight(insight));
            aiGenerated = report != null;
            if (report != null) {
                // 大模型不一定听话，展示前统一清洗为纯文本（去 Markdown / emoji）
                insight.setReport(PlainTextCleaner.clean(report));
            }
        }
        if (report == null) {
            // 2. 兜底：本地规则模板（完全离线可用），模板已按纯文本书写，这里再兜一层清洗
            insight.setReport(PlainTextCleaner.clean(teamStatsService.buildLocalReport(insight)));
            insight.setAiGenerated(false);
        } else {
            insight.setAiGenerated(true);
        }
        // 3. 落库，便于历史回看与导出
        Long artifactId = saveArtifact(teamId, userId, TYPE_INSIGHT,
                insight.getTeamName() + " 团队洞察", null, insight.getReport(), null, null);
        insight.setArtifactId(artifactId);
        return insight;
    }

    /**
     * 洞察报告的大模型提示词（要求只依据给定数据）
     */
    private String userPromptInsight(TeamInsightVO insight) {
        return """
                你是团队管理分析师。下面是某团队的真实数据统计，请基于这些数据写一份简洁的团队管理分析报告。

                输出要求：
                1. 严格只使用下面给出的数据，禁止编造任何数字、人员或不存在的事实。
                2. 必须使用纯文本，禁止使用任何 Markdown 语法符号（如 #、*、_、`、>、|、~），禁止使用 emoji 与装饰性符号（如 ✅、❌、📊、📌、▍、●）。
                3. 不要使用表格，指标请用「指标：数值」逐行陈述；需要列表时直接换行书写，或用「1、」这类中文序号。
                4. 依次包含四个小节，小节标题用中文序号形式：【整体评估】【风险预警】【成员负荷分析】【下周行动建议】。
                5. 全文控制在 400 字以内，语气专业、直接、可执行，不要客套话和空话。
                6. 直接输出报告正文，不要输出任何前言。

                团队数据如下：
                %s
                """.formatted(teamStatsService.toPromptText(insight));
    }

    private String systemPromptAnalyst() {
        return "你是CoBot团队协作智能体的数据分析模块，擅长把枯燥的任务数据转化为管理者一眼能用的决策建议。"
                + "你只依据用户提供的数据作答，不编造任何数字或人员。"
                + "你的全部回复必须是纯文本：禁止使用任何 Markdown 语法符号（#、*、_、`、>、|、~），禁止使用 emoji 与装饰性符号，不要使用表格。";
    }

    // ==================== 二、项目计划 ====================

    /**
     * 生成项目计划
     *
     * @param teamId 团队 ID
     * @param userId 发起人用户 ID
     * @param topic  项目主题
     * @param weeks  计划周期（周），默认 4
     * @return 结构化计划
     */
    public ProjectPlanVO generatePlan(Long teamId, Long userId, String topic, Integer weeks) {
        int planWeeks = (weeks == null || weeks < 1 || weeks > 26) ? 4 : weeks;
        List<String> members = memberNames(teamId);

        ProjectPlanVO plan = null;
        if (agentDispatchService.isLlmMode()) {
            String json = callLlm(systemPromptPlanner(), userPromptPlan(topic, planWeeks, members));
            plan = parseJson(json, ProjectPlanVO.class);
        }
        boolean aiGenerated = plan != null && plan.getStages() != null && !plan.getStages().isEmpty();
        if (!aiGenerated) {
            plan = localPlan(topic, planWeeks, members);
            plan.setAdvice(plan.getAdvice() == null ? "" : plan.getAdvice());
        }
        plan.setTopic(topic);
        plan.setWeeks(planWeeks);
        plan.setAiGenerated(aiGenerated);
        // 展示前统一清洗为纯文本（大模型 / 本地模板两条路径都覆盖）
        sanitizePlan(plan);
        // 统计任务总数
        int count = 0;
        for (ProjectPlanVO.PlanStage stage : plan.getStages()) {
            count += stage.getTasks() == null ? 0 : stage.getTasks().size();
        }
        plan.setTaskCount(count);

        // 落库：content 存 JSON 原文，后续"一键导入看板"要按原文反序列化
        Long artifactId = saveArtifact(teamId, userId, TYPE_PLAN, topic, null,
                toJson(plan), null, null);
        plan.setArtifactId(artifactId);
        return plan;
    }

    /**
     * 项目计划的大模型提示词
     */
    private String userPromptPlan(String topic, int weeks, List<String> members) {
        return """
                你是资深项目经理。请为下面的项目生成一份可直接落地执行的项目计划。

                项目主题：%s
                计划周期：%d 周
                可分配团队成员：%s
                今天日期：%s

                输出要求：
                1. 严格只输出一个 JSON 对象，不要任何解释文字，不要 markdown 代码围栏。
                2. JSON 结构如下（字段名必须完全一致）：
                {"goal":"一句话项目目标","advice":"落地建议，100字以内","stages":[{"stageName":"第一阶段：xxx","goal":"阶段目标","period":"第1周","tasks":[{"taskContent":"动词开头的具体任务","ownerName":"成员用户名","priority":"高","deadline":"%s"}]}]}
                3. 阶段数量 3 到 4 个，每个阶段 2 到 4 个任务，任务必须具体可执行，不要写"推进项目"这类空话。
                4. ownerName 必须从上面给定的团队成员列表中原样选取，禁止编造不存在的名字。
                5. priority 只能是"高"、"普通"、"低"三者之一。
                6. deadline 格式为 yyyy-MM-dd，必须落在今天之后的 %d 周内，且随阶段推进逐周靠后。
                7. JSON 内所有字符串字段的值必须是纯文本：禁止包含任何 Markdown 语法符号（如 #、*、_、`、>、|、~），禁止包含 emoji 与装饰性符号。
                """.formatted(topic, weeks, members.isEmpty() ? "（暂无成员，ownerName 留空字符串）" : String.join("、", members),
                LocalDate.now(), LocalDate.now().plusDays(7), weeks);
    }

    private String systemPromptPlanner() {
        return "你是CoBot团队协作智能体的项目计划模块，擅长把模糊的项目想法拆解成阶段清晰、任务可执行、责任到人的落地计划。"
                + "你严格按要求的 JSON 结构输出，从不输出多余文字。"
                + "JSON 内所有字符串字段的值必须是纯文本：禁止包含任何 Markdown 语法符号（#、*、_、`、>、|、~），禁止包含 emoji 与装饰性符号。";
    }

    /**
     * 本地规则兜底的项目计划模板（无大模型时可用）
     */
    private ProjectPlanVO localPlan(String topic, int weeks, List<String> members) {
        ProjectPlanVO plan = new ProjectPlanVO();
        plan.setGoal("完成「" + topic + "」的交付，形成可演示成果并沉淀文档");
        plan.setAdvice("本地模板生成的通用计划，建议由负责人按实际情况调整阶段划分与责任分工。"
                + LLM_FALLBACK_NOTE);

        String[][] stageTemplates = {
                {"第一阶段：需求与设计", "明确需求边界并完成方案设计", "梳理「" + topic + "」的核心需求与验收标准", "输出功能清单与优先级排序", "完成技术方案与原型设计"},
                {"第二阶段：开发与实现", "按方案完成主体功能开发", "搭建项目骨架与基础框架", "完成核心功能模块开发", "完成前后端联调与数据打通"},
                {"第三阶段：测试与优化", "保障质量并完成性能与体验优化", "编写测试用例并执行功能测试", "修复缺陷并完成回归验证", "优化性能与交互体验"},
                {"第四阶段：交付与复盘", "完成验收交付与经验沉淀", "整理项目文档与使用说明", "准备演示材料并完成验收汇报", "召开复盘会并沉淀经验"}
        };
        int stageCount = Math.max(1, Math.min(4, weeks / 2 + 1));
        String[] periods = buildPeriods(weeks, stageCount);
        int memberIdx = 0;
        int dayOffset = 7;

        for (int i = 0; i < stageCount; i++) {
            ProjectPlanVO.PlanStage stage = new ProjectPlanVO.PlanStage();
            stage.setStageName(stageTemplates[i][0]);
            stage.setGoal(stageTemplates[i][1]);
            stage.setPeriod(periods[i]);
            // 每个阶段取模板里的任务（去掉前两列说明）
            for (int j = 2; j < stageTemplates[i].length; j++) {
                ProjectPlanVO.PlanTaskItem item = new ProjectPlanVO.PlanTaskItem();
                item.setTaskContent(stageTemplates[i][j]);
                item.setOwnerName(members.isEmpty() ? "" : members.get(memberIdx++ % members.size()));
                item.setPriority(j == 2 ? "高" : "普通");
                item.setDeadline(LocalDate.now().plusDays(dayOffset).toString());
                stage.getTasks().add(item);
                if (j == 4) {
                    dayOffset += 5;   // 阶段内的后续任务稍微往后排
                }
            }
            dayOffset += 5;
            plan.getStages().add(stage);
        }
        return plan;
    }

    /**
     * 把周期切成若干段的可读描述（如"第1周"、"第2-3周"）
     */
    private String[] buildPeriods(int weeks, int stageCount) {
        String[] periods = new String[stageCount];
        int base = Math.max(1, weeks / stageCount);
        int cursor = 1;
        for (int i = 0; i < stageCount; i++) {
            int start = cursor;
            int end = (i == stageCount - 1) ? Math.max(start, weeks) : Math.min(weeks, start + base - 1);
            cursor = end + 1;
            periods[i] = start == end ? ("第" + start + "周") : ("第" + start + "-" + end + "周");
        }
        return periods;
    }

    // ==================== 三、一键生成 PPT ====================

    /**
     * 生成 PPT：先让大模型出大纲，再用 POI 渲染成真实 .pptx
     *
     * @param teamId     团队 ID
     * @param userId     发起人用户 ID
     * @param topic      演示主题
     * @param slideCount 期望页数（含封面与结尾），默认 8
     * @param scene      场景：report=项目汇报 / plan=项目方案 / activity=活动策划 / general=通用
     * @param themeKey   配色主题标识（blue/green/purple/orange/teal/red/dark），空则用默认蓝
     * @return 大纲 + 可下载文件信息
     */
    public PptOutlineVO generatePpt(Long teamId, Long userId, String topic,
                                    Integer slideCount, String scene, String themeKey) {
        int pages = (slideCount == null || slideCount < 4 || slideCount > 20) ? 8 : slideCount;
        String sceneText = sceneName(scene);
        PptTheme theme = PptTheme.byKey(themeKey);

        PptOutlineVO outline = null;
        if (agentDispatchService.isLlmMode()) {
            String json = callLlm(systemPromptPpt(), userPromptPpt(topic, pages, sceneText, teamId));
            outline = parseJson(json, PptOutlineVO.class);
        }
        boolean aiGenerated = outline != null && outline.getSlides() != null && !outline.getSlides().isEmpty();
        if (!aiGenerated) {
            outline = localOutline(topic, pages, sceneText);
        }
        outline.setTopic(topic);
        outline.setAiGenerated(aiGenerated);
        // 保险：补齐封面 / 结尾页，保证大纲结构完整
        normalizeOutline(outline, topic, sceneText);
        // 展示 / 出稿前统一清洗为纯文本（副标题、页标题、要点）
        sanitizeOutline(outline);

        // 渲染真实 .pptx（按用户所选配色主题出图）
        byte[] fileBytes = pptxBuildService.build(outline, theme);
        String fileName = safeFileName(topic) + ".pptx";
        outline.setFileName(fileName);
        outline.setFileSize(fileBytes.length);
        outline.setTheme(theme.key());
        outline.setThemeName(theme.name());

        Long artifactId = saveArtifact(teamId, userId, TYPE_PPT, topic, theme.key(),
                toJson(outline), fileName, fileBytes);
        outline.setArtifactId(artifactId);
        return outline;
    }

    /**
     * PPT 大纲的大模型提示词
     */
    private String userPromptPpt(String topic, int pages, String sceneText, Long teamId) {
        // 项目汇报场景下，把团队真实数据喂进去，让 PPT 内容有据可依
        String context = "";
        if ("项目汇报".equals(sceneText)) {
            TeamInsightVO insight = teamStatsService.buildInsight(teamId);
            if (insight != null) {
                context = "\n可供引用的团队真实数据（请优先据此撰写汇报内容，不要编造数字）：\n"
                        + teamStatsService.toPromptText(insight) + "\n";
            }
        }
        return """
                你是资深演示文稿策划。请为下面的主题设计一份 PPT 大纲。

                主题：%s
                场景：%s
                总页数：%d 页（含封面与结尾页）
                今天日期：%s
                %s
                输出要求：
                1. 严格只输出一个 JSON 对象，不要任何解释文字，不要 markdown 代码围栏。
                2. JSON 结构如下（字段名必须完全一致）：
                {"subtitle":"副标题","slides":[{"type":"cover","title":"主标题","bullets":[]},{"type":"agenda","title":"目录","bullets":["章节一","章节二","章节三"]},{"type":"content","title":"页标题","bullets":["要点1","要点2","要点3"]},{"type":"end","title":"谢谢聆听","bullets":["结语一句"]}]}
                3. slides 数组长度必须正好是 %d：第 1 页 type 必须是 cover，第 2 页必须是 agenda，最后一页必须是 end，中间全部是 content。
                4. 每个 content 页给出 3 到 5 条要点，每条不超过 30 个字，不要写空话。
                5. 全部使用中文。
                6. JSON 内所有字符串字段的值必须是纯文本：禁止包含任何 Markdown 语法符号（如 #、*、_、`、>、|、~），禁止包含 emoji 与装饰性符号。
                """.formatted(topic, sceneText, pages, LocalDate.now(), context, pages);
    }

    private String systemPromptPpt() {
        return "你是CoBot团队协作智能体的演示文稿模块，擅长把主题拆解成逻辑清晰、要点精炼的 PPT 大纲。"
                + "你严格按要求的 JSON 结构输出，从不输出多余文字。"
                + "JSON 内所有字符串字段的值必须是纯文本：禁止包含任何 Markdown 语法符号（#、*、_、`、>、|、~），禁止包含 emoji 与装饰性符号。";
    }

    /**
     * 场景代码 -> 中文场景名
     */
    private String sceneName(String scene) {
        if (scene == null) {
            return "项目汇报";
        }
        return switch (scene.trim().toLowerCase()) {
            case "plan" -> "项目方案";
            case "activity" -> "活动策划";
            case "general" -> "通用介绍";
            default -> "项目汇报";
        };
    }

    /**
     * 本地规则兜底的 PPT 大纲模板
     */
    private PptOutlineVO localOutline(String topic, int pages, String sceneText) {
        PptOutlineVO outline = new PptOutlineVO();
        outline.setSubtitle(sceneText + "（由 CoBot 智能体生成）");
        List<PptOutlineVO.PptSlide> slides = new ArrayList<>();
        slides.add(slide("cover", topic, List.of()));
        slides.add(slide("agenda", "目 录", List.of("项目背景", "目标与范围", "实施计划", "团队分工", "风险与对策", "预期成果")));

        String[][] contentTemplates = {
                {"项目背景", "当下的问题与痛点", "业务与用户诉求", "为什么现在要做"},
                {"目标与范围", "本期要达成的核心目标", "明确不做的部分", "验收标准"},
                {"实施计划", "分阶段推进路线", "关键里程碑与时间点", "依赖与前置条件"},
                {"团队分工", "角色与职责划分", "任务分配原则", "协作与同步机制"},
                {"风险与对策", "主要风险识别", "应对预案", "兜底方案"},
                {"预期成果", "可量化的成果指标", "交付物清单", "后续演进方向"}
        };
        int contentPages = Math.max(1, pages - 3);
        for (int i = 0; i < contentPages; i++) {
            String[] tpl = contentTemplates[i % contentTemplates.length];
            slides.add(slide("content", tpl[0], List.of(tpl[1], tpl[2], tpl[3])));
        }
        slides.add(slide("end", "谢谢聆听", List.of("欢迎在聊天室继续讨论（CoBot 智能体）")));
        outline.setSlides(slides);
        return outline;
    }

    /**
     * 构造单页幻灯片的小工具
     */
    private PptOutlineVO.PptSlide slide(String type, String title, List<String> bullets) {
        PptOutlineVO.PptSlide s = new PptOutlineVO.PptSlide();
        s.setType(type);
        s.setTitle(title);
        s.setBullets(new ArrayList<>(bullets));
        return s;
    }

    /**
     * 校正大纲结构：确保有封面 / 目录 / 结尾页，页数合理
     */
    private void normalizeOutline(PptOutlineVO outline, String topic, String sceneText) {
        List<PptOutlineVO.PptSlide> slides = outline.getSlides();
        if (slides == null) {
            slides = new ArrayList<>();
            outline.setSlides(slides);
        }
        // 去掉 null 页，并给缺失 type 的页兜底为 content
        slides.removeIf(s -> s == null);
        for (PptOutlineVO.PptSlide s : slides) {
            if (s.getType() == null || s.getType().isBlank()) {
                s.setType("content");
            }
            if (s.getBullets() == null) {
                s.setBullets(new ArrayList<>());
            }
        }
        if (slides.isEmpty() || !"cover".equals(slides.get(0).getType())) {
            slides.add(0, slide("cover", topic, List.of()));
        }
        if (!"end".equals(slides.get(slides.size() - 1).getType())) {
            slides.add(slide("end", "谢谢聆听", List.of("欢迎在聊天室继续讨论（CoBot 智能体）")));
        }
        if (outline.getSubtitle() == null || outline.getSubtitle().isBlank()) {
            outline.setSubtitle(sceneText + "（由 CoBot 智能体生成）");
        }
    }

    /**
     * 把项目计划里的展示文本统一清洗为纯文本
     *
     * <p>覆盖：项目目标、落地建议、各阶段名称与目标、各任务内容。负责人姓名与日期是
     * 结构化字段（要参与成员匹配 / 日期解析），不做清洗，避免破坏数据一致性。
     */
    private void sanitizePlan(ProjectPlanVO plan) {
        if (plan == null) {
            return;
        }
        plan.setGoal(PlainTextCleaner.clean(plan.getGoal()));
        plan.setAdvice(PlainTextCleaner.clean(plan.getAdvice()));
        if (plan.getStages() == null) {
            return;
        }
        for (ProjectPlanVO.PlanStage stage : plan.getStages()) {
            if (stage == null) {
                continue;
            }
            stage.setStageName(PlainTextCleaner.clean(stage.getStageName()));
            stage.setGoal(PlainTextCleaner.clean(stage.getGoal()));
            if (stage.getTasks() == null) {
                continue;
            }
            for (ProjectPlanVO.PlanTaskItem item : stage.getTasks()) {
                if (item != null) {
                    item.setTaskContent(PlainTextCleaner.clean(item.getTaskContent()));
                }
            }
        }
    }

    /**
     * 把 PPT 大纲里的展示文本统一清洗为纯文本（副标题、各页标题、各页要点）
     */
    private void sanitizeOutline(PptOutlineVO outline) {
        if (outline == null) {
            return;
        }
        outline.setSubtitle(PlainTextCleaner.clean(outline.getSubtitle()));
        if (outline.getSlides() == null) {
            return;
        }
        for (PptOutlineVO.PptSlide slide : outline.getSlides()) {
            if (slide == null) {
                continue;
            }
            slide.setTitle(PlainTextCleaner.clean(slide.getTitle()));
            if (slide.getBullets() == null) {
                continue;
            }
            List<String> cleaned = new ArrayList<>(slide.getBullets().size());
            for (String bullet : slide.getBullets()) {
                cleaned.add(PlainTextCleaner.clean(bullet));
            }
            slide.setBullets(cleaned);
        }
    }

    /**
     * 文件名安全化（去掉 Windows 不允许的字符）
     */
    private String safeFileName(String topic) {
        String name = topic == null ? "CoBot演示文稿" : topic.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        if (name.length() > 60) {
            name = name.substring(0, 60);
        }
        return name.isEmpty() ? "CoBot演示文稿" : name;
    }

    // ==================== 四、计划一键导入任务看板 ====================

    /**
     * 把已生成的项目计划批量导入任务看板
     *
     * <p>权限与角色联动：负责人导入的任务直接"进行中"；
     * 普通成员导入的任务进入"待确认"，由负责人审批后执行（与聊天室创建任务的规则一致）。
     *
     * @param teamId     团队 ID
     * @param userId     发起人用户 ID
     * @param artifactId 计划产物 ID
     * @param isOwner    发起人是否该团队负责人
     * @return 导入结果描述
     */
    public String importPlanTasks(Long teamId, Long userId, Long artifactId, boolean isOwner) {
        AiArtifact artifact = aiArtifactMapper.selectById(artifactId);
        if (artifact == null) {
            return "计划不存在或已被删除";
        }
        if (!teamId.equals(artifact.getTeamId())) {
            return "该计划不属于当前团队，无法导入";
        }
        if (!TYPE_PLAN.equals(artifact.getGenType())) {
            return "该产物不是项目计划，无法导入任务看板";
        }
        ProjectPlanVO plan = parseJson(artifact.getContent(), ProjectPlanVO.class);
        if (plan == null || plan.getStages() == null || plan.getStages().isEmpty()) {
            return "计划内容为空或已损坏，无法导入";
        }
        // 团队成员用户名集合，用于校验计划里的负责人是否真实存在
        List<String> members = memberNames(teamId);
        String initStatus = isOwner ? "进行中" : "待确认";
        int imported = 0;
        for (ProjectPlanVO.PlanStage stage : plan.getStages()) {
            if (stage.getTasks() == null) {
                continue;
            }
            for (ProjectPlanVO.PlanTaskItem item : stage.getTasks()) {
                if (item.getTaskContent() == null || item.getTaskContent().isBlank()) {
                    continue;
                }
                SysTask task = new SysTask();
                task.setTeamId(teamId);
                // 负责人必须是本团队真实成员，否则置为发起人自己，避免出现"幽灵负责人"
                String owner = item.getOwnerName();
                if (owner == null || owner.isBlank() || !members.contains(owner)) {
                    owner = username(userId);
                }
                task.setOwnerName(owner);
                task.setTaskContent("【" + stage.getStageName() + "】" + item.getTaskContent());
                task.setPriority(normalizePriority(item.getPriority()));
                task.setTaskStatus(initStatus);
                if (item.getDeadline() != null && item.getDeadline().matches("\\d{4}-\\d{2}-\\d{2}")) {
                    task.setDeadline(LocalDate.parse(item.getDeadline()));
                }
                sysTaskMapper.insert(task);
                imported++;
            }
        }
        if (imported == 0) {
            return "计划里没有可导入的任务";
        }
        return "✅ 已导入 " + imported + " 条任务到任务看板"
                + (isOwner ? "（负责人导入，直接进入「进行中」）" : "（成员导入，进入「待确认」，待负责人审批）");
    }

    /**
     * 优先级归一化
     */
    private String normalizePriority(String priority) {
        if (priority == null) {
            return "普通";
        }
        String p = priority.trim();
        return switch (p) {
            case "高", "中", "低" -> "中".equals(p) ? "普通" : p;
            default -> "普通";
        };
    }

    // ==================== 五、生成历史 / 文件下载 ====================

    /**
     * 查询团队 AI 生成历史（倒序，最多 50 条）
     *
     * <p>只返回列表展示所需字段，正文与文件字节不在此处搬运。
     */
    public List<ArtifactVO> history(Long teamId) {
        List<AiArtifact> list = aiArtifactMapper.selectList(new LambdaQueryWrapper<AiArtifact>()
                .eq(AiArtifact::getTeamId, teamId)
                .select(AiArtifact::getId, AiArtifact::getGenType, AiArtifact::getTopic, AiArtifact::getTheme,
                        AiArtifact::getFileName, AiArtifact::getUserId, AiArtifact::getCreateTime)
                .orderByDesc(AiArtifact::getId)
                .last("LIMIT 50"));
        // 批量补用户名，避免 N+1 查询
        Map<Long, String> nameMap = new LinkedHashMap<>();
        for (AiArtifact artifact : list) {
            Long uid = artifact.getUserId();
            if (uid != null && !nameMap.containsKey(uid)) {
                nameMap.put(uid, username(uid));
            }
        }
        List<ArtifactVO> voList = new ArrayList<>();
        for (AiArtifact artifact : list) {
            ArtifactVO vo = new ArtifactVO();
            vo.setId(artifact.getId());
            vo.setGenType(artifact.getGenType());
            vo.setGenTypeName(typeName(artifact.getGenType()));
            vo.setTopic(artifact.getTopic());
            // 配色主题：历史列表展示主题色点，无主题（洞察/计划）时为空
            if (artifact.getTheme() != null) {
                PptTheme t = PptTheme.byKey(artifact.getTheme());
                vo.setTheme(t.key());
                vo.setThemeName(t.name());
                vo.setThemeColor(PptTheme.hex(t.primary()));
            }
            vo.setFileName(artifact.getFileName());
            vo.setCreateTime(artifact.getCreateTime());
            vo.setUsername(nameMap.getOrDefault(artifact.getUserId(), "未知用户"));
            voList.add(vo);
        }
        return voList;
    }

    /**
     * 读取产物详情（含正文），用于历史回看
     */
    public AiArtifact detail(Long artifactId) {
        return aiArtifactMapper.selectById(artifactId);
    }

    /**
     * 读取产物文件字节（PPT 下载）
     */
    public byte[] fileBytes(Long artifactId) {
        AiArtifact artifact = aiArtifactMapper.selectWithFile(artifactId);
        if (artifact == null) {
            return null;
        }
        // 优先读外置文件（新产物走磁盘）；读不到再回落库内 LONGBLOB（历史数据）
        if (artifact.getFilePath() != null && !artifact.getFilePath().isBlank()) {
            byte[] fromDisk = fileStorageService.read(artifact.getFilePath());
            if (fromDisk != null && fromDisk.length > 0) {
                return fromDisk;
            }
            log.warn("[CoBot] 外置文件缺失，回落到库内数据：artifactId={} path={}",
                    artifactId, artifact.getFilePath());
        }
        return artifact.getFileData();
    }

    /**
     * 生成类型中文名
     */
    private String typeName(String genType) {
        if (genType == null) {
            return "未知";
        }
        return switch (genType) {
            case TYPE_INSIGHT -> "团队洞察";
            case TYPE_PLAN -> "项目计划";
            case TYPE_PPT -> "演示文稿";
            case "meeting" -> "纪要拆任务";
            case "weekly" -> "团队周报";
            case "qa" -> "团队问答";
            default -> genType;
        };
    }

    // ==================== 通用工具 ====================

    /**
     * 落库一条生成产物
     *
     * @param fileData 文件字节；非文件类产物传 null
     */
    private Long saveArtifact(Long teamId, Long userId, String genType, String topic, String theme,
                              String content, String fileName, byte[] fileData) {
        AiArtifact artifact = new AiArtifact();
        artifact.setTeamId(teamId);
        artifact.setUserId(userId);
        artifact.setGenType(genType);
        artifact.setTopic(topic == null ? null : (topic.length() > 190 ? topic.substring(0, 190) : topic));
        artifact.setTheme(theme);
        artifact.setContent(content);
        artifact.setFileName(fileName);
        // 大字段外置：文件落磁盘，库里只留相对路径（历史数据仍可从 file_data 兼容读取）
        if (fileData != null && fileData.length > 0) {
            artifact.setFilePath(fileStorageService.saveArtifact(teamId, fileName, fileData));
        }
        artifact.setCreateTime(LocalDateTime.now());
        aiArtifactMapper.insert(artifact);
        return artifact.getId();
    }

    /**
     * 调用大模型；任何异常（网络 / 额度 / 超时）都返回 null，由调用方降级
     */
    private String callLlm(String systemPrompt, String userPrompt) {
        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            return (content == null || content.isBlank()) ? null : content;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从大模型输出里解析 JSON（兼容 ```json 围栏与前后废话）
     */
    private <T> T parseJson(String raw, Class<T> clazz) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String json = extractJson(raw);
            return json == null ? null : objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 截取文本里最外层的 JSON 对象
     */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 团队成员用户名列表（按加入顺序）
     */
    private List<String> memberNames(Long teamId) {
        List<SysTeamMember> relations = sysTeamMemberMapper.selectList(
                new LambdaQueryWrapper<SysTeamMember>().eq(SysTeamMember::getTeamId, teamId));
        List<String> names = new ArrayList<>();
        for (SysTeamMember relation : relations) {
            SysUser user = sysUserMapper.selectById(relation.getUserId());
            if (user != null) {
                names.add(user.getUsername());
            }
        }
        return names;
    }

    private String username(Long userId) {
        if (userId == null) {
            return "未分配";
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? "未分配" : user.getUsername();
    }

    /**
     * 当前日期字符串（供外部拼提示词用）
     */
    public String today() {
        return LocalDate.now().format(DateTimeFormatter.ISO_DATE);
    }
}
