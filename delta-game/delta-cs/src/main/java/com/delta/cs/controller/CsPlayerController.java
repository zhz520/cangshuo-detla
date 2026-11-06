package com.delta.cs.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.pay.entity.Transaction;
import com.delta.pay.service.TransactionService;
import com.delta.player.entity.Player;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.service.PlayerService;
import com.delta.player.service.PlayerWalletService;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/cs/player")
@RequiredArgsConstructor
public class CsPlayerController {
    private final PlayerService playerService;
    private final com.delta.player.service.PlayerGovernanceService governance;
    private final PlayerWalletService playerWalletService;
    private final CrossModuleMapper crossModuleMapper;
    private final TransactionService transactionService;
    private final SysConfigService sysConfigService;

    @GetMapping("/list")
    public R<Page<Player>> list(PageQuery query, @RequestParam(required = false) Long taskId,
                                @RequestParam(value = "status", required = false) String status,
                                @RequestParam(value = "keyword", required = false) String keyword) {
        LambdaQueryWrapper<Player> w = new LambdaQueryWrapper<Player>()
                .eq(taskId != null, Player::getId, taskId)
                .eq(status != null && !status.isEmpty(), Player::getStatus, status)
                .and(keyword != null && !keyword.isEmpty(), qw -> qw.like(Player::getNickname, keyword).or().like(Player::getPhone, keyword))
                .orderByDesc(Player::getCreatedAt);
        Page<Player> page = playerService.page(new Page<>(query.getPageNum(), query.getPageSize()), w);
        for (Player p : page.getRecords()) {
            p.setCompletedOrders(crossModuleMapper.selectPlayerCompletedOrders(p.getId()));
            p.setActiveOrders(crossModuleMapper.selectPlayerActiveOrders(p.getId()));
        }
        return R.ok(page);
    }

    /**
     * 指派订单专用打手列表：返回打手信息 + 最大接单数配置
     */
    @GetMapping("/assign-list")
    public R<Map<String, Object>> assignList(PageQuery query,
                                              @RequestParam(value = "keyword", required = false) String keyword) {
        LambdaQueryWrapper<Player> w = new LambdaQueryWrapper<Player>()
                .eq(Player::getStatus, "ACTIVE")
                .and(keyword != null && !keyword.isEmpty(), qw -> qw.like(Player::getNickname, keyword).or().like(Player::getPhone, keyword))
                .orderByDesc(Player::getCreatedAt);
        Page<Player> page = playerService.page(new Page<>(query.getPageNum(), query.getPageSize()), w);
        for (Player p : page.getRecords()) {
            p.setCompletedOrders(crossModuleMapper.selectPlayerCompletedOrders(p.getId()));
            p.setActiveOrders(crossModuleMapper.selectPlayerActiveOrders(p.getId()));
        }
        int maxConcurrent = Integer.parseInt(sysConfigService.getConfigValue("order.max_active_per_player", "1"));
        Map<String, Object> result = new HashMap<>();
        result.put("players", page);
        result.put("maxConcurrent", maxConcurrent);
        return R.ok(result);
    }

    @GetMapping("/{id}")
    public R<Player> detail(@PathVariable Long id) {
        Player p = playerService.getById(id);
        if (p != null) {
            p.setCompletedOrders(crossModuleMapper.selectPlayerCompletedOrders(id));
            p.setActiveOrders(crossModuleMapper.selectPlayerActiveOrders(id));
            PlayerWallet wallet = playerWalletService.getByPlayerId(id);
            p.setBalance(wallet != null ? wallet.getBalance() : BigDecimal.ZERO);
        }
        return R.ok(p);
    }

    @OpLog(module="shift",operation="客服操作打手下班")
    @PutMapping("/{id}/online-status")
    public R<Void> offline(@PathVariable Long id,@RequestBody Map<String,Boolean> body) {
        if(!Boolean.FALSE.equals(body.get("online")))return R.fail(403,"客服只能操作打手下班，不能操作上班");
        Player player=playerService.getById(id);if(player==null)return R.fail(404,"打手不存在");
        Player update=new Player();update.setId(id);update.setIsOnline(0);playerService.updateById(update);return R.ok();
    }
    @GetMapping("/{id}/transactions")
    public R<Page<Transaction>> transactions(@PathVariable Long id, PageQuery query) {
        LambdaQueryWrapper<Transaction> w = new LambdaQueryWrapper<Transaction>()
                .eq(Transaction::getUserType, "PLAYER")
                .eq(Transaction::getUserId, id)
                .orderByDesc(Transaction::getCreatedAt);
        return R.ok(transactionService.page(new Page<>(query.getPageNum(), query.getPageSize()), w));
    }

    @OpLog(module = "player", operation = "审核打手")
    @PutMapping("/{id}/audit")
    public R<Void> audit(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.getOrDefault("status", "");
        String rejectReason = body.getOrDefault("rejectReason", "");
        if(!java.util.Set.of("ACTIVE","REJECTED").contains(status))return R.fail(400,"审核状态无效");
        var existing=playerService.getById(id);if(existing==null||!"PENDING".equals(existing.getStatus()))return R.fail(409,"入驻申请不存在或已审核");
        if("ACTIVE".equals(status))governance.checkApplication(id);
        Player p = new Player();
        p.setId(id);
        p.setStatus(status);
        p.setRejectReason(rejectReason);
        playerService.updateById(p);
        return R.ok();
    }

    @OpLog(module = "player", operation = "冻结打手")
    @PutMapping("/{id}/freeze")
    public R<Void> freeze(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Player p = new Player();
        p.setId(id);
        p.setStatus("FROZEN");
        p.setIsOnline(0);
        playerService.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Player>().eq(Player::getId,id).set(Player::getFrozenUntil,null));
        // 支持冻结时长（小时），0 或不传表示永久冻结
        if (body != null && body.get("hours") != null) {
            int hours = Integer.parseInt(body.get("hours").toString());
            if (hours > 0) {
                p.setFrozenUntil(LocalDateTime.now().plusHours(hours));
            }
        }
        playerService.updateById(p);
        return R.ok();
    }

    @OpLog(module = "player", operation = "修改打手昵称")
    @PutMapping("/{id}/nickname")
    public R<Void> updateNickname(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String nickname = body.get("nickname");
        if (nickname == null || nickname.trim().isEmpty()) {
            return R.fail("昵称不能为空");
        }
        Player p = new Player();
        p.setId(id);
        p.setNickname(nickname.trim());
        playerService.updateById(p);
        return R.ok();
    }
}
