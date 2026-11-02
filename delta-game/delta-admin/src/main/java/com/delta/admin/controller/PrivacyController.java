package com.delta.admin.controller;

import com.delta.admin.service.UserManagementService;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class PrivacyController {
    private final JdbcTemplate jdbc;
    private final UserManagementService management;
    public record Request(@NotBlank @Pattern(regexp="ERASE_IDENTITY|CLOSE_ACCOUNT") String kind,@Size(max=500) String note){}
    @GetMapping("/user/privacy/requests") public R<List<Map<String,Object>>> mine(){return R.ok(jdbc.queryForList("SELECT id,kind,status,note,created_at,reviewed_at FROM privacy_request WHERE user_id=? ORDER BY created_at DESC LIMIT 30",SecurityUtils.getUserId()));}
    @PostMapping("/user/privacy/requests")
    @Transactional(rollbackFor=Exception.class)
    @OpLog(module="privacy",operation="用户申请注销或删除实名资料")
    public R<Void> request(@Valid @RequestBody Request body){
        Long id=SecurityUtils.getUserId();jdbc.queryForList("SELECT id FROM `user` WHERE id=? FOR UPDATE",id);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM privacy_request WHERE user_id=? AND status='PENDING'",Long.class,id)>0)throw new BusinessException(409,"已有待处理申请");
        jdbc.update("INSERT INTO privacy_request(id,user_id,kind,status,note) VALUES (?,?,?,'PENDING',?)",UUID.randomUUID().toString(),id,body.kind(),body.note());return R.ok();
    }
    @GetMapping("/admin/privacy/requests")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('user.manage')")
    public R<List<Map<String,Object>>> list(){return R.ok(jdbc.queryForList("SELECT r.*,u.nickname FROM privacy_request r LEFT JOIN `user` u ON u.id=r.user_id ORDER BY r.created_at DESC LIMIT 300"));}
    public record Decision(boolean approved,@NotBlank @Size(max=500) String note){}
    @PutMapping("/admin/privacy/requests/{id}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('user.manage')")
    @OpLog(module="privacy",operation="审核注销或实名删除申请")
    @Transactional(rollbackFor=Exception.class)
    public R<Void> review(@PathVariable String id,@Valid @RequestBody Decision body){
        var requests=jdbc.queryForList("SELECT * FROM privacy_request WHERE id=? FOR UPDATE",id);
        if(requests.isEmpty()||!"PENDING".equals(requests.get(0).get("status")))throw new BusinessException(409,"申请不存在或已处理");
        var row=requests.get(0);long userId=((Number)row.get("user_id")).longValue();
        if(body.approved()){
            var users=jdbc.queryForList("SELECT openid FROM `user` WHERE id=? FOR UPDATE",userId);if(users.isEmpty())throw new BusinessException(404,"账号不存在");
            String openid=(String)users.get(0).get("openid");
            jdbc.update("DELETE FROM identity_verification WHERE user_id=?",userId);
            if("CLOSE_ACCOUNT".equals(row.get("kind"))){
                management.delete(userId); // Checks unsettled orders, payments, balances, withdrawals and rewards.
                if(jdbc.queryForObject("SELECT COUNT(*) FROM order_player op JOIN `order` o ON o.id=op.order_id JOIN player p ON p.id=op.player_id WHERE p.openid=? AND op.status='ACCEPTED' AND o.status NOT IN ('CONFIRMED','REVIEWED','SETTLED','CANCELLED','REFUNDED','ARBITRATED')",Long.class,openid)>0)throw new BusinessException(409,"仍有队友订单未完成，请先处理");
                jdbc.update("UPDATE `user` SET phone='',openid=?,nickname='已注销用户',avatar='',password=NULL,auth_version=COALESCE(auth_version,0)+1 WHERE id=?","closed:"+userId,userId);
                jdbc.update("UPDATE player SET real_name='',phone='',nickname='已注销打手',avatar='',cover_image='',album_images='',story='',voice_url='',introduction='',proof_images='',status='FROZEN',is_online=0 WHERE openid=?",openid);
                jdbc.update("UPDATE player_homepage_revision SET payload='{}' WHERE player_id IN (SELECT id FROM player WHERE openid=?)",openid);
                jdbc.update("DELETE FROM wechat_official_binding WHERE user_type='USER' AND user_id=?",userId);
                jdbc.update("DELETE FROM wechat_official_binding WHERE user_type='PLAYER' AND user_id IN (SELECT id FROM player WHERE openid=?)",openid);
            }else jdbc.update("UPDATE player SET real_name='' WHERE openid=?",openid);
        }
        jdbc.update("UPDATE privacy_request SET status=?,note=?,reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?",body.approved()?"APPROVED":"REJECTED",body.note(),SecurityUtils.getUserId(),id);return R.ok();
    }
}
