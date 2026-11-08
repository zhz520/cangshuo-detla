package com.delta.admin.service;

import com.alibaba.fastjson2.*;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.service.StaffPermissions;
import com.delta.admin.platform.PlatformService;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.event.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class OfficialWechatService {
 private final JdbcTemplate jdbc;
 private final SysConfigService configs;
 private final StaffPermissions permissions;
 private final PlatformService platform;
 private final org.springframework.data.redis.core.StringRedisTemplate redis;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 private volatile String accessToken="";private volatile long tokenUntil=0;
 public static final String FEATURE_ID="official_wechat_notifications";
 public void enqueue(String event,String type,Long user,Long order,String message,String dedupeKey){
  if(user==null||dedupeKey==null||dedupeKey.isBlank())return;
  Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM wechat_notice_queue WHERE dedupe_key=?",Integer.class,dedupeKey);
  if(exists!=null&&exists>0)return;
  String safe=Objects.toString(message,"");
  try{
   jdbc.update("INSERT INTO wechat_notice_queue(id,dedupe_key,event_type,user_type,user_id,order_id,message,status) VALUES (?,?,?,?,?,?,?,'PENDING')",
     UUID.randomUUID().toString(),dedupeKey,event,type,user,order,safe.substring(0,Math.min(1000,safe.length())));
  }catch(org.springframework.dao.DuplicateKeyException ignored){
   // A concurrent producer already queued this exact event.
  }
 }
 @Scheduled(fixedDelay=15000,initialDelay=30000)
 public synchronized void deliver(){
  try{
   jdbc.update("UPDATE wechat_notice_queue SET status='EXPIRED',last_error='WECHAT_EXPIRED' WHERE status IN ('PENDING','RETRY') AND created_at<?",LocalDateTime.now().minusDays(1));
   jdbc.update("DELETE FROM wechat_notice_queue WHERE status IN ('SENT','FAILED','EXPIRED') AND created_at<?",LocalDateTime.now().minusDays(30));
  }catch(Exception error){log.warn("公众号通知队列清理失败",error);}
  if(!platform.featureEnabled(FEATURE_ID))return;
  if(!"true".equals(configs.getConfigValue("wechat.official.enabled","false")))return;
  jdbc.update("UPDATE wechat_notice_queue SET status='DELIVERY_UNKNOWN',last_error='WECHAT_DELIVERY_UNKNOWN' WHERE status='SENDING' AND next_at<CURRENT_TIMESTAMP");
  for(var row:jdbc.queryForList("SELECT * FROM wechat_notice_queue WHERE status IN ('PENDING','RETRY') AND next_at<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 20")){
   String id=(String)row.get("id");int attempts=((Number)row.get("attempts")).intValue()+1;
   if(jdbc.update("UPDATE wechat_notice_queue SET status='SENDING',attempts=?,next_at=? WHERE id=? AND status IN ('PENDING','RETRY')",attempts,LocalDateTime.now().plusMinutes(5),id)==0)continue;
   try{send(row);jdbc.update("UPDATE wechat_notice_queue SET status='SENT',last_error=NULL WHERE id=?",id);}
   catch(Exception error){
    String code=error instanceof DeliveryError?error.getMessage():"WECHAT_TEMPORARILY_UNAVAILABLE";
    boolean ambiguous=!(error instanceof DeliveryError)||"WECHAT_HTTP_ERROR".equals(code);
    String status=ambiguous?"DELIVERY_UNKNOWN":attempts>=5?"FAILED":"RETRY";
    jdbc.update("UPDATE wechat_notice_queue SET status=?,last_error=?,next_at=? WHERE id=?",status,code,LocalDateTime.now().plusSeconds(Math.min(3600,30L*(1<<attempts))),id);
   }
  }
 }
 private void send(Map<String,Object> row)throws Exception{
  var bindings=jdbc.queryForList("SELECT openid FROM wechat_official_binding WHERE user_type=? AND user_id=?",row.get("user_type"),row.get("user_id"));
  if(bindings.isEmpty())throw new DeliveryError("RECIPIENT_NOT_BOUND");
  String event=(String)row.get("event_type");String group=groupForEvent(event);
  JSONObject template=templateFor((String)row.get("user_type"),event,group);
  String templateId=template.getString("templateId");var fields=template.getJSONObject("fields");if(templateId==null||templateId.isBlank()||fields==null||fields.isEmpty())throw new DeliveryError("TEMPLATE_NOT_CONFIGURED");
  var orders=row.get("order_id")==null?List.<Map<String,Object>>of():jdbc.queryForList("SELECT o.order_no,o.amount,u.nickname AS customer_name,p.nickname AS player_name FROM `order` o LEFT JOIN `user` u ON u.id=o.user_id LEFT JOIN player p ON p.id=o.player_id WHERE o.id=?",row.get("order_id"));
  Map<String,String> values=Map.of("message",Objects.toString(row.get("message"),""),"status",statusText(event),"time",LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),"orderNo",orders.isEmpty()?"-":String.valueOf(orders.get(0).get("order_no")),"amount",amountText(orders.isEmpty()?null:orders.get(0).get("amount")),"customerName",orders.isEmpty()||orders.get(0).get("customer_name")==null?"-":String.valueOf(orders.get(0).get("customer_name")),"playerName",orders.isEmpty()||orders.get(0).get("player_name")==null?"未指派":String.valueOf(orders.get(0).get("player_name")));
  Map<String,Object> data=new LinkedHashMap<>();for(String field:fields.keySet()){if(!field.matches("[A-Za-z_]+[0-9]*"))throw new DeliveryError("TEMPLATE_CONFIG_INVALID");String value=values.get(fields.getString(field));if(value==null)throw new DeliveryError("TEMPLATE_CONFIG_INVALID");data.put(field,Map.of("value",fieldValue(field,value)));}
  Map<String,Object> payload=messagePayload(Objects.toString(bindings.get(0).get("openid"),""),templateId,data,template.getString("url"),template.getJSONObject("miniprogram"),"cangshuo-"+row.get("id"));
  var result=post("https://api.weixin.qq.com/cgi-bin/message/template/send?access_token="+token(),payload);
  if(result.getIntValue("errcode")!=0){int code=result.getIntValue("errcode");if(Set.of(40001,40014,42001).contains(code))tokenUntil=0;throw new DeliveryError("WECHAT_"+code);}
 }
 private synchronized String token()throws Exception{
  if(System.currentTimeMillis()<tokenUntil)return accessToken;
  var result=post("https://api.weixin.qq.com/cgi-bin/stable_token",Map.of("grant_type","client_credential","appid",configs.getConfigValue("wechat.official.app_id",""),"secret",configs.getConfigValue("wechat.official.app_secret","")));
  String value=result.getString("access_token");if(value==null||value.isBlank())throw new DeliveryError("WECHAT_TOKEN_UNAVAILABLE");
  accessToken=value;tokenUntil=System.currentTimeMillis()+Math.max(60,result.getIntValue("expires_in")-120)*1000L;return value;
 }
 public Map<String,Object> binding(String type,Long id){boolean licensed=platform.featureEnabled(FEATURE_ID);return Map.of("enabled",licensed&&"true".equals(configs.getConfigValue("wechat.official.enabled","false")),"licensed",licensed,"bound",jdbc.queryForObject("SELECT COUNT(*) FROM wechat_official_binding WHERE user_type=? AND user_id=?",Long.class,type,id)>0);}
 public String startBinding(String type,Long id,String requestDomain){
  platform.requireFeature(FEATURE_ID,requestDomain);
  if(!"true".equals(configs.getConfigValue("wechat.official.enabled","false")))throw new BusinessException(503,"公众号通知尚未启用");
  String appId=configs.getConfigValue("wechat.official.app_id",""),redirect=configs.getConfigValue("wechat.official.redirect_url","");URI uri;
  try{uri=URI.create(redirect);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getFragment()!=null||uri.getQuery()!=null)throw new IllegalArgumentException();}catch(Exception e){throw new BusinessException(400,"请先配置公众号授权回调页面的HTTPS地址");}
  if(appId.isBlank())throw new BusinessException(400,"请先配置公众号AppID");
  String state=UUID.randomUUID().toString().replace("-","");redis.opsForValue().set("wechat:binding:"+state,JSON.toJSONString(Map.of("type",type,"id",id)),Duration.ofMinutes(5));
  return "https://open.weixin.qq.com/connect/oauth2/authorize?appid="+encode(appId)+"&redirect_uri="+encode(redirect)+"&response_type=code&scope=snsapi_base&state="+state+"#wechat_redirect";
 }
 @Transactional(rollbackFor=Exception.class)
 public void finishBinding(String type,Long id,String code,String state,String requestDomain)throws Exception{
  platform.requireFeature(FEATURE_ID,requestDomain);
  if(state==null||!state.matches("[a-f0-9]{32}")||code==null||code.length()>256)throw new BusinessException(400,"绑定凭据无效");
  String value=redis.opsForValue().getAndDelete("wechat:binding:"+state);if(value==null)throw new BusinessException(400,"绑定凭据已过期，请重新操作");
  JSONObject claim=JSON.parseObject(value);if(!type.equals(claim.getString("type"))||id.longValue()!=claim.getLongValue("id"))throw new BusinessException(403,"绑定身份不匹配");
  String url="https://api.weixin.qq.com/sns/oauth2/access_token?appid="+encode(configs.getConfigValue("wechat.official.app_id",""))+"&secret="+encode(configs.getConfigValue("wechat.official.app_secret",""))+"&code="+encode(code)+"&grant_type=authorization_code";
  var response=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
  JSONObject result=JSON.parseObject(response.body());String openid=result.getString("openid");if(response.statusCode()!=200||openid==null||!openid.matches("[A-Za-z0-9_-]{10,128}"))throw new BusinessException(400,"微信授权未完成，请重新绑定");
  if(jdbc.queryForObject("SELECT COUNT(*) FROM wechat_official_binding WHERE openid=? AND user_type=? AND user_id<>?",Long.class,openid,type,id)>0)throw new BusinessException(409,"该微信已绑定同一身份的其他账号，请先解除原绑定");
  jdbc.update("DELETE FROM wechat_official_binding WHERE user_type=? AND user_id=?",type,id);jdbc.update("INSERT INTO wechat_official_binding(user_type,user_id,openid) VALUES (?,?,?)",type,id,openid);
  jdbc.update("UPDATE wechat_notice_queue SET status='PENDING',attempts=0,next_at=CURRENT_TIMESTAMP WHERE user_type=? AND user_id=? AND status IN ('RETRY','FAILED') AND last_error='RECIPIENT_NOT_BOUND' AND created_at>?",type,id,LocalDateTime.now().minusDays(1));
 }
 private JSONObject post(String url,Object body)throws Exception{var response=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body))).build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200||response.body().length()>100000)throw new DeliveryError("WECHAT_HTTP_ERROR");return JSON.parseObject(response.body());}
 JSONObject templateFor(String userType,String group){return templateFor(userType,null,group);}
 JSONObject templateFor(String userType,String event,String group){
  String role=switch(Objects.toString(userType,"").toUpperCase(java.util.Locale.ROOT)){case "USER","GUEST"->"user";case "PLAYER"->"player";case "ADMIN"->"admin";case "CS"->"cs";default->null;};
  if(role!=null){
   try{
    JSONObject roleTemplates=JSON.parseObject(configs.getConfigValue("wechat.official.templates."+role,"{}"));
    Object node=roleTemplates.get(group);
    if(node instanceof JSONObject template&&!template.isEmpty()){
     if(template.getString("templateId")==null||template.getString("templateId").isBlank()||template.getJSONObject("fields")==null||template.getJSONObject("fields").isEmpty())throw new DeliveryError("TEMPLATE_CONFIG_INVALID");
     return template;
    }
   }catch(DeliveryError error){throw error;}catch(Exception error){throw new DeliveryError("TEMPLATE_CONFIG_INVALID");}
  }
  String legacyGroup=legacyGroupFor(event,group);
  try{return JSON.parseObject(configs.getConfigValue("wechat.official.template."+legacyGroup,"{}"));}
  catch(Exception error){throw new DeliveryError("TEMPLATE_CONFIG_INVALID");}
 }
 static String groupForEvent(String event){return switch(event){
  case "NEW_ORDER","ORDER_PAID"->"new_order";
  case "ORDER_ASSIGNED","ORDER_ACCEPTED","ORDER_STARTED","ORDER_COMPLETED","ORDER_CONFIRMED","ORDER_CANCELLED","ORDER_REFUNDED"->"order_status";
  case "REFUND_REQUESTED","REFUND_CS_REVIEWING","REFUND_CS_REVIEWED","REFUND_ADMIN_APPROVED","REFUND_ADMIN_REJECTED","REFUND_PROCESSING","REFUND_SUCCEEDED","REFUND_FAILED","WITHDRAW_COMPLETED","WITHDRAW_REJECTED"->"refund_status";
  case "NEW_COMPLAINT","COMPLAINT_APPEALED","COMPLAINT_REFUND_PENDING","COMPLAINT_RESOLVED"->"complaint_status";
  case "PLAYER_APPLICATION_SUBMITTED","PLAYER_APPROVED","PLAYER_REJECTED","MATERIAL_SUBMITTED","MATERIAL_REVIEWED","PLAYER_FROZEN","PLAYER_UNFROZEN"->"staff_review";
  default->"order_status";
 };}
 private static String legacyGroupFor(String event,String group){
  if(event==null)return group;
  return switch(event){
   case "ORDER_COMPLETED","ORDER_CONFIRMED"->"order_completed";
   case "MATERIAL_SUBMITTED","MATERIAL_REVIEWED","PLAYER_FROZEN","PLAYER_APPLICATION_SUBMITTED","PLAYER_APPROVED","PLAYER_REJECTED","PLAYER_UNFROZEN"->"material_reviewed";
   case "REFUND_REQUESTED","REFUND_CS_REVIEWING","REFUND_CS_REVIEWED","REFUND_ADMIN_APPROVED","REFUND_ADMIN_REJECTED","REFUND_PROCESSING","REFUND_SUCCEEDED","REFUND_FAILED","ORDER_REFUNDED","WITHDRAW_COMPLETED","WITHDRAW_REJECTED"->"withdraw_reviewed";
   default->"order_accepted";
  };
 }
 private static String statusText(String event){return SmsNoticeService.statusText(event);}
 static int maxFieldLength(String field){if(field.startsWith("thing"))return 20;if(field.startsWith("character_string"))return 32;if(field.startsWith("time"))return 64;if(field.startsWith("amount"))return 20;if(field.startsWith("phone_number"))return 17;if(field.startsWith("car_number"))return 8;if(field.startsWith("const"))return 20;if(field.startsWith("phrase"))return 5;return 200;}
 static String fieldValue(String field,String value){String clean=Objects.toString(value,"").replaceAll("[\\r\\n\\t]+"," ").trim();int max=maxFieldLength(field),length=clean.codePointCount(0,clean.length());return length<=max?clean:clean.substring(0,clean.offsetByCodePoints(0,max));}
 static String amountText(Object value){String text=value==null?"0.00":String.valueOf(value).trim();return text.matches("^[¥￥$€£].*")?text:"￥"+text;}
 static Map<String,Object> messagePayload(String openid,String templateId,Map<String,Object> data,String rawUrl,JSONObject mini,String clientMessageId){
  if(openid==null||openid.isBlank()||templateId==null||templateId.isBlank()||data==null||data.isEmpty()||clientMessageId==null||clientMessageId.length()>128)throw new DeliveryError("TEMPLATE_CONFIG_INVALID");
  Map<String,Object> payload=new LinkedHashMap<>();payload.put("touser",openid);payload.put("template_id",templateId);payload.put("data",data);payload.put("client_msg_id",clientMessageId);
  String templateUrl=templateUrl(rawUrl);if(templateUrl!=null)payload.put("url",templateUrl);
  if(mini!=null&&(!Objects.toString(mini.getString("appid"),"").isBlank()||!Objects.toString(mini.getString("pagepath"),"").isBlank())){
   String appid=Objects.toString(mini.getString("appid"),"").trim(),pagepath=Objects.toString(mini.getString("pagepath"),"").trim();
   if(!appid.matches("[A-Za-z0-9_-]{2,64}")||pagepath.isBlank()||pagepath.length()>200)throw new DeliveryError("TEMPLATE_CONFIG_INVALID");
   payload.put("miniprogram",Map.of("appid",appid,"pagepath",pagepath));
  }
  return payload;
 }
 static String templateUrl(String value){
  if(value==null||value.isBlank())return null;
  String clean=value.trim();if(clean.length()>2000)throw new DeliveryError("TEMPLATE_CONFIG_INVALID");
  try{URI uri=URI.create(clean);String scheme=Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT);if(!Set.of("http","https").contains(scheme)||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();return clean;}
  catch(Exception error){throw new DeliveryError("TEMPLATE_CONFIG_INVALID");}
 }
 private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
 private static class DeliveryError extends RuntimeException{DeliveryError(String code){super(code);}}
}
