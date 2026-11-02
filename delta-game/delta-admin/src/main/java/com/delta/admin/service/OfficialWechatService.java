package com.delta.admin.service;

import com.alibaba.fastjson2.*;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.service.StaffPermissions;
import com.delta.admin.platform.PlatformService;
import com.delta.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class OfficialWechatService {
 private final JdbcTemplate jdbc;
 private final SysConfigService configs;
 private final StaffPermissions permissions;
 private final PlatformService platform;
 private final org.springframework.data.redis.core.StringRedisTemplate redis;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 private volatile String accessToken="";private volatile long tokenUntil=0;
 private static final String FEATURE_ID="official_wechat_notifications";
 private static final Set<String> EVENTS=Set.of("ORDER_PAID","ORDER_ACCEPTED","ORDER_ASSIGNED","ORDER_COMPLETED","MATERIAL_REVIEWED","WITHDRAW_COMPLETED","WITHDRAW_REJECTED","PLAYER_FROZEN");
 @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void event(BusinessEvent event){
  if(!platform.featureEnabled(FEATURE_ID))return;
  if(!EVENTS.contains(event.getEventType()))return;
  if(event.getUserId()!=null)queue(event.getEventType(),event.getUserType(),event.getUserId(),event.getOrderId(),event.getMessage());
  if("ORDER_PAID".equals(event.getEventType()))for(var staff:jdbc.queryForList("SELECT a.id,a.role FROM admin a LEFT JOIN staff_shift s ON s.admin_id=a.id WHERE a.status=1 AND a.deleted=0 AND (UPPER(a.role) IN ('ADMIN','BOTH') OR s.online=1)")){
   Long id=((Number)staff.get("id")).longValue();String role=com.delta.common.security.utils.AdminRolePolicy.effectiveRole(String.valueOf(staff.get("role")));var grants=permissions.forStaff(id,role);
   if(grants.contains("order.read")||grants.contains("*"))queue("NEW_ORDER",role,id,event.getOrderId(),"新订单已支付，请及时处理");
  }
 }
 private void queue(String event,String type,Long user,Long order,String message){jdbc.update("INSERT INTO wechat_notice_queue(id,event_type,user_type,user_id,order_id,message,status) VALUES (?,?,?,?,?,?,'PENDING')",UUID.randomUUID().toString(),event,type,user,order,Objects.toString(message,"").substring(0,Math.min(1000,Objects.toString(message,"").length())));}
 @Scheduled(fixedDelay=15000,initialDelay=30000)
 public synchronized void deliver(){
  if(!platform.featureEnabled(FEATURE_ID))return;
  if(!"true".equals(configs.getConfigValue("wechat.official.enabled","false")))return;
  jdbc.update("UPDATE wechat_notice_queue SET status='EXPIRED' WHERE status IN ('PENDING','RETRY') AND created_at<?",LocalDateTime.now().minusDays(1));
  jdbc.update("UPDATE wechat_notice_queue SET status='RETRY'  WHERE status='SENDING' AND next_at<CURRENT_TIMESTAMP");
  for(var row:jdbc.queryForList("SELECT * FROM wechat_notice_queue WHERE status IN ('PENDING','RETRY') AND next_at<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 20")){
   String id=(String)row.get("id");int attempts=((Number)row.get("attempts")).intValue()+1;
   if(jdbc.update("UPDATE wechat_notice_queue SET status='SENDING',attempts=?,next_at=? WHERE id=? AND status IN ('PENDING','RETRY')",attempts,LocalDateTime.now().plusMinutes(5),id)==0)continue;
   try{send(row);jdbc.update("UPDATE wechat_notice_queue SET status='SENT',last_error=NULL WHERE id=?",id);}
   catch(Exception error){String code=error instanceof DeliveryError?error.getMessage():"WECHAT_TEMPORARILY_UNAVAILABLE";jdbc.update("UPDATE wechat_notice_queue SET status=?,last_error=?,next_at=? WHERE id=?",attempts>=5?"FAILED":"RETRY",code,LocalDateTime.now().plusSeconds(Math.min(3600,30L*(1<<attempts))),id);}
  }
 }
 private void send(Map<String,Object> row)throws Exception{
  var bindings=jdbc.queryForList("SELECT openid FROM wechat_official_binding WHERE user_type=? AND user_id=?",row.get("user_type"),row.get("user_id"));
  if(bindings.isEmpty())throw new DeliveryError("RECIPIENT_NOT_BOUND");
  String event=(String)row.get("event_type");String group=switch(event){case "NEW_ORDER","ORDER_PAID"->"new_order";case "ORDER_ACCEPTED","ORDER_ASSIGNED"->"order_accepted";case "ORDER_COMPLETED"->"order_completed";case "MATERIAL_REVIEWED","PLAYER_FROZEN"->"material_reviewed";default->"withdraw_reviewed";};
  JSONObject template;try{template=JSON.parseObject(configs.getConfigValue("wechat.official.template."+group,"{}"));}catch(Exception e){throw new DeliveryError("TEMPLATE_CONFIG_INVALID");}
  String templateId=template.getString("templateId");var fields=template.getJSONObject("fields");if(templateId==null||templateId.isBlank()||fields==null||fields.isEmpty())throw new DeliveryError("TEMPLATE_NOT_CONFIGURED");
  var orders=row.get("order_id")==null?List.<Map<String,Object>>of():jdbc.queryForList("SELECT order_no,amount FROM `order` WHERE id=?",row.get("order_id"));
  Map<String,String> values=Map.of("message",Objects.toString(row.get("message"),""),"status",switch(event){case "NEW_ORDER","ORDER_PAID"->"新订单";case "ORDER_ACCEPTED"->"已接单";case "ORDER_ASSIGNED"->"已派单";case "ORDER_COMPLETED"->"已完结";case "MATERIAL_REVIEWED"->"审核完成";case "WITHDRAW_COMPLETED"->"提现通过";case "WITHDRAW_REJECTED"->"提现驳回";default->"状态变更";},"time",LocalDateTime.now().toString().replace('T',' '),"orderNo",orders.isEmpty()?"-":String.valueOf(orders.get(0).get("order_no")),"amount",orders.isEmpty()?"0.00":String.valueOf(orders.get(0).get("amount")));
  Map<String,Object> data=new LinkedHashMap<>();for(String field:fields.keySet()){if(!field.matches("[A-Za-z_]+[0-9]*"))throw new DeliveryError("TEMPLATE_CONFIG_INVALID");String value=values.get(fields.getString(field));if(value==null)throw new DeliveryError("TEMPLATE_CONFIG_INVALID");int max=field.startsWith("thing")?20:field.startsWith("phrase")?5:field.startsWith("character_string")?32:field.startsWith("time")?19:200;data.put(field,Map.of("value",value.substring(0,Math.min(max,value.length()))));}
  var result=post("https://api.weixin.qq.com/cgi-bin/message/template/send?access_token="+token(),Map.of("touser",bindings.get(0).get("openid"),"template_id",templateId,"data",data));
  if(result.getIntValue("errcode")!=0){int code=result.getIntValue("errcode");if(Set.of(40001,40014,42001).contains(code))tokenUntil=0;throw new DeliveryError("WECHAT_"+code);}
 }
 private synchronized String token()throws Exception{
  if(System.currentTimeMillis()<tokenUntil)return accessToken;
  var result=post("https://api.weixin.qq.com/cgi-bin/stable_token",Map.of("grant_type","client_credential","appid",configs.getConfigValue("wechat.official.app_id",""),"secret",configs.getConfigValue("wechat.official.app_secret","")));
  String value=result.getString("access_token");if(value==null||value.isBlank())throw new DeliveryError("WECHAT_TOKEN_UNAVAILABLE");
  accessToken=value;tokenUntil=System.currentTimeMillis()+Math.max(60,result.getIntValue("expires_in")-120)*1000L;return value;
 }
 public Map<String,Object> binding(String type,Long id){boolean licensed=platform.featureEnabled(FEATURE_ID);return Map.of("enabled",licensed&&"true".equals(configs.getConfigValue("wechat.official.enabled","false")),"licensed",licensed,"bound",jdbc.queryForObject("SELECT COUNT(*) FROM wechat_official_binding WHERE user_type=? AND user_id=?",Long.class,type,id)>0);}
 public String startBinding(String type,Long id){
  platform.requireFeature(FEATURE_ID);
  if(!"true".equals(configs.getConfigValue("wechat.official.enabled","false")))throw new BusinessException(503,"公众号通知尚未启用");
  String appId=configs.getConfigValue("wechat.official.app_id",""),redirect=configs.getConfigValue("wechat.official.redirect_url","");URI uri;
  try{uri=URI.create(redirect);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getFragment()!=null||uri.getQuery()!=null)throw new IllegalArgumentException();}catch(Exception e){throw new BusinessException(400,"请先配置公众号授权回调页面的HTTPS地址");}
  if(appId.isBlank())throw new BusinessException(400,"请先配置公众号AppID");
  String state=UUID.randomUUID().toString().replace("-","");redis.opsForValue().set("wechat:binding:"+state,JSON.toJSONString(Map.of("type",type,"id",id)),Duration.ofMinutes(5));
  return "https://open.weixin.qq.com/connect/oauth2/authorize?appid="+encode(appId)+"&redirect_uri="+encode(redirect)+"&response_type=code&scope=snsapi_base&state="+state+"#wechat_redirect";
 }
 @Transactional(rollbackFor=Exception.class)
 public void finishBinding(String type,Long id,String code,String state)throws Exception{
  platform.requireFeature(FEATURE_ID);
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
 private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
 private static class DeliveryError extends Exception{DeliveryError(String code){super(code);}}
}
