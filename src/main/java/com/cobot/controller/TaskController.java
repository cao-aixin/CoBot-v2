package com.cobot.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.annotation.AuditLog;
import com.cobot.dto.PendingTask;
import com.cobot.dto.Result;
import com.cobot.dto.TaskApproveRequest;
import com.cobot.dto.TaskAssignRequest;
import com.cobot.dto.TaskConfirmRequest;
import com.cobot.dto.TaskIdRequest;
import com.cobot.dto.TaskStatusRequest;
import com.cobot.entity.SysTask;
import com.cobot.entity.SysTeam;
import com.cobot.entity.SysTeamMember;
import com.cobot.mapper.SysTaskMapper;
import com.cobot.mapper.SysTeamMapper;
import com.cobot.mapper.SysTeamMemberMapper;
import com.cobot.mapper.SysUserMapper;
import com.cobot.service.AuthService;
import com.cobot.service.ExportService;
import com.cobot.service.PendingTaskStore;
import com.cobot.service.TaskBusinessService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 任务接口（需登录；列表/待确认需为对应团队成员）
 *
 * <p>GET  /api/task/list    获取团队任务列表（支持状态筛选）
 * <br>POST /api/task/confirm 确认 AI 识别的任务，入库
 * <br>POST /api/task/status  手动更新任务状态（看板卡片上操作）
 * <br>POST /api/task/assign  指派 / 改派（负责人）
 * <br>POST /api/task/delete  删除（负责人）
 * <br>POST /api/task/approve 审批待确认任务（负责人）
 *
 * <p>所有写操作都挂了 {@code @AuditLog}，留下"谁在何时改了哪条任务"的追溯链。
 */
@RestController
@RequestMapping("/api/task")
public class TaskController {

    private final SysTaskMapper sysTaskMapper;

    private final TaskBusinessService taskBusinessService;

    private final PendingTaskStore pendingTaskStore;

    private final SysTeamMemberMapper sysTeamMemberMapper;

    private final SysUserMapper sysUserMapper;

    private final ExportService exportService;

    private final SysTeamMapper sysTeamMapper;

    @Resource
    private AuthService authService;

    public TaskController(SysTaskMapper sysTaskMapper,
                          TaskBusinessService taskBusinessService,
                          PendingTaskStore pendingTaskStore,
                          SysTeamMemberMapper sysTeamMemberMapper,
                          SysUserMapper sysUserMapper,
                          ExportService exportService,
                          SysTeamMapper sysTeamMapper) {
        this.sysTaskMapper = sysTaskMapper;
        this.taskBusinessService = taskBusinessService;
        this.pendingTaskStore = pendingTaskStore;
        this.sysTeamMemberMapper = sysTeamMemberMapper;
        this.sysUserMapper = sysUserMapper;
        this.exportService = exportService;
        this.sysTeamMapper = sysTeamMapper;
    }

    /**
     * 获取团队任务列表
     *
     * @param teamId   团队 ID
     * @param status   可选状态筛选：待确认/进行中/已完成/归档；不传返回全部
     * @param owner    可选按负责人筛选（看板"我的任务"）
     * @param keyword  可选关键字（任务内容模糊匹配）
     * @param page     页码，从 1 开始；不传则返回全部（兼容旧前端）
     * @param pageSize 每页条数
     */
    @GetMapping("/list")
    public Result<Object> list(@RequestParam Long teamId,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String owner,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(defaultValue = "20") Integer pageSize,
                               HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        LambdaQueryWrapper<SysTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysTask::getTeamId, teamId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(SysTask::getTaskStatus, status);
        }
        if (owner != null && !owner.isBlank()) {
            wrapper.eq(SysTask::getOwnerName, owner.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(SysTask::getTaskContent, keyword.trim());
        }
        wrapper.orderByDesc(SysTask::getCreateTime);
        // 分页：传了 page 才分页，否则返回全量（保持对旧前端的兼容）
        if (page != null && page > 0) {
            int size = (pageSize == null || pageSize <= 0 || pageSize > 200) ? 20 : pageSize;
            Long total = sysTaskMapper.selectCount(wrapper);
            wrapper.last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size);
            List<SysTask> records = sysTaskMapper.selectList(wrapper);
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("total", total);
            data.put("page", page);
            data.put("pageSize", size);
            data.put("records", records);
            return Result.ok(data);
        }
        return Result.ok(sysTaskMapper.selectList(wrapper));
    }

    /**
     * 确认创建任务：把 AI/正则识别出的暂存任务正式入库
     *
     * <p>安全：需为该团队成员；确认的是"该团队成员自己的"待确认任务。
     * <br>角色：负责人确认后直接"进行中"；普通成员确认后进入"待确认"待审批。
     */
    @PostMapping("/confirm")
    @AuditLog(action = "TASK_CONFIRM", value = "确认创建任务")
    public Result<String> confirm(@RequestBody TaskConfirmRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        String requesterName = (String) httpRequest.getAttribute("loginUsername");
        if (!authService.isMember(request.getTeamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        String reply = taskBusinessService.confirmPendingTask(request.getTeamId(), loginUserId, requesterName);
        // 区分"没有待确认任务"与"确认成功"两种提示
        if (reply.contains("没有待确认")) {
            return Result.fail(reply);
        }
        return Result.ok(reply);
    }

    /**
     * 更新任务状态（看板下拉选择）
     *
     * <p>权限规则：
     * <ul>
     *   <li>非团队成员：拒绝（防止跨团队改状态）</li>
     *   <li>普通成员：只能更新<b>自己名下</b>的任务（不能替他人改状态）</li>
     *   <li>团队负责人：可更新本团队任意任务（含审批"待确认"任务）</li>
     * </ul>
     */
    @PostMapping("/status")
    @AuditLog(action = "TASK_STATUS", value = "更新任务状态")
    public Result<String> updateStatus(@RequestBody TaskStatusRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        String loginUsername = (String) httpRequest.getAttribute("loginUsername");
        SysTask task = sysTaskMapper.selectById(request.getTaskId());
        if (task == null) {
            return Result.fail("任务不存在：" + request.getTaskId());
        }
        String role = authService.roleInTeam(task.getTeamId(), loginUserId);
        if (role == null) {
            return Result.fail("你不是该任务所属团队的成员，无法操作");
        }
        // 普通成员只能动自己名下的任务
        if (!"owner".equals(role) && !loginUsername.equals(task.getOwnerName())) {
            return Result.fail("普通成员只能更新自己名下的任务，请让负责人操作");
        }
        task.setTaskStatus(request.getStatus());
        sysTaskMapper.updateById(task);
        return Result.ok("任务 #" + task.getId() + " 状态已更新为：" + request.getStatus());
    }

    /**
     * 任务指派 / 改派（仅团队负责人）
     *
     * <p>把任务改派给团队内任意成员，若任务为"待确认"则同时转为"进行中"（视为审批通过）。
     */
    @PostMapping("/assign")
    @AuditLog(action = "TASK_ASSIGN", value = "指派任务")
    public Result<String> assign(@RequestBody TaskAssignRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        SysTask task = sysTaskMapper.selectById(request.taskId());
        if (task == null) {
            return Result.fail("任务不存在：" + request.taskId());
        }
        if (!authService.isOwner(task.getTeamId(), loginUserId)) {
            return Result.fail("仅团队负责人可指派任务");
        }
        if (request.ownerName() == null || request.ownerName().isBlank()) {
            return Result.fail("请选择负责人");
        }
        String ownerName = request.ownerName().trim();
        // 被指派人必须是该团队成员（按用户名匹配）
        boolean inTeam = sysTeamMemberMapper.selectList(new LambdaQueryWrapper<SysTeamMember>()
                        .eq(SysTeamMember::getTeamId, task.getTeamId()))
                .stream().map(SysTeamMember::getUserId)
                .map(id -> sysUserMapper.selectById(id))
                .anyMatch(u -> u != null && u.getUsername().equals(ownerName));
        if (!inTeam) {
            return Result.fail("「" + ownerName + "」不是本团队成员，无法指派");
        }
        task.setOwnerName(ownerName);
        if ("待确认".equals(task.getTaskStatus())) {
            task.setTaskStatus("进行中");
        }
        sysTaskMapper.updateById(task);
        return Result.ok("任务 #" + task.getId() + " 已指派给 " + ownerName);
    }

    /**
     * 删除任务（仅团队负责人）
     */
    @PostMapping("/delete")
    @AuditLog(action = "TASK_DELETE", value = "删除任务")
    public Result<String> delete(@RequestBody TaskIdRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        SysTask task = sysTaskMapper.selectById(request.taskId());
        if (task == null) {
            return Result.fail("任务不存在：" + request.taskId());
        }
        if (!authService.isOwner(task.getTeamId(), loginUserId)) {
            return Result.fail("仅团队负责人可删除任务");
        }
        sysTaskMapper.deleteById(task.getId());
        return Result.ok("任务 #" + task.getId() + " 已删除（" + task.getTaskContent() + "）");
    }

    /**
     * 审批任务：负责人把"待确认"任务批准为"进行中"或驳回为"归档"
     */
    @PostMapping("/approve")
    @AuditLog(action = "TASK_APPROVE", value = "审批任务")
    public Result<String> approve(@RequestBody TaskApproveRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        SysTask task = sysTaskMapper.selectById(request.taskId());
        if (task == null) {
            return Result.fail("任务不存在：" + request.taskId());
        }
        if (!authService.isOwner(task.getTeamId(), loginUserId)) {
            return Result.fail("仅团队负责人可审批任务");
        }
        if (!"待确认".equals(task.getTaskStatus())) {
            return Result.fail("该任务不是待确认状态，无需审批");
        }
        task.setTaskStatus(Boolean.TRUE.equals(request.approved()) ? "进行中" : "归档");
        sysTaskMapper.updateById(task);
        return Result.ok(Boolean.TRUE.equals(request.approved())
                ? "已批准任务 #" + task.getId() + "，状态转为进行中"
                : "已驳回任务 #" + task.getId());
    }

    /**
     * 查看当前团队是否有待确认任务（前端看板顶部提示条；需为该团队成员，且只看自己发起的）
     */
    @GetMapping("/pending")
    public Result<PendingTask> pending(@RequestParam Long teamId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        String requesterName = (String) request.getAttribute("loginUsername");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        return Result.ok(pendingTaskStore.peek(teamId, requesterName));
    }

    /**
     * 导出任务看板为 Excel（团队成员均可导出，支持按状态筛选）
     *
     * <p>用接口导出而非静态文件，保证权限校验覆盖到导出内容本身。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam Long teamId,
                                         @RequestParam(required = false) String status,
                                         HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        if (!authService.isMember(teamId, loginUserId)) {
            return ResponseEntity.status(403).build();
        }
        LambdaQueryWrapper<SysTask> wrapper = new LambdaQueryWrapper<SysTask>()
                .eq(SysTask::getTeamId, teamId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(SysTask::getTaskStatus, status);
        }
        wrapper.orderByDesc(SysTask::getCreateTime);
        List<SysTask> tasks = sysTaskMapper.selectList(wrapper);

        SysTeam team = sysTeamMapper.selectById(teamId);
        String teamName = team == null ? "团队" : team.getTeamName();
        byte[] bytes = exportService.exportTasks(teamName, tasks);
        if (bytes == null) {
            return ResponseEntity.status(500).build();
        }
        String fileName = teamName + "-任务看板-"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        headers.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }
}
