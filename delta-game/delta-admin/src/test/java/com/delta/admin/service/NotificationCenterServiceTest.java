package com.delta.admin.service;

import com.delta.common.event.BusinessEvent;
import com.delta.common.security.service.StaffPermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationCenterServiceTest {
    @Mock JdbcTemplate jdbc;
    @Mock OfficialWechatService wechat;
    @Mock SmsNoticeService sms;
    @Mock StaffPermissions permissions;

    private NotificationCenterService service;

    @BeforeEach
    void setUp(){service=new NotificationCenterService(jdbc,wechat,sms,permissions);}

    @Test
    void unassignedPaidOrderQueuesOnDutyStaffAndAllPlayersWhenNobodyIsOnline(){
        Map<String,Object> order=new LinkedHashMap<>();
        order.put("id",100L);order.put("order_no","A100");order.put("user_id",7L);
        order.put("player_id",null);order.put("designated_player_id",null);order.put("product_name","护航");order.put("amount","68.00");order.put("status","PAID");
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("FROM `order` o")),eq(100L)))
                .thenReturn(List.of(order));
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("FROM admin a"))))
                .thenReturn(List.of(
                        Map.of("id",1L,"role","ADMIN","phone","13800000001","shift_online",0),
                        Map.of("id",2L,"role","CS","phone","13800000002","shift_online",1),
                        Map.of("id",3L,"role","CS","phone","13800000003","shift_online",0)));
        when(permissions.forStaff(2L,"CS")).thenReturn(List.of("order.read"));
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("AND is_online=1")))).thenReturn(List.of());
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("status='ACTIVE'")&&!sql.contains("is_online=1"))))
                .thenReturn(List.of(Map.of("id",10L,"phone","13900000010"),Map.of("id",11L,"phone","13900000011")));
        List<String> playerMessages=new ArrayList<>();
        doAnswer(invocation->{
            if("PLAYER".equals(invocation.getArgument(1)))playerMessages.add(invocation.getArgument(4));
            return null;
        }).when(wechat).enqueue(anyString(),anyString(),anyLong(),nullable(Long.class),anyString(),anyString());

        service.onBusinessEvent(new BusinessEvent(this,"ORDER_PAID","USER",7L,100L,"订单支付成功"));

        verify(wechat).enqueue(eq("ORDER_PAID"),eq("USER"),eq(7L),eq(100L),contains("订单支付成功"),anyString());
        verify(wechat).enqueue(eq("ORDER_PAID"),eq("ADMIN"),eq(1L),eq(100L),contains("新订单待处理"),anyString());
        verify(wechat).enqueue(eq("ORDER_PAID"),eq("CS"),eq(2L),eq(100L),contains("新订单待处理"),anyString());
        verify(wechat,never()).enqueue(anyString(),eq("CS"),eq(3L),any(),anyString(),anyString());
        verify(wechat).enqueue(eq("ORDER_PAID"),eq("PLAYER"),eq(10L),eq(100L),contains("当前暂无打手在线"),anyString());
        verify(wechat).enqueue(eq("ORDER_PAID"),eq("PLAYER"),eq(11L),eq(100L),contains("当前暂无打手在线"),anyString());
        assertThat(playerMessages).hasSize(2);
        verify(sms,times(5)).enqueue(anyString(),anyString(),anyString(),anyLong(),nullable(Long.class),anyString(),anyString(),anyString());
    }

    @Test
    void designatedOrderOnlyNotifiesThatPlayerAndOrderStatusUsesRoleTemplateGroup(){
        Map<String,Object> order=new LinkedHashMap<>();
        order.put("id",100L);order.put("order_no","A100");order.put("user_id",7L);
        order.put("player_id",10L);order.put("designated_player_id",10L);order.put("product_name","护航");order.put("amount","68.00");order.put("status","ACCEPTED");
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("FROM `order` o")),eq(100L)))
                .thenReturn(List.of(order));
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("FROM order_player")),eq(100L))).thenReturn(List.of());
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("FROM admin a")))).thenReturn(List.of());

        service.onBusinessEvent(new BusinessEvent(this,"ORDER_ACCEPTED","USER",7L,100L,"打手已接单"));

        verify(wechat).enqueue(eq("ORDER_ACCEPTED"),eq("USER"),eq(7L),eq(100L),anyString(),anyString());
        verify(wechat).enqueue(eq("ORDER_ACCEPTED"),eq("PLAYER"),eq(10L),eq(100L),anyString(),anyString());
        verify(wechat,never()).enqueue(anyString(),eq("PLAYER"),eq(11L),any(),anyString(),anyString());
    }

    @Test
    void complaintResultKeepsTailoredCustomerAndPlayerMessagesSeparate(){
        Map<String,Object> order=new LinkedHashMap<>();
        order.put("id",100L);order.put("order_no","A100");order.put("user_id",7L);
        order.put("player_id",10L);order.put("designated_player_id",10L);order.put("product_name","护航");order.put("amount","68.00");order.put("status","COMPLETED");
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("FROM `order` o")),eq(100L)))
                .thenReturn(List.of(order));
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("FROM admin a")))).thenReturn(List.of());
        Map<String,String> messages=new LinkedHashMap<>();
        doAnswer(invocation->{
            messages.put(invocation.getArgument(1)+":"+invocation.getArgument(2),invocation.getArgument(4));
            return null;
        }).when(wechat).enqueue(anyString(),anyString(),anyLong(),nullable(Long.class),anyString(),anyString());

        service.onBusinessEvent(new BusinessEvent(this,"COMPLAINT_RESOLVED","USER",7L,100L,"投诉已驳回，订单正常结算"));
        service.onBusinessEvent(new BusinessEvent(this,"COMPLAINT_RESOLVED","PLAYER",10L,100L,"订单仲裁结果：需重新提供服务"));

        assertThat(messages).containsEntry("USER:7","投诉已驳回，订单正常结算")
                .containsEntry("PLAYER:10","订单仲裁结果：需重新提供服务");
        assertThat(messages).hasSize(2);
    }

    @Test
    void unpaidCancellationAndLegacyRefundCompletionDoNotCreateChannelSpam(){
        service.onBusinessEvent(new BusinessEvent(this,"ORDER_CANCELLED","USER",7L,100L,"订单已取消"));
        service.onBusinessEvent(new BusinessEvent(this,"ORDER_REFUNDED","USER",7L,100L,"订单已退款"));

        verifyNoInteractions(wechat,sms);
    }

    @Test
    void wildcardStaffPermissionDoesNotReceiveRefundNotifications(){
        Map<String,Object> order=new LinkedHashMap<>();
        order.put("id",100L);order.put("order_no","A100");order.put("user_id",7L);
        order.put("player_id",10L);order.put("designated_player_id",10L);order.put("product_name","护航");
        order.put("amount","68.00");order.put("status","PAID");
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("FROM `order` o")),eq(100L)))
                .thenReturn(List.of(order));
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("FROM order_player")),eq(100L)))
                .thenReturn(List.of());
        when(jdbc.queryForList(argThat(sql->sql!=null&&sql.contains("FROM admin a"))))
                .thenReturn(List.of(Map.of("id",2L,"role","CS","phone","13800000002","shift_online",1)));
        when(permissions.forStaff(2L,"CS")).thenReturn(List.of("*"));

        service.onBusinessEvent(new BusinessEvent(this,"REFUND_REQUESTED","USER",7L,100L,"退款申请已提交"));

        verify(wechat,never()).enqueue(anyString(),eq("CS"),eq(2L),any(),anyString(),anyString());
        verify(sms,never()).enqueue(anyString(),anyString(),eq("CS"),eq(2L),any(),anyString(),anyString(),anyString());
    }
}
