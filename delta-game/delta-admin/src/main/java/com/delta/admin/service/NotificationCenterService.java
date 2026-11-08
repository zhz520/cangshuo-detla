package com.delta.admin.service;

import com.delta.common.event.BusinessEvent;
import com.delta.common.event.OrderConfirmedEvent;
import com.delta.common.security.service.StaffPermissions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Central recipient routing for official-account and business SMS notifications. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCenterService {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private static final Set<String> ORDER_EVENTS=Set.of(
            "ORDER_PAID","ORDER_ASSIGNED","ORDER_ACCEPTED","ORDER_STARTED","ORDER_COMPLETED",
            "ORDER_CONFIRMED");
    private static final Set<String> REFUND_EVENTS=Set.of(
            "REFUND_REQUESTED","REFUND_CS_REVIEWING","REFUND_CS_REVIEWED","REFUND_ADMIN_APPROVED",
            "REFUND_ADMIN_REJECTED","REFUND_PROCESSING","REFUND_SUCCEEDED","REFUND_FAILED");
    private static final Set<String> COMPLAINT_EVENTS=Set.of(
            "NEW_COMPLAINT","COMPLAINT_APPEALED","COMPLAINT_REFUND_PENDING","COMPLAINT_RESOLVED");
    private static final Set<String> TAILORED_COMPLAINT_EVENTS=Set.of(
            "COMPLAINT_APPEALED","COMPLAINT_REFUND_PENDING","COMPLAINT_RESOLVED");
    private static final Set<String> STAFF_REVIEW_EVENTS=Set.of(
            "PLAYER_APPLICATION_SUBMITTED","PLAYER_APPROVED","PLAYER_REJECTED",
            "MATERIAL_SUBMITTED","MATERIAL_REVIEWED","PLAYER_FROZEN","PLAYER_UNFROZEN");
    private static final Set<String> WITHDRAW_EVENTS=Set.of(
            "WITHDRAW_APPLY","WITHDRAW_COMPLETED","WITHDRAW_REJECTED");
    private final JdbcTemplate jdbc;
    private final OfficialWechatService wechat;
    private final SmsNoticeService sms;
    private final StaffPermissions permissions;

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void onBusinessEvent(BusinessEvent event){
        try{
            dispatch(event.getEventType(),event.getUserType(),event.getUserId(),event.getOrderId(),
                    event.getMessage(),Long.toString(event.getTimestamp()));
        }catch(Exception error){
            log.error("通知中心事件分发失败: {}",event.getEventType(),error);
        }
    }

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void onOrderConfirmed(OrderConfirmedEvent event){
        try{
            String operator=event.getOperatorType();
            dispatch("ORDER_CONFIRMED","SYSTEM",null,event.getOrderId(),
                    "订单已确认完成"+("ADMIN".equals(operator)?"（管理员确认）":"CS".equals(operator)?"（客服确认）":""),
                    Long.toString(event.getTimestamp()));
        }catch(Exception error){
            log.error("订单确认通知分发失败: orderId={}",event.getOrderId(),error);
        }
    }

    private void dispatch(String eventType,String sourceType,Long sourceId,Long orderId,String baseMessage,String eventStamp){
        String group=OfficialWechatService.groupForEvent(eventType);
        if(!isSupported(eventType,group))return;
        Map<String,Recipient> recipients=new LinkedHashMap<>();
        Map<String,Object> order=orderId==null||WITHDRAW_EVENTS.contains(eventType)?null:order(orderId);

        if(sourceId!=null&&sourceType!=null&&!dynamicSubmission(eventType)){
            add(recipients,sourceType,sourceId,null);
        }
        // Complaint publishers emit one tailored event per customer/player. Routing each event to
        // every order party would make the first event suppress the second recipient's message.
        if(order!=null&&!TAILORED_COMPLAINT_EVENTS.contains(eventType)){
            add(recipients,"USER",number(order.get("user_id")),null);
            if(!"ORDER_PAID".equals(eventType)&&!"ORDER_CONFIRMED".equals(eventType))addOrderPlayers(recipients,orderId,order);
        }
        if(ORDER_EVENTS.contains(eventType)&&"ORDER_PAID".equals(eventType))addNewOrderPlayers(recipients,order);
        if(REFUND_EVENTS.contains(eventType)||COMPLAINT_EVENTS.contains(eventType)||STAFF_REVIEW_EVENTS.contains(eventType)
                ||WITHDRAW_EVENTS.contains(eventType)||ORDER_EVENTS.contains(eventType)){
            addStaff(recipients,group);
        }

        String orderText=orderSummary(order,eventType);
        String defaultMessage=baseMessage==null||baseMessage.isBlank()?orderText:baseMessage;
        for(Recipient recipient:recipients.values()){
            try{
                String message=messageFor(recipient,eventType,group,defaultMessage,orderText);
                String scope;
                if(ORDER_EVENTS.contains(eventType)){
                    scope=LocalDate.now(BUSINESS_ZONE).toString();
                }else if(COMPLAINT_EVENTS.contains(eventType)
                        &&("ADMIN".equals(recipient.type)||"CS".equals(recipient.type))){
                    // Paired customer/player complaint events share one staff notice.
                    scope=LocalDate.now(BUSINESS_ZONE).toString();
                }else{
                    // Refund numbers, review notes and tailored complaint text distinguish real repeat events.
                    scope="event:"+eventStamp+"|"+defaultMessage;
                }
                String dedupe=digest(scope+"|"+eventType+"|"+orderId+"|"+recipient.type+"|"+recipient.id);
                wechat.enqueue(eventType,recipient.type,recipient.id,orderId,message,dedupe);
                String phone=recipient.phone==null?sms.phoneFor(recipient.type,recipient.id):recipient.phone;
                if(phone==null)phone="";
                sms.enqueue(eventType,group,recipient.type,recipient.id,orderId,phone,message,digest("sms|"+dedupe));
            }catch(Exception error){
                log.error("通知接收人入队失败: event={}, recipient={}:{}",eventType,recipient.type,recipient.id,error);
            }
        }
    }

    private boolean isSupported(String event,String group){
        return ORDER_EVENTS.contains(event)||REFUND_EVENTS.contains(event)||COMPLAINT_EVENTS.contains(event)
                ||STAFF_REVIEW_EVENTS.contains(event)||WITHDRAW_EVENTS.contains(event);
    }

    private boolean dynamicSubmission(String event){
        return "PLAYER_APPLICATION_SUBMITTED".equals(event)||"MATERIAL_SUBMITTED".equals(event);
    }

    private void addOrderPlayers(Map<String,Recipient> recipients,Long orderId,Map<String,Object> order){
        add(recipients,"PLAYER",number(order.get("player_id")),null);
        for(Map<String,Object> row:jdbc.queryForList(
                "SELECT player_id FROM order_player WHERE order_id=? AND status='ACCEPTED'",orderId)){
            add(recipients,"PLAYER",number(row.get("player_id")),null);
        }
    }

    private void addNewOrderPlayers(Map<String,Recipient> recipients,Map<String,Object> order){
        Long primary=number(order==null?null:order.get("player_id"));
        if(primary!=null){add(recipients,"PLAYER",primary,null);return;}
        Long designated=number(order==null?null:order.get("designated_player_id"));
        if(designated!=null){add(recipients,"PLAYER",designated,null);return;}
        List<Map<String,Object>> online=jdbc.queryForList(
                "SELECT id,phone FROM player WHERE deleted=0 AND status='ACTIVE' AND is_online=1");
        List<Map<String,Object>> targets=online.isEmpty()?
                jdbc.queryForList("SELECT id,phone FROM player WHERE deleted=0 AND status='ACTIVE'"):online;
        String noOnline=online.isEmpty()?"当前暂无打手在线":null;
        for(Map<String,Object> player:targets){
            add(recipients,"PLAYER",number(player.get("id")),player.get("phone")==null?null:String.valueOf(player.get("phone")),noOnline);
        }
    }

    private void addStaff(Map<String,Recipient> recipients,String group){
        for(Map<String,Object> staff:jdbc.queryForList(
                "SELECT a.id,a.role,a.phone,COALESCE(s.online,0) AS shift_online FROM admin a " +
                        "LEFT JOIN staff_shift s ON s.admin_id=a.id WHERE a.status=1 AND a.deleted=0")){
            String role=com.delta.common.security.utils.AdminRolePolicy.effectiveRole(String.valueOf(staff.get("role")));
            if("CS".equals(role)&&((Number)staff.get("shift_online")).intValue()!=1)continue;
            if(!permissionFor(role,number(staff.get("id")),group))continue;
            add(recipients,role,number(staff.get("id")),staff.get("phone")==null?null:String.valueOf(staff.get("phone")));
        }
    }

    private boolean permissionFor(String role,Long id,String group){
        if("ADMIN".equals(role))return true;
        List<String> grants=permissions.forStaff(id,role);
        if("refund_status".equals(group))return grants.contains("order.refund");
        if(grants.contains("*"))return true;
        return switch(group){
            case "new_order"->grants.contains("order.read")||grants.contains("order.manage");
            case "order_status"->grants.contains("order.read")||grants.contains("order.manage");
            case "complaint_status"->grants.contains("order.manage")||grants.contains("order.read");
            case "staff_review"->grants.contains("player.manage")||grants.contains("media.review");
            default->false;
        };
    }

    private String messageFor(Recipient recipient,String event,String group,String base,String orderText){
        if(WITHDRAW_EVENTS.contains(event)&&("ADMIN".equals(recipient.type)||"CS".equals(recipient.type)))
            return "提现流程更新："+base;
        if("PLAYER".equals(recipient.type)&&"ORDER_PAID".equals(event))
            return "新订单待接单："+orderText+(recipient.note==null?"":"；"+recipient.note);
        if("ADMIN".equals(recipient.type)||"CS".equals(recipient.type)){
            return switch(group){
                case "new_order"->"新订单待处理："+orderText;
                case "order_status"->"订单状态更新："+orderText;
                case "refund_status"->"退款流程更新："+base;
                case "complaint_status"->"投诉处理更新："+base;
                case "staff_review"->"人员审核更新："+base;
                default->base;
            };
        }
        return base;
    }

    private String orderSummary(Map<String,Object> order,String event){
        if(order==null)return baseStatus(event);
        Object amount=order.get("amount");
        return "订单"+order.get("order_no")+" "+baseStatus(event)+"，客户："+(order.get("customer_name")==null?"-":order.get("customer_name"))+
                "，打手："+(order.get("player_name")==null?"未指派":order.get("player_name"))+"，商品："+order.get("product_name")+"，金额：¥"+amount;
    }

    private static String baseStatus(String event){return SmsNoticeService.statusText(event);}

    private Map<String,Object> order(Long id){
        List<Map<String,Object>> rows=jdbc.queryForList(
                "SELECT o.id,o.order_no,o.user_id,o.player_id,o.designated_player_id,o.product_name,o.amount,o.status,u.nickname AS customer_name,p.nickname AS player_name FROM `order` o LEFT JOIN `user` u ON u.id=o.user_id LEFT JOIN player p ON p.id=o.player_id WHERE o.id=?",id);
        return rows.isEmpty()?null:rows.get(0);
    }

    private static void add(Map<String,Recipient> recipients,String type,Long id,String phone){add(recipients,type,id,phone,null);}
    private static void add(Map<String,Recipient> recipients,String type,Long id,String phone,String note){
        if(id==null||type==null)return;
        String normalized=type.toUpperCase(Locale.ROOT);
        if("BOTH".equals(normalized))normalized="ADMIN";
        if(!Set.of("USER","GUEST","PLAYER","ADMIN","CS").contains(normalized))return;
        recipients.putIfAbsent(normalized+":"+id,new Recipient(normalized,id,phone,note));
    }
    private static Long number(Object value){return value instanceof Number n?n.longValue():value==null?null:Long.valueOf(String.valueOf(value));}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private record Recipient(String type,Long id,String phone,String note){}
}
