package com.delta.player.controller;

import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.common.utils.ImageListUtils;
import com.delta.player.dto.PlayerProfileVO;
import com.delta.player.dto.PlayerHomepageRequest;
import com.delta.player.dto.PublicPlayerVO;
import com.delta.player.service.PlayerLevelService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import com.delta.player.entity.Player;
import com.delta.player.entity.PlayerWallet;
import com.delta.player.service.PlayerService;
import com.delta.player.service.PlayerWalletService;
import com.delta.pay.service.PaymentService;
import com.delta.user.entity.User;
import com.delta.user.service.UserService;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/player")
@RequiredArgsConstructor
public class PlayerController {
    private final PlayerService playerService;
    private final com.delta.player.service.PlayerGovernanceService governance;
    private final PlayerLevelService levelService;
    private final PlayerWalletService playerWalletService;
    private final UserService userService;
    private final PaymentService paymentService;
    private final SysConfigService sysConfigService;

    @GetMapping("/profile")
    public R<PlayerProfileVO> profile() {
        Long playerId = SecurityUtils.getUserId();
        Player player = playerService.getById(playerId);
        if (player == null) return R.fail(1002, "打手记录不存在");
        PlayerWallet wallet = playerWalletService.getByPlayerId(playerId);
        PlayerProfileVO vo = new PlayerProfileVO();
        vo.setId(player.getId());
        vo.setNickname(player.getNickname());
        vo.setAvatar(player.getAvatar());
        vo.setRealName(player.getRealName());
        vo.setPhone(player.getPhone());
        vo.setStatus(player.getStatus());
        vo.setGameLevel(player.getGameLevel());
        vo.setLevel(player.getLevel());
        vo.setIntroduction(player.getIntroduction());
        vo.setLevelName(levelService.name(player.getLevel()));
        vo.setCoverImage(player.getCoverImage());
        vo.setAlbumImages(PublicPlayerVO.from(player).getAlbumImages());
        vo.setStory(player.getStory());
        vo.setVoiceUrl(player.getVoiceUrl());
        vo.setVoiceDuration(player.getVoiceDuration());
        vo.setAvgRating(player.getAvgRating());
        vo.setOrderCount(player.getOrderCount());
        vo.setCompleteRate(player.getCompleteRate());
        vo.setIsOnline(player.getIsOnline());
        if (wallet != null) {
            vo.setBalance(wallet.getBalance());
            vo.setTotalIncome(wallet.getTotalIncome());
        }
        return R.ok(vo);
    }

    @PutMapping("/profile")
    public R<Void> updateProfile(@RequestBody Player player) {
        if (player.getIntroduction() != null && player.getIntroduction().length() > 1000) return R.fail("个人简介不能超过1000字");
        Player update = new Player();
        update.setId(SecurityUtils.getUserId());
        // Public avatar changes go through the reviewed homepage endpoint.
        // Real identity is maintained by staff and privacy requests, not the public editor.
        update.setSkillTags(player.getSkillTags());
        update.setGameLevel(player.getGameLevel());
        if (player.getProofImages() != null) update.setProofImages(ImageListUtils.normalizeLocal(player.getProofImages(), 4));
        update.setServiceTypes(player.getServiceTypes());
        // Public introduction changes go through the reviewed homepage endpoint.
        playerService.updateById(update);
        return R.ok();
    }

    @PutMapping("/homepage")
    @PreAuthorize("hasRole('PLAYER')")
    public R<Void> updateHomepage(@Valid @RequestBody PlayerHomepageRequest body) {
        Long id=SecurityUtils.getUserId();
        if(playerService.getById(id)==null) return R.fail(404,"打手不存在");
        governance.submit(id,body);return R.ok();
    }

    @PostMapping("/apply")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public R<Void> apply(@RequestBody Player player) {
        Player supplied=player;player=new Player();
        if(!Boolean.TRUE.equals(supplied.getPrivacyAccepted()))return R.fail(400,"请同意用户协议和隐私政策");
        if(supplied.getNickname()==null||supplied.getNickname().isBlank()||supplied.getNickname().length()>32||supplied.getRealName()==null||supplied.getRealName().isBlank()||supplied.getRealName().length()>50||supplied.getAvatar()==null||!supplied.getAvatar().matches("/file/.{1,500}"))return R.fail(400,"请上传头像并填写接单名和真实姓名");
        player.setNickname(supplied.getNickname());player.setAvatar(supplied.getAvatar());player.setRealName(supplied.getRealName());
        player.setSkillTags(supplied.getSkillTags());player.setGameLevel(supplied.getGameLevel());player.setIntroduction(supplied.getIntroduction());
        player.setProofImages(supplied.getProofImages());player.setServiceTypes(supplied.getServiceTypes());player.setDepositPaymentNo(supplied.getDepositPaymentNo());
        player.setLevel(null); // Platform levels can only be assigned by an administrator.
        if (player.getIntroduction() != null && player.getIntroduction().length() > 1000) return R.fail("个人简介不能超过1000字");
        Long userId = SecurityUtils.getUserId();
        User applicant=userService.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>().eq(User::getId,userId).last("FOR UPDATE"));
        if(applicant==null) return R.fail(404,"用户不存在");
        if(applicant.getPhone()==null||!applicant.getPhone().matches("1[0-9]{10}"))return R.fail(400,"请先验证并绑定手机号");
        Player existing = playerService.getByOpenid(applicant.getOpenid());
        if(existing!=null && !"REJECTED".equals(existing.getStatus())) return R.fail(409,"已有入驻记录，请查看当前状态");
        String depositPaymentNo = null;
        if (enrollmentRequiresDeposit()) {
            BigDecimal required = paymentService.playerDepositRequiredAmount(userId);
            if (required.signum() > 0) {
                // Recover the receipt from the current user's ledger, never from browser storage.
                depositPaymentNo = paymentService.latestPaidPlayerDepositNo(userId);
                if (depositPaymentNo == null || paymentService.playerDepositPaidAmount(userId).compareTo(required) < 0)
                    return R.fail(409, "押金未到账或未缴足，请到账后再提交");
            }
        }
        player.setPhone(applicant.getPhone());
        player.setProofImages(ImageListUtils.normalizeLocal(player.getProofImages(), 4));
        if (existing == null) {
            // 从用户表获取基本信息
            User user = userService.getById(userId);
            if (user == null) return R.fail("用户不存在");
            // 新用户申请，插入新记录
            player.setId(null);
            player.setOpenid(user.getOpenid());
            if (player.getNickname() == null) player.setNickname(user.getNickname());
            if (player.getAvatar() == null) player.setAvatar(user.getAvatar());
            player.setStatus("PENDING");
            player.setOrderCount(0);
            player.setAvgRating(BigDecimal.ZERO);
            player.setCompleteRate(BigDecimal.ZERO);
            player.setDepositPaymentNo(depositPaymentNo);
            playerService.save(player);
            playerWalletService.initWallet(player.getId());
        } else {
            // 已有记录，更新资料重新提交
            player.setId(existing.getId());
            player.setOpenid(null); // 不允许修改openid
            player.setStatus("PENDING");
            player.setRejectReason(null);
            player.setDepositPaymentNo(depositPaymentNo);
            playerService.updateById(player);
        }
        governance.enroll(userId,player.getId(),supplied.getInvitationToken(),supplied.getDeviceId(),Boolean.TRUE.equals(supplied.getPrivacyAccepted()));
        return R.ok();
    }

    private boolean enrollmentRequiresDeposit() {
        String fallback = "true".equalsIgnoreCase(sysConfigService.getConfigValue("player.deposit_required", "true")) ? "REQUIRED" : "NONE";
        return "REQUIRED".equalsIgnoreCase(java.util.Objects.toString(
                sysConfigService.getConfigValue("player.deposit_mode", fallback), fallback).trim());
    }

    /** Read-only application lookup for a normal user before switching to a player token. */
    @GetMapping("/apply/status")
    public R<Map<String, Object>> applicationStatus() {
        User applicant = userService.getById(SecurityUtils.getUserId());
        if (applicant == null) return R.fail(404, "用户不存在");
        Player existing = applicant.getOpenid() == null || applicant.getOpenid().isBlank()
                ? null : playerService.getByOpenid(applicant.getOpenid());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registered", existing != null);
        if (enrollmentRequiresDeposit()) {
            BigDecimal required = paymentService.playerDepositRequiredAmount(applicant.getId());
            result.put("depositSatisfied", required.signum() <= 0
                    || paymentService.playerDepositPaidAmount(applicant.getId()).compareTo(required) >= 0);
        }
        if (existing != null) {
            result.put("status", existing.getStatus());
            result.put("rejectReason", existing.getRejectReason());
        }
        return R.ok(result);
    }

    @com.delta.common.annotation.OpLog(module="shift",operation="打手自主上下班")
    @PostMapping("/online-status")
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public R<Void> toggleOnlineStatus(@RequestParam("online") Boolean online) {
        Long playerId = SecurityUtils.getUserId();
        var current=playerService.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Player>().eq(Player::getId,playerId).last("FOR UPDATE"));
        if(current==null||!"ACTIVE".equals(current.getStatus()))return R.fail(403,"账号未通过审核或已冻结");
        Player p = new Player();
        p.setId(playerId);
        p.setIsOnline(Boolean.TRUE.equals(online) ? 1 : 0);
        p.setLastOnlineAt(LocalDateTime.now());
        playerService.updateById(p);
        return R.ok();
    }
}
