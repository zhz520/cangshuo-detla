package com.delta.player.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.enums.OrderStatusEnum;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.order.entity.Order;
import com.delta.order.service.OrderAssignmentRules;
import com.delta.order.service.OrderService;
import com.delta.player.entity.Player;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderAssignmentCandidateService {
    private final OrderService orders;
    private final PlayerService players;
    private final PlayerLevelService levels;
    private final CrossModuleMapper identities;
    private final SysConfigService config;

    public record Candidate(Long id, String nickname, Integer levelId, String levelName, int activeOrders) {}
    public record Candidates(Page<Candidate> players, int maxConcurrent, Integer requiredLevelId, String requiredLevelName) {}

    public Candidates candidates(Long orderId, long page, long size, String keyword) {
        Order order = orders.getById(orderId);
        OrderAssignmentRules.requireAssignable(order);
        String search = keyword == null ? "" : keyword.strip();
        if (search.length() > 50) throw new BusinessException(400, "搜索内容过长");
        boolean reassign = OrderStatusEnum.ASSIGNED.name().equals(order.getStatus());
        var filter = new LambdaQueryWrapper<Player>()
                .select(Player::getId, Player::getNickname, Player::getLevel)
                .eq(Player::getStatus, "ACTIVE").eq(Player::getIsOnline, 1)
                .ne(reassign && order.getPlayerId() != null, Player::getId, order.getPlayerId())
                .like(!search.isEmpty(), Player::getNickname, search)
                .orderByAsc(Player::getId);
        OrderAssignmentRules.restrictToRequestedLevel(order, filter, Player::getLevel);
        var result = players.page(new Page<Player>(page, size), filter);
        var names = OrderCandidateLevelNames.load(levels, result.getRecords());
        var out = new Page<Candidate>(result.getCurrent(), result.getSize(), result.getTotal());
        out.setRecords(result.getRecords().stream().map(player -> new Candidate(player.getId(), player.getNickname(),
                player.getLevel(), player.getLevel() == null ? null : names.get(player.getLevel()),
                identities.countActivePlayerOrders(player.getId()))).toList());
        Integer requiredLevelId = order.getRequestedPlayerLevelId();
        return new Candidates(out, OrderAssignmentRules.maxConcurrent(config), requiredLevelId,
                requiredLevelId == null ? null : order.getPlayerLevelName());
    }
}
