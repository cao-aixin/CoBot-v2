package com.cobot.controller;

import com.cobot.annotation.AuditLog;
import com.cobot.dto.ArtifactVO;
import com.cobot.dto.MeetingParseVO;
import com.cobot.dto.PptOutlineVO;
import com.cobot.dto.PptTheme;
import com.cobot.dto.ProjectPlanVO;
import com.cobot.dto.Result;
import com.cobot.dto.TeamInsightVO;
import com.cobot.dto.WeeklyReportVO;
import com.cobot.entity.AiArtifact;
import com.cobot.service.AiContentService;
import com.cobot.service.AiExtendService;
import com.cobot.service.AuthService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 工作台接口（需登录，且均校验团队成员资格）
 *
 * <p>四大能力：
 * <ul>
 *   <li>GET  /api/ai/insight       团队数据洞察（<b>仅负责人</b>）—— 统计数字 + AI 管理分析</li>
 *   <li>POST /api/ai/plan          一键生成项目计划（阶段 + 任务）</li>
 *   <li>POST /api/ai/plan/import   把计划批量导入任务看板（负责人直接进行中，成员进待确认）</li>
 *   <li>POST /api/ai/ppt           一键生成 PPT（大纲 + 真实 .pptx 文件，可选配色主题）</li>
 *   <li>GET  /api/ai/themes        可选 PPT 配色主题列表</li>
 *   <li>GET  /api/ai/history       生成历史</li>
 *   <li>GET  /api/ai/detail/{id}   产物详情（历史回看）</li>
 *   <li>GET  /api/ai/file/{id}     下载生成的 .pptx 文件</li>
 * </ul>
 *
 * <p>权限设计：所有接口都以登录会话中的 userId 为准（前端传参不作数）；
 * 团队洞察是负责人专属，任务导入按角色决定初始状态。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    /** pptx 的标准 MIME 类型 */
    private static final String PPTX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    @Resource
    private AiContentService aiContentService;

    @Resource
    private AiExtendService aiExtendService;

    @Resource
    private AuthService authService;

    /** 项目计划生成请求体 */
    public record PlanRequest(Long teamId, String topic, Integer weeks) {}

    /** PPT 生成请求体（scene：report/plan/activity/general；theme：配色主题标识） */
    public record PptRequest(Long teamId, String topic, Integer slideCount, String scene, String theme) {}

    /** 计划导入请求体 */
    public record ImportRequest(Long teamId, Long artifactId) {}

    /** 会议纪要拆任务请求体 */
    public record MeetingRequest(Long teamId, String notes) {}

    /** 团队问答请求体 */
    public record AskRequest(Long teamId, String question) {}

    /**
     * 团队数据洞察（仅团队负责人）
     *
     * <p>返回的数据分两层：stats 是数据库真实聚合的客观数字，
     * report 是大模型基于这些数字写出的管理分析（local 模式降级为规则模板）。
     */
    @GetMapping("/insight")
    public Result<TeamInsightVO> insight(@RequestParam Long teamId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        if (!authService.isOwner(teamId, loginUserId)) {
            return Result.fail("团队数据洞察为负责人专属功能，请联系团队负责人查看");
        }
        TeamInsightVO insight = aiContentService.generateInsight(teamId, loginUserId);
        if (insight == null) {
            return Result.fail("团队不存在");
        }
        return Result.ok(insight);
    }

    /**
     * 一键生成项目计划（团队成员均可）
     */
    @PostMapping("/plan")
    @AuditLog(action = "AI_PLAN", value = "生成项目计划")
    public Result<ProjectPlanVO> plan(@RequestBody PlanRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.teamId() == null || !authService.isMember(request.teamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        if (request.topic() == null || request.topic().isBlank()) {
            return Result.fail("请填写项目主题");
        }
        String topic = request.topic().trim();
        if (topic.length() > 100) {
            return Result.fail("项目主题最长 100 个字");
        }
        return Result.ok(aiContentService.generatePlan(request.teamId(), loginUserId, topic, request.weeks()));
    }

    /**
     * 把生成好的项目计划一键导入任务看板
     */
    @PostMapping("/plan/import")
    public Result<String> importPlan(@RequestBody ImportRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.teamId() == null || request.artifactId() == null) {
            return Result.fail("teamId、artifactId 不能为空");
        }
        if (!authService.isMember(request.teamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        boolean isOwner = authService.isOwner(request.teamId(), loginUserId);
        String msg = aiContentService.importPlanTasks(request.teamId(), loginUserId,
                request.artifactId(), isOwner);
        return msg.startsWith("✅") ? Result.ok(msg) : Result.fail(msg);
    }

    /**
     * 一键生成 PPT（大纲 + 真实 .pptx 文件）
     */
    @PostMapping("/ppt")
    public Result<PptOutlineVO> ppt(@RequestBody PptRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.teamId() == null || !authService.isMember(request.teamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        if (request.topic() == null || request.topic().isBlank()) {
            return Result.fail("请填写演示主题");
        }
        String topic = request.topic().trim();
        if (topic.length() > 100) {
            return Result.fail("演示主题最长 100 个字");
        }
        return Result.ok(aiContentService.generatePpt(request.teamId(), loginUserId, topic,
                request.slideCount(), request.scene(), request.theme()));
    }

    /**
     * 可选的 PPT 配色主题列表（前端下拉渲染用）
     *
     * <p>返回每个主题的 key / 中文名 / 三个 hex 色值，前端据此画色点与预览；
     * 主题表在后端维护，前端不需要硬编码，后续加主题只改后端一处。
     */
    @GetMapping("/themes")
    public Result<List<PptTheme.ThemeVO>> themes() {
        return Result.ok(PptTheme.ALL.stream().map(PptTheme::toVO).toList());
    }

    /**
     * AI 生成历史（当前团队，倒序）
     */
    @GetMapping("/history")
    public Result<List<ArtifactVO>> history(@RequestParam Long teamId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        return Result.ok(aiContentService.history(teamId));
    }

    /**
     * 产物详情（历史回看：返回正文内容；PPT 类还会带回可下载标记）
     */
    @GetMapping("/detail/{artifactId}")
    public Result<Map<String, Object>> detail(@PathVariable Long artifactId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        AiArtifact artifact = aiContentService.detail(artifactId);
        if (artifact == null) {
            return Result.fail("产物不存在");
        }
        if (!authService.isMember(artifact.getTeamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", artifact.getId());
        data.put("genType", artifact.getGenType());
        data.put("topic", artifact.getTopic());
        data.put("fileName", artifact.getFileName());
        data.put("content", artifact.getContent());
        data.put("createTime", artifact.getCreateTime());
        data.put("downloadable", artifact.getFileName() != null && !artifact.getFileName().isBlank());
        return Result.ok(data);
    }

    /**
     * 下载生成的 .pptx 文件（需登录 + 团队成员资格）
     *
     * <p>用接口下载而非静态目录，是为了让权限校验也覆盖到文件本身，
     * 避免生成出来的 PPT 变成谁都能拿的公开链接。
     */
    @GetMapping("/file/{artifactId}")
    public ResponseEntity<byte[]> download(@PathVariable Long artifactId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        AiArtifact artifact = aiContentService.detail(artifactId);
        if (artifact == null || !authService.isMember(artifact.getTeamId(), loginUserId)) {
            return ResponseEntity.status(403).build();
        }
        byte[] bytes = aiContentService.fileBytes(artifactId);
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        String fileName = artifact.getFileName() == null ? "CoBot演示文稿.pptx" : artifact.getFileName();
        // 中文文件名必须 URL 编码，否则部分浏览器会乱码
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(PPTX_CONTENT_TYPE));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        headers.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    // ==================== 第二期能力：纪要拆任务 / 周报 / 团队问答 ====================

    /**
     * 会议纪要 -> 任务清单（团队成员均可）
     *
     * <p>把一段纪要丢进来，AI 抽取出可执行任务，用户确认后一键导入任务看板。
     */
    @PostMapping("/meeting")
    @AuditLog(action = "AI_MEETING", value = "纪要拆任务")
    public Result<MeetingParseVO> meeting(@RequestBody MeetingRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.teamId() == null || !authService.isMember(request.teamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        if (request.notes() == null || request.notes().isBlank()) {
            return Result.fail("请粘贴会议纪要内容");
        }
        String notes = request.notes().trim();
        if (notes.length() < 8) {
            return Result.fail("纪要内容太短，至少 8 个字");
        }
        if (notes.length() > 6000) {
            return Result.fail("纪要内容过长，请控制在 6000 字以内");
        }
        return Result.ok(aiExtendService.parseMeeting(request.teamId(), loginUserId, notes));
    }

    /**
     * 把纪要解析出的任务导入任务看板
     *
     * <p>与项目计划导入保持一致的角色语义：负责人导入直接"进行中"，成员导入进"待确认"。
     */
    @PostMapping("/meeting/import")
    @AuditLog(action = "AI_MEETING_IMPORT", value = "纪要任务导入看板")
    public Result<String> importMeeting(@RequestBody ImportRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.teamId() == null || request.artifactId() == null) {
            return Result.fail("teamId、artifactId 不能为空");
        }
        if (!authService.isMember(request.teamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        boolean isOwner = authService.isOwner(request.teamId(), loginUserId);
        String msg = aiExtendService.importMeetingTasks(request.teamId(), request.artifactId(), isOwner);
        return msg.startsWith("✅") ? Result.ok(msg) : Result.fail(msg);
    }

    /**
     * AI 周报生成（仅团队负责人）
     *
     * <p>数字全部来自数据库真实聚合，大模型只负责组织语言；生成结果会落库便于回看与推送。
     */
    @PostMapping("/weekly")
    @AuditLog(action = "AI_WEEKLY", value = "生成团队周报")
    public Result<WeeklyReportVO> weekly(@RequestBody ImportRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.teamId() == null || !authService.isMember(request.teamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        if (!authService.isOwner(request.teamId(), loginUserId)) {
            return Result.fail("AI 周报为负责人专属功能");
        }
        WeeklyReportVO report = aiExtendService.generateWeekly(request.teamId(), loginUserId);
        if (report == null) {
            return Result.fail("团队不存在");
        }
        return Result.ok(report);
    }

    /**
     * 把已生成的周报推送到团队聊天室（仅团队负责人）
     */
    @PostMapping("/weekly/push")
    @AuditLog(action = "AI_WEEKLY_PUSH", value = "推送周报到聊天室")
    public Result<String> pushWeekly(@RequestBody ImportRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.teamId() == null || request.artifactId() == null) {
            return Result.fail("teamId、artifactId 不能为空");
        }
        if (!authService.isOwner(request.teamId(), loginUserId)) {
            return Result.fail("仅团队负责人可推送周报");
        }
        String msg = aiExtendService.pushWeeklyToChat(request.teamId(), request.artifactId());
        return msg.startsWith("✅") ? Result.ok(msg) : Result.fail(msg);
    }

    /**
     * 团队数据问答（仅团队负责人）
     *
     * <p>回答严格基于数据库真实统计，数据里没有的信息会明确说"不知道"，不编造。
     */
    @PostMapping("/ask")
    @AuditLog(action = "AI_ASK", value = "团队数据问答")
    public Result<Map<String, Object>> ask(@RequestBody AskRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.teamId() == null || !authService.isMember(request.teamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        if (!authService.isOwner(request.teamId(), loginUserId)) {
            return Result.fail("团队数据问答为负责人专属功能");
        }
        if (request.question() == null || request.question().isBlank()) {
            return Result.fail("请输入要问的问题");
        }
        String question = request.question().trim();
        if (question.length() > 200) {
            return Result.fail("问题最长 200 个字");
        }
        Map<String, Object> answer = aiExtendService.askTeam(request.teamId(), loginUserId, question);
        if (answer == null) {
            return Result.fail("团队不存在");
        }
        return Result.ok(answer);
    }
}
