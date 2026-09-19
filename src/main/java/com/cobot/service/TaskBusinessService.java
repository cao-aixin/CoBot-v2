package com.cobot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cobot.dto.PendingTask;
import com.cobot.entity.ChatMessage;
import com.cobot.entity.SysTask;
import com.cobot.entity.SysTeamMember;
import com.cobot.entity.SysUser;
import com.cobot.mapper.ChatMessageMapper;
import com.cobot.mapper.SysTaskMapper;
import com.cobot.mapper.SysTeamMemberMapper;
import com.cobot.mapper.SysUserMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息业务层
 *
 * <p>负责"用户发言 -> 智能体回复"的完整闭环：
 * <ol>
 *   <li>用户消息入库 chat_message（同时解析并记录 @提及）</li>
 *   <li>"确认"指令处理：把 PendingTaskStore 里暂存的任务真正写入 sys_task</li>
 *   <li>其余消息交给 AgentDispatchService（llm/local 双模式）生成回复</li>
 *   <li>bot 回复入库 chat_message</li>
 * </ol>
 */
@Service
public class TaskBusinessService {

    private final AgentDispatchService agentDispatchService;

    private final ChatMessageMapper chatMessageMapper;

    private final SysTaskMapper sysTaskMapper;

    private final SysUserMapper sysUserMapper;

    private final SysTeamMemberMapper sysTeamMemberMapper;

    private final MentionParser mentionParser;

    @Resource
    private PendingTaskStore pendingTaskStore;

    @Resource
    private AuthService authService;

    public TaskBusinessService(AgentDispatchService agentDispatchService,
                               ChatMessageMapper chatMessageMapper,
                               SysTaskMapper sysTaskMapper,
                               SysUserMapper sysUserMapper,
                               SysTeamMemberMapper sysTeamMemberMapper,
                               MentionParser mentionParser) {
        this.agentDispatchService = agentDispatchService;
        this.chatMessageMapper = chatMessageMapper;
        this.sysTaskMapper = sysTaskMapper;
        this.sysUserMapper = sysUserMapper;
        this.sysTeamMemberMapper = sysTeamMemberMapper;
        this.mentionParser = mentionParser;
    }

    /**
     * 处理一条用户聊天消息
     *
     * @param teamId   团队（聊天室） ID
     * @param sendUserId 发送人用户 ID
     * @param userMsg  消息内容
     * @return bot 回复消息 ID；未触发回复时返回 null
     */
    public Long handleChatMessage(Long teamId, Long sendUserId, String userMsg) {
        // 1. 保存用户消息（顺带解析 @提及，供前端高亮 / 提醒）
        ChatMessage userChatMsg = new ChatMessage();
        userChatMsg.setTeamId(teamId);
        userChatMsg.setSenderId(sendUserId);
        userChatMsg.setSenderType(0);
        userChatMsg.setMsgContent(userMsg);
        userChatMsg.setMsgType("text");
        userChatMsg.setRevoked(0);
        userChatMsg.setMentions(mentionParser.join(
                mentionParser.parse(userMsg, teamMemberNames(teamId))));
        userChatMsg.setCreateTime(LocalDateTime.now());
        chatMessageMapper.insert(userChatMsg);

        // 2. 生成 bot 回复
        String botReply = generateReply(teamId, sendUserId, userMsg);

        // 3. 回复入库（无法理解时不回复，保持安静）
        Long botReplyId = null;
        if (botReply != null) {
            ChatMessage botReplyMsg = new ChatMessage();
            botReplyMsg.setTeamId(teamId);
            botReplyMsg.setSenderId(null);
            botReplyMsg.setSenderType(1);
            botReplyMsg.setMsgContent(botReply);
            botReplyMsg.setMsgType("text");
            botReplyMsg.setRevoked(0);
            botReplyMsg.setCreateTime(LocalDateTime.now());
            chatMessageMapper.insert(botReplyMsg);
            botReplyId = botReplyMsg.getId();
        }
        return botReplyId;
    }

    /**
     * 取团队成员用户名列表（@提及只在团队内匹配，防止 @ 到团队外的人）
     */
    private List<String> teamMemberNames(Long teamId) {
        List<Long> userIds = sysTeamMemberMapper.selectList(new LambdaQueryWrapper<SysTeamMember>()
                        .eq(SysTeamMember::getTeamId, teamId))
                .stream().map(SysTeamMember::getUserId).toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(userIds.size());
        for (SysUser u : sysUserMapper.selectBatchIds(userIds)) {
            names.add(u.getUsername());
        }
        return names;
    }

    /**
     * 回复生成：优先处理"确认"指令，其余走 Agent 分发
     */
    private String generateReply(Long teamId, Long userId, String userMsg) {
        String requesterName = getUsername(userId);

        // —— "确认"指令：把暂存的任务落库 ——
        if (userMsg != null && userMsg.trim().equalsIgnoreCase("确认")) {
            return confirmPendingTask(teamId, userId, requesterName);
        }

        // —— 常规意图分发（llm / local 由 yml 决定） ——
        return agentDispatchService.dispatchSkill(userMsg, userId, teamId, requesterName);
    }

    /**
     * 确认创建：取出该团队+该发起人暂存的任务写入 sys_task（团队+用户双维度隔离）
     *
     * <p>角色规则：<b>负责人</b>创建的任务直接进入"进行中"；
     * <b>普通成员</b>创建的任务进入"待确认"，需负责人审批后才开始执行。
     */
    public String confirmPendingTask(Long teamId, Long userId, String requesterName) {
        PendingTask pending = pendingTaskStore.poll(teamId, requesterName);
        if (pending == null) {
            return "当前没有待确认的任务，请先 @CoBot 创建一个任务吧。";
        }
        boolean isOwner = authService.isOwner(teamId, userId);
        SysTask task = pending.toEntity(isOwner ? "进行中" : "待确认");
        sysTaskMapper.insert(task);
        if (isOwner) {
            return "✅ 任务已创建（#" + task.getId() + "）：负责人[" + task.getOwnerName() + "] "
                    + task.getTaskContent() + "，截止：" + (task.getDeadline() == null ? "未设置" : task.getDeadline());
        }
        return "📨 任务已提交（#" + task.getId() + "）：负责人[" + task.getOwnerName() + "] "
                + task.getTaskContent() + "，状态为\"待确认\"，等待团队负责人审批后开始执行。";
    }

    /**
     * 用户 ID -> 用户名
     */
    public String getUsername(Long userId) {
        if (userId == null) {
            return "未知用户";
        }
        var user = sysUserMapper.selectById(userId);
        return user == null ? "未知用户" : user.getUsername();
    }
}
