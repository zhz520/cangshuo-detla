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
    public Map<String,Object> repair(String accountId,String domain,String role,String scenario,String revision) {
        if(role==null || scenario==null || !WechatAccountService.ROLES.contains(role) || !WechatAccountService.SCENARIOS.contains(scenario))
            throw new BusinessException(400,"请选择有效的模板场景");
        if(accounts.accounts().stream().noneMatch(a->accountId.equals(a.get("id")) && "OPEN_PLATFORM".equals(a.get("mode")) && Boolean.TRUE.equals(a.get("enabled"))))
            throw new BusinessException(409,"请先选择已启用的扫码授权公众号");
        String scope=role+":"+scenario;
        var specs=accounts.provisioningSchemes();
        var versions=revisions(specs);
        if(revision==null || !revision.equals(versions.get(scope))) throw new BusinessException(409,"授权中心方案已变化，请刷新后再试");
        var signed=new ArrayList<WechatProvisioningScheme>();
        specs.forEach(n->signed.add(new WechatProvisioningScheme(n)));
        var targets=signed.stream().filter(s->s.scope().equals(scope)).toList();
        if(targets.size()!=1) throw new BusinessException(409,"当前方案不唯一");
        var target=targets.get(0);
        var keywords=target.orderedKeywords();
        if(keywords==null || target.addBody(keywords)==null) throw new BusinessException(409,"请先配置模板库编号和关键词");
        var group=signed.stream().filter(s->s.semantic() && target.shortId().equals(s.shortId()) && keywords.equals(s.orderedKeywords())).map(WechatProvisioningScheme::scope).toList();
        tx.executeWithoutResult(ignore->{
            var raw=jdbc.query("SELECT report_json FROM "+TABLE+" WHERE id=? AND status='ATTENTION' FOR UPDATE",(rs,i)->rs.getString(1),"a:"+accountId);
            var previous=raw.size()==1 && raw.get(0)!=null?JSON.parseObject(raw.get(0)):null;
            if(previous==null || !versions.equals(previous.getJSONObject("revisions")) || previous.getJSONArray("issues")==null
                || previous.getJSONArray("issues").stream().noneMatch(i->i instanceof Map<?,?> m && role.equals(m.get("role")) && scenario.equals(m.get("scenario")) && "RECEIPT_NOT_IN_CATALOG".equals(m.get("code"))))
                throw new BusinessException(409,"当前状态不支持补建，请先重新检查模板");
            previous.put("repairScope",scope);
            previous.put("changedScopes",group);
            jdbc.update("UPDATE "+TABLE+" SET status='PENDING',next_run=?,domain_name=?,report_json=? WHERE id=?",now(0),domain,previous.toJSONString(),"a:"+accountId);
        });
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
    private final AtomicBoolean comparing = new AtomicBoolean();
    @Scheduled(fixedDelay=60000,initialDelay=25000)
    public void checkSchemeUpdates() {
        if (!comparing.compareAndSet(false,true)) return;
        try { setupWorker.execute(()->{try{TenantScheduler.run(tenants,this::queueChanges);}finally{comparing.set(false);}}); }
        catch(RejectedExecutionException ignored){comparing.set(false);}
    }
    static Map<String,String> revisions(com.fasterxml.jackson.databind.JsonNode specs) {
        var out=new TreeMap<String,String>();
        if(specs!=null && specs.isArray() && specs.size()<=40) for(var node:specs) {
            var scheme=new WechatProvisioningScheme(node);
            if(scheme.semantic() && WechatAccountService.ROLES.contains(scheme.role()) && WechatAccountService.SCENARIOS.contains(scheme.scenario()))
                out.put(scheme.scope(),node.path("id").asText()+":"+node.path("version").asInt());
        }
        return out;
    }
    void queueChanges() {
        var snapshot=platform.projectSnapshot();
        var specs=accounts.provisioningSchemes();
        if(specs==null || !specs.isArray() || specs.size()>40) return;
        var current=revisions(specs);
        var domains=snapshot==null?null:snapshot.path("allowedDomains");
        if(!current.isEmpty() && domains!=null && domains.isArray() && domains.size()>0){
            String domain=domains.get(0).asText("");
            if(!domain.isBlank()) for(String account:jdbc.queryForList("SELECT a.id FROM wechat_mp_account a LEFT JOIN "+TABLE+" j ON j.id=CONCAT('a:',a.id) WHERE a.enabled=1 AND a.account_mode='OPEN_PLATFORM' AND j.id IS NULL LIMIT 8",String.class)) {
                String report=JSON.toJSONString(Map.of("changedScopes",current.keySet()));
                jdbc.update("INSERT INTO "+TABLE+"(id,kind,status,account_id,domain_name,next_run,report_json) VALUES(?,'SETUP','PENDING',?,?,?,?) ON DUPLICATE KEY UPDATE id=id","a:"+account,account,domain,now(0),report);
            }
        }
        for(var row:jdbc.queryForList("SELECT id,status,report_json FROM "+TABLE+" WHERE kind='SETUP' AND status IN ('DONE','ATTENTION')")) {
            var raw=(String)row.get("report_json");
            var last=raw==null?new com.alibaba.fastjson2.JSONObject():JSON.parseObject(raw);
            var seen=last.getJSONObject("revisions");
            var changed=new TreeSet<String>();
            if(seen!=null) for(String k:seen.keySet()) if(!current.containsKey(k)) changed.add(k);
            current.forEach((k,v)->{if(seen==null||!v.equals(seen.getString(k))) changed.add(k);});
            if(changed.isEmpty()) continue;
            last.put("changedScopes",changed);
            jdbc.update("UPDATE "+TABLE+" SET status='PENDING',next_run=?,report_json=? WHERE id=? AND status=? AND (report_json=? OR (report_json IS NULL AND ? IS NULL))",
                    now(0),last.toJSONString(),row.get("id"),row.get("status"),raw,raw);
        }
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
        var job=jdbc.queryForMap("SELECT kind,domain_name,state_cipher,account_id,expires_at,report_json FROM "+TABLE+" WHERE id=?",id);
        boolean scan="SCAN".equals(job.get("kind"));
        var specs=scan?null:accounts.provisioningSchemes();
        var revisions=revisions(specs);
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
                var last=job.get("report_json")==null?new com.alibaba.fastjson2.JSONObject():JSON.parseObject((String)job.get("report_json"));
                var scopeList=last.getList("changedScopes",String.class);
                Set<String> only=scopeList==null?null:new HashSet<>(scopeList);
                if(only!=null){
                    var seen=last.getJSONObject("revisions");
                    revisions.forEach((k,v)->{if(seen==null||!v.equals(seen.getString(k))) only.add(k);});
                    if(specs!=null && specs.isArray() && seen!=null) for(String k:seen.keySet()) if(!revisions.containsKey(k)) {
                        String[] parts=k.split(":",2);
                        if(parts.length==2 && WechatAccountService.ROLES.contains(parts[0]) && WechatAccountService.SCENARIOS.contains(parts[1]))
                            jdbc.update("UPDATE wechat_mp_template SET enabled=0 WHERE account_id=? AND role=? AND scenario=? AND source='AUTO_SEMANTIC'",job.get("account_id"),parts[0],parts[1]);
                    }
                    only.retainAll(revisions.keySet());
                }
                String repair=last.getString("repairScope");
                if(repair!=null && !revisions.equals(last.getJSONObject("revisions")))
                    throw new BusinessException(409,"授权中心方案已变化，请重新检查");
                var report=new LinkedHashMap<String,Object>(repair!=null?provisioner.provision((String)job.get("account_id"),specs,only,repair):specs==null?provisioner.provision((String)job.get("account_id")):provisioner.provision((String)job.get("account_id"),specs,only));
                if(only!=null && last.getJSONArray("issues")!=null) {
                    var merged=new ArrayList<Object>((List<?>)report.getOrDefault("issues",List.of()));
                    for(var item:last.getJSONArray("issues")) if(item instanceof Map<?,?> m && !only.contains(m.get("role")+":"+m.get("scenario"))) merged.add(item);
                    report.put("issues",merged);
                }
                report.put("revisions",revisions);
                boolean ready=report.get("issues") instanceof List<?> list && list.isEmpty();
                terminal(id,lease,ready?"DONE":"ATTENTION",report);
            }
        } catch(Exception e) {
            if(scan && (!(e instanceof BusinessException b) || b.getCode()>=500)){
                jdbc.update("UPDATE "+TABLE+" SET lease_id=NULL,next_run=? WHERE id=? AND lease_id=?",now(8),id,lease);
            } else terminal(id,lease,scan?"FAILED":"ATTENTION",Map.of("revisions",revisions,"message",
                e instanceof BusinessException b && b.getCode()==403?"请检查功能商城权益及公众号授权权限":"请检查该号模板消息权限和授权中心连接，然后重试"));
        }
    }
    private void terminal(String id,String lease,String status,Map<String,Object> data){
        jdbc.update("UPDATE "+TABLE+" SET status=?,report_json=?,state_cipher=NULL,lease_id=NULL WHERE id=? AND lease_id=?",
                status,data==null?null:JSON.toJSONString(data),id,lease);
    }
}
