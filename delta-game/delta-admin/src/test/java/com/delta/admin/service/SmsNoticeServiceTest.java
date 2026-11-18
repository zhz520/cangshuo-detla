package com.delta.admin.service;

import com.delta.common.sms.service.SmsGatewayClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmsNoticeServiceTest {
    @Test void eligibleNoticeUsesAuthorityTemplateAndStableDeliveryKey() {
        var jdbc = mock(JdbcTemplate.class);
        var gateway = mock(SmsGatewayClient.class);
        var facts = mock(NotificationTemplateDataService.class);
        var row = Map.<String,Object>of("id", "record", "delivery_key", "attempt-group", "attempts", 0,
                "user_type", "PLAYER", "event_group", "order_status", "phone", "13800138000");
        when(facts.resolve(row)).thenReturn(Map.of("orderNo", "test-order"));
        when(jdbc.update(contains("SET status='SENDING'"), eq(1), any(), eq("record"))).thenReturn(1);
        new SmsNoticeService(jdbc, gateway, facts).deliverOne(row);
        verify(gateway).send("notice_player_order_status", "13800138000", Map.of("orderNo", "test-order"), "notice:attempt-group:1");
    }
}
