package com.delta.admin.platform;

import com.delta.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;

@Service @Slf4j
public class PlatformRemote {
    private final PlatformProperties properties;
    private final PlatformService platform;
    private final ObjectMapper json;
    private final Clock clock;
    private volatile JsonNode cached;
    private volatile long lastAttempt;
    private final java.util.concurrent.atomic.AtomicBoolean planning = new java.util.concurrent.atomic.AtomicBoolean();
    @org.springframework.beans.factory.annotation.Autowired
    public PlatformRemote(PlatformProperties properties, PlatformService platform, ObjectMapper json) {
        this(properties,platform,json,Clock.systemUTC());
    }
    PlatformRemote(PlatformProperties properties, PlatformService platform, ObjectMapper json, Clock clock) {
        this.properties=properties;this.platform=platform;this.json=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);this.clock=clock;
    }
    private Path path(String name) { return Path.of(properties.getStatePath()).toAbsolutePath().getParent().resolve(name); }
    public synchronized JsonNode status() {
        if(cached==null) {
            try { var file=path("authority.json");if(Files.exists(file)&&Files.size(file)<2_000_000) cached=json.readTree(Files.readString(file)); }
            catch(Exception ignored) {}
        }
        String currentVersion=effectiveVersion();
        var result=json.createObjectNode();result.put("currentVersion",currentVersion);result.put("backendVersion",platform.overview().version());result.put("connected",cached!=null);
        result.set("componentVersions",componentVersions());
        var installed=json.createArrayNode();
        try(var files=Files.list(path("installed-plans"))) {for(var file:files.limit(500).toList())if(Files.isRegularFile(file)&&Files.size(file)<500000)for(var item:json.readTree(Files.readString(file)).path("items"))installed.add(item.path("key").asText());}catch(Exception ignored){}
        result.set("installedItems",installed);
        if(cached!=null) { result.set("updates",currentUpdates(cached.path("updates"),currentVersion));result.set("project",project());result.set("lastSync",cached.path("receivedAt")); }
        try {var file=path("update-result.json");if(Files.exists(file)&&Files.size(file)<65536)result.set("job",json.readTree(Files.readString(file)));} catch(Exception ignored){}
        result.put("installerAvailable",Files.exists(path("installer-ready")));
        return result;
    }
    JsonNode verify(JsonNode envelope,String format) throws Exception {
        if(!format.equals(envelope.path("format").asText()))throw new IllegalArgumentException();
        String payload=envelope.path("payload").asText(),sig=envelope.path("signature").asText();
        if(payload.length()>1_500_000||sig.length()>128||!payload.matches("[A-Za-z0-9_-]+")||!sig.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
        var keyPath=Path.of(properties.getPublicKeyPath());if(Files.size(keyPath)>4096)throw new IllegalArgumentException();
        String pem=Files.readString(keyPath).replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
        var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var signature=Signature.getInstance("Ed25519");signature.initVerify(key);signature.update((format+"."+payload).getBytes(StandardCharsets.UTF_8));
        if(!signature.verify(Base64.getUrlDecoder().decode(sig)))throw new IllegalArgumentException();
        var claims=json.readTree(Base64.getUrlDecoder().decode(payload));
        if(!properties.getCustomerId().equals(claims.path("customerId").asText())||!platform.overview().instanceId().equals(claims.path("instanceId").asText()))throw new IllegalArgumentException();
        if(Instant.parse(claims.path("issuedAt").asText()).isAfter(clock.instant().plusSeconds(300)))throw new IllegalArgumentException();
        return claims;
    }
    private JsonNode componentVersions() {
        try {var file=path("component-versions.json");if(Files.exists(file)&&Files.size(file)<4096)return json.readTree(Files.readString(file));}catch(Exception ignored){}
        String version=platform.overview().version();return json.valueToTree(Map.of("backend",version,"admin",version,"h5",version));
    }
    private String effectiveVersion() {
        var versions=componentVersions();String installed=null;
        String release=versions.path("release").asText();
        if(release.matches("\\d+\\.\\d+\\.\\d+"))installed=release;
        String planned=latestInstalledRelease(path("installed-plans"),json);
        if(planned!=null&&(installed==null||compare(planned,installed)>0))installed=planned;
        if(installed!=null)return installed;
        // Older installations have no signed plan marker. Their overall version is
        // the lowest installed component so no pending component is hidden.
        String minimum=platform.overview().version();
        for(String component:List.of("backend","admin","h5")){String value=versions.path(component).asText();if(value.matches("\\d+\\.\\d+\\.\\d+")&&compare(value,minimum)<0)minimum=value;}
        return minimum;
    }
    static String latestInstalledRelease(Path directory,ObjectMapper json) {
        String latest=null;
        if(!Files.isDirectory(directory))return null;
        try(var files=Files.list(directory)) {
            for(var file:files.limit(500).toList()) {
                if(!Files.isRegularFile(file)||Files.size(file)>=500000)continue;
                try {String value=json.readTree(Files.readString(file)).path("targetVersion").asText();
                    if(value.matches("\\d+\\.\\d+\\.\\d+")&&(latest==null||compare(value,latest)>0))latest=value;
                }catch(Exception ignored) {}
            }
        }catch(Exception ignored) {}
        return latest;
    }
    JsonNode currentUpdates(JsonNode cached,String currentVersion) {
        if(!cached.isObject())return json.createObjectNode();
        var updates=((com.fasterxml.jackson.databind.node.ObjectNode)cached).deepCopy();
        var applicable=json.createArrayNode();
        for(var release:cached.path("releases"))if(release.path("version").asText().matches("\\d+\\.\\d+\\.\\d+")&&compare(release.path("version").asText(),currentVersion)>0)applicable.add(release);
        updates.put("currentVersion",currentVersion);updates.set("releases",applicable);updates.put("updateAvailable",!applicable.isEmpty());
        return updates;
    }
    private static int compare(String left,String right){String[] a=left.split("\\."),b=right.split("\\.");try{for(int i=0;i<3;i++){int value=Integer.compare(Integer.parseInt(a[i]),Integer.parseInt(b[i]));if(value!=0)return value;}}catch(Exception ignored){}return 0;}
    public synchronized JsonNode project() {
        if(cached==null)status();
        if(cached==null)return json.createObjectNode();
        try{return verify(cached.path("project"),"cangshuo-project-v1");}catch(Exception error){return json.createObjectNode();}
    }
    public boolean updating() { return Files.exists(path("updating")); }
    public boolean domainAllowed(String domain) {
        if(properties.getReportUrl()==null||properties.getReportUrl().isBlank())return true;
        var claims=project();if(claims.isEmpty())return false;
        String host=String.valueOf(domain).toLowerCase(Locale.ROOT).replaceFirst(":\\d+$","").replaceFirst("\\.$","");
        for(var allowed:claims.path("allowedDomains")){String name=allowed.asText();if(name.equals(host)||(name.startsWith("*.")&&host.endsWith(name.substring(1))&&host.length()>name.length()-1))return true;}
        return false;
    }
    public boolean suspended() {
        if(properties.getReportUrl()==null||properties.getReportUrl().isBlank())return false;
        var claims=project();
        if(claims.isEmpty()) return true;
        if(!"ACTIVE".equals(claims.path("status").asText()))return true;
        if("PENDING".equals(claims.path("pausePolicy").asText()))return false;
        try{return !"ACTIVE".equals(claims.path("status").asText())||!clock.instant().isBefore(Instant.parse(claims.path("maintenanceUntil").asText()));}catch(Exception error){return true;}
    }
    public JsonNode refresh() {
        synchronized(this) {if(clock.millis()-lastAttempt<10000)return status();lastAttempt=clock.millis();}
        var payload=platform.reportPayload();if(payload==null)return status();
        try {
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(payload);
            request.put("version",effectiveVersion());
            request.set("updateResult",status().path("job"));
            var response=send(properties.getReportUrl(),request);
            verify(response.path("project"),"cangshuo-project-v1");
            if(response.hasNonNull("license")) platform.importLicense(json.treeToValue(response.path("license"),PlatformLicense.Envelope.class));
            ((com.fasterxml.jackson.databind.node.ObjectNode)response).put("receivedAt",clock.instant().toString());
            atomic(path("authority.json"),json.writeValueAsBytes(response));cached=response;
            return status();
        } catch(InterruptedException error){Thread.currentThread().interrupt();throw new BusinessException(503,"授权中心连接已中断");}
        catch(Exception error){log.warn("授权中心同步失败：{}",error.getClass().getSimpleName());throw new BusinessException(503,"授权中心暂时无法连接，保留上次检查结果");}
    }
    private JsonNode send(String target,JsonNode body)throws Exception {
        if(target==null||target.isBlank())throw new IllegalArgumentException();
        var uri=URI.create(target);if(!Set.of("http","https").contains(uri.getScheme()))throw new IllegalArgumentException();
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build().send(request,HttpResponse.BodyHandlers.ofString());
        if(response.body().length()>2_000_000)throw new IllegalArgumentException();
        var result=json.readTree(response.body());if(response.statusCode()/100!=2)throw new BusinessException(response.statusCode(),result.path("message").asText("授权中心请求失败"));return result;
    }
    public JsonNode install(JsonNode selection) {
        if(!Files.exists(path("installer-ready")))throw new BusinessException(409,"本实例尚未接入更新执行器");
        if(suspended())throw new BusinessException(403,"项目维护期已到期，请先续期");
        if(!planning.compareAndSet(false,true))throw new BusinessException(409,"已有更新计划正在生成");
        try {
            var payload=json.createObjectNode();payload.set("license",json.valueToTree(platform.reportPayload().license()));payload.put("version",effectiveVersion());payload.set("componentVersions",componentVersions());payload.set("selection",selection);
            String target=properties.getReportUrl().replaceFirst("/report$","/update-plan");
            var envelope=send(target,payload);var plan=verify(envelope,"cangshuo-update-v1");
            var pending=path("update-job.json");
            if(Files.exists(pending))throw new BusinessException(409,"已有更新任务正在执行");
            atomic(pending,json.writeValueAsBytes(envelope));
            var result=json.createObjectNode();result.put("id",plan.path("id").asText());result.put("status","QUEUED");atomic(path("update-result.json"),json.writeValueAsBytes(result));return result;
        }catch(BusinessException error){throw error;}catch(InterruptedException error){Thread.currentThread().interrupt();throw new BusinessException(503,"更新请求已中断");}catch(Exception error){throw new BusinessException(503,"更新计划暂不可用，请稍后重试");}finally{planning.set(false);}
    }
    static void atomic(Path file,byte[] data)throws Exception {Files.createDirectories(file.getParent());var temp=Files.createTempFile(file.getParent(),".authority-",".tmp");try{Files.write(temp,data);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}
}
