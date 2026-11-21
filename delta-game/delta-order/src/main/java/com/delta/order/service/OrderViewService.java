package com.delta.order.service;

import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.mapper.StatsMapper;
import com.delta.order.entity.Order;
import com.delta.order.entity.OrderPlayer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Enriches order views with user/player/CS display data and spending rank. */
@Service
@RequiredArgsConstructor
public class OrderViewService {
    private final CrossModuleMapper crossModuleMapper;
    private final StatsMapper statsMapper;
    private final JdbcTemplate jdbc;

    public void enrichOrder(Order order, boolean fallbackNames) {
        if (order.getUserId() != null) {
            String nickname = crossModuleMapper.selectUserNickname(order.getUserId());
            order.setUserNickname(fallbackNames && (nickname == null || nickname.isEmpty()) ? "用户" + order.getUserId() : nickname);
            order.setUserAvatar(crossModuleMapper.selectUserAvatar(order.getUserId()));
            order.setUserPhone(crossModuleMapper.selectUserPhone(order.getUserId()));
        }
        if (order.getPlayerId() != null) {
            String nickname = crossModuleMapper.selectPlayerNickname(order.getPlayerId());
            order.setPlayerName(fallbackNames && (nickname == null || nickname.isEmpty()) ? "接单员" + order.getPlayerId() : nickname);
            order.setPlayerAvatar(crossModuleMapper.selectPlayerAvatar(order.getPlayerId()));
        }
        fillCs(order);
    }

    public void enrichTeammate(OrderPlayer teammate) {
        teammate.setNickname(crossModuleMapper.selectPlayerNickname(teammate.getPlayerId()));
        teammate.setAvatar(crossModuleMapper.selectPlayerAvatar(teammate.getPlayerId()));
    }

    public void fillCs(Order order) {
        try {
            List<Long> rows = jdbc.queryForList("SELECT cs_id FROM `order` WHERE id=?", Long.class, order.getId());
            if (rows != null && !rows.isEmpty()) {
                order.setCsId(rows.get(0));
                if (rows.get(0) != null) order.setCsName(crossModuleMapper.selectAdminNickname(rows.get(0)));
            }
        } catch (org.springframework.dao.DataAccessException ignored) {
        }
    }

    public List<Map<String, Object>> spendingRank(int limit) {
        return statsMapper.userSpendingRank(Math.min(limit, 200));
    }
}
