package com.delta.common.chat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.chat.domain.entity.ChatMessage;
import com.delta.common.chat.domain.entity.ChatSession;
import com.delta.common.chat.service.ChatAccessService;
import com.delta.common.chat.service.ChatSmsReminderService;
import com.delta.common.chat.service.ChatServiceImpl;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.common.customer.CsRoutingService;
import com.delta.common.chat.util.ChatParticipantId;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/common/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatServiceImpl chatService;
    private final ChatSmsReminderService chatSmsReminderService;
    private final ChatAccessService chatAccessService;
    private final com.delta.common.security.service.RateLimitService limits;
    private final CsRoutingService csRouting;

    /** 会话列表：id1=我的编码ID OR id2=我的编码ID */
    @GetMapping("/session/list")
    public R<?> sessionList(@RequestParam(required = false) String chatRole) {
        long myEncodedId = getMyEncodedId(chatRole);
        LambdaQueryWrapper<ChatSession> qw = new LambdaQueryWrapper<ChatSession>()
                .and(q -> q.eq(ChatSession::getStatus, "ACTIVE")
                        .or(c -> c.eq(ChatSession::getStatus, "CLOSED")
                                .isNull(ChatSession::getRating)
                                .apply("(id1 BETWEEN 3000000000 AND 3999999999 OR id2 BETWEEN 3000000000 AND 3999999999)")))
                .and(q -> q.eq(ChatSession::getId1, myEncodedId).or().eq(ChatSession::getId2, myEncodedId))
                .orderByDesc(ChatSession::getLastMessageAt);
        List<ChatSession> all = chatService.list(qw);
        for (ChatSession s : all) {
            chatAccessService.enrichSession(s, myEncodedId);
        }
        return R.ok(all);
    }

    /** 根据两个参与者编码ID创建/查找会话（前端可传 id1/id2 或 userId+userType 等） */
    @PostMapping("/session/create")
    public R<ChatSession> createSession(@RequestParam long id1, @RequestParam long id2) {
        long myEncodedId = getMyEncodedId(null);
        limits.check("chat-create:"+myEncodedId,10,60);
        if(id1==id2 || id1!=myEncodedId && id2!=myEncodedId) return R.fail(403,"无权限创建该会话");
        long other=id1==myEncodedId?id2:id1;
        int entity=ChatParticipantId.getEntityType(other);
        long raw=ChatParticipantId.getRawId(other);
        if("GUEST".equals(SecurityUtils.getUserType())) {
            long guestUserId=ChatParticipantId.getRawId(myEncodedId);
            if(entity==1) return R.fail(403,"游客不能主动创建会员私聊");
            if(entity==2) {
                if(!chatAccessService.canGuestContactPlayer(guestUserId,raw)) return R.fail(403,"只能联系本人订单的接单员");
            }
        }
        if(!chatAccessService.participantExists(entity,raw))
            return R.fail(404,"对方账号不存在");
        ChatSession s = chatService.findOrCreate(id1, id2);
        chatAccessService.enrichSession(s, myEncodedId);
        return R.ok(s);
    }

    /** 根据订单获取用户-打手会话（从订单解析 user_id、player_id 后查会话） */
    @GetMapping("/session/by-order/{orderId}")
    public R<ChatSession> sessionByOrder(@PathVariable Long orderId,
                                         @RequestParam(required = false) String chatRole) {
        long myEncodedId = getMyEncodedId(chatRole);
        ChatAccessService.OrderParticipants participants = chatAccessService.findOrderParticipants(orderId);
        if (participants == null) return R.fail("订单不存在");
        long userId = participants.userId();
        Long playerIdNum = participants.playerId();
        long encoded1 = ChatParticipantId.encodeUser(userId);
        if (myEncodedId != encoded1 && (playerIdNum == null
                || myEncodedId != ChatParticipantId.encodePlayer(playerIdNum)))
            return R.fail(404, "订单不存在");
        if (playerIdNum == null) return R.fail("该订单还未指派打手，暂无法聊天");
        long playerId = playerIdNum;
        long encoded2 = ChatParticipantId.encodePlayer(playerId);
        ChatSession s = chatService.findOrCreate(encoded1, encoded2);
        if (!isSessionMember(s, myEncodedId)) return R.fail("无权限查看该会话");
        chatAccessService.enrichSession(s, myEncodedId);
        return R.ok(s);
    }

    @GetMapping("/session/{id}")
    public R<ChatSession> sessionDetail(@PathVariable Long id,
                                        @RequestParam(required = false) String chatRole) {
        ChatSession s = chatService.getById(id);
        if (s == null) return R.fail("会话不存在");
        long myEncodedId = getMyEncodedId(chatRole);
        if (!isSessionMember(s, myEncodedId)) return R.fail("无权限查看该会话");
        chatAccessService.enrichSession(s, myEncodedId);
        return R.ok(s);
    }

    @GetMapping("/message/list")
    public R<Page<ChatMessage>> messageList(@RequestParam Long sessionId,
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String chatRole) {
        ChatSession s = chatService.getById(sessionId);
        if (s == null) return R.fail("会话不存在");
        long myEncodedId = getMyEncodedId(chatRole);
        if (!isSessionMember(s, myEncodedId)) return R.fail("无权限查看该会话消息");
        return R.ok(chatService.listMessages(sessionId, pageNum, pageSize));
    }

    @PostMapping("/message/read")
    public R<Integer> markRead(@RequestParam Long sessionId, @RequestParam(required = false) String chatRole) {
        ChatSession s = chatService.getById(sessionId);
        if (s == null) return R.fail("会话不存在");
        long myEncodedId = getMyEncodedId(chatRole);
        if (!isSessionMember(s, myEncodedId)) return R.fail("无权限操作该会话");
        String userType = ChatParticipantId.getEntityType(myEncodedId) == 1 ? "USER" :
                ChatParticipantId.getEntityType(myEncodedId) == 2 ? "PLAYER" : "CS";
        return R.ok(chatService.markRead(sessionId, userType + ":" + ChatParticipantId.getRawId(myEncodedId)));
    }

    /** 用户发起客服会话：分配在线客服 */
    @PostMapping("/session/cs")
    public R<ChatSession> createCsSession(@RequestParam(required = false) Long productId,
                                          @RequestParam(required = false) Long orderId) {
        long userId = SecurityUtils.getUserId();
        long encodedUser = getMyEncodedId(null);
        limits.check("chat-create:"+encodedUser,10,60);
        Long resolved = null;
        if (orderId != null) resolved = csRouting.resolveForOrder(orderId);
        if (resolved == null && productId != null) resolved = csRouting.resolveForProduct(productId);
        if (resolved == null) resolved = csRouting.anyOnDutyCs();
        long csId;
        if (resolved != null) {
            csId = resolved;
        } else {
            Long fallback = chatAccessService.findRandomAvailableCsId();
            if (fallback == null) return R.fail("暂无可用客服，请稍后再试");
            csId = fallback;
        }
        long encodedCs = ChatParticipantId.encodeCs(csId);
        ChatSession s = chatService.findOrCreate(encodedUser, encodedCs);
        chatAccessService.enrichSession(s, encodedUser);
        return R.ok(s);
    }

    @PostMapping("/message/send")
    public R<?> send(@RequestBody ChatMessage msg, @RequestParam(required = false) String chatRole) {
        Long userId = SecurityUtils.getUserId();
        String userType = resolveChatRole(chatRole);
        long myEncodedId=getMyEncodedId(chatRole);
        limits.check("chat-http:"+myEncodedId,30,60);
        ChatSession session = chatService.getById(msg.getSessionId());
        com.delta.common.chat.util.ChatIdentity.requireMember(session, myEncodedId);
        if (!"ACTIVE".equals(session.getStatus())) return R.fail(409, "会话已结束，无法继续发送消息");
        if(msg.getSenderType()!=null && !userType.equals(msg.getSenderType())) return R.fail(403,"聊天身份不匹配");
        com.delta.common.chat.util.ChatIdentity.validateMessage(msg.getType(),msg.getContent());
        msg.setId(null);
        msg.setSenderType(userType);
        msg.setSenderId(userId);
        msg.setIsRead(0);
        msg.setCreatedAt(LocalDateTime.now());
        chatService.saveMessage(msg);
        return R.ok();
    }

    @PostMapping("/session/{id}/close")
    public R<Void> closeSession(@PathVariable Long id, @RequestParam(required = false) String chatRole) {
        ChatSession s = chatService.getById(id);
        if (s == null) return R.fail("会话不存在");
        long myEncodedId = getMyEncodedId(chatRole);
        if (!isSessionMember(s, myEncodedId)) return R.fail(403, "无权限操作该会话");
        limits.check("chat-close:" + myEncodedId, 6, 60);
        if ("ACTIVE".equals(s.getStatus())) {
            chatService.lambdaUpdate()
                    .eq(ChatSession::getId, s.getId())
                    .set(ChatSession::getStatus, "CLOSED")
                    .update();
        }
        return R.ok();
    }

    @PostMapping("/session/{id}/rate")
    public R<Void> rateSession(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body,
                               @RequestParam(required = false) String chatRole) {
        ChatSession s = chatService.getById(id);
        if (s == null) return R.fail("会话不存在");
        long myEncodedId = getMyEncodedId(chatRole);
        if (!isSessionMember(s, myEncodedId)) return R.fail(403, "无权限评价该会话");
        if (ChatParticipantId.getEntityType(myEncodedId) != 1) return R.fail(403, "只有用户可以评价客服");
        long targetId = s.getId1().equals(myEncodedId) ? s.getId2() : s.getId1();
        if (ChatParticipantId.getEntityType(targetId) != 3) return R.fail(400, "该会话不是客服会话");
        if (!"CLOSED".equals(s.getStatus())) return R.fail(409, "客服结束会话后才能评价");
        if (s.getRating() != null) return R.fail(409, "该会话已经评价过了");
        int rating;
        try {
            Object value = body == null ? null : body.get("rating");
            rating = value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException e) {
            return R.fail(400, "评分须为1至5星");
        }
        if (rating < 1 || rating > 5) return R.fail(400, "评分须为1至5星");
        String comment = body.get("comment") == null ? "" : String.valueOf(body.get("comment")).trim();
        if (comment.length() > 500) return R.fail(400, "评价内容不能超过500字");
        s.setRating(rating);
        s.setRatingComment(comment);
        s.setRatedAt(LocalDateTime.now());
        chatService.updateById(s);
        return R.ok();
    }

    @PostMapping("/sms-reminder")
    public R<Void> sendSmsReminder(@RequestBody SmsReminderRequest request, jakarta.servlet.http.HttpServletRequest servletRequest,
                                   @RequestParam(required = false) String chatRole) {
        long myEncodedId = getMyEncodedId(chatRole);
        limits.check("chat-sms-sender:"+myEncodedId,5,3600);
        limits.check("chat-sms-ip:"+servletRequest.getRemoteAddr(),20,3600);
        chatSmsReminderService.sendReminder(request.getSessionId(), myEncodedId, request.getReminderCode());
        return R.ok();
    }

    private long getMyEncodedId(String chatRole) {
        Long rawId = SecurityUtils.getUserId();
        String type = resolveChatRole(chatRole);
        return com.delta.common.chat.util.ChatIdentity.encoded(type,rawId);
    }

    private static String resolveChatRole(String chatRole) {
        return com.delta.common.chat.util.ChatIdentity.role(SecurityUtils.getUserType(),chatRole);
    }

    private boolean isSessionMember(ChatSession s, long myEncodedId) {
        return s != null && (s.getId1() != null && s.getId1().equals(myEncodedId) || s.getId2() != null && s.getId2().equals(myEncodedId));
    }

    @Data
    public static class SmsReminderRequest {
        private Long sessionId;
        private String reminderCode;
    }
}
