package com.delta.admin.service;

import com.delta.common.sms.service.AliyunSmsService;
import com.delta.system.service.SysConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmsNoticeServiceTest {
    @Test
    void eachRoleResolvesItsOwnSmsTemplateGroup(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        SysConfigService configs=mock(SysConfigService.class);
        AliyunSmsService sms=mock(AliyunSmsService.class);
        for(String role:new String[]{"user","player","admin","cs"}){
            when(configs.getConfigValue("sms.notification.templates."+role,"{}"))
                    .thenReturn("{\"order_status\":{\"templateCode\":\"SMS_"+role.toUpperCase()+"\",\"fields\":{\"orderNo\":\"orderNo\"}}}");
        }
        SmsNoticeService service=new SmsNoticeService(jdbc,configs,sms,new NotificationTemplateDataService(jdbc));

        assertThat(service.templateFor("USER","order_status").getString("templateCode")).isEqualTo("SMS_USER");
        assertThat(service.templateFor("PLAYER","order_status").getString("templateCode")).isEqualTo("SMS_PLAYER");
        assertThat(service.templateFor("ADMIN","order_status").getString("templateCode")).isEqualTo("SMS_ADMIN");
        assertThat(service.templateFor("CS","order_status").getString("templateCode")).isEqualTo("SMS_CS");
    }
}
