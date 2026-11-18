package com.delta.player.service;

import com.delta.player.entity.Player;
import com.delta.player.entity.PlayerLevel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

final class OrderCandidateLevelNames {
    private OrderCandidateLevelNames() {}

    static Map<Integer, String> load(PlayerLevelService levels, List<Player> players) {
        var ids = players.stream().map(Player::getLevel).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return levels.lambdaQuery().select(PlayerLevel::getId, PlayerLevel::getName).in(PlayerLevel::getId, ids)
                .list().stream().collect(Collectors.toMap(PlayerLevel::getId, PlayerLevel::getName));
    }
}
