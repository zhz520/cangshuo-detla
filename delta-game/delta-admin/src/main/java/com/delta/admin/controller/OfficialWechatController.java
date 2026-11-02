package com.delta.admin.controller;
import com.delta.admin.platform.PlatformService;
import com.delta.admin.service.OfficialWechatService;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
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
 @GetMapping("/common/wechat/binding") public R<Map<String,Object>> binding(){return R.ok(service.binding(SecurityUtils.getUserType(),SecurityUtils.getUserId()));}
 @PostMapping("/common/wechat/binding/start") public R<String> start(HttpServletRequest request){return R.ok(service.startBinding(SecurityUtils.getUserType(),SecurityUtils.getUserId(),request.getServerName()));}
 @PostMapping("/common/wechat/binding/complete") public R<Void> complete(@RequestBody Map<String,String> body,HttpServletRequest request)throws Exception{service.finishBinding(SecurityUtils.getUserType(),SecurityUtils.getUserId(),body.get("code"),body.get("state"),request.getServerName());return R.ok();}
 @DeleteMapping("/common/wechat/binding") @OpLog(module="wechat",operation="解除公众号通知绑定") public R<Void> remove(){jdbc.update("DELETE FROM wechat_official_binding WHERE user_type=? AND user_id=?",SecurityUtils.getUserType(),SecurityUtils.getUserId());return R.ok();}
 @GetMapping("/admin/wechat/notifications") @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')") public R<List<Map<String,Object>>> notices(HttpServletRequest request){platform.requireFeature(OfficialWechatService.FEATURE_ID,request.getServerName());return R.ok(jdbc.queryForList("SELECT id,event_type,user_type,user_id,order_id,status,attempts,last_error,created_at FROM wechat_notice_queue ORDER BY created_at DESC LIMIT 300"));}
 @PostMapping("/admin/wechat/notifications/{id}/retry") @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')") @OpLog(module="wechat",operation="重试公众号通知") public R<Void> retry(@PathVariable String id,HttpServletRequest request){platform.requireFeature(OfficialWechatService.FEATURE_ID,request.getServerName());jdbc.update("UPDATE wechat_notice_queue SET status='PENDING',attempts=0,next_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('FAILED','RETRY')",id);return R.ok();}
}
