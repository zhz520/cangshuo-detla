package com.delta.player.service;

import com.alibaba.fastjson2.JSON;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.player.dto.PlayerHomepageRequest;
import com.delta.player.entity.Player;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PlayerGovernanceService {
    private final JdbcTemplate jdbc;
    private final PlayerService players;
    private final SysConfigService configs;
    private final MediaRiskService mediaRisk;
    private final ApplicationEventPublisher events;

    public void checkApplication(Long playerId) {
        Player player=players.getById(playerId);if(player==null)throw new BusinessException(404,"打手不存在");
        if(!"PENDING".equals(player.getStatus()))throw new BusinessException(409,"仅待审核入驻申请可审核通过");
        PlayerHomepageRequest body=new PlayerHomepageRequest();body.setNickname(player.getNickname());body.setAvatar(player.getAvatar());body.setIntroduction(player.getIntroduction());
        var result=mediaRisk.check(body);if(!"PENDING_REVIEW".equals(result.status()))throw new BusinessException(409,result.reason());
    }
    public Map<String,Object> invitation(String token) {
        var rows=jdbc.queryForList("SELECT token,name,referrer_id,one_device FROM player_invitation WHERE token=? AND enabled=1 AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)",token);
        if(rows.isEmpty())throw new BusinessException(400,"邀请链接已停用或过期");
        return rows.get(0);
    }
    @Transactional(rollbackFor=Exception.class)
    public void enroll(Long userId,Long playerId,String invite,String device,boolean accepted) {
        if(!accepted)throw new BusinessException(400,"请阅读并同意用户协议和隐私政策");
        var existing=jdbc.queryForList("SELECT * FROM player_enrollment WHERE user_id=? FOR UPDATE",userId);
        if(!existing.isEmpty())return; // Rejected applications retain their original attribution.
        Long referrer=null;String fingerprint=null;
        if(invite!=null&&!invite.isBlank()) {
            var rows=jdbc.queryForList("SELECT * FROM player_invitation WHERE token=? FOR UPDATE",invite);
            invitation(invite);
            var row=rows.get(0);referrer=((Number)row.get("referrer_id")).longValue();
            if(((Number)row.get("one_device")).intValue()==1) {
                if(device==null||!device.matches("[A-Za-z0-9_-]{16,100}"))throw new BusinessException(400,"请允许保存设备标识后重新提交");
                fingerprint=hash(device);
                if(jdbc.queryForObject("SELECT COUNT(*) FROM player_enrollment WHERE device_hash=?",Long.class,fingerprint)>0)
                    throw new BusinessException(409,"当前设备已提交过入驻，请联系客服处理");
            }
        } else if("true".equals(configs.getConfigValue("player.invite_required","false")))throw new BusinessException(400,"请使用客服提供的邀请链接入驻");
        jdbc.update("INSERT INTO player_enrollment(user_id,player_id,invite_token,referrer_id,device_hash,privacy_version) VALUES (?,?,?,?,?,?)",userId,playerId,invite,referrer,fingerprint,"2026-11-02");
        events.publishEvent(new BusinessEvent(this,"PLAYER_APPLICATION_SUBMITTED","PLAYER",playerId,null,"新的打手入驻申请待审核"));
    }
    @Transactional(rollbackFor=Exception.class)
    public String submit(Long playerId,PlayerHomepageRequest body) {
        if(players.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Player>().eq(Player::getId,playerId).last("FOR UPDATE"))==null)throw new BusinessException(404,"打手不存在");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM player_homepage_revision WHERE player_id=? AND status IN ('PENDING_SYSTEM','PENDING_REVIEW')",Long.class,playerId)>0)
            throw new BusinessException(409,"已有待审核资料，请等待审核后再提交");
        String payload=JSON.toJSONString(body),id=UUID.randomUUID().toString();
        MediaRiskService.Result risk=mediaRisk.check(body);
        jdbc.update("INSERT INTO player_homepage_revision(id,player_id,payload,status,risk_reason) VALUES (?,?,?,?,?)",id,playerId,payload,risk.status(),risk.reason());
        events.publishEvent(new BusinessEvent(this,"MATERIAL_SUBMITTED","PLAYER",playerId,null,"打手主页素材待审核"));
        if("REJECTED".equals(risk.status())){jdbc.update("UPDATE player_homepage_revision SET violation=1 WHERE id=?",id);violation(playerId);}
        return id;
    }
    public List<Map<String,Object>> history(Long id) {
        return jdbc.queryForList("SELECT id,status,risk_reason,review_note,created_at,reviewed_at FROM player_homepage_revision WHERE player_id=? ORDER BY created_at DESC LIMIT 20",id);
    }
    @Transactional(rollbackFor=Exception.class)
    public void review(String id,boolean approved,String note,boolean isViolation) {
        var rows=jdbc.queryForList("SELECT * FROM player_homepage_revision WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new BusinessException(404,"资料不存在");var row=rows.get(0);
        if(!"PENDING_REVIEW".equals(row.get("status")))throw new BusinessException(409,"资料尚未通过系统检测或已完成审核");
        Long playerId=((Number)row.get("player_id")).longValue();
        if(!approved&&(note==null||note.isBlank()))throw new BusinessException(400,"驳回时请填写原因");
        if(approved){Player update=new Player();update.setId(playerId);JSON.parseObject((String)row.get("payload"),PlayerHomepageRequest.class).applyTo(update);players.updateById(update);}
        jdbc.update("UPDATE player_homepage_revision SET status=?,review_note=?,reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP WHERE id=?",approved?"APPROVED":"REJECTED",note,SecurityUtils.getUserId(),id);
        if(!approved&&isViolation){jdbc.update("UPDATE player_homepage_revision SET violation=1 WHERE id=?",id);violation(playerId);}
        events.publishEvent(new BusinessEvent(this,"MATERIAL_REVIEWED","PLAYER",playerId,null,approved?"主页资料审核通过，已生效":"主页资料审核未通过："+note));
    }
    @Transactional(rollbackFor=Exception.class)
    public void recheck(String id) {
        var rows=jdbc.queryForList("SELECT * FROM player_homepage_revision WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()||!"PENDING_SYSTEM".equals(rows.get(0).get("status")))throw new BusinessException(409,"该资料无需重新检测");
        var row=rows.get(0);var result=mediaRisk.check(JSON.parseObject((String)row.get("payload"),PlayerHomepageRequest.class));
        jdbc.update("UPDATE player_homepage_revision SET status=?,risk_reason=? WHERE id=?",result.status(),result.reason(),id);
        if("REJECTED".equals(result.status())){jdbc.update("UPDATE player_homepage_revision SET violation=1 WHERE id=?",id);violation(((Number)row.get("player_id")).longValue());}
    }
    private void violation(Long playerId) {
        int threshold=3;try{threshold=Math.max(1,Integer.parseInt(configs.getConfigValue("risk.freeze_threshold","3")));}catch(NumberFormatException ignored){}
        if(jdbc.queryForObject("SELECT COUNT(*) FROM player_homepage_revision WHERE player_id=? AND violation=1",Long.class,playerId)>=threshold){
            jdbc.update("UPDATE player SET status='FROZEN',is_online=0,frozen_until=NULL WHERE id=?",playerId);
            events.publishEvent(new BusinessEvent(this,"PLAYER_FROZEN","PLAYER",playerId,null,"多次提交违规资料，接单权限已冻结，请联系客服复核"));
        }
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
