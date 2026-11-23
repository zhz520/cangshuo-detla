package com.delta.notification.service;

import com.alibaba.fastjson2.JSONObject;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.service.StaffPermissions;
import com.delta.platform.PlatformService;
import com.delta.system.service.SysConfigService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.*;


import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatNotificationDeliveryTest {
    static final String A="wx1111111111111111", B="wx2222222222222222", OPENID="verified-follower-1";
    JdbcTemplate db;
    SysConfigService configs=mock(SysConfigService.class);
    WechatOpenPlatformClient gateway=mock(WechatOpenPlatformClient.class);
    PlatformService platform=mock(PlatformService.class);
    StringRedisTemplate redis=mock(StringRedisTemplate.class);
    WechatNotificationSender senders;
    WechatAccountService accounts;
    OfficialWechatService official;
    List<String> urls=new ArrayList<>();
    List<Map<?,?>> payloads=new ArrayList<>();
    int responseCode;

    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE wechat_mp_account(id VARCHAR PRIMARY KEY,name VARCHAR,app_id VARCHAR,account_mode VARCHAR,enabled INT,is_default INT DEFAULT 0,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,app_secret_encrypted VARCHAR)");
        db.execute("CREATE TABLE wechat_mp_template(account_id VARCHAR,role VARCHAR,scenario VARCHAR,template_id VARCHAR,fields_json VARCHAR,url VARCHAR,miniprogram_json VARCHAR,enabled INT)");
        db.execute("CREATE TABLE wechat_mp_template_catalog(account_id VARCHAR,template_id VARCHAR)");
        db.execute("CREATE TABLE wechat_notice_sender(id INT PRIMARY KEY,mode VARCHAR,account_id VARCHAR)");
        db.execute("CREATE TABLE wechat_notice_recipient(app_id VARCHAR,user_type VARCHAR,user_id BIGINT,openid VARCHAR,PRIMARY KEY(app_id,user_type,user_id),UNIQUE(app_id,openid))");
        db.execute("CREATE TABLE wechat_official_binding(user_type VARCHAR,user_id BIGINT,openid VARCHAR)");
        db.execute("CREATE TABLE wechat_notice_queue(id VARCHAR PRIMARY KEY,dedupe_key VARCHAR UNIQUE,event_type VARCHAR,user_type VARCHAR,user_id BIGINT,order_id BIGINT,message VARCHAR,status VARCHAR,attempts INT DEFAULT 0,last_error VARCHAR,next_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        senders=new WechatNotificationSender(db,configs);
        accounts=spy(new WechatAccountService(db,null,platform,gateway));
        var data=mock(NotificationTemplateDataService.class);
        when(data.resolve(anyMap())).thenReturn(Map.of("message","fixture"));
        official=new OfficialWechatService(db,senders,gateway,configs,data,mock(StaffPermissions.class),platform,redis){
            @Override JSONObject post(String url,Object body){
                urls.add(url);
                payloads.add((Map<?,?>)body);
                if(url.endsWith("/stable_token"))return JSONObject.of("access_token","legacy-token","expires_in",7200);
                return JSONObject.of("errcode",responseCode);
            }
        };
        ReflectionTestUtils.setField(official,"accounts",accounts);
        when(configs.getConfigValue(anyString(),anyString())).thenAnswer(i->i.getArgument(1));
        when(platform.featureEnabled(OfficialWechatService.FEATURE_ID)).thenReturn(true);
        when(gateway.accessToken(A)).thenReturn("token-A");
        when(gateway.accessToken(B)).thenReturn("token-B");
    }
    void account(String id,String app,String mode){
        db.update("INSERT INTO wechat_mp_account(id,name,app_id,account_mode,enabled,app_secret_encrypted) VALUES(?,?,?,?,1,?)",id,id,app,mode,"DIRECT".equals(mode)?"configured":"");
        db.update("INSERT INTO wechat_mp_template_catalog VALUES(?,?)",id,"tpl-"+id);
        db.update("INSERT INTO wechat_mp_template(account_id,role,scenario,template_id,fields_json,enabled) VALUES(?,'USER','new_order',?,'{\"thing1\":\"message\"}',1)",id,"tpl-"+id);
    }
    void bind(String app,String openid){db.update("INSERT INTO wechat_notice_recipient VALUES(?,'USER',7,?)",app,openid);}
    void enqueue(){official.enqueue("ORDER_PAID","USER",7L,null,"fixture","one");}
    String field(String field){return db.queryForObject("SELECT "+field+" FROM wechat_notice_queue",String.class);}
    void manual(String id){senders.choose(new WechatNotificationSender.Choice("MANUAL",id));}

    @Test void singleManagedActuallySendsWithItsOwnTokenTemplateAndVerifiedRecipientOnce() {
        account("a",A,"OPEN_PLATFORM");bind(A,OPENID);bind(B,"other-app-recipient");
        db.update("INSERT INTO wechat_official_binding VALUES('USER',7,'login-recipient')");
        enqueue();enqueue();
        official.deliver();official.deliver();
        assertThat(field("status")).isEqualTo("SENT");
        assertThat(urls).containsExactly("https://api.weixin.qq.com/cgi-bin/message/template/send?access_token=token-A");
        assertThat(payloads.get(0).get("touser")).isEqualTo(OPENID);
        assertThat(payloads.get(0).get("template_id")).isEqualTo("tpl-a");
        verify(gateway).accessToken(A);
        verifyNoMoreInteractions(gateway);
    }
    @Test void ambiguousManualDisabledAndReenabledQueue() {
        account("a",A,"OPEN_PLATFORM");account("b",B,"OPEN_PLATFORM");bind(B,"bound-on-B");
        enqueue();official.deliver();
        assertThat(senders.current().status()).isEqualTo("CHOICE_REQUIRED");
        assertThat(field("attempts")).isEqualTo("0");
        assertThatThrownBy(()->manual("unknown")).isInstanceOf(BusinessException.class);
        manual("b");
        db.update("UPDATE wechat_mp_account SET enabled=0 WHERE id='b'");
        official.deliver();assertThat(senders.current().status()).isEqualTo("UNAVAILABLE");
        db.update("UPDATE wechat_mp_account SET enabled=1 WHERE id='b'");
        senders.choose(new WechatNotificationSender.Choice("DISABLED",null));
        official.deliver();assertThat(urls).isEmpty();
        manual("b");
        when(configs.getConfigValue("wechat.official.enabled","auto")).thenReturn("false","true");
        official.deliver();assertThat(field("attempts")).isEqualTo("0");
        official.deliver();assertThat(field("status")).isEqualTo("SENT");
        assertThat(urls.get(0)).endsWith("token-B");
        assertThat(payloads.get(0).get("template_id")).isEqualTo("tpl-b");
    }
    @Test void orphanLegacyOpenidAndOtherAppOrRoleNeverQualify() {
        account("a",A,"OPEN_PLATFORM");
        when(configs.getConfigValue("wechat.official.app_id","")).thenReturn("stale-id"); // no matching secret
        bind(B,"other-app");db.update("INSERT INTO wechat_notice_recipient VALUES(?,'PLAYER',7,'player-only')",A);
        db.update("INSERT INTO wechat_official_binding VALUES('USER',7,'orphan')");
        enqueue();official.deliver();
        assertThat(field("last_error")).isEqualTo("RECIPIENT_NOT_BOUND");
        assertThat(official.binding("USER",7L)).containsEntry("bound",false);
        assertThat(urls).isEmpty();verifyNoInteractions(gateway);
    }
    @Test void disabledTemplateOrOtherAccountTemplateDoesNotTriggerAnyRequest(){
        account("a",A,"OPEN_PLATFORM");account("b",B,"OPEN_PLATFORM");manual("a");bind(A,OPENID);
        db.update("UPDATE wechat_mp_template SET enabled=0 WHERE account_id='a'");
        enqueue();official.deliver();assertThat(field("last_error")).isEqualTo("TEMPLATE_NOT_CONFIGURED");
        assertThat(urls).isEmpty();verifyNoInteractions(gateway);
    }
    @Test void orphanDirectDoesNotBlockManagedAndDisabledAccountCannotSendViaLegacyAlias(){
        account("d","orphan-app","DIRECT");account("a",A,"OPEN_PLATFORM");
        db.update("UPDATE wechat_mp_account SET is_default=1,app_secret_encrypted=NULL WHERE id='d'");
        assertThat(senders.current().status()).isEqualTo("MANAGED");
        when(configs.getConfigValue("wechat.official.app_id","")).thenReturn("orphan-app");
        when(configs.getConfigValue("wechat.official.app_secret","")).thenReturn("configured");
        assertThat(senders.current().status()).isEqualTo("LEGACY");
        db.update("UPDATE wechat_mp_account SET enabled=0 WHERE id='d'");
        assertThat(senders.current().status()).isEqualTo("MANAGED");
        assertThatThrownBy(()->manual("d")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->senders.choose(new WechatNotificationSender.Choice("AUTO","a"))).isInstanceOf(BusinessException.class);
    }

    @Test void staleCatalogOrUnlicensedFeatureDoesNotSend(){
        account("a",A,"OPEN_PLATFORM");bind(A,OPENID);
        when(platform.featureEnabled(OfficialWechatService.FEATURE_ID)).thenReturn(false,true);
        enqueue();official.deliver();assertThat(field("attempts")).isEqualTo("0");
        db.update("DELETE FROM wechat_mp_template_catalog");
        db.update("INSERT INTO wechat_mp_template_catalog VALUES('wrong-account','tpl-a')");
        official.deliver();assertThat(field("last_error")).isEqualTo("TEMPLATE_NOT_CONFIGURED");
        assertThat(urls).isEmpty();verifyNoInteractions(gateway);
    }

    @Test void directThenLegacyRetainPrecedenceAndLegacyIdentity() throws Exception {
        account("a",A,"OPEN_PLATFORM");account("d","direct-app","DIRECT");
        when(configs.getConfigValue("wechat.official.enabled","auto")).thenReturn("true");
        when(configs.getConfigValue("wechat.official.app_id","")).thenReturn("legacy-app");
        when(configs.getConfigValue("wechat.official.app_secret","")).thenReturn("legacy-fixture");
        when(configs.getConfigValue("wechat.official.templates.user","{}")).thenReturn("{\"new_order\":{\"templateId\":\"old-template\",\"fields\":{\"thing1\":\"message\"}}}");
        doReturn("direct-token").when(accounts).notificationToken(argThat(s->s!=null && s.status().equals("DIRECT")));
        db.update("INSERT INTO wechat_official_binding VALUES('USER',7,'old-openid')");
        assertThat(senders.current().status()).isEqualTo("DIRECT");
        enqueue();official.deliver();assertThat(urls.get(0)).endsWith("direct-token");
        assertThat(payloads.get(0).get("touser")).isEqualTo("old-openid");
        assertThat(payloads.get(0).get("template_id")).isEqualTo("tpl-d");
        db.update("DELETE FROM wechat_mp_account WHERE id='d'");
        assertThat(senders.current().status()).isEqualTo("LEGACY");
        official.enqueue("ORDER_PAID","USER",7L,null,"fixture","two");official.deliver();
        assertThat(urls).hasSize(3);assertThat(urls.get(2)).endsWith("legacy-token");
        assertThat(payloads.get(2).get("template_id")).isEqualTo("old-template");
        verifyNoInteractions(gateway);
    }
    @Test void lostSubscriptionAndPreSendTokenFailureAreDistinguished(){
        account("a",A,"OPEN_PLATFORM");bind(A,OPENID);bind(B,OPENID);
        when(gateway.accessToken(A)).thenThrow(new BusinessException(503,"unavailable")).thenReturn("token-A");
        enqueue();official.deliver();
        assertThat(field("status")).isEqualTo("RETRY");assertThat(urls).isEmpty();
        official.retryNotification(field("id"));responseCode=43004;official.deliver();
        assertThat(field("status")).isEqualTo("FAILED");assertThat(field("last_error")).isEqualTo("RECIPIENT_NOT_SUBSCRIBED");
        assertThat(db.queryForList("SELECT app_id FROM wechat_notice_recipient",String.class)).containsExactly(B);
    }
    @Test void cleanTenantBindingChecksStateAppIdFollowAndDoesNotTouchLogin() throws Exception {
        account("a",A,"OPEN_PLATFORM");enqueue();official.deliver();
        db.update("INSERT INTO wechat_official_binding VALUES('USER',7,'login-identity')");
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String,String> pending=new HashMap<>();
        doAnswer(i->{pending.put(i.getArgument(0),i.getArgument(1));return null;}).when(values).set(anyString(),anyString(),any(Duration.class));
        when(values.getAndDelete(anyString())).thenAnswer(i->pending.remove((String)i.getArgument(0)));
        when(gateway.bindingComponent(A)).thenReturn("wx3333333333333333");
        when(gateway.bindingOpenid(A,"user-code")).thenReturn(OPENID);
        when(platform.overview()).thenReturn(new PlatformService.Overview("","","","",true,"","",null,"","",List.of("shop.test"),true));
        assertThatThrownBy(()->official.startBinding("USER",7L,"unlisted.test")).isInstanceOf(BusinessException.class);
        String auth=official.startBinding("USER",7L,"shop.test");
        assertThat(auth).contains("appid="+A+"&","component_appid=wx3333333333333333","redirect_uri=https%3A%2F%2Fshop.test%2F");
        String state=auth.split("state=")[1].split("#")[0];
        doReturn(JSONObject.of("subscribe",0)).when(accounts).apiGet(eq("a"),contains("openid="+OPENID));
        assertThatThrownBy(()->official.finishBinding("USER",7L,"user-code",state,"shop.test")).hasMessageContaining("关注");
        assertThat(official.binding("USER",7L)).containsEntry("bound",false);
        doReturn(JSONObject.of("openid",OPENID,"subscribe",1)).when(accounts).apiGet(eq("a"),contains("openid="+OPENID));
        String beforeSwitch=official.startBinding("USER",7L,"shop.test").split("state=")[1].split("#")[0];
        account("b",B,"OPEN_PLATFORM");manual("b");
        assertThatThrownBy(()->official.finishBinding("USER",7L,"user-code",beforeSwitch,"shop.test")).hasMessageContaining("变更");
        verify(gateway,never()).bindingOpenid(eq(B),anyString());
        manual("a");
        String wrongRole=official.startBinding("USER",7L,"shop.test").split("state=")[1].split("#")[0];
        assertThatThrownBy(()->official.finishBinding("PLAYER",7L,"user-code",wrongRole,"shop.test")).hasMessageContaining("身份");
        String fresh=official.startBinding("USER",7L,"shop.test").split("state=")[1].split("#")[0];
        official.finishBinding("USER",7L,"user-code",fresh,"shop.test");
        assertThatThrownBy(()->official.finishBinding("USER",7L,"user-code",fresh,"shop.test")).hasMessageContaining("过期");
        assertThat(official.binding("USER",7L)).containsEntry("bound",true);
        official.deliver();assertThat(field("status")).isEqualTo("SENT");
        official.removeNotificationBinding("USER",7L);
        assertThat(official.binding("USER",7L)).containsEntry("bound",false);
        assertThat(db.queryForObject("SELECT openid FROM wechat_official_binding",String.class)).isEqualTo("login-identity");
    }
}
