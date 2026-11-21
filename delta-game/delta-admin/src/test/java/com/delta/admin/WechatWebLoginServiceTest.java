package com.delta.admin;

import com.delta.notification.service.OfficialWechatService;
import com.delta.notification.service.WechatIdentityService;
import com.delta.notification.service.WechatWebLoginService;
import com.delta.common.exception.BusinessException;
import com.delta.common.redis.service.RedisService;
import com.delta.common.security.service.VerificationService;
import com.delta.system.service.SysConfigService;
import com.delta.user.entity.User;
import com.delta.user.service.WebLoginPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WechatWebLoginServiceTest {
    static final String PHONE = "13800000001";
    static final String OPENID = "openid-test-01";
    final Map<String,Object> cache = new ConcurrentHashMap<>();
    final RedisService redis = mock(RedisService.class);
    final OfficialWechatService official = mock(OfficialWechatService.class);
    final SysConfigService configs = mock(SysConfigService.class);
    final WebLoginPolicy policy = mock(WebLoginPolicy.class);
    final WechatIdentityService identity = mock(WechatIdentityService.class);
    final VerificationService verification = mock(VerificationService.class);
    final WechatWebLoginService service = new WechatWebLoginService(official, configs, policy, redis, identity, verification);

    @BeforeEach void initialize() throws Exception {
        when(policy.wechatEnabled()).thenReturn(true);
        when(policy.customerOrigin()).thenReturn("https://shop.example.com");
        when(configs.getConfigValue("wechat.official.app_id", "")).thenReturn("wx-test");
        when(official.exchangeOpenid("test-code")).thenReturn(OPENID);
        doAnswer(call -> {cache.put(call.getArgument(0), call.getArgument(1)); return null;})
                .when(redis).set(anyString(), any(), anyLong(), any(TimeUnit.class));
        when(redis.get(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
        when(redis.getAndDelete(anyString())).thenAnswer(call -> cache.remove(call.getArgument(0)));
    }

    String ticket() throws Exception {
        var auth = service.start("/pages/login/index?redirect=%2Fpages%2Fmine%2Findex", "", null);
        assertThat(auth.url()).contains("scope=snsapi_base",
                "redirect_uri=https%3A%2F%2Fshop.example.com%2Fapi%2Fuser%2Fauth%2Fwechat%2Fcallback");
        String callback = service.complete("test-code", auth.state(), auth.state());
        return callback.substring(callback.indexOf("wechat_ticket=") + "wechat_ticket=".length());
    }

    String accountTicket(long id) throws Exception {
        var auth = service.start(null, null, id);
        return service.complete("test-code", auth.state(), auth.state()).split("wechat_ticket=", 2)[1];
    }

    @Test
    void oauthStateMustMatchBrowserAndCannotBeReplayed() throws Exception {
        var auth = service.start(null, "", null);
        assertThatThrownBy(() -> service.complete("test-code", auth.state(), "00000000000000000000000000000000"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(official);
        service.complete("test-code", auth.state(), auth.state());
        assertThatThrownBy(() -> service.complete("test-code", auth.state(), auth.state()))
                .isInstanceOf(BusinessException.class);
        verify(official, times(1)).exchangeOpenid("test-code");
    }

    @Test
    void unboundAccountGetsOnlyBindingTicketAndVerificationIsBoundToIt() throws Exception {
        String oauth = ticket();
        var pending = service.exchange(oauth);
        assertThat(pending.phoneBound()).isFalse();
        assertThat(pending.token()).isNull();
        assertThat(pending.bindTicket()).matches("[a-f0-9]{32}");
        assertThatThrownBy(() -> service.exchange(oauth)).isInstanceOf(BusinessException.class);
        service.sendCode(pending.bindTicket(), PHONE, "captcha-id", "123456", "127.0.0.1", null);
        verify(verification).send(PHONE, "WECHAT_BIND", pending.bindTicket(), "captcha-id", "123456", "127.0.0.1");
        verify(identity, never()).bindNewPhone(anyMap(), anyString());
    }

    @Test
    void legacyWechatIdentityAlsoReceivesOnlyATicketOnNewLogin() throws Exception {
        User legacy = new User();
        legacy.setId(42L);
        legacy.setOpenid("oa-legacy-fixture");
        legacy.setPhone("");
        legacy.setStatus(1);
        when(identity.findWechatAccount(OPENID)).thenReturn(legacy);
        var pending = service.exchange(ticket());
        assertThat(pending.phoneBound()).isFalse();
        assertThat(pending.token()).isNull();
        assertThat(pending.bindTicket()).matches("[a-f0-9]{32}");
    }

    @Test
    void existingPhoneRequiresAnotherExplicitStepBeforeBinding() throws Exception {
        var pending = service.exchange(ticket());
        User target = new User();
        target.setId(7L); target.setPhone(PHONE); target.setStatus(1);
        when(identity.findPhoneAccount(PHONE)).thenReturn(target);
        var confirmation = service.bindPhone(pending.bindTicket(), PHONE, "sms-id", "123456", null);
        verify(verification).verify(PHONE, "WECHAT_BIND", pending.bindTicket(), "sms-id", "123456");
        assertThat(confirmation.token()).isNull();
        verify(identity, never()).merge(anyMap());
        when(identity.merge(anyMap())).thenReturn(new WechatWebLoginService.Completion("token",7L,"name",null,true,null,null));
        assertThat(service.confirm(confirmation.confirmationTicket(), null).token()).isEqualTo("token");
        assertThatThrownBy(() -> service.confirm(confirmation.confirmationTicket(), null)).isInstanceOf(BusinessException.class);
        verify(identity).merge(argThat(map -> "7".equals(map.get("target")) && PHONE.equals(map.get("phone"))));
    }

    @Test void personalCenterRequiresItsOwnSessionAndVerifiedPhoneBeforeIssuingConfirmation() throws Exception {
        User target = new User(); target.setId(7L); target.setPhone(PHONE); target.setAuthVersion(3L);
        User source = new User(); source.setId(42L); source.setOpenid("oa-test");
        when(identity.requireAccount(7L)).thenReturn(target);
        when(identity.attachToExisting(7L, "3", OPENID)).thenReturn(source);
        String oauth = accountTicket(7L);
        assertThatThrownBy(() -> service.bindExisting(8L, oauth)).isInstanceOf(BusinessException.class);
        var pending = service.bindExisting(7L, oauth);
        assertThat(pending.maskedPhone()).isEqualTo("138****0001");
        String ticket = pending.bindTicket();
        assertThatThrownBy(() -> service.bindExisting(7L, oauth)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.sendCode(ticket, PHONE, "captcha", "123456", "localhost", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.bindPhone(ticket, PHONE, "sms", "123456", 8L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.bindPhone(ticket, "13800000008", "sms", "123456", 7L))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(verification);
        service.sendCode(ticket, PHONE, "captcha", "123456", "localhost", 7L);
        verify(verification).send(PHONE, "WECHAT_BIND", ticket, "captcha", "123456", "localhost");
        doThrow(new BusinessException(400, "验证码错误")).when(verification).verify(PHONE, "WECHAT_BIND", ticket, "sms", "111111");
        assertThatThrownBy(() -> service.bindPhone(ticket, PHONE, "sms", "111111", 7L)).hasMessage("验证码错误");
        assertThat(cache.keySet()).noneMatch(key -> key.contains(":confirm:"));
        var confirmed = service.bindPhone(ticket, PHONE, "sms", "123456", 7L);
        verify(identity, never()).merge(anyMap());
        assertThatThrownBy(() -> service.confirm(confirmed.confirmationTicket(), 8L)).isInstanceOf(BusinessException.class);
        service.confirm(confirmed.confirmationTicket(), 7L);
        verify(identity).merge(argThat(proof -> "42".equals(proof.get("source")) && "7".equals(proof.get("target"))
                && "3".equals(proof.get("targetVersion")) && PHONE.equals(proof.get("phone")) && "".equals(proof.get("targetWechat"))));
    }

    @Test void protectedLoginSourceCanStillVerifyAnUnusedPhoneOnTheSameTicket() throws Exception {
        User source = new User(); source.setId(42L);
        User target = new User(); target.setId(7L); target.setPhone(PHONE);
        when(identity.findWechatAccount(OPENID)).thenReturn(source);
        when(identity.findPhoneAccount(PHONE)).thenReturn(target);
        doThrow(new BusinessException(422, "原微信账号受保护")).when(identity).checkTransferSource(anyMap());
        String ticket = service.exchange(ticket()).bindTicket();
        assertThatThrownBy(() -> service.bindPhone(ticket, PHONE, "first-otp", "123456", null))
                .isInstanceOfSatisfying(BusinessException.class, blocked -> assertThat(blocked.getCode()).isEqualTo(422));
        assertThat(cache.keySet()).noneMatch(key -> key.contains(":confirm:"));
        verify(identity, never()).merge(anyMap());
        verify(identity, never()).bindNewPhone(anyMap(), anyString());

        String unused = "13800000009";
        service.bindPhone(ticket, unused, "new-otp", "654321", null);
        var order = inOrder(verification, identity);
        order.verify(verification).verify(PHONE, "WECHAT_BIND", ticket, "first-otp", "123456");
        order.verify(identity).checkTransferSource(anyMap());
        order.verify(verification).verify(unused, "WECHAT_BIND", ticket, "new-otp", "654321");
        order.verify(identity).bindNewPhone(argThat(proof -> "42".equals(proof.get("source"))), eq(unused));
    }

    @Test void competingVerifiedSubmissionsCanConsumeTheSameBindTicketOnlyOnce() throws Exception {
        String ticket = service.exchange(ticket()).bindTicket();
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> { barrier.await(5, TimeUnit.SECONDS); return null; })
                .when(verification).verify(eq(PHONE), eq("WECHAT_BIND"), eq(ticket), anyString(), eq("123456"));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var submissions = executor.invokeAll(java.util.List.of(
                    () -> consumeBinding(ticket, "otp-1"), () -> consumeBinding(ticket, "otp-2")), 10, TimeUnit.SECONDS);
            assertThat(java.util.List.of(submissions.get(0).get(), submissions.get(1).get())).containsExactlyInAnyOrder(true, false);
            verify(identity, times(1)).bindNewPhone(anyMap(), eq(PHONE));
        } finally { executor.shutdownNow(); }
    }

    private boolean consumeBinding(String ticket, String requestId) {
        try { service.bindPhone(ticket, PHONE, requestId, "123456", null); return true; }
        catch (BusinessException rejected) {
            assertThat(rejected.getCode()).isEqualTo(401);
            return false;
        }
    }
}
