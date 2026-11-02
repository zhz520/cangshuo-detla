package com.delta.admin;

import com.delta.admin.controller.*;
import com.delta.common.config.SecurityConfig;
import com.delta.common.domain.R;
import com.delta.common.exception.*;
import com.delta.common.security.filter.JwtAuthenticationFilter;
import com.delta.common.security.handler.*;
import com.delta.common.security.service.*;
import com.delta.player.controller.PlayerController;
import com.delta.player.dto.PlayerHomepageRequest;
import com.delta.player.service.*;
import com.delta.player.service.impl.PlayerServiceImpl;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=GovernanceIntegrationTest.Config.class,properties={"spring.profiles.active=test","spring.datasource.url=jdbc:h2:mem:governance;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,LEVEL","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.datasource.type=com.zaxxer.hikari.HikariDataSource"})
@AutoConfigureMockMvc
@Sql(scripts={"/directory-schema.sql","/db/governance-v1.sql","/governance-fixtures.sql"},config=@org.springframework.test.context.jdbc.SqlConfig(encoding="UTF-8"))
class GovernanceIntegrationTest {
 @Configuration @EnableAutoConfiguration(excludeName="com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure")
 @MapperScan({"com.delta.player.mapper","com.delta.common.mapper"})
 @Import({SecurityConfig.class,JwtAuthenticationFilter.class,AuthenticationEntryPointImpl.class,AccessDeniedHandlerImpl.class,GlobalExceptionHandler.class,com.delta.admin.config.StaffPermissionConfig.class,com.delta.cs.controller.CsPlayerController.class,StaffRoleController.class,GovernanceController.class,PrivacyController.class,PlayerController.class,PlayerServiceImpl.class,PlayerGovernanceService.class,MediaRiskService.class,StaffPermissions.class,RefundProbe.class})
 static class Config{}
 @RestController static class RefundProbe {
  @PostMapping("/cs/order/{id}/refund") @PreAuthorize("@staffPermissions.allowed('order.refund')") public R<Void> refund(@PathVariable Long id){return R.ok();}
 }
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired PlayerGovernanceService governance;
 @MockBean TokenService tokens;@MockBean SysConfigService configs;
 @MockBean PlayerLevelService levels;@MockBean PlayerWalletService wallets;
 @MockBean com.delta.user.service.UserService users;@MockBean com.delta.pay.service.PaymentService payments;
 @MockBean com.delta.admin.service.UserManagementService userManagement;
 @MockBean com.delta.pay.service.TransactionService transactions;
 @BeforeEach void defaults(){when(configs.getConfigValue(anyString(),anyString())).thenAnswer(a->a.getArgument(1));}
 RequestPostProcessor role(String role,long id){return authentication(new UsernamePasswordAuthenticationToken(Map.of("userId",id,"userType",role),null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
 @Test void refundsAreExplicitlyGrantedAndRevokedByRole()throws Exception{
  mvc.perform(post("/cs/order/100/refund").with(role("CS",2))).andExpect(jsonPath("$.code").value(403));
  mvc.perform(post("/cs/order/100/refund").with(role("USER",7))).andExpect(jsonPath("$.code").value(403));
  mvc.perform(post("/cs/order/100/refund").with(role("ADMIN",1))).andExpect(jsonPath("$.code").value(200));
  mvc.perform(put("/system/roles").with(role("ADMIN",1)).contentType("application/json").content("{\"code\":\"CS\",\"name\":\"客服\",\"enabled\":true,\"permissions\":[\"order.refund\"]}")).andExpect(jsonPath("$.code").value(200));
  mvc.perform(post("/cs/order/100/refund").with(role("CS",2))).andExpect(jsonPath("$.code").value(200));
  jdbc.update("UPDATE staff_role SET permissions='[\"*\"]' WHERE code='CS'");
  mvc.perform(post("/cs/order/100/refund").with(role("CS",2))).andExpect(jsonPath("$.code").value(403));
 }
 @Test void staffCannotEscalateOrRemoveBuiltInRoles()throws Exception{
  mvc.perform(put("/system/roles").with(role("CS",2)).contentType("application/json").content("{\"code\":\"evil\",\"name\":\"x\",\"enabled\":true,\"permissions\":[\"*\"]}")).andExpect(jsonPath("$.code").value(403));
  mvc.perform(put("/system/roles/members/2").with(role("ADMIN",1)).contentType("application/json").content("{\"roleCode\":\"ADMIN\"}")).andExpect(jsonPath("$.code").value(400));
  mvc.perform(delete("/system/roles/ADMIN").with(role("ADMIN",1))).andExpect(jsonPath("$.code").value(400));
 }
 @Test void disabledInvitesCannotRegisterAndDeviceAttributionIsImmutable(){
  jdbc.update("INSERT INTO player_invitation(token,name,referrer_id,one_device) VALUES ('valid','邀请',2,1)");
  assertThatThrownBy(()->governance.enroll(7L,1L,"valid","a-device-1234567890",false)).isInstanceOf(BusinessException.class);
  governance.enroll(7L,1L,"valid","a-device-1234567890",true);
  assertThatThrownBy(()->governance.enroll(8L,2L,"valid","a-device-1234567890",true)).isInstanceOf(BusinessException.class);
  governance.enroll(7L,1L,"different","different-device-12345",true);
  assertThat(jdbc.queryForObject("SELECT referrer_id FROM player_enrollment WHERE user_id=7",Long.class)).isEqualTo(2L);
  jdbc.update("UPDATE player_invitation SET enabled=0 WHERE token='valid'");
  assertThatThrownBy(()->governance.enroll(9L,3L,"valid","second-device-12345",true)).isInstanceOf(BusinessException.class);
 }
 @Test void publicHomepageStaysUnchangedUntilSystemAndHumanReview()throws Exception{
  var body=new PlayerHomepageRequest();body.setNickname("待审核昵称");body.setStory("个人游戏故事");
  String id=governance.submit(1L,body);
  assertThat(jdbc.queryForObject("SELECT nickname FROM player WHERE id=1",String.class)).isEqualTo("测试打手");
  mvc.perform(put("/cs/governance/revisions/"+id).with(role("USER",7)).contentType("application/json").content("{\"approved\":true}")).andExpect(jsonPath("$.code").value(403));
  mvc.perform(put("/cs/governance/revisions/"+id).with(role("CS",2)).contentType("application/json").content("{\"approved\":true}")).andExpect(jsonPath("$.code").value(200));
  assertThat(jdbc.queryForObject("SELECT nickname FROM player WHERE id=1",String.class)).isEqualTo("待审核昵称");
  mvc.perform(put("/cs/governance/revisions/"+id).with(role("ADMIN",1)).contentType("application/json").content("{\"approved\":true}")).andExpect(jsonPath("$.code").value(409));
 }
 @Test void unavailableMediaScannerDoesNotBypassApproval()throws Exception{
  var body=new PlayerHomepageRequest();body.setAvatar("/file/picture.jpg");String id=governance.submit(1L,body);
  assertThat(jdbc.queryForObject("SELECT status FROM player_homepage_revision WHERE id=?",String.class,id)).isEqualTo("PENDING_SYSTEM");
  mvc.perform(put("/cs/governance/revisions/"+id).with(role("ADMIN",1)).contentType("application/json").content("{\"approved\":true}")).andExpect(jsonPath("$.code").value(409));
 }
 @Test void policyViolationsFreezePlayerAndHideFromOrderIntake(){
  for(int i=0;i<3;i++){var body=new PlayerHomepageRequest();body.setStory("提供外挂服务");governance.submit(1L,body);}
  assertThat(jdbc.queryForObject("SELECT status FROM player WHERE id=1",String.class)).isEqualTo("FROZEN");
  assertThat(jdbc.queryForObject("SELECT is_online FROM player WHERE id=1",Integer.class)).isEqualTo(0);
 }
 @Test void onlySelfCanChangeCustomerServiceShift()throws Exception{
  mvc.perform(put("/cs/governance/shifts/1").with(role("CS",2)).contentType("application/json").content("{\"online\":true}")).andExpect(jsonPath("$.code").value(403));
  mvc.perform(put("/cs/governance/shifts/2").with(role("CS",2)).contentType("application/json").content("{\"online\":true}")).andExpect(jsonPath("$.code").value(200));
 }
 @Test void registrationCannotSkipAgreementOrRequiredAvatar()throws Exception{
  mvc.perform(post("/player/apply").with(role("USER",7)).contentType("application/json").content("{}")).andExpect(jsonPath("$.code").value(400));
  mvc.perform(post("/player/apply").with(role("USER",7)).contentType("application/json").content("{\"privacyAccepted\":true,\"nickname\":\"x\",\"realName\":\"测试\"}")).andExpect(jsonPath("$.code").value(400));
 }
 @Test void customerServiceCanOnlyTakePlayersOffDuty()throws Exception{
  mvc.perform(put("/cs/player/1/online-status").with(role("CS",2)).contentType("application/json").content("{\"online\":true}")).andExpect(jsonPath("$.code").value(403));
  mvc.perform(put("/cs/player/1/online-status").with(role("CS",2)).contentType("application/json").content("{\"online\":false}")).andExpect(jsonPath("$.code").value(200));
  assertThat(jdbc.queryForObject("SELECT is_online FROM player WHERE id=1",Integer.class)).isZero();
 }
 @Test void privacyRequestsCannotBeReadOrApprovedByOtherUsersOrCustomerService()throws Exception{
  mvc.perform(post("/user/privacy/requests").with(role("USER",7)).contentType("application/json").content("{\"kind\":\"ERASE_IDENTITY\",\"note\":\"删除实名\"}")).andExpect(jsonPath("$.code").value(200));
  mvc.perform(get("/user/privacy/requests").with(role("USER",8))).andExpect(jsonPath("$.data").isEmpty());
  String id=jdbc.queryForObject("SELECT id FROM privacy_request WHERE user_id=7",String.class);
  mvc.perform(put("/admin/privacy/requests/"+id).with(role("CS",2)).contentType("application/json").content("{\"approved\":true,\"note\":\"确认\"}")).andExpect(jsonPath("$.code").value(403));
  jdbc.update("UPDATE `user` SET openid='private-openid' WHERE id=7");
  mvc.perform(put("/admin/privacy/requests/"+id).with(role("ADMIN",1)).contentType("application/json").content("{\"approved\":true,\"note\":\"确认删除\"}")).andExpect(jsonPath("$.code").value(200));
  assertThat(jdbc.queryForObject("SELECT real_name FROM player WHERE id=1",String.class)).isEmpty();
 }
 @Test void accountClosureUsesBusinessGuardsThenErasesIdentityWithoutViolatingNonNullPhone()throws Exception{
  jdbc.update("UPDATE `user` SET openid='private-openid',phone='13900000000' WHERE id=7");
  mvc.perform(post("/user/privacy/requests").with(role("USER",7)).contentType("application/json").content("{\"kind\":\"CLOSE_ACCOUNT\"}")).andExpect(jsonPath("$.code").value(200));
  String id=jdbc.queryForObject("SELECT id FROM privacy_request WHERE user_id=7",String.class);
  mvc.perform(put("/admin/privacy/requests/"+id).with(role("ADMIN",1)).contentType("application/json").content("{\"approved\":true,\"note\":\"确认注销\"}")).andExpect(jsonPath("$.code").value(200));
  verify(userManagement).delete(7L);
  assertThat(jdbc.queryForObject("SELECT phone FROM `user` WHERE id=7",String.class)).isEmpty();
  assertThat(jdbc.queryForObject("SELECT real_name FROM player WHERE id=1",String.class)).isEmpty();
 }
}
