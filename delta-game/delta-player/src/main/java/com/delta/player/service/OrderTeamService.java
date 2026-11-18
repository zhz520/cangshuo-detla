package com.delta.player.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.chat.service.ChatSessionService;
import com.delta.common.chat.util.ChatParticipantId;
import com.delta.common.customer.CsRoutingService;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.utils.Money;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderPlayer;
import com.delta.order.entity.OrderProgress;
import com.delta.order.service.OrderAssignmentRules;
import com.delta.order.service.OrderPlayerService;
import com.delta.order.service.OrderProgressService;
import com.delta.order.service.OrderService;
import com.delta.player.entity.Player;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderTeamService {
    private static final Set<String> OPEN = Set.of("ACCEPTED", "WAITING_TEAMMATE", "IN_PROGRESS");
    private static final Set<String> SPLITS = Set.of("FIFTY_FIFTY", "FORTY_SIXTY", "THIRTY_SEVENTY", "CUSTOM");

    private final OrderService orders;
    private final OrderPlayerService members;
    private final OrderProgressService progress;
    private final PlayerService players;
    private final PlayerLevelService levels;
    private final ChatSessionService chats;
    private final ApplicationEventPublisher events;
    private final SysConfigService config;
    private final CrossModuleMapper identities;
    private final CsRoutingService csRouting;
    private final JdbcTemplate jdbc;

    public record Actor(String type, Long id) {}
    public record Invite(Long inviteePlayerId, Long replacedTeammateId, String splitType, BigDecimal customAmount, String reason) {}
    public record Reason(String reason) {}
    public record Candidate(Long id, String nickname, String avatar, BigDecimal avgRating, Integer completedOrders,
                            Integer activeOrders, Integer levelId, String levelName) {}
    public record Candidates(Page<Candidate> players, int maxConcurrent, Integer requiredLevelId, String requiredLevelName) {}

    public List<OrderPlayer> roster(Long orderId) {
        LocalDateTime now = LocalDateTime.now();
        var result = members.list(rows(orderId).orderByDesc(OrderPlayer::getId));
        for (var member : result) {
            member.setNickname(identities.selectPlayerNickname(member.getPlayerId()));
            member.setAvatar(identities.selectPlayerAvatar(member.getPlayerId()));
            if ("INVITED".equals(member.getStatus()) && expired(member, now)) member.setStatus("EXPIRED");
        }
        return result;
    }

    public Candidates candidates(Long orderId, Actor actor, long page, long size, String keyword) {
        Order order = requireOrder(orderId);
        authorize(order, actor);
        requireOpen(order);
        if (keyword != null && keyword.length() > 50) throw new BusinessException(400, "搜索内容过长");
        // Retain the existing rule against inviting a participant already recorded on this order.
        var used = members.list(new LambdaQueryWrapper<OrderPlayer>().eq(OrderPlayer::getOrderId, orderId)
                .select(OrderPlayer::getPlayerId)).stream().map(OrderPlayer::getPlayerId).toList();
        var filter = new LambdaQueryWrapper<Player>().eq(Player::getStatus, "ACTIVE").eq(Player::getIsOnline, 1)
                .select(Player::getId, Player::getNickname, Player::getAvatar, Player::getAvgRating, Player::getLevel)
                .ne(Player::getId, order.getPlayerId()).notIn(!used.isEmpty(), Player::getId, used)
                .like(keyword != null && !keyword.isBlank(), Player::getNickname, keyword)
                .orderByDesc(Player::getAvgRating).orderByAsc(Player::getId);
        boolean staff = isStaff(actor.type());
        if (staff) OrderAssignmentRules.restrictToRequestedLevel(order, filter, Player::getLevel);
        var result = players.page(new Page<Player>(Math.max(1, page), Math.max(1, Math.min(50, size))), filter);
        var names = staff ? OrderCandidateLevelNames.load(levels, result.getRecords()) : Map.<Integer, String>of();
        var out = new Page<Candidate>(result.getCurrent(), result.getSize(), result.getTotal());
        out.setRecords(result.getRecords().stream().map(player -> new Candidate(player.getId(), player.getNickname(), player.getAvatar(),
                player.getAvgRating(), identities.selectPlayerCompletedOrders(player.getId()), identities.countActivePlayerOrders(player.getId()),
                staff ? player.getLevel() : null, player.getLevel() == null ? null : names.get(player.getLevel()))).toList());
        Integer requiredLevelId = staff ? order.getRequestedPlayerLevelId() : null;
        return new Candidates(out, maxConcurrent(), requiredLevelId, requiredLevelId == null ? null : order.getPlayerLevelName());
    }

    @Transactional(rollbackFor = Exception.class)
    public void invite(Long orderId, Actor actor, Invite request) {
        Order order = requireOrder(orderId);
        authorize(order, actor);
        requireOpen(order);
        if (!"true".equalsIgnoreCase(config.getConfigValue("order.cooperation.enabled", "true")))
            throw new BusinessException(403, "协同打单功能已关闭");
        if (request == null || request.inviteePlayerId() == null || request.inviteePlayerId().equals(order.getPlayerId()))
            throw new BusinessException(400, "请选择其他可用打手");
        if (request.splitType() == null || !SPLITS.contains(request.splitType()))
            throw new BusinessException(400, "请选择有效的分成方式");
        String reason = reason(request.reason(), request.replacedTeammateId() != null && !"PLAYER".equals(actor.type()));
        BigDecimal customAmount = "CUSTOM".equals(request.splitType()) ? Money.positive(request.customAmount()) : null;
        LocalDateTime now = LocalDateTime.now();
        members.update(new LambdaUpdateWrapper<OrderPlayer>().eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getRole, "TEAMMATE").eq(OrderPlayer::getStatus, "INVITED")
                .le(OrderPlayer::getInviteDeadline, now).set(OrderPlayer::getStatus, "EXPIRED")
                .set(OrderPlayer::getRejectedAt, now));
        List<OrderPlayer> active = activeMembers(orderId, now);
        OrderPlayer outgoing = request.replacedTeammateId() == null ? null : active.stream()
                .filter(m -> "TEAMMATE".equals(m.getRole()) && request.replacedTeammateId().equals(m.getPlayerId())
                        && "ACCEPTED".equals(m.getStatus()))
                .findFirst().orElseThrow(() -> new BusinessException(409, "原队友已不在队伍中，请刷新订单"));
        if (outgoing != null && active.stream().anyMatch(m -> "INVITED".equals(m.getStatus())
                && outgoing.getId().equals(m.getReplacesParticipantId()))) {
            throw new BusinessException(409, "这位队友已有待接受的更换邀请");
        }
        if (outgoing == null && active.stream().filter(m -> "TEAMMATE".equals(m.getRole())
                && ("ACCEPTED".equals(m.getStatus()) || m.getReplacesParticipantId() == null)).count() >= capacity(order))
            throw new BusinessException(409, "当前订单队伍人数已满");
        if (members.count(new LambdaQueryWrapper<OrderPlayer>().eq(OrderPlayer::getOrderId, orderId)
                .eq(OrderPlayer::getPlayerId, request.inviteePlayerId())) > 0)
            throw new BusinessException(409, "已邀请过该打手");
        if (identities.lockActivePlayer(request.inviteePlayerId()) == null)
            throw new BusinessException(409, "新队友不在线或不可接单");
        if (isStaff(actor.type()))
            OrderAssignmentRules.requireRequestedLevel(order, () -> identities.selectPlayerLevelForUpdate(request.inviteePlayerId()));
        if (identities.countActivePlayerOrders(request.inviteePlayerId()) >= maxConcurrent())
            throw new BusinessException(409, "该打手进行中的订单已达上限");

        OrderPlayer invitation = new OrderPlayer();
        invitation.setOrderId(orderId);
        invitation.setPlayerId(request.inviteePlayerId());
        invitation.setRole("TEAMMATE");
        invitation.setStatus("INVITED");
        invitation.setInvitedBy(actor.id());
        invitation.setInvitedByType(actor.type());
        invitation.setReplacesParticipantId(outgoing == null ? null : outgoing.getId());
        invitation.setSplitType(request.splitType());
        invitation.setSplitAmount(customAmount);
        invitation.setInvitedAt(now);
        invitation.setInviteDeadline(now.plusMinutes(5));
        invitation.setCreatedAt(now);
        active.add(invitation);
        validateShares(order, active);
        if (!members.save(invitation)) throw new BusinessException(409, "邀请未保存，请重试");
        updateReadiness(order, active, now);
        String name = displayName(invitation.getPlayerId());
        if (outgoing == null) {
            log(orderId, actor, "TEAMMATE_INVITED", "邀请队友 " + name, reason);
        } else {
            log(orderId, actor, "TEAMMATE_REPLACE_INVITED",
                    "邀请 " + name + " 接替 " + displayName(outgoing.getPlayerId()) + "，等待新队友接受", reason);
        }
        notice("TEAMMATE_INVITED", "PLAYER", invitation.getPlayerId(), orderId,
                outgoing == null ? "您收到一份组队邀请，请尽快确认" : "您收到一份协同接替邀请，请确认分成后接受");
        if (!"PLAYER".equals(actor.type())) notice("TEAMMATE_INVITED_STAFF", "PLAYER", order.getPlayerId(), orderId,
                "后台已向 " + name + " 发出协同邀请");
    }

    @Transactional(rollbackFor = Exception.class)
    public void accept(Long orderId, Long playerId) {
        Order order = requireOrder(orderId);
        requireOpen(order);
        LocalDateTime now = LocalDateTime.now();
        OrderPlayer invite = pending(orderId, playerId, now);
        if (identities.lockActivePlayer(playerId) == null)
            throw new BusinessException(403, "打手未上班、未通过审核或账号已冻结，无法接单");
        if (isStaff(invite.getInvitedByType()))
            OrderAssignmentRules.requireRequestedLevel(order, () -> identities.selectPlayerLevelForUpdate(playerId));
        int max = maxConcurrent();
        if (identities.countActivePlayerOrders(playerId) >= max)
            throw new BusinessException(400, "您当前进行中的订单已达上限(" + max + "单)");
        var active = activeMembers(orderId, now);
        var outgoing = invite.getReplacesParticipantId() == null ? null : active.stream()
                .filter(m -> "TEAMMATE".equals(m.getRole()) && invite.getReplacesParticipantId().equals(m.getId())
                        && "ACCEPTED".equals(m.getStatus()))
                .findFirst().orElseThrow(() -> new BusinessException(409, "原队友已变更，此邀请无法接受"));
        long count = acceptedCount(active) - (outgoing == null ? 0 : 1);
        if (count >= capacity(order) || playerId.equals(order.getPlayerId()))
            throw new BusinessException(409, "订单队伍已变化，无法接受邀请");
        active.removeIf(m -> Objects.equals(m.getId(), invite.getId()) || (outgoing != null && outgoing.getId().equals(m.getId())));
        String prior = order.getStatus();
        if (outgoing != null) changeStatus(outgoing, "REPLACED", now);
        changeStatus(invite, "ACCEPTED", now);
        active.add(invite);
        validateShares(order, active);
        updateReadiness(order, active, now);
        Actor actor = new Actor("PLAYER", playerId);
        if (outgoing != null) {
            log(orderId, actor, "TEAMMATE_REPLACED", displayName(playerId) + " 已接受邀请并接替 " + displayName(outgoing.getPlayerId()), "");
            notice("TEAMMATE_REMOVED", "PLAYER", outgoing.getPlayerId(), orderId, "新队友已接受邀请，您已退出本单协作");
        } else {
            log(orderId, actor, "TEAMMATE_ACCEPTED", "队友确认分成方案并接受邀请", "");
        }
        chats.findOrCreate(ChatParticipantId.encodePlayer(order.getPlayerId()), ChatParticipantId.encodePlayer(playerId));
        notice("TEAMMATE_ACCEPTED", "PLAYER", order.getPlayerId(), orderId,
                outgoing != null ? "新队友已接受邀请，协同换人完成" : "队友已接受协同邀请");
        notifyInvitingStaff(invite, order, "TEAMMATE_ACCEPTED", "新队友已接受协同邀请");
        if ("WAITING_TEAMMATE".equals(prior) && "ACCEPTED".equals(order.getStatus()))
            notice("TEAM_READY", "USER", order.getUserId(), orderId, "打手已组队完成");
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long orderId, Long playerId) {
        Order order = requireOrder(orderId);
        requireOpen(order);
        LocalDateTime now = LocalDateTime.now();
        OrderPlayer invite = pending(orderId, playerId, now);
        changeStatus(invite, "REJECTED", now);
        log(orderId, new Actor("PLAYER", playerId), "TEAMMATE_REJECTED",
                invite.getReplacesParticipantId() == null ? "队友拒绝邀请" : "新队友拒绝更换邀请，原队友保留", "");
        notice("TEAMMATE_REJECTED", "PLAYER", order.getPlayerId(), orderId,
                invite.getReplacesParticipantId() == null ? "队友已拒绝邀请" : "新队友已拒绝更换邀请，原队友仍在队伍中");
        notifyInvitingStaff(invite, order, "TEAMMATE_REJECTED", "新队友已拒绝协同邀请");
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long orderId, Actor actor, Long memberId, String rawReason) {
        Order order = requireOrder(orderId);
        authorize(order, actor);
        requireOpen(order);
        String reason = reason(rawReason, false);
        LocalDateTime now = LocalDateTime.now();
        var row = activeMembers(orderId, now).stream()
                .filter(m -> "TEAMMATE".equals(m.getRole()) && Objects.equals(memberId, m.getId()) && "INVITED".equals(m.getStatus()))
                .findFirst().orElseThrow(() -> new BusinessException(409, "邀请已处理或过期"));
        cancel(orderId, actor, row, now, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long orderId, Actor actor, Long memberId, String rawReason) {
        Order order = requireOrder(orderId);
        authorize(order, actor);
        requireOpen(order);
        String reason = reason(rawReason, true);
        LocalDateTime now = LocalDateTime.now();
        var active = activeMembers(orderId, now);
        var row = active.stream().filter(m -> "TEAMMATE".equals(m.getRole())
                && Objects.equals(memberId, m.getId()) && "ACCEPTED".equals(m.getStatus()))
                .findFirst().orElseThrow(() -> new BusinessException(409, "队友已变更，请刷新订单"));
        removeAccepted(order, actor, active, row, now, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void legacyRemove(Long orderId, Actor actor, Long playerId) {
        Order order = requireOrder(orderId);
        authorize(order, actor);
        requireOpen(order);
        LocalDateTime now = LocalDateTime.now();
        var active = activeMembers(orderId, now);
        var matches = active.stream().filter(m -> "TEAMMATE".equals(m.getRole()) && (playerId == null || playerId.equals(m.getPlayerId()))).toList();
        if (matches.size() != 1) throw new BusinessException(409, "队友已变更，请刷新订单并重选");
        var row = matches.get(0);
        if ("INVITED".equals(row.getStatus())) cancel(orderId, actor, row, now, "");
        else removeAccepted(order, actor, active, row, now, "主打手通过旧版入口移除队友");
    }

    private void removeAccepted(Order order, Actor actor, List<OrderPlayer> active, OrderPlayer row, LocalDateTime now, String reason) {
        for (var invitation : List.copyOf(active)) {
            if ("INVITED".equals(invitation.getStatus()) && row.getId().equals(invitation.getReplacesParticipantId())) {
                cancel(order.getId(), actor, invitation, now, "原队友已移除");
                active.remove(invitation);
            }
        }
        changeStatus(row, "REPLACED", now);
        active.remove(row);
        validateShares(order, active);
        updateReadiness(order, active, now);
        log(order.getId(), actor, "TEAMMATE_REMOVED", "立即移除队友 " + displayName(row.getPlayerId()), reason);
        notice("TEAMMATE_REMOVED", "PLAYER", row.getPlayerId(), order.getId(), "您已退出本单协作");
        if (!"PLAYER".equals(actor.type())) notice("TEAMMATE_REMOVED_STAFF", "PLAYER", order.getPlayerId(),
                order.getId(), "后台已移除队友 " + displayName(row.getPlayerId()));
    }

    private void cancel(Long orderId, Actor actor, OrderPlayer row, LocalDateTime now, String reason) {
        changeStatus(row, "CANCELLED", now);
        log(orderId, actor, "TEAMMATE_INVITE_REVOKED", "撤回 " + displayName(row.getPlayerId()) + " 的协同邀请", reason);
        notice("TEAMMATE_INVITE_REVOKED", "PLAYER", row.getPlayerId(), orderId, "您的协同邀请已撤回");
    }

    private Order requireOrder(Long id) {
        // OrderService locks this row in an active transaction, the same lock used by start/complete and settlement.
        Order order = orders.getById(id);
        if (order == null) throw new BusinessException(404, "订单不存在");
        return order;
    }

    private static boolean isStaff(String type) {
        return "ADMIN".equals(type) || "CS".equals(type);
    }

    private void authorize(Order order, Actor actor) {
        if ("ADMIN".equals(actor.type())) return;
        if ("CS".equals(actor.type()) && actor.id().equals(csRouting.orderCsId(order.getId()))) return;
        if ("PLAYER".equals(actor.type()) && actor.id().equals(order.getPlayerId())) return;
        throw new BusinessException(403, "只有主打手、归属客服或有权限的管理员可以管理协同队伍");
    }

    private void requireOpen(Order order) {
        if (order.getPlayerId() == null || !OPEN.contains(order.getStatus()) || Integer.valueOf(1).equals(order.getSettled())
                || order.getSettleTime() != null || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END FROM order_settlement_ledger WHERE order_id=?",
                Boolean.class, order.getId()))) {
            throw new BusinessException(409, "当前订单状态不允许管理协同队伍");
        }
    }

    private LambdaQueryWrapper<OrderPlayer> rows(Long orderId) {
        return new LambdaQueryWrapper<OrderPlayer>().eq(OrderPlayer::getOrderId, orderId).eq(OrderPlayer::getRole, "TEAMMATE");
    }

    private OrderPlayer pending(Long orderId, Long playerId, LocalDateTime now) {
        var result = members.getOne(rows(orderId).eq(OrderPlayer::getPlayerId, playerId)
                .eq(OrderPlayer::getStatus, "INVITED").last("FOR UPDATE"));
        if (result == null) throw new BusinessException("未找到有效邀请");
        if (expired(result, now)) throw new BusinessException(409, "邀请已过期");
        return result;
    }

    private List<OrderPlayer> activeMembers(Long orderId, LocalDateTime now) {
        // Do not write here: the expiry worker may independently transition expired invitations.
        return members.list(new LambdaQueryWrapper<OrderPlayer>().eq(OrderPlayer::getOrderId, orderId)
                        .in(OrderPlayer::getRole, "TEAMMATE", "RELAY_OUT").in(OrderPlayer::getStatus, "ACCEPTED", "INVITED"))
                .stream().filter(m -> !"INVITED".equals(m.getStatus()) || !expired(m, now))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean expired(OrderPlayer row, LocalDateTime now) {
        return row.getInviteDeadline() != null && !row.getInviteDeadline().isAfter(now);
    }

    private void changeStatus(OrderPlayer member, String status, LocalDateTime now) {
        var update = new LambdaUpdateWrapper<OrderPlayer>().eq(OrderPlayer::getId, member.getId())
                .eq(OrderPlayer::getStatus, member.getStatus()).set(OrderPlayer::getStatus, status)
                .set("ACCEPTED".equals(status) ? OrderPlayer::getAcceptedAt : OrderPlayer::getRejectedAt, now);
        if (!members.update(update)) throw new BusinessException(409, "队友状态已变化，请刷新订单");
        member.setStatus(status);
    }

    private void updateReadiness(Order order, List<OrderPlayer> active, LocalDateTime now) {
        if ("IN_PROGRESS".equals(order.getStatus())) return;
        String next = acceptedCount(active) < required(order) ? "WAITING_TEAMMATE" : "ACCEPTED";
        if (next.equals(order.getStatus())) return;
        if (!orders.update(new LambdaUpdateWrapper<Order>().eq(Order::getId, order.getId()).set(Order::getStatus, next)
                .set(Order::getTeammateDeadline, "WAITING_TEAMMATE".equals(next) ? now.plusHours(2) : null)
                .set(Order::getUpdatedAt, now))) throw new BusinessException(409, "订单状态已变化");
        order.setStatus(next);
    }

    private static int required(Order order) { return Math.max(0, Optional.ofNullable(order.getRequiredPlayerCount()).orElse(1) - 1); }
    private static int capacity(Order order) { return Math.max(1, required(order)); }
    private static long acceptedCount(List<OrderPlayer> roster) {
        return roster.stream().filter(m -> "TEAMMATE".equals(m.getRole()) && "ACCEPTED".equals(m.getStatus())).count();
    }

    private int maxConcurrent() {
        try { return Math.max(1, Integer.parseInt(config.getConfigValue("order.max_active_per_player", "5"))); }
        catch (NumberFormatException e) { return 5; }
    }

    private void validateShares(Order order, List<OrderPlayer> roster) {
        var current = roster.stream().filter(m -> "ACCEPTED".equals(m.getStatus())).toList();
        var standalone = roster.stream().filter(m -> "INVITED".equals(m.getStatus()) && m.getReplacesParticipantId() == null).toList();
        var replacements = roster.stream().filter(m -> "INVITED".equals(m.getStatus()) && m.getReplacesParticipantId() != null)
                .collect(Collectors.toMap(OrderPlayer::getReplacesParticipantId, Function.identity()));
        BigDecimal rate = order.getCommissionRate() == null
                ? new BigDecimal(config.getConfigValue("settlement.commission_rate", "0.2")) : order.getCommissionRate();
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0)
            throw new BusinessException(409, "抽佣比例无效");
        BigDecimal available = order.getAmount().multiply(BigDecimal.ONE.subtract(rate)).setScale(2, RoundingMode.HALF_UP);
        if (!roster.isEmpty() && available.signum() <= 0) throw new BusinessException(409, "订单没有可分配的收益");
        // Check every possible headcount. Within each headcount, select the largest share
        // for each replacement slot and the largest ordinary invitations.
        for (int added = 0; added <= standalone.size(); added++) {
            int sharing = current.size() + added;
            BigDecimal total = BigDecimal.ZERO;
            for (OrderPlayer m : current) {
                BigDecimal share = share(m, available, sharing);
                OrderPlayer incoming = replacements.get(m.getId());
                if (incoming != null) share = share.max(share(incoming, available, sharing));
                total = total.add(share);
            }
            total = total.add(standalone.stream().map(m -> share(m, available, sharing))
                    .sorted(Comparator.reverseOrder()).limit(added).reduce(BigDecimal.ZERO, BigDecimal::add));
            if (total.compareTo(available) > 0) throw new BusinessException(409, "队伍分成总额超过可结算收益");
        }
    }

    private static BigDecimal share(OrderPlayer m, BigDecimal amount, int sharing) {
        String split = Objects.toString(m.getSplitType(), "FIFTY_FIFTY");
        return switch (split) {
            case "CUSTOM" -> Optional.ofNullable(m.getSplitAmount()).orElse(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            case "FORTY_SIXTY" -> amount.multiply(new BigDecimal("0.4")).setScale(2, RoundingMode.HALF_UP);
            case "THIRTY_SEVENTY" -> amount.multiply(new BigDecimal("0.3")).setScale(2, RoundingMode.HALF_UP);
            default -> amount.divide(BigDecimal.valueOf(sharing + 1L), 2, RoundingMode.HALF_UP);
        };
    }

    private String displayName(Long id) {
        String name = identities.selectPlayerNickname(id);
        return name == null || name.isBlank() ? "打手 #" + id : name;
    }

    private static String reason(String raw, boolean required) {
        String value = raw == null ? "" : raw.strip();
        if ((required && value.isBlank()) || value.length() > 200)
            throw new BusinessException(400, "请填写不超过 200 字的操作原因");
        return value;
    }

    private void log(Long id, Actor actor, String action, String text, String reason) {
        OrderProgress entry = new OrderProgress();
        entry.setOrderId(id);
        entry.setType(action);
        entry.setOperatorType(actor.type());
        entry.setOperatorId(actor.id());
        entry.setContent(text);
        if (!reason.isBlank()) entry.setRemark(reason);
        entry.setCreatedAt(LocalDateTime.now());
        if (!progress.save(entry)) throw new BusinessException(409, "未能记录协作变更，请重试");
    }

    private void notifyInvitingStaff(OrderPlayer invitation, Order order, String event, String message) {
        String role = invitation.getInvitedByType();
        if ("ADMIN".equals(role) || ("CS".equals(role) && Objects.equals(invitation.getInvitedBy(), csRouting.orderCsId(order.getId())))) {
            notice(event, role, invitation.getInvitedBy(), order.getId(), message);
        }
    }

    private void notice(String type, String role, Long recipient, Long orderId, String message) {
        if (recipient != null) events.publishEvent(new BusinessEvent(this, type, role, recipient, orderId, message));
    }
}
