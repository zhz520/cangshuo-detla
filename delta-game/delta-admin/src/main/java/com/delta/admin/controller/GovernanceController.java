package com.delta.admin.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.player.dto.PlayerHomepageRequest;
import com.delta.player.service.PlayerGovernanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class GovernanceController {
    private final JdbcTemplate jdbc;
    private final PlayerGovernanceService governance;
    @GetMapping("/player/invitations/{token}") public R<Map<String,Object>> invite(@PathVariable String token){
        Map<String,Object> source=governance.invitation(token);
        return R.ok(Map.of("name",source.get("name"),"oneDevice",source.get("one_device")));
    }
    @GetMapping("/player/homepage/revisions") public R<List<Map<String,Object>>> history(){return R.ok(governance.history(SecurityUtils.getUserId()));}
    @GetMapping("/cs/governance/revisions")
    @PreAuthorize("@staffPermissions.allowed('media.review')")
    public R<List<Map<String,Object>>> revisions(@RequestParam(required = false) String taskId){return R.ok(jdbc.queryForList("SELECT r.*,p.nickname FROM player_homepage_revision r LEFT JOIN player p ON p.id=r.player_id WHERE (? IS NULL OR r.id=?) ORDER BY r.created_at DESC LIMIT 300",taskId,taskId));}
    public record Review(boolean approved,@Size(max=500) String note,boolean violation){}
    @PutMapping("/cs/governance/revisions/{id}")
    @PreAuthorize("@staffPermissions.allowed('media.review')")
    @OpLog(module="media",operation="人工审核打手主页资料")
    public R<Void> review(@PathVariable String id,@Valid @RequestBody Review body){governance.review(id,body.approved(),body.note(),body.violation());return R.ok();}
    @PostMapping("/cs/governance/revisions/{id}/recheck")
    @PreAuthorize("@staffPermissions.allowed('media.review')")
    @OpLog(module="media",operation="重新提交系统风控检测")
    public R<Void> recheck(@PathVariable String id){governance.recheck(id);return R.ok();}
    @GetMapping("/cs/governance/invitations")
    @PreAuthorize("@staffPermissions.allowed('player.manage')")
    public R<List<Map<String,Object>>> invitations(){return R.ok(jdbc.queryForList("SELECT i.*,(SELECT COUNT(*) FROM player_enrollment e WHERE e.invite_token=i.token) AS registrations FROM player_invitation i ORDER BY i.created_at DESC LIMIT 300"));}
    public record InvitationBody(@NotBlank @Size(max=100) String name,boolean oneDevice,LocalDateTime expiresAt){}
    @PostMapping("/cs/governance/invitations")
    @PreAuthorize("@staffPermissions.allowed('player.manage')")
    @OpLog(module="invitation",operation="创建入驻邀请链接")
    public R<String> createInvite(@Valid @RequestBody InvitationBody body){String token=UUID.randomUUID().toString().replace("-","");if(body.expiresAt()!=null&&!body.expiresAt().isAfter(LocalDateTime.now()))throw new BusinessException(400,"有效期必须在未来");jdbc.update("INSERT INTO player_invitation(token,name,referrer_id,one_device,expires_at) VALUES (?,?,?,?,?)",token,body.name(),SecurityUtils.getUserId(),body.oneDevice()?1:0,body.expiresAt());return R.ok(token);}
    @PutMapping("/cs/governance/invitations/{token}")
    @PreAuthorize("@staffPermissions.allowed('player.manage')")
    @OpLog(module="invitation",operation="启用或停用入驻邀请链接")
    public R<Void> toggle(@PathVariable String token,@RequestBody Map<String,Boolean> body){if(jdbc.update("UPDATE player_invitation SET enabled=? WHERE token=?",Boolean.TRUE.equals(body.get("enabled"))?1:0,token)==0)throw new BusinessException(404,"邀请链接不存在");return R.ok();}
    @GetMapping("/cs/governance/shifts") public R<List<Map<String,Object>>> shifts(){return R.ok(jdbc.queryForList("SELECT a.id,a.nickname,a.role,COALESCE(s.online,0) AS online FROM admin a LEFT JOIN staff_shift s ON s.admin_id=a.id WHERE a.deleted=0 AND a.status=1"));}
    @PutMapping("/cs/governance/shifts/{id}")
    @OpLog(module="shift",operation="员工上下班操作")
    @Transactional(rollbackFor=Exception.class)
    public R<Void> shift(@PathVariable Long id,@RequestBody Map<String,Boolean> body){if(!id.equals(SecurityUtils.getUserId())&&!"ADMIN".equals(SecurityUtils.getUserType()))throw new BusinessException(403,"客服只能操作自己的上下班状态");jdbc.queryForList("SELECT id FROM admin WHERE id=? FOR UPDATE",id);jdbc.update("DELETE FROM staff_shift WHERE admin_id=?",id);jdbc.update("INSERT INTO staff_shift(admin_id,online) VALUES (?,?)",id,Boolean.TRUE.equals(body.get("online"))?1:0);return R.ok();}
}
