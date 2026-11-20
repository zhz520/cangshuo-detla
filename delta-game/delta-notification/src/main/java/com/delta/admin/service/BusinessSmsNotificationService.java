package com.delta.admin.service;

import cn.hutool.core.util.StrUtil;
import com.delta.common.event.BusinessEvent;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.sms.service.SmsGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 业务短信通知（后台自动发送，不走聊天提醒冷却与扣费）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessSmsNotificationService {
    private final CrossModuleMapper crossModuleMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private SmsGatewayClient smsGateway;

    public void sendIfNeeded(BusinessEvent event) {
        if (event == null || !"PLAYER".equals(event.getUserType()) || event.getUserId() == null) {
            return;
        }

        SmsScene scene = SmsScene.fromEventType(event.getEventType());
        if (scene == null) {
            return;
        }

        if (smsGateway == null || !smsGateway.enabled()) {
            log.debug("business sms skipped because sms is disabled, eventType={}", event.getEventType());
            return;
        }

        String phone = crossModuleMapper.selectPlayerPhone(event.getUserId());
        if (StrUtil.isBlank(phone)) {
            log.warn("business sms skipped because player phone is blank, eventType={}, playerId={}",
                    event.getEventType(), event.getUserId());
            return;
        }

        String idempotencyKey = "business:" + event.getEventType() + ":" + event.getUserId() + ":"
                + String.valueOf(event.getOrderId());
        try {
            smsGateway.send("business_player_teammate_invited", phone, Map.of(), idempotencyKey);
        } catch (SmsGatewayClient.SmsGatewayUnknownException unknown) {
            log.warn("business sms awaits provider confirmation, eventType={}, playerId={}",
                    event.getEventType(), event.getUserId());
            return;
        }
        String maskedPhone=phone.length()>=7 ? phone.substring(0,3)+"****"+phone.substring(phone.length()-4) : "***";
        log.info("business sms sent, eventType={}, playerId={}, phone={}",
                event.getEventType(), event.getUserId(), maskedPhone);
    }

    @RequiredArgsConstructor
    private enum SmsScene {
        PLAYER_TEAMMATE_INVITED("TEAMMATE_INVITED", null, null);

        private final String eventType;
        private final String templateConfigKey;
        private final String defaultTemplateCode;

        private static SmsScene fromEventType(String eventType) {
            if (StrUtil.isBlank(eventType)) {
                return null;
            }
            for (SmsScene scene : values()) {
                if (scene.eventType.equalsIgnoreCase(eventType)) {
                    return scene;
                }
            }
            return null;
        }
    }
}
