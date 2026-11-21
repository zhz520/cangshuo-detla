package com.delta.admin;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.delta.notification.service.OfficialWechatService;
import com.delta.notification.service.WechatIdentityService;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.security.service.TokenService;
import com.delta.user.controller.InviteController;
import com.delta.user.entity.User;
import com.delta.user.mapper.UserMapper;
import com.delta.user.service.UserService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WechatIdentityServiceTest {
    final UserService users = mock(UserService.class);
    final TokenService tokens = mock(TokenService.class);
    final com.delta.user.service.WebLoginPolicy loginPolicy = mock(com.delta.user.service.WebLoginPolicy.class);
    final OfficialWechatService officialWechat = mock(OfficialWechatService.class);
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    WechatIdentityService service;
    final Map<String,String> confirm = Map.of("source","1","sourceVersion","0","target","2",
            "targetVersion","0","phone","13800000002","openid","wechat-source-01","targetWechat","wechat-previous-01");

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "wechat-identity-test"), User.class);
        var db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:wechat-bind-" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(db));
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,openid VARCHAR(64),phone VARCHAR(20),auth_version BIGINT,deleted INT)");
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT)");
        jdbc.execute("CREATE TABLE payment(user_id BIGINT)");
        jdbc.execute("CREATE TABLE wallet(user_id BIGINT,balance DECIMAL(12,2),frozen_amount DECIMAL(12,2))");
        jdbc.execute("CREATE TABLE `transaction`(user_id BIGINT,user_type VARCHAR(16))");
        for (String table : new String[]{"privacy_request","identity_verification","invite_code","user_coupon","user_game_info","user_favorite","player_follow"})
            jdbc.execute("CREATE TABLE " + table + "(user_id BIGINT)");
        jdbc.execute("CREATE TABLE player(openid VARCHAR(64))");
        jdbc.execute("CREATE TABLE invite_relation(inviter_id BIGINT,invitee_id BIGINT)");
        jdbc.execute("CREATE TABLE chat_session(id1 BIGINT,id2 BIGINT)");
        jdbc.execute("CREATE TABLE guest_user_link(registered_user_id BIGINT)");
        jdbc.execute("CREATE TABLE wechat_official_binding(user_type VARCHAR(16),user_id BIGINT,openid VARCHAR(128),PRIMARY KEY(user_type,user_id),UNIQUE(openid))");
        jdbc.update("INSERT INTO `user` VALUES(1,'oa-legacy','',0,0),(2,'h5:13800000002','13800000002',0,0)");
        jdbc.update("INSERT INTO wechat_official_binding VALUES('USER',1,'wechat-source-01'),('USER',2,'wechat-previous-01')");
        var source = user(1L, "oa-legacy", "");
        var target = user(2L, "h5:13800000002", "13800000002");
        when(users.getOne(any())).thenReturn(source, target);
        service = new WechatIdentityService(jdbc, users, mock(UserMapper.class), mock(CrossModuleMapper.class),
                mock(InviteController.class), officialWechat, tokens, loginPolicy, new DataSourceTransactionManager(db));
    }

    @Test void confirmedIdentityOnlyMergeKeepsOriginalPhoneAccountAndReplacesWechat() {
        jdbc.update("INSERT INTO wallet VALUES (1,0,0),(2,25,1)");
        jdbc.update("INSERT INTO `order` VALUES(10,2)");
        when(tokens.createToken(eq(2L), any(com.delta.common.enums.UserTypeEnum.class))).thenReturn("signed-result");
        var result = transaction.execute(s -> service.merge(confirm));
        assertThat(result.userId()).isEqualTo(2L);
        assertThat(result.token()).isEqualTo("signed-result");
        assertThat(jdbc.queryForList("SELECT openid FROM wechat_official_binding WHERE user_id=2",String.class))
                .containsExactly("wechat-source-01");
        assertThat(jdbc.queryForObject("SELECT deleted FROM `user` WHERE id=1",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT user_id FROM `order` WHERE id=10",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT balance FROM wallet WHERE user_id=2",java.math.BigDecimal.class)).isEqualByComparingTo("25");
        assertThat(jdbc.queryForList("SELECT auth_version FROM `user` ORDER BY id", Long.class)).containsExactly(1L, 1L);
    }

    @Test void historicalOrderBlocksMergeWithoutChangingEitherBinding() {
        jdbc.update("INSERT INTO `order` VALUES(10,1)");
        assertThatThrownBy(() -> transaction.execute(s -> service.merge(confirm))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForList("SELECT openid FROM wechat_official_binding ORDER BY user_id",String.class))
                .containsExactly("wechat-source-01","wechat-previous-01");
        assertThat(jdbc.queryForObject("SELECT user_id FROM `order` WHERE id=10",Long.class)).isEqualTo(1L);
        verifyNoInteractions(tokens);
    }

    @Test void frozenFundsBlockTransferEvenWhenAvailableBalanceIsZero() {
        jdbc.update("INSERT INTO wallet VALUES(1,0,2)");
        assertThatThrownBy(() -> transaction.execute(s -> service.merge(confirm)))
                .isInstanceOfSatisfying(BusinessException.class, blocked -> assertThat(blocked.getCode()).isEqualTo(422));
        assertThat(jdbc.queryForList("SELECT openid FROM wechat_official_binding ORDER BY user_id", String.class))
                .containsExactly("wechat-source-01", "wechat-previous-01");
        assertThat(jdbc.queryForObject("SELECT frozen_amount FROM wallet WHERE user_id=1", java.math.BigDecimal.class))
                .isEqualByComparingTo("2");
        verifyNoInteractions(tokens);
    }

    @Test void protectedSourceCanBindAnUnusedPhoneWithoutMovingOrderOrWechat() {
        jdbc.update("INSERT INTO `order` VALUES(10,1)");
        when(users.getById(1L)).thenReturn(user(1L, "oa-legacy", ""));
        assertThatThrownBy(() -> service.checkTransferSource(confirm)).isInstanceOf(BusinessException.class);
        String unused = "13800000003";
        var result = transaction.execute(s -> service.bindNewPhone(confirm, unused));
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT phone FROM `user` WHERE id=1", String.class)).isEqualTo(unused);
        assertThat(jdbc.queryForObject("SELECT user_id FROM `order` WHERE id=10", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT openid FROM wechat_official_binding ORDER BY user_id", String.class))
                .containsExactly("wechat-source-01", "wechat-previous-01");
    }

    @Test void targetBindingChangedAfterOtpCannotBeOverwrittenByOldConfirmation() {
        jdbc.update("UPDATE wechat_official_binding SET openid='wechat-new-01' WHERE user_id=2");
        assertThatThrownBy(() -> transaction.execute(s -> service.merge(confirm))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForList("SELECT openid FROM wechat_official_binding ORDER BY user_id", String.class))
                .containsExactly("wechat-source-01", "wechat-new-01");
        verifyNoInteractions(tokens);
    }

    @Test void personalCenterReturnsPendingSourceWithoutMovingBindings() {
        jdbc.update("DELETE FROM wechat_official_binding WHERE user_id=2");
        when(users.getOne(any())).thenReturn(user(2L, "h5:13800000002", "13800000002"));
        when(users.getById(1L)).thenReturn(user(1L, "oa-legacy", ""));
        var pending = transaction.execute(s -> service.attachToExisting(2L, "0", "wechat-source-01"));
        assertThat(pending.getId()).isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT user_id FROM wechat_official_binding", Long.class)).containsExactly(1L);
        verifyNoInteractions(tokens, officialWechat);
    }

    @Test void personalCenterCannotTakeWechatFromAnotherPhoneBoundAccount() {
        jdbc.update("DELETE FROM wechat_official_binding WHERE user_id=2");
        when(users.getOne(any())).thenReturn(user(2L, "h5:13800000002", "13800000002"));
        when(users.getById(1L)).thenReturn(user(1L, "oa-legacy", "13800000001"));
        assertThatThrownBy(() -> transaction.execute(s -> service.attachToExisting(2L, "0", "wechat-source-01")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("其他正式账号");
        assertThat(jdbc.queryForList("SELECT user_id FROM wechat_official_binding", Long.class)).containsExactly(1L);
    }

    @Test void concurrentConfirmedTakeoversCanOnlyMoveWechatOnce() throws Exception {
        jdbc.update("INSERT INTO `user` VALUES(3,'h5:13800000003','13800000003',0,0)");
        jdbc.update("DELETE FROM wechat_official_binding WHERE user_id=2");
        jdbc.update("INSERT INTO wallet VALUES(1,0,0)");
        var first = new HashMap<>(confirm); first.put("targetWechat", "");
        var other = new HashMap<>(first); other.put("target", "3"); other.put("phone", "13800000003");
        doAnswer(call -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>) call.getArgument(0);
            query.getSqlSegment();
            var id = query.getParamNameValuePairs().values().iterator().next();
            return jdbc.queryForObject("SELECT id,openid,phone,auth_version FROM `user` WHERE id=? FOR UPDATE", (rs,i) -> {
                var row = user(rs.getLong("id"), rs.getString("openid"), rs.getString("phone"));
                row.setAuthVersion(rs.getLong("auth_version"));
                return row;
            }, id);
        }).when(users).getOne(any());
        var ready = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var transfers = executor.invokeAll(java.util.List.of(
                    () -> tryTransfer(first, ready), () -> tryTransfer(other, ready)), 10, TimeUnit.SECONDS);
            assertThat(java.util.List.of(transfers.get(0).get(), transfers.get(1).get()))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForList("SELECT user_id FROM wechat_official_binding", Long.class)).hasSize(1).allMatch(id -> id == 2L || id == 3L);
            verify(tokens, times(1)).createToken(anyLong(), any(com.delta.common.enums.UserTypeEnum.class));
        } finally { executor.shutdownNow(); }
    }

    private boolean tryTransfer(Map<String,String> proof, CyclicBarrier ready) throws Exception {
        ready.await(5, TimeUnit.SECONDS);
        try { transaction.execute(s -> service.merge(proof)); return true; }
        catch (BusinessException rejected) {
            assertThat(rejected.getCode()).isEqualTo(409);
            return false;
        }
    }

    @Test void noPhoneMeansNoUnbind() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> service.detach(1L)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("先绑定手机号");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wechat_official_binding",Integer.class)).isEqualTo(2);
    }

    @Test void cannotUnbindTheOnlyAvailablePrimaryLogin() {
        when(users.getOne(any())).thenReturn(user(2L, "oa-existing", "13800000002"));
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> service.detach(2L)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未开放手机号登录");
        verifyNoInteractions(officialWechat);
        when(loginPolicy.phoneEnabled()).thenReturn(true);
        transaction.executeWithoutResult(s -> service.detach(2L));
        verify(officialWechat).removeBinding("USER", 2L);
    }

    private static User user(long id,String openid,String phone) {
        User user=new User(); user.setId(id);user.setStatus(1);user.setOpenid(openid);user.setPhone(phone);return user;
    }
}
