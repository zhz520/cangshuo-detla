package com.delta.notification.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.delta.common.sms.service.SmsGatewayClient;
import com.delta.system.service.SysConfigService;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable business SMS delivery, separate from OTP and chat reminder SMS. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsNoticeService {
    private final JdbcTemplate jdbc;
    private final SmsGatewayClient smsGateway;
    private final NotificationTemplateDataService templateData;
    @Autowired(required=false) private TenantRegistry tenantRegistry;
    @Autowired private SysConfigService configs;

    public String phoneFor(String userType,Long userId){
        if(userId==null)return "";
        try{
            List<String> phones=switch(String.valueOf(userType).toUpperCase(java.util.Locale.ROOT)){
                case "USER","GUEST"->jdbc.queryForList("SELECT phone FROM `user` WHERE id=? AND deleted=0",String.class,userId);
                case "PLAYER"->jdbc.queryForList("SELECT phone FROM player WHERE id=? AND deleted=0",String.class,userId);
                case "ADMIN","CS"->jdbc.queryForList("SELECT phone FROM admin WHERE id=? AND deleted=0",String.class,userId);
                default->List.of();
            };
            return phones.isEmpty()||phones.get(0)==null?"":phones.get(0).trim();
        }catch(Exception ignored){
            return "";
        }
    }

    public void enqueue(String eventType,String eventGroup,String userType,Long userId,Long orderId,
                        String phone,String message,String dedupeKey){
        if (!smsGateway.enabled()) return;
        if(userId==null||dedupeKey==null||dedupeKey.isBlank())return;
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM sms_notice_queue WHERE dedupe_key=?",Integer.class,dedupeKey);
        if(exists!=null&&exists>0)return;
        String safeMessage=message==null?"":message.substring(0,Math.min(1000,message.length()));
        try{
            jdbc.update("INSERT INTO sms_notice_queue(id,dedupe_key,delivery_key,event_type,event_group,user_type,user_id,order_id,phone,message,status,last_error) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),dedupeKey,UUID.randomUUID().toString(),eventType,eventGroup,userType,userId,orderId,
                    phone==null?"":phone.trim(),safeMessage,
                    phone==null||phone.isBlank()?"FAILED":"PENDING",
                    phone==null||phone.isBlank()?"SMS_PHONE_MISSING":null);
        }catch(org.springframework.dao.DuplicateKeyException ignored){
            // A concurrent producer already queued this exact event.
        }
    }

    @Scheduled(fixedDelay=15_000,initialDelay=45_000)
    public void deliver(){
        TenantScheduler.run(tenantRegistry,this::deliverForTenant);
    }

    private synchronized void deliverForTenant(){
        try{
            jdbc.update("UPDATE sms_notice_queue SET status='EXPIRED',last_error='SMS_EXPIRED' WHERE status IN ('PENDING','RETRY') AND created_at<?",
                    LocalDateTime.now().minusDays(1));
            jdbc.update("DELETE FROM sms_notice_queue WHERE status IN ('SENT','FAILED','EXPIRED') AND created_at<?",
                    LocalDateTime.now().minusDays(30));
        }catch(Exception error){
            log.warn("业务短信队列清理失败",error);
        }
        if(smsGateway==null||!smsGateway.enabled())return;
        jdbc.update("UPDATE sms_notice_queue SET status='DELIVERY_UNKNOWN',last_error='SMS_DELIVERY_UNKNOWN' WHERE status='SENDING' AND next_at<CURRENT_TIMESTAMP");
        for(Map<String,Object> row:jdbc.queryForList(
                "SELECT * FROM sms_notice_queue WHERE status IN ('PENDING','RETRY') AND next_at<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 20")){
            deliverOne(row);
        }
    }

    void deliverOne(Map<String,Object> row){
        String id=String.valueOf(row.get("id"));
        int attempts=((Number)row.get("attempts")).intValue()+1;
        if(jdbc.update("UPDATE sms_notice_queue SET status='SENDING',attempts=?,next_at=? WHERE id=? AND status IN ('PENDING','RETRY')",
                attempts,LocalDateTime.now().plusMinutes(5),id)==0)return;
        try{
            String userType=String.valueOf(row.get("user_type"));
            String group=String.valueOf(row.get("event_group"));
            String role=roleKey(userType);
            if(role==null)throw new DeliveryError("SMS_TEMPLATE_NOT_CONFIGURED");
            Map<String,String> values=templateData.resolve(row);
            Map<String,String> params=new LinkedHashMap<>();
            if (smsGateway.legacyEnabled()) {
                JSONObject templates=JSON.parseObject(configs.getConfigValue("sms.notification.templates."+role,"{}"));
                JSONObject template=templates==null?null:templates.getJSONObject(group);
                JSONObject fields=template==null?null:template.getJSONObject("fields");
                String code=template==null?null:template.getString("templateCode");
                if (fields==null || fields.isEmpty() || code==null || code.isBlank()) throw new DeliveryError("SMS_TEMPLATE_NOT_CONFIGURED");
                for (String field : fields.keySet()) {
                    String value=values.get(fields.getString(field));
                    if (value==null) throw new DeliveryError("SMS_TEMPLATE_INVALID");
                    params.put(field,value);
                }
                smsGateway.sendLegacyTemplate(code,String.valueOf(row.get("phone")),params);
                jdbc.update("UPDATE sms_notice_queue SET status='SENT',last_error=NULL,sent_at=CURRENT_TIMESTAMP WHERE id=?",id);
                return;
            }
            for (String key : java.util.Set.of("orderNo","status","orderStatus","time","eventType","productName",
                    "amount","orderAmount","refundAmount","withdrawAmount","payDeadline")) {
                String value = values.get(key);
                if (value != null && !value.isBlank()) params.put(key, value);
            }
            String deliveryKey=java.util.Objects.toString(row.get("delivery_key"), id);
            smsGateway.send("notice_"+role+"_"+group.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]","_"),
                    String.valueOf(row.get("phone")),params,"notice:"+deliveryKey+":"+attempts);
            jdbc.update("UPDATE sms_notice_queue SET status='SENT',last_error=NULL,sent_at=CURRENT_TIMESTAMP WHERE id=?",id);
        }catch(Exception error){
            boolean definitive=error instanceof DeliveryError||error instanceof SmsGatewayClient.SmsGatewayRejectedException;
            String code=error instanceof DeliveryError?error.getMessage():definitive?"SMS_PROVIDER_REJECTED":"SMS_SEND_FAILED";
            String status=!definitive?"DELIVERY_UNKNOWN":attempts>=5?"FAILED":"RETRY";
            jdbc.update("UPDATE sms_notice_queue SET status=?,last_error=?,next_at=? WHERE id=?",
                    status,code,
                    LocalDateTime.now().plusSeconds(Math.min(3600,30L*(1<<attempts))),id);
            log.warn("业务短信发送失败 id={} code={}",id,code);
        }
    }

    private static String roleKey(String type){
        return switch(String.valueOf(type).toUpperCase(java.util.Locale.ROOT)){
            case "USER","GUEST"->"user";
            case "PLAYER"->"player";
            case "ADMIN"->"admin";
            case "CS"->"cs";
            default->null;
        };
    }

    static String statusText(String event){
        return switch(event){
            case "ORDER_PAID","NEW_ORDER"->"新订单";
            case "ORDER_ASSIGNED"->"已指派打手";
            case "ORDER_ACCEPTED"->"打手已接单";
            case "ORDER_STARTED"->"已开始服务";
            case "ORDER_COMPLETED"->"服务已完成";
            case "ORDER_CONFIRMED"->"订单已确认";
            case "ORDER_CANCELLED"->"订单已取消";
            case "ORDER_REFUNDED"->"订单已退款";
            case "REFUND_REQUESTED"->"退款申请已提交";
            case "REFUND_CS_REVIEWING"->"退款客服核验中";
            case "REFUND_CS_REVIEWED"->"退款已初审";
            case "REFUND_ADMIN_APPROVED"->"退款已批准";
            case "REFUND_ADMIN_REJECTED"->"退款已驳回";
            case "REFUND_PROCESSING"->"退款处理中";
            case "REFUND_SUCCEEDED"->"退款成功";
            case "REFUND_FAILED"->"退款失败";
            case "WITHDRAW_APPLY"->"提现申请待处理";
            case "WITHDRAW_COMPLETED"->"提现已完成";
            case "WITHDRAW_REJECTED"->"提现已驳回";
            case "NEW_COMPLAINT"->"新投诉待处理";
            case "COMPLAINT_APPEALED"->"投诉申诉待处理";
            case "COMPLAINT_REFUND_PENDING"->"投诉退款等待管理员终审";
            case "COMPLAINT_RESOLVED"->"投诉已处理";
            case "PLAYER_APPLICATION_SUBMITTED"->"新入驻申请待审核";
            case "PLAYER_APPROVED"->"入驻审核通过";
            case "PLAYER_REJECTED"->"入驻审核驳回";
            case "MATERIAL_SUBMITTED"->"主页素材待审核";
            case "MATERIAL_REVIEWED"->"素材审核完成";
            case "PLAYER_FROZEN"->"打手已冻结";
            case "PLAYER_UNFROZEN"->"打手已解冻";
            default->"状态更新";
        };
    }

    private static class DeliveryError extends RuntimeException{DeliveryError(String code){super(code);}}
}
