package com.delta.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.user.controller.InviteController;
import com.delta.user.entity.User;
import com.delta.user.mapper.UserMapper;
import com.delta.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

import static com.delta.notification.service.WechatWebLoginService.hasPhone;
import static com.delta.notification.service.WechatWebLoginService.version;

@Service
@RequiredArgsConstructor
public class WechatIdentityService {
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final UserMapper mapper;
    private final CrossModuleMapper identities;
    private final InviteController invites;
    private final OfficialWechatService officialWechat;
    private final com.delta.common.security.service.TokenService tokens;
    private final com.delta.user.service.WebLoginPolicy loginPolicy;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Transactional(rollbackFor = Exception.class)
    public WechatWebLoginService.Completion signIfBound(String openid) {
        Long id = bindingOwner(openid, false);
        if (id == null) return null;
        User account = lockAccount(id);
        if (!Objects.equals(bindingOwner(openid, true), id)) throw changed();
        return hasPhone(account) ? sign(account) : null;
    }

    private WechatWebLoginService.Completion sign(User user) {
        return new WechatWebLoginService.Completion(
                tokens.createToken(user.getId(), com.delta.common.enums.UserTypeEnum.USER), user.getId(),
                user.getNickname(), user.getAvatar(), true, null, null);
    }

    public User requireAccount(Long id) {
        return requireUsable(users.getById(id));
    }

    public User findWechatAccount(String openid) {
        Long id = bindingOwner(openid, false);
        return id == null ? null : requireAccount(id);
    }

    public User findPhoneAccount(String phone) {
        User user = users.getByPhone(phone);
        return user == null ? null : requireUsable(user);
    }

    public boolean hasWechat(Long id) {
        return !accountWechat(id, false).isEmpty();
    }

    String accountWechat(Long id, boolean lock) {
        var matches = jdbc.queryForList("SELECT openid FROM wechat_official_binding WHERE user_type='USER' AND user_id=?"
                + (lock ? " FOR UPDATE" : ""), String.class, id);
        return matches.isEmpty() ? "" : matches.get(0);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public WechatWebLoginService.Completion bindNewPhone(Map<String,String> proof, String phone) {
        Long source = source(proof);
        User user = source == null ? null : lockAccount(source);
        verifySource(proof, user);
        try {
            if (findPhoneAccount(phone) != null) throw changed();
            if (user == null) {
                user = users.registerNewPhone(phone);
                setNewBinding(user.getId(), proof.get("openid"), false);
                bindInvite(user.getId(), proof.get("invite"));
            } else {
                mapper.releaseDeletedPhone(phone);
                String openid = user.getOpenid();
                identities.syncVerifiedIdentity(openid, openid, phone);
                rows("UPDATE `user` SET phone=?,auth_version=COALESCE(auth_version,0)+1 WHERE id=? AND deleted=0", phone, user.getId());
                user.setPhone(phone);
            }
            return sign(user);
        } catch (DuplicateKeyException failure) {
            throw changed();
        }
    }

    public void checkTransferSource(Map<String,String> proof) {
        Long sourceId = source(proof);
        if (sourceId == null) return;
        User source = requireAccount(sourceId);
        if (hasPhone(source) || !Objects.equals(proof.get("sourceVersion"), version(source))
                || !Objects.equals(sourceId, bindingOwner(proof.get("openid"), false))) throw changed();
        // Early feedback only. merge rechecks eligibility and ownership after taking locks.
        assertIdentityOnly(source);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public WechatWebLoginService.Completion merge(Map<String,String> proof) {
        Long targetId = Long.valueOf(proof.get("target"));
        Long sourceId = source(proof);
        if (targetId.equals(sourceId)) throw changed();
        User source = null;
        User target;
        // Stable row order for concurrent changes to either account.
        if (sourceId != null && sourceId < targetId) {
            source = lockAccount(sourceId);
            target = lockAccount(targetId);
        } else {
            target = lockAccount(targetId);
            if (sourceId != null) source = lockAccount(sourceId);
        }
        if (!Objects.equals(proof.get("phone"), target.getPhone()) || !Objects.equals(proof.get("targetVersion"), version(target)))
            throw changed();
        verifySource(proof, source);
        if (!Objects.equals(proof.get("targetWechat"), accountWechat(targetId, true))) throw changed();
        if (source == null) {
            setNewBinding(targetId, proof.get("openid"), true);
        } else {
            assertIdentityOnly(source);
            rows("DELETE FROM wechat_official_binding WHERE user_type='USER' AND user_id=?", targetId);
            if (rows("UPDATE wechat_official_binding SET user_id=? WHERE user_type='USER' AND user_id=? AND openid=?",
                    targetId, sourceId, proof.get("openid")) != 1) throw changed();
            // Keep the legacy row. It no longer has a web WeChat identity.
            touch(sourceId);
        }
        touch(targetId);
        return sign(target);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public User attachToExisting(Long id, String expectedVersion, String openid) {
        User user = lockAccount(id);
        if (!Objects.equals(expectedVersion, version(user))) throw changed();
        Long owner = bindingOwner(openid, true);
        if (Objects.equals(id, owner)) return hasPhone(user) ? null : user;
        if (!hasPhone(user) || !accountWechat(id, true).isEmpty()) throw changed();
        if (owner != null) {
            User source = requireAccount(owner);
            if (hasPhone(source)) throw new BusinessException(409, "该微信已绑定其他正式账号，请联系客服");
            return source; // No changes. SMS and explicit confirmation are required before transfer.
        }
        setNewBinding(id, openid, false);
        // The current login method and credentials are unchanged; preserve this session.
        return null;
    }

    @Transactional(rollbackFor = Exception.class)
    public void detach(Long id) {
        User user = lockAccount(id);
        if (!hasPhone(user)) throw new BusinessException(409, "请先绑定手机号，再解绑微信");
        if (!loginPolicy.phoneEnabled()) throw new BusinessException(409, "当前站点未开放手机号登录，暂不能解绑微信");
        officialWechat.removeBinding("USER", id);
    }

    private void assertIdentityOnly(User source) {
        long id = source.getId();
        long chat = com.delta.common.chat.util.ChatParticipantId.encodeUser(id);
        // Fail closed on any historical business relationship; never rewrite orders, balances or audit rows here.
        if (source.getOpenid() == null || !source.getOpenid().startsWith("oa-") || source.isPasswordSet()
                || has("SELECT COUNT(*) FROM `order` WHERE user_id=?", id)
                || has("SELECT COUNT(*) FROM payment WHERE user_id=?", id)
                || has("SELECT COUNT(*) FROM wallet WHERE user_id=? AND (balance<>0 OR frozen_amount<>0)", id)
                || has("SELECT COUNT(*) FROM `transaction` WHERE user_type='USER' AND user_id=?", id)
                || has("SELECT COUNT(*) FROM privacy_request WHERE user_id=?", id)
                || has("SELECT COUNT(*) FROM identity_verification WHERE user_id=?", id)
                || has("SELECT COUNT(*) FROM player WHERE openid=?", source.getOpenid())
                || has("SELECT COUNT(*) FROM invite_relation WHERE inviter_id=? OR invitee_id=?", id, id)
                || has("SELECT COUNT(*) FROM invite_code WHERE user_id=?", id)
                || has("SELECT COUNT(*) FROM user_coupon WHERE user_id=?", id)
                || has("SELECT COUNT(*) FROM user_game_info WHERE user_id=?", id)
                || has("SELECT COUNT(*) FROM user_favorite WHERE user_id=?", id)
                || has("SELECT COUNT(*) FROM player_follow WHERE user_id=?", id)
                || has("SELECT COUNT(*) FROM chat_session WHERE id1=? OR id2=?", chat, chat)
                || has("SELECT COUNT(*) FROM guest_user_link WHERE registered_user_id=?", id))
            throw new BusinessException(422, "原微信账号可能有业务记录或资产，不能转移。请使用未注册的手机号绑定原账号，或联系平台客服");
    }

    private void setNewBinding(Long id, String openid, boolean replace) {
        Long owner = bindingOwner(openid, true);
        if (owner != null) throw changed();
        if (replace) rows("DELETE FROM wechat_official_binding WHERE user_type='USER' AND user_id=?", id);
        officialWechat.saveBinding("USER", id, openid);
    }

    private void bindInvite(Long userId, String code) {
        if (code == null || code.isBlank()) return;
        // An invalid invite is optional. Run it after the registration transaction commits.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        var write = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                        write.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        try { write.executeWithoutResult(status -> invites.bindCode(userId, code)); }
                        catch (RuntimeException ignored) { /* optional attribution */ }
                    }
                });
    }

    private Long bindingOwner(String openid, boolean lock) {
        var matches = jdbc.queryForList("SELECT user_id FROM wechat_official_binding WHERE user_type='USER' AND openid=?"
                + (lock ? " FOR UPDATE" : ""), Long.class, openid);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private void verifySource(Map<String,String> proof, User user) {
        Long owner = bindingOwner(proof.get("openid"), true);
        if (user == null ? owner != null : !Objects.equals(owner, user.getId())
                || hasPhone(user) || !Objects.equals(proof.get("sourceVersion"), version(user))) throw changed();
    }

    private User lockAccount(Long id) {
        return requireUsable(users.getOne(new LambdaQueryWrapper<User>().eq(User::getId, id).last("FOR UPDATE")));
    }

    private User requireUsable(User user) {
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || Integer.valueOf(1).equals(user.getIsGuest()))
            throw new BusinessException(403, "账号暂不可用，请联系客服");
        return user;
    }

    private static Long source(Map<String,String> proof) {
        return proof.get("source").isEmpty() ? null : Long.valueOf(proof.get("source"));
    }

    private void touch(Long id) { rows("UPDATE `user` SET auth_version=COALESCE(auth_version,0)+1 WHERE id=?", id); }

    private boolean has(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private int rows(String sql, Object... args) { return jdbc.update(sql, args); }

    static BusinessException changed() { return new BusinessException(409, "绑定状态已变化，请重新授权并验证手机号"); }
}
