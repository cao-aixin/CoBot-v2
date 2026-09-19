package com.cobot.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.dto.ChatMessageVO;
import com.cobot.dto.ChatMsgRequest;
import com.cobot.dto.ChatReadRequest;
import com.cobot.dto.ChatSendRequest;
import com.cobot.dto.ChatUploadVO;
import com.cobot.dto.Result;
import com.cobot.entity.ChatMessage;
import com.cobot.entity.SysReadState;
import com.cobot.entity.SysTeamMember;
import com.cobot.entity.SysUser;
import com.cobot.mapper.ChatMessageMapper;
import com.cobot.mapper.SysReadStateMapper;
import com.cobot.mapper.SysTeamMemberMapper;
import com.cobot.mapper.SysUserMapper;
import com.cobot.service.AuthService;
import com.cobot.service.FileStorageService;
import com.cobot.service.MentionParser;
import com.cobot.service.TaskBusinessService;
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
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天接口（需登录）
 *
 * <p>读取类：
 * <ul>
 *   <li>GET  /api/chat/list       团队聊天记录（前端 2s 轮询增量拉取；需为该团队成员）</li>
 *   <li>GET  /api/chat/unread     我在该聊天室的未读消息数（红点）</li>
 * </ul>
 *
 * <p>写入类：
 * <ul>
 *   <li>POST /api/chat/send       发送消息（文本触发智能体；也可发送附件消息）</li>
 *   <li>POST /api/chat/upload     上传图片 / 文件附件（返回服务端路径）</li>
 *   <li>POST /api/chat/revoke     撤回消息（本人 2 分钟内，或负责人随时）</li>
 *   <li>POST /api/chat/delete     删除消息（仅负责人，物理删除）</li>
 *   <li>POST /api/chat/read       标记已读（清红点）</li>
 *   <li>GET  /api/chat/file/{id}  下载附件（需为该团队成员，避免附件变成公开链接）</li>
 * </ul>
 *
 * <p>安全：发送人身份一律取登录会话，前端传的 userId 不作数，杜绝冒充他人发言。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** 单次拉取的最大消息条数 */
    private static final int MAX_FETCH = 200;

    /** 普通成员可自行撤回的时间窗口（分钟） */
    private static final int SELF_REVOKE_MINUTES = 2;

    private final TaskBusinessService taskBusinessService;

    private final SysUserMapper sysUserMapper;

    private final ChatMessageMapper chatMessageMapper;

    private final SysTeamMemberMapper sysTeamMemberMapper;

    private final SysReadStateMapper sysReadStateMapper;

    private final FileStorageService fileStorageService;

    private final MentionParser mentionParser;

    @Resource
    private AuthService authService;

    public ChatController(TaskBusinessService taskBusinessService,
                          SysUserMapper sysUserMapper,
                          ChatMessageMapper chatMessageMapper,
                          SysTeamMemberMapper sysTeamMemberMapper,
                          SysReadStateMapper sysReadStateMapper,
                          FileStorageService fileStorageService,
                          MentionParser mentionParser) {
        this.taskBusinessService = taskBusinessService;
        this.sysUserMapper = sysUserMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.sysTeamMemberMapper = sysTeamMemberMapper;
        this.sysReadStateMapper = sysReadStateMapper;
        this.fileStorageService = fileStorageService;
        this.mentionParser = mentionParser;
    }

    /**
     * 发送聊天消息
     *
     * <p>文本消息：用户消息入库 -> 智能体处理 -> bot 回复入库；
     * 附件消息（图片 / 文件）：校验服务端签发的 filePath 归属后直接入库，不触发智能体。
     */
    @PostMapping("/send")
    public Result<Long> send(@RequestBody ChatSendRequest request, HttpServletRequest httpRequest) {
        // 发送人身份一律取登录会话，防止前端冒充他人
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.getTeamId() == null) {
            return Result.fail("teamId 不能为空");
        }
        // 成员资格校验：不在该团队的人不能在对应聊天室发言
        if (!authService.isMember(request.getTeamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员，无法在此聊天室发言");
        }
        String msgType = normalizeMsgType(request.getMsgType());
        boolean hasAttachment = request.getFilePath() != null && !request.getFilePath().isBlank();
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.isEmpty() && !hasAttachment) {
            return Result.fail("消息内容不能为空");
        }
        if ("text".equals(msgType) || !hasAttachment) {
            return Result.ok(taskBusinessService.handleChatMessage(
                    request.getTeamId(), loginUserId, content));
        }
        // 附件消息：只接受本团队目录下的文件，防止前端塞任意路径读取服务器文件
        String expectedPrefix = "attachments/" + request.getTeamId() + "/";
        if (!request.getFilePath().replace('\\', '/').startsWith(expectedPrefix)) {
            return Result.fail("附件路径非法，请重新上传");
        }
        ChatMessage msg = new ChatMessage();
        msg.setTeamId(request.getTeamId());
        msg.setSenderId(loginUserId);
        msg.setSenderType(0);
        msg.setMsgContent(content.isEmpty() ? request.getFileName() : content);
        msg.setMsgType(msgType);
        msg.setFileName(request.getFileName());
        msg.setFilePath(request.getFilePath());
        msg.setFileSize(request.getFileSize());
        msg.setRevoked(0);
        msg.setCreateTime(LocalDateTime.now());
        chatMessageMapper.insert(msg);
        return Result.ok(null);
    }

    /**
     * 上传附件（图片 / 文件）
     *
     * <p>限制：仅团队成员可传；大小上限由 spring.servlet.multipart 控制；
     * 返回服务端签发的 filePath，前端再带它去 /send 发消息。
     */
    @PostMapping("/upload")
    public Result<ChatUploadVO> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam Long teamId,
                                       HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        if (file == null || file.isEmpty()) {
            return Result.fail("请选择要上传的文件");
        }
        String path = fileStorageService.saveAttachment(teamId, file);
        if (path == null) {
            return Result.fail("文件保存失败，请重试");
        }
        ChatUploadVO vo = new ChatUploadVO();
        vo.setFileName(file.getOriginalFilename());
        vo.setFilePath(path);
        vo.setFileSize(file.getSize());
        vo.setMsgType(guessMsgType(file.getOriginalFilename(), file.getContentType()));
        return Result.ok(vo);
    }

    /**
     * 下载聊天附件（需为该团队成员）
     */
    @GetMapping("/file/{messageId}")
    public ResponseEntity<byte[]> downloadFile(@PathVariable Long messageId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        ChatMessage msg = chatMessageMapper.selectById(messageId);
        if (msg == null || msg.getFilePath() == null
                || !authService.isMember(msg.getTeamId(), loginUserId)) {
            return ResponseEntity.status(403).build();
        }
        byte[] bytes = fileStorageService.read(msg.getFilePath());
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        String fileName = msg.getFileName() == null ? "附件" : msg.getFileName();
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        headers.setContentLength(bytes.length);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    /**
     * 撤回消息
     *
     * <p>规则：
     * <ul>
     *   <li>本人：发送后 {@value #SELF_REVOKE_MINUTES} 分钟内可自行撤回</li>
     *   <li>团队负责人：可撤回本团队任意消息（含超过时限的、以及机器人消息）</li>
     * </ul>
     * 撤回只把 revoked 置 1，原文仍留在库中便于追溯（前端展示为"消息已撤回"）。
     */
    @PostMapping("/revoke")
    public Result<String> revoke(@RequestBody ChatMsgRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        ChatMessage msg = chatMessageMapper.selectById(request.getMessageId());
        if (msg == null) {
            return Result.fail("消息不存在");
        }
        if (!authService.isMember(msg.getTeamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        if (Integer.valueOf(1).equals(msg.getRevoked())) {
            return Result.ok("该消息已经撤回过了");
        }
        boolean isSender = msg.getSenderId() != null && msg.getSenderId().equals(loginUserId);
        boolean isOwner = authService.isOwner(msg.getTeamId(), loginUserId);
        if (!isSender && !isOwner) {
            return Result.fail("只能撤回自己发送的消息，或请团队负责人操作");
        }
        // 机器人消息只有负责人能动
        if (!isSender && msg.getSenderType() != null && msg.getSenderType() == 1 && !isOwner) {
            return Result.fail("机器人消息仅团队负责人可撤回");
        }
        // 非负责人的本人撤回有时限
        if (isSender && !isOwner && msg.getCreateTime() != null
                && msg.getCreateTime().isBefore(LocalDateTime.now().minusMinutes(SELF_REVOKE_MINUTES))) {
            return Result.fail("超过 " + SELF_REVOKE_MINUTES + " 分钟的消息无法自行撤回，请联系团队负责人");
        }
        msg.setRevoked(1);
        chatMessageMapper.updateById(msg);
        return Result.ok("消息已撤回");
    }

    /**
     * 删除消息（仅团队负责人，物理删除；附件一并从磁盘清掉）
     */
    @PostMapping("/delete")
    public Result<String> delete(@RequestBody ChatMsgRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        ChatMessage msg = chatMessageMapper.selectById(request.getMessageId());
        if (msg == null) {
            return Result.fail("消息不存在");
        }
        if (!authService.isOwner(msg.getTeamId(), loginUserId)) {
            return Result.fail("仅团队负责人可删除消息记录");
        }
        if (msg.getFilePath() != null && !msg.getFilePath().isBlank()) {
            fileStorageService.delete(msg.getFilePath());
        }
        chatMessageMapper.deleteById(msg.getId());
        return Result.ok("消息已删除");
    }

    /**
     * 标记已读：把 last_read_id 推到指定消息 ID（只增不减）
     */
    @PostMapping("/read")
    public Result<String> markRead(@RequestBody ChatReadRequest request, HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        if (request.getTeamId() == null) {
            return Result.fail("teamId 不能为空");
        }
        if (!authService.isMember(request.getTeamId(), loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        long lastReadId = request.getLastReadId() == null ? 0L : request.getLastReadId();
        SysReadState state = sysReadStateMapper.selectOne(new LambdaQueryWrapper<SysReadState>()
                .eq(SysReadState::getTeamId, request.getTeamId())
                .eq(SysReadState::getUserId, loginUserId));
        if (state == null) {
            state = new SysReadState();
            state.setTeamId(request.getTeamId());
            state.setUserId(loginUserId);
            state.setLastReadId(lastReadId);
            sysReadStateMapper.insert(state);
        } else if (state.getLastReadId() == null || state.getLastReadId() < lastReadId) {
            state.setLastReadId(lastReadId);
            sysReadStateMapper.updateById(state);
        }
        return Result.ok("已标记已读");
    }

    /**
     * 未读消息数（红点）
     *
     * <p>口径 = 本团队内 id 大于"已读到的最大 id"的消息条数。
     * 注意不能用"本团队最新消息 id - 已读 id"来算：消息 id 是全库自增的，
     * 别的团队聊得越多，这个差值就虚高得越离谱（新团队会出现几十条假未读）。
     */
    @GetMapping("/unread")
    public Result<Integer> unread(@RequestParam Long teamId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        SysReadState state = sysReadStateMapper.selectOne(new LambdaQueryWrapper<SysReadState>()
                .eq(SysReadState::getTeamId, teamId)
                .eq(SysReadState::getUserId, loginUserId));
        long readId = state == null || state.getLastReadId() == null ? 0L : state.getLastReadId();
        Long unread = chatMessageMapper.selectCount(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getTeamId, teamId)
                .gt(ChatMessage::getId, readId));
        return Result.ok(unread == null ? 0 : unread.intValue());
    }

    /**
     * 获取团队聊天记录（按时间升序，前端轮询拉取；需为该团队成员）
     *
     * @param teamId  团队 ID
     * @param afterId 只拉取 id 大于 afterId 的增量消息（首次传 0）
     * @param limit   单次最多条数（默认 200，上限 200）
     */
    @GetMapping("/list")
    public Result<List<ChatMessageVO>> list(@RequestParam Long teamId,
                                            @RequestParam(defaultValue = "0") Long afterId,
                                            @RequestParam(defaultValue = "200") Integer limit,
                                            HttpServletRequest httpRequest) {
        Long loginUserId = (Long) httpRequest.getAttribute("loginUserId");
        String loginUsername = (String) httpRequest.getAttribute("loginUsername");
        if (!authService.isMember(teamId, loginUserId)) {
            return Result.fail("你不是该团队的成员");
        }
        int size = (limit == null || limit <= 0 || limit > MAX_FETCH) ? MAX_FETCH : limit;
        // 1. 查消息
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getTeamId, teamId)
                .gt(afterId != null && afterId > 0, ChatMessage::getId, afterId)
                .orderByAsc(ChatMessage::getId)
                .last("LIMIT " + size);
        List<ChatMessage> messages = chatMessageMapper.selectList(wrapper);

        // 2. 批量补发送人昵称（bot 消息固定 CoBot）
        Map<Long, String> nameMap = sysUserMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getUsername, (a, b) -> a, LinkedHashMap::new));

        List<ChatMessageVO> voList = new ArrayList<>(messages.size());
        for (ChatMessage msg : messages) {
            ChatMessageVO vo = new ChatMessageVO();
            vo.setId(msg.getId());
            vo.setTeamId(msg.getTeamId());
            vo.setSenderId(msg.getSenderId());
            vo.setSenderType(msg.getSenderType());
            vo.setRevoked(msg.getRevoked() == null ? 0 : msg.getRevoked());
            vo.setMsgType(msg.getMsgType() == null ? "text" : msg.getMsgType());
            vo.setFileName(msg.getFileName());
            vo.setFileSize(msg.getFileSize());
            List<String> mentions = mentionParser.split(msg.getMentions());
            vo.setMentions(mentions);
            vo.setMentionMe(loginUsername != null && mentions.contains(loginUsername));
            vo.setCreateTime(msg.getCreateTime());   // 前端气泡上要显示 "发送人 · HH:mm"，漏了这行时间就是空的
            if (Integer.valueOf(1).equals(msg.getRevoked())) {
                // 撤回后不再下发原文，避免通过接口把撤回内容捞回来
                vo.setMsgContent("该消息已被撤回");
            } else {
                vo.setMsgContent(msg.getMsgContent());
            }
            if (msg.getSenderType() != null && msg.getSenderType() == 1) {
                vo.setSenderName("CoBot");
            } else {
                vo.setSenderName(nameMap.getOrDefault(msg.getSenderId(), "未知用户"));
            }
            voList.add(vo);
        }
        return Result.ok(voList);
    }

    /** 附件类型推断：常见图片扩展名走 image，其余走 file */
    private String guessMsgType(String fileName, String contentType) {
        if (contentType != null && contentType.startsWith("image/")) {
            return "image";
        }
        String name = fileName == null ? "" : fileName.toLowerCase();
        for (String ext : new String[]{".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"}) {
            if (name.endsWith(ext)) {
                return "image";
            }
        }
        return "file";
    }

    /** 归一化消息类型：非法值一律降级为 text */
    private String normalizeMsgType(String msgType) {
        if (msgType == null || msgType.isBlank()) {
            return "text";
        }
        String t = msgType.trim().toLowerCase();
        return ("image".equals(t) || "file".equals(t)) ? t : "text";
    }
}
