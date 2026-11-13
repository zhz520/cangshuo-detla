package com.delta.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.BatchIds;
import com.delta.common.domain.BatchResult;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.order.entity.Order;
import com.delta.order.service.OrderService;
import com.delta.pay.entity.Transaction;
import com.delta.pay.service.TransactionService;
import com.delta.player.entity.Player;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.service.PlayerService;
import com.delta.player.service.PlayerLevelService;
import com.delta.player.dto.PlayerAdminUpdateRequest;
import jakarta.validation.Valid;
import com.delta.player.service.PlayerWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/admin/player")
@RequiredArgsConstructor
public class AdminPlayerController {
    private final PlayerService playerService;
    private final com.delta.player.service.PlayerGovernanceService governance;
    private final PlayerLevelService levelService;
    private final PlayerWalletService playerWalletService;
    private final OrderService orderService;
    private final TransactionService transactionService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.delta.system.service.FeatureAuditService featureAudit;

    public record BatchIdsRequest(List<Long> ids) {}
    public record BatchRejectRequest(List<Long> ids, String reason) {}
    public record BatchLevelRequest(List<Long> ids, Integer levelId) {}

    @GetMapping("/list")
    public R<Page<Player>> list(PageQuery query, @RequestParam(required = false) Long taskId,
                                @RequestParam(value = "status", required = false) String status,
                                @RequestParam(value = "keyword", required = false) String keyword,
                                @RequestParam(value = "level", required = false) Integer level) {
        LambdaQueryWrapper<Player> w = new LambdaQueryWrapper<Player>()
                .eq(taskId != null, Player::getId, taskId)
                .eq(status != null && !status.isEmpty(), Player::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), Player::getNickname, keyword)
                .eq(level != null, Player::getLevel, level)
                .orderByDesc(Player::getCreatedAt);
        Page<Player> page = playerService.page(new Page<>(query.getPageNum(), query.getPageSize()), w);
        for (Player p : page.getRecords()) {
            PlayerWallet wallet = playerWalletService.getByPlayerId(p.getId());
            p.setBalance(wallet != null ? wallet.getBalance() : BigDecimal.ZERO);
            long completed = orderService.count(new LambdaQueryWrapper<Order>()
                    .eq(Order::getPlayerId, p.getId())
                    .in(Order::getStatus, "CONFIRMED", "REVIEWED", "SETTLED"));
            p.setCompletedOrders((int) completed);
        }
        return R.ok(page);
    }

    @GetMapping("/{id}")
    public R<Player> detail(@PathVariable Long id) {
        Player p = playerService.getById(id);
        if (p != null) {
            PlayerWallet wallet = playerWalletService.getByPlayerId(id);
            p.setBalance(wallet != null ? wallet.getBalance() : BigDecimal.ZERO);
            long completed = orderService.count(new LambdaQueryWrapper<Order>()
                    .eq(Order::getPlayerId, id)
                    .in(Order::getStatus, "CONFIRMED", "REVIEWED", "SETTLED"));
            p.setCompletedOrders((int) completed);
        }
        return R.ok(p);
    }

    @GetMapping("/{id}/transactions")
    public R<Page<Transaction>> transactions(@PathVariable Long id, PageQuery query) {
        LambdaQueryWrapper<Transaction> w = new LambdaQueryWrapper<Transaction>()
                .eq(Transaction::getUserType, "PLAYER")
                .eq(Transaction::getUserId, id)
                .orderByDesc(Transaction::getCreatedAt);
        return R.ok(transactionService.page(new Page<>(query.getPageNum(), query.getPageSize()), w));
    }

    @OpLog(module = "player", operation = "审核通过")
    @PutMapping("/{id}/approve")
    public R<Void> approve(@PathVariable Long id, @RequestParam(required = false) Integer levelId) {
        approveApplication(id, levelId);
        return R.ok();
    }

    private void approveApplication(Long id, Integer levelId) {
        governance.checkApplication(id);
        if (levelId != null) {
            var level = levelService.getById(levelId);
            if (level == null || !Integer.valueOf(1).equals(level.getStatus())) {
                throw new BusinessException(409, "请选择有效且启用的打手等级");
            }
        }
        boolean updated = playerService.update(new LambdaUpdateWrapper<Player>()
                .eq(Player::getId, id)
                .eq(Player::getStatus, "PENDING")
                .set(Player::getStatus, "ACTIVE")
                .set(Player::getRejectReason, "")
                .set(levelId != null, Player::getLevel, levelId));
        if (!updated) throw new BusinessException(409, "申请状态已变更，请刷新后重试");
        eventPublisher.publishEvent(new BusinessEvent(this, "PLAYER_APPROVED",
                "PLAYER", id, null, "您的入驻申请已通过审核"));
    }

    @OpLog(module = "player", operation = "审核驳回")
    @PutMapping("/{id}/reject")
    public R<Void> reject(@PathVariable Long id, @RequestParam("reason") String reason) {
        requireStatus(id, "PENDING", "仅待审核入驻申请可驳回");
        Player p = new Player();
        p.setId(id);
        p.setStatus("REJECTED");
        p.setRejectReason(reason);
        playerService.updateById(p);
        eventPublisher.publishEvent(new BusinessEvent(this, "PLAYER_REJECTED",
                "PLAYER", id, null, "入驻申请被驳回：" + reason));
        return R.ok();
    }

    @OpLog(module = "player", operation = "冻结打手")
    @PutMapping("/{id}/freeze")
    public R<Void> freeze(@PathVariable Long id, @RequestBody Player body) {
        Player p = new Player();
        p.setId(id);
        p.setStatus("FROZEN");
        p.setIsOnline(0);
        p.setFrozenUntil(body.getFrozenUntil());
        playerService.updateById(p);
        eventPublisher.publishEvent(new BusinessEvent(this, "PLAYER_FROZEN",
                "PLAYER", id, null, "您的账号已被冻结，冻结期间无法接单和提现"));
        return R.ok();
    }

    @OpLog(module = "player", operation = "解冻打手")
    @PutMapping("/{id}/unfreeze")
    public R<Void> unfreeze(@PathVariable Long id) {
        requireStatus(id, "FROZEN", "仅已冻结打手可解冻");
        Player p = new Player();
        p.setId(id);
        p.setStatus("ACTIVE");
        p.setIsOnline(0);
        playerService.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Player>().eq(Player::getId,id).set(Player::getFrozenUntil,null));
        playerService.updateById(p);
        eventPublisher.publishEvent(new BusinessEvent(this, "PLAYER_UNFROZEN",
                "PLAYER", id, null, "您的账号已解冻"));
        return R.ok();
    }

    private Player requireStatus(Long id, String expected, String message) {
        Player current = playerService.getById(id);
        if (current == null) throw new BusinessException(404, "打手不存在");
        if (!expected.equals(current.getStatus())) throw new BusinessException(409, message);
        return current;
    }

    @OpLog(module = "player", operation = "批量通过入驻审核")
    @PostMapping("/batch/approve")
    public R<BatchResult> batchApprove(@RequestBody BatchIdsRequest body) {
        List<Long> ids = BatchIds.normalize(body.ids(), 100);
        int success = 0;
        var failedIds = new ArrayList<Long>();
        var messages = new ArrayList<String>();
        for (Long id : ids) {
            try {
                approveApplication(id, null);
                success++;
            } catch (BusinessException error) {
                failedIds.add(id);
                messages.add("打手 " + id + "：" + error.getMessage());
            } catch (RuntimeException error) {
                failedIds.add(id);
                messages.add("打手 " + id + "：操作失败");
            }
        }
        return R.ok(new BatchResult(success, failedIds.size(), failedIds, messages));
    }

    @OpLog(module = "player", operation = "批量驳回入驻审核")
    @PostMapping("/batch/reject")
    public R<BatchResult> batchReject(@RequestBody BatchRejectRequest body) {
        List<Long> ids = BatchIds.normalize(body.ids(), 100);
        String reason = body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty()) throw new BusinessException(400, "请填写驳回原因");
        if (reason.length() > 255) throw new BusinessException(400, "驳回原因不能超过 255 字");
        int success = 0;
        var failedIds = new ArrayList<Long>();
        var messages = new ArrayList<String>();
        for (Long id : ids) {
            try {
                reject(id, reason);
                success++;
            } catch (BusinessException error) {
                failedIds.add(id);
                messages.add("打手 " + id + "：" + error.getMessage());
            } catch (RuntimeException error) {
                failedIds.add(id);
                messages.add("打手 " + id + "：操作失败");
            }
        }
        return R.ok(new BatchResult(success, failedIds.size(), failedIds, messages));
    }

    @OpLog(module = "player", operation = "批量调整打手等级")
    @PostMapping("/batch/level")
    public R<BatchResult> batchLevel(@RequestBody BatchLevelRequest body) {
        List<Long> ids = BatchIds.normalize(body.ids(), 200);
        if (body.levelId() == null) throw new BusinessException(400, "等级分类不存在");
        var level = levelService.getById(body.levelId());
        if (level == null) throw new BusinessException(400, "等级分类不存在");
        if (!Integer.valueOf(1).equals(level.getStatus())) {
            throw new BusinessException(409, "所选等级已停用，请启用后再批量调整");
        }
        int success = 0;
        var failedIds = new ArrayList<Long>();
        var messages = new ArrayList<String>();
        for (Long id : ids) {
            try {
                Player current = playerService.getById(id);
                if (current == null) throw new BusinessException(404, "打手不存在");
                Player update = new Player();
                update.setId(id);
                update.setLevel(body.levelId());
                playerService.updateById(update);
                success++;
            } catch (BusinessException error) {
                failedIds.add(id);
                messages.add("打手 " + id + "：" + error.getMessage());
            } catch (RuntimeException error) {
                failedIds.add(id);
                messages.add("打手 " + id + "：操作失败");
            }
        }
        return R.ok(new BatchResult(success, failedIds.size(), failedIds, messages));
    }

    @OpLog(module = "player", operation = "批量解冻打手")
    @PostMapping("/batch/unfreeze")
    public R<BatchResult> batchUnfreeze(@RequestBody BatchIdsRequest body) {
        List<Long> ids = BatchIds.normalize(body.ids(), 100);
        int success = 0;
        var failedIds = new ArrayList<Long>();
        var messages = new ArrayList<String>();
        for (Long id : ids) {
            try {
                unfreeze(id);
                success++;
            } catch (BusinessException error) {
                failedIds.add(id);
                messages.add("打手 " + id + "：" + error.getMessage());
            } catch (RuntimeException error) {
                failedIds.add(id);
                messages.add("打手 " + id + "：操作失败");
            }
        }
        return R.ok(new BatchResult(success, failedIds.size(), failedIds, messages));
    }

    @OpLog(module = "player", operation = "更新打手状态")
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam("status") String status) {
        if(!java.util.Set.of("ACTIVE","PENDING","REJECTED","FROZEN").contains(status))return R.fail(400,"打手状态无效");
        if("ACTIVE".equals(status)){var current=playerService.getById(id);if(current!=null&&"PENDING".equals(current.getStatus()))governance.checkApplication(id);}
        Player p = new Player();
        p.setId(id);
        p.setStatus(status);
        playerService.updateById(p);
        return R.ok();
    }

    @OpLog(module = "player", operation = "删除打手（保留历史）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        playerService.deleteSafely(id);
        return R.ok();
    }

    @OpLog(module = "player", operation = "更新打手信息")
    @PutMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public R<Void> updatePlayer(@PathVariable Long id, @Valid @RequestBody PlayerAdminUpdateRequest body) {
        if(playerService.getById(id)==null) return R.fail(404,"打手不存在");
        if(body.getLevel()!=null&&levelService.getById(body.getLevel())==null) return R.fail(400,"等级分类不存在");
        Player p = new Player();
        p.setId(id);
        body.applyTo(p);
        p.setRealName(body.getRealName());
        p.setLevel(body.getLevel());
        p.setGrabCaptchaOverride(body.getGrabCaptchaOverride());
        p.setPriceIncreaseRate(body.getPriceIncreaseRate());
        Player before=playerService.getById(id);
        playerService.updateById(p);
        featureAudit.record("修改打手涨价比例",id,
                before.getPriceIncreaseRate()==null?new java.math.BigDecimal("-1"):before.getPriceIncreaseRate(),
                p.getPriceIncreaseRate()==null?before.getPriceIncreaseRate():p.getPriceIncreaseRate());
        featureAudit.record("修改打手等级及验证覆盖",id,
                java.util.Map.of("level",before.getLevel()==null?1:before.getLevel(),"grabCaptchaOverride",before.getGrabCaptchaOverride()==null?-1:before.getGrabCaptchaOverride()),
                java.util.Map.of("level",p.getLevel()==null?(before.getLevel()==null?1:before.getLevel()):p.getLevel(),"grabCaptchaOverride",p.getGrabCaptchaOverride()==null?(before.getGrabCaptchaOverride()==null?-1:before.getGrabCaptchaOverride()):p.getGrabCaptchaOverride()));
        return R.ok();
    }
}
