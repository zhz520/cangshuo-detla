package com.delta.notification.service;

import com.alibaba.fastjson2.JSON;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.SecretCipher;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantScheduler;
import com.delta.platform.PlatformService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-tenant durable scan completion and template setup. No browser poll or payment scheduler thread owns a WeChat call. */
@Service
@RequiredArgsConstructor
public class WechatTemplateSetupJobs {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SecretCipher cipher;
    private final WechatOpenPlatformClient client;
    private final WechatAccountService accounts;
    private final WechatTemplateProvisioner provisioner;
    private final PlatformService platform;
    private final TenantRegistry tenants;
    // A slow template-library call must not delay the expiry-sensitive scan queue of another tenant.
    private final ExecutorService scanWorker = worker("wechat-auth-scan");
    private final ExecutorService setupWorker = worker("wechat-template-setup");
    private final AtomicBoolean scanning = new AtomicBoolean(), provisioning = new AtomicBoolean();
    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r,name); t.setDaemon(true); return t;
        });
    }
    private static final String TABLE = "wechat_template_setup_job";
    private static Timestamp now(long plus) { return Timestamp.from(Instant.now().plusSeconds(plus)); }
    private static String scanId(String state) {
        try { return "q:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public WechatOpenPlatformClient.Authorization start(String domain) {
        var a=client.start();
        jdbc.update("INSERT INTO "+TABLE+"(id,kind,status,domain_name,state_cipher,expires_at,next_run) VALUES(?,'SCAN','PENDING',?,?,?,?)",
                scanId(a.state()),domain,cipher.encrypt(a.state()),Timestamp.from(Instant.parse(a.expiresAt())),now(2));
        return a;
    }
    public WechatOpenPlatformClient.Progress progress(String state) {
        client.verifyAttemptOwner(state);
        return jdbc.query("SELECT status,account_id,opened FROM "+TABLE+" WHERE id=?",(rs,n)->{
            String status=rs.getString(1), accountId=rs.getString(2);
            Map<String,Object> account="AUTHORIZED".equals(status)?accounts.accounts().stream()
                    .filter(a->Objects.equals(accountId,a.get("id"))).findFirst().orElse(null):null;
            if("AUTHORIZED".equals(status)&&account==null) status="FAILED";
            return new WechatOpenPlatformClient.Progress(status,account,rs.getBoolean(3));
        },scanId(state)).stream().findFirst().orElseThrow(()->new BusinessException(404,"授权会话不存在"));
    }
    public Map<String,Object> retry(String accountId,String domain) {
        if(accounts.accounts().stream().noneMatch(a->accountId.equals(a.get("id")) && "OPEN_PLATFORM".equals(a.get("mode")) && Boolean.TRUE.equals(a.get("enabled"))))
            throw new BusinessException(409,"请先选择已启用的扫码授权公众号");
        enqueue(accountId,domain);
        return report(accountId);
    }
    private void enqueue(String accountId,String domain) {
        String id="a:"+accountId;
        jdbc.update("INSERT INTO "+TABLE+"(id,kind,status,account_id,domain_name,next_run) VALUES(?,'SETUP','PENDING',?,?,?) ON DUPLICATE KEY UPDATE id=id",id,accountId,domain,now(0));
        jdbc.update("UPDATE "+TABLE+" SET status='PENDING',next_run=?,domain_name=?,report_json=NULL WHERE id=? AND status IN ('DONE','ATTENTION')",now(0),domain,id);
    }
    public Map<String,Object> report(String accountId) {
        return jdbc.query("SELECT status,report_json FROM "+TABLE+" WHERE id=?",(rs,n)->{
            Map<String,Object> result=new LinkedHashMap<>();
            if(rs.getString(2)!=null) result.putAll(JSON.parseObject(rs.getString(2)));
            result.put("status",rs.getString(1)); return result;
        },"a:"+accountId).stream().findFirst().orElse(Map.of("status","NOT_STARTED"));
    }
    @Scheduled(fixedDelay=4000,initialDelay=5000)
    public void tick() {
        schedule(scanWorker,scanning,"SCAN");
        schedule(setupWorker,provisioning,"SETUP");
    }
    private void schedule(ExecutorService executor, AtomicBoolean running, String kind) {
        if(!running.compareAndSet(false,true)) return;
        try {
            executor.execute(()->{try{TenantScheduler.run(tenants,()->drain(kind));} finally{running.set(false);}});
        } catch (RejectedExecutionException shutdown) { running.set(false); }
    }
    @PreDestroy public void stop(){scanWorker.shutdownNow();setupWorker.shutdownNow();}
    void drain(String kind) {
        // Reclaim abandoned leases; finished scan tickets are not business history.
        if ("SCAN".equals(kind)) jdbc.update("DELETE FROM "+TABLE+" WHERE kind='SCAN' AND expires_at<?",now(-600));
        var ids=jdbc.queryForList("SELECT id FROM "+TABLE+" WHERE kind=? AND status IN ('PENDING','PROCESSING') AND next_run<=? ORDER BY next_run LIMIT 8",String.class,kind,now(0));
        for(String id:ids) process(id);
    }
    private void process(String id) {
        String lease=UUID.randomUUID().toString();
        if(jdbc.update("UPDATE "+TABLE+" SET lease_id=?,next_run=?,status=CASE WHEN kind='SETUP' THEN 'PROCESSING' ELSE status END WHERE id=? AND status IN ('PENDING','PROCESSING') AND next_run<=?",
                lease,now(id.startsWith("q:")?45:1200),id,now(0))!=1) return;
        var job=jdbc.queryForMap("SELECT kind,domain_name,state_cipher,account_id,expires_at FROM "+TABLE+" WHERE id=?",id);
        boolean scan="SCAN".equals(job.get("kind"));
        try {
            String domain=(String)job.get("domain_name");
            if(scan && jdbc.queryForObject("SELECT COUNT(*) FROM "+TABLE+" WHERE id=? AND expires_at<=?",Integer.class,id,now(0))==1){
                terminal(id,lease,"EXPIRED",null); return;
            }
            platform.requireFeature(OfficialWechatService.FEATURE_ID,domain);
            if(scan) {
                var p=client.reconcileAttempt(cipher.decrypt((String)job.get("state_cipher")),accounts);
                if("AUTHORIZED".equals(p.status())) {
                    String accountId=p.account().get("id").toString();
                    tx.executeWithoutResult(unused->{
                        int changed=jdbc.update("UPDATE "+TABLE+" SET status='AUTHORIZED',account_id=?,state_cipher=NULL,opened=1,lease_id=NULL WHERE id=? AND lease_id=?",accountId,id,lease);
                        if(changed==1 && "OPEN_PLATFORM".equals(p.account().get("mode"))) enqueue(accountId,domain);
                    });
                } else if(Set.of("FAILED","EXPIRED").contains(p.status())) terminal(id,lease,p.status(),null);
                else jdbc.update("UPDATE "+TABLE+" SET status=?,opened=?,lease_id=NULL,next_run=? WHERE id=? AND lease_id=?",p.status(),p.opened()?1:0,now(4),id,lease);
            } else {
                var report=provisioner.provision((String)job.get("account_id"));
                boolean ready=report.get("issues") instanceof List<?> list && list.isEmpty();
                terminal(id,lease,ready?"DONE":"ATTENTION",report);
            }
        } catch(Exception e) {
            if(scan && (!(e instanceof BusinessException b) || b.getCode()>=500)){
                jdbc.update("UPDATE "+TABLE+" SET lease_id=NULL,next_run=? WHERE id=? AND lease_id=?",now(8),id,lease);
            } else terminal(id,lease,scan?"FAILED":"ATTENTION",Map.of("message",
                e instanceof BusinessException b && b.getCode()==403?"请检查功能商城权益及公众号授权权限":"请检查该号模板消息权限和授权中心连接，然后重试"));
        }
    }
    private void terminal(String id,String lease,String status,Map<String,Object> data){
        jdbc.update("UPDATE "+TABLE+" SET status=?,report_json=?,state_cipher=NULL,lease_id=NULL WHERE id=? AND lease_id=?",
                status,data==null?null:JSON.toJSONString(data),id,lease);
    }
}
