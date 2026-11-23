package com.delta.notification.service;

import com.delta.common.exception.BusinessException;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** A per-tenant notification preference, deliberately independent of OAuth login/payment defaults. */
@Service
@RequiredArgsConstructor
public class WechatNotificationSender {
    private final JdbcTemplate jdbc;
    private final SysConfigService configs;

    public record Candidate(String id, String name, String appId) {}
    public record Selection(String mode, String status, String accountId, String appId, List<Candidate> candidates) {
        boolean managed() { return "MANAGED".equals(status); }
        boolean ready() { return managed() || "DIRECT".equals(status) || "LEGACY".equals(status); }
    }
    public record Choice(String mode, String accountId) {}

    public Selection current() {
        var preferences = jdbc.query("SELECT mode,account_id FROM wechat_notice_sender WHERE id=1",
                (rs,n) -> new Choice(rs.getString(1),rs.getString(2)));
        var choice = preferences.isEmpty() ? new Choice("AUTO", "") : preferences.get(0);
        var candidates = candidates("OPEN_PLATFORM");
        if ("DISABLED".equals(choice.mode())) return idle(choice, "DISABLED", candidates);
        if ("MANUAL".equals(choice.mode())) {
            return candidates.stream().filter(c -> c.id().equals(choice.accountId())).findFirst()
                    .map(c -> selected(choice,"MANAGED",c,candidates))
                    .orElseGet(() -> idle(choice,"UNAVAILABLE",candidates));
        }
        if (!"AUTO".equals(choice.mode())) return idle(choice,"UNAVAILABLE",candidates);
        var direct = candidates("DIRECT");
        if (!direct.isEmpty()) return selected(choice,"DIRECT",direct.get(0),candidates);
        String legacy = configs.getConfigValue("wechat.official.app_id","");
        if (legacy != null && !legacy.isBlank() && !java.util.Objects.toString(
                configs.getConfigValue("wechat.official.app_secret",""),"").isBlank() &&
                jdbc.queryForList("SELECT id FROM wechat_mp_account WHERE app_id=? AND enabled=0",String.class,legacy.trim()).isEmpty())
            return new Selection("AUTO","LEGACY","",legacy.trim(),candidates);
        if (candidates.size() == 1) return selected(choice,"MANAGED",candidates.get(0),candidates);
        return idle(choice,candidates.isEmpty() ? "UNAVAILABLE" : "CHOICE_REQUIRED",candidates);
    }

    @Transactional
    public Selection choose(Choice choice) {
        if (choice == null || choice.mode() == null || !List.of("AUTO","DISABLED","MANUAL").contains(choice.mode()))
            throw new BusinessException(400,"请选择有效的通知发送模式");
        boolean manual = "MANUAL".equals(choice.mode());
        if (manual && candidates("OPEN_PLATFORM").stream().noneMatch(c -> c.id().equals(choice.accountId())))
            throw new BusinessException(400,"请选择当前租户已启用的授权公众号");
        if (!manual && choice.accountId() != null && !choice.accountId().isBlank())
            throw new BusinessException(400,"仅指定模式可以设置发送公众号");
        jdbc.update("INSERT INTO wechat_notice_sender(id,mode,account_id) VALUES(1,?,?) " +
                "ON DUPLICATE KEY UPDATE mode=VALUES(mode),account_id=VALUES(account_id)",
                choice.mode(),manual ? choice.accountId() : "");
        return current();
    }
    private List<Candidate> candidates(String mode) {
        return jdbc.query("SELECT id,name,app_id FROM wechat_mp_account WHERE enabled=1 AND account_mode=? " +
                "AND (account_mode<>'DIRECT' OR COALESCE(app_secret_encrypted,'')<>'') ORDER BY is_default DESC,created_at,id",
                (rs,n) -> new Candidate(rs.getString(1),rs.getString(2),rs.getString(3)),mode);
    }
    private static Selection selected(Choice choice,String status,Candidate account,List<Candidate> candidates) {
        return new Selection(choice.mode(),status,account.id(),account.appId(),candidates);
    }
    private static Selection idle(Choice choice,String status,List<Candidate> candidates) {
        return new Selection(choice.mode(),status,choice.accountId(),"",candidates);
    }
}
