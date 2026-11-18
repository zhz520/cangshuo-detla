package com.delta.admin.controller;
import com.delta.admin.platform.PlatformService;
import com.delta.admin.service.OfficialWechatService;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.common.sms.service.SmsGatewayClient;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
@RestController
@RequiredArgsConstructor
public class OfficialWechatController {
 private final OfficialWechatService service;
 private final JdbcTemplate jdbc;
 private final PlatformService platform;
 private final SmsGatewayClient smsGateway;
 @GetMapping("/common/wechat/binding") public R<Map<String,Object>> binding(){return R.ok(service.binding(SecurityUtils.getUserType(),SecurityUtils.getUserId()));}
 @PostMapping("/common/wechat/binding/start") public R<String> start(HttpServletRequest request){return R.ok(service.startBinding(SecurityUtils.getUserType(),SecurityUtils.getUserId(),request.getServerName()));}
 @PostMapping("/common/wechat/binding/complete") public R<Void> complete(@RequestBody Map<String,String> body,HttpServletRequest request)throws Exception{service.finishBinding(SecurityUtils.getUserType(),SecurityUtils.getUserId(),body.get("code"),body.get("state"),request.getServerName());return R.ok();}
 @DeleteMapping("/common/wechat/binding") @OpLog(module="wechat",operation="解除公众号通知绑定") public R<Void> remove(){service.removeBinding(SecurityUtils.getUserType(),SecurityUtils.getUserId());return R.ok();}
 @GetMapping("/admin/wechat/notifications") @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')") public R<List<Map<String,Object>>> notices(HttpServletRequest request){platform.requireFeature("official_wechat_notifications",request.getServerName());return R.ok(jdbc.queryForList("SELECT id,event_type,user_type,user_id,order_id,status,attempts,last_error,created_at FROM wechat_notice_queue ORDER BY created_at DESC LIMIT 300"));}
 @PostMapping("/admin/wechat/notifications/{id}/retry") @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')") @OpLog(module="wechat",operation="重试公众号通知") public R<Void> retry(@PathVariable String id,HttpServletRequest request){platform.requireFeature("official_wechat_notifications",request.getServerName());jdbc.update("UPDATE wechat_notice_queue SET status='PENDING',attempts=0,next_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=? AND status IN ('FAILED','RETRY','DELIVERY_UNKNOWN')",id);return R.ok();}
 @GetMapping("/admin/sms/notifications") @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')") public R<List<Map<String,Object>>> smsNotices(){return R.ok(jdbc.queryForList("SELECT id,event_type,event_group,user_type,user_id,order_id,CONCAT(LEFT(phone,3),'****',RIGHT(phone,4)) AS phone,status,attempts,last_error,created_at,sent_at FROM sms_notice_queue ORDER BY created_at DESC LIMIT 300"));}
 @PostMapping("/admin/sms/notifications/{id}/retry") @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')") @OpLog(module="sms",operation="重试业务短信通知") public R<Void> retrySms(@PathVariable String id){requireSms();jdbc.update("UPDATE sms_notice_queue SET status='PENDING',attempts=0,delivery_key=UUID(),next_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=? AND status IN ('FAILED','RETRY')",id);return R.ok();}
 private void requireSms(){if(smsGateway==null||!smsGateway.enabled())throw new com.delta.common.exception.BusinessException(403,"授权中心短信服务未启用");}
}
