package com.delta.admin.service;

import com.alibaba.fastjson2.JSONObject;
import com.delta.admin.platform.PlatformService;
import com.delta.common.event.BusinessEvent;
import com.delta.common.security.service.StaffPermissions;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OfficialWechatRoleTemplateTest {
    @Mock JdbcTemplate jdbc;
    @Mock SysConfigService configs;
    @Mock StaffPermissions permissions;
    @Mock PlatformService platform;
    @Mock StringRedisTemplate redis;

    private OfficialWechatService service;

    @BeforeEach
    void setUp() {
        service = new OfficialWechatService(jdbc, configs, permissions, platform, redis);
    }

    @Test
    void customerPlayerAdminAndCsResolveDifferentTemplateIds() {
        when(configs.getConfigValue("wechat.official.templates.user", "{}"))
                .thenReturn("{\"new_order\":{\"templateId\":\"user-order\",\"fields\":{\"thing1\":\"message\"}}}");
        when(configs.getConfigValue("wechat.official.templates.player", "{}"))
                .thenReturn("{\"new_order\":{\"templateId\":\"player-order\",\"fields\":{\"thing1\":\"message\"}}}");
        when(configs.getConfigValue("wechat.official.templates.admin", "{}"))
                .thenReturn("{\"new_order\":{\"templateId\":\"admin-order\",\"fields\":{\"thing1\":\"message\"}}}");
        when(configs.getConfigValue("wechat.official.templates.cs", "{}"))
                .thenReturn("{\"new_order\":{\"templateId\":\"cs-order\",\"fields\":{\"thing1\":\"message\"}}}");

        assertThat(service.templateFor("USER", "new_order").getString("templateId")).isEqualTo("user-order");
        assertThat(service.templateFor("PLAYER", "new_order").getString("templateId")).isEqualTo("player-order");
        assertThat(service.templateFor("ADMIN", "new_order").getString("templateId")).isEqualTo("admin-order");
        assertThat(service.templateFor("CS", "new_order").getString("templateId")).isEqualTo("cs-order");
    }

    @Test
    void legacyTemplateStillWorksWhenARoleHasNoDedicatedMapping() {
        when(configs.getConfigValue("wechat.official.templates.user", "{}")).thenReturn("{}");
        when(configs.getConfigValue("wechat.official.template.new_order", "{}"))
                .thenReturn("{\"templateId\":\"legacy-order\",\"fields\":{\"thing1\":\"message\"}}");

        JSONObject template = service.templateFor("USER", "new_order");

        assertThat(template.getString("templateId")).isEqualTo("legacy-order");
    }

    @Test
    void paidOrderQueuesDedicatedNotificationsForUserAdminAndCs() {
        when(platform.featureEnabled(OfficialWechatService.FEATURE_ID)).thenReturn(true);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", 1L, "role", "ADMIN"),
                Map.of("id", 2L, "role", "CS")
        ));
        when(permissions.forStaff(anyLong(), anyString())).thenReturn(List.of("order.read"));
        List<Object[]> queued = new ArrayList<>();
        doAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            queued.add(Arrays.copyOfRange(arguments, 1, arguments.length));
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));

        service.event(new BusinessEvent(this, "ORDER_PAID", "USER", 7L, 100L, "订单支付成功"));

        assertThat(queued).hasSize(3);
        assertThat(queued).extracting(args -> args[2]).containsExactlyInAnyOrder("USER", "ADMIN", "CS");
        assertThat(queued).extracting(args -> args[1]).contains("ORDER_PAID", "NEW_ORDER", "NEW_ORDER");
    }
}
