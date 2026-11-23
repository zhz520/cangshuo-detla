package com.delta.pay.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delta.pay.entity.Payment;
import com.delta.pay.config.WxPayConfiguration;
import com.delta.common.redis.service.RedisService;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import com.delta.system.service.SysConfigService;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.service.refund.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/** Durable refund requests and missed payment notifications are retried from committed rows. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WxPaymentRecoveryTask {
    private final PaymentService payments;
    private final WxPayConfiguration configuration;
    private final SysConfigService configs;
    private final RedisService redis;
    @Autowired(required=false) private TenantRegistry tenantRegistry;

    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void recover() {
        TenantScheduler.run("wechat-payment-recovery", tenantRegistry,this::recoverForTenant);
    }

    private void recoverForTenant() {
        var clients=configuration.getClients();if(clients==null || !redis.tryLock("task:wx-payment-recovery",5,TimeUnit.MINUTES)) return;
        try {
            long closeHours=24;
            try { closeHours=Math.max(1,Math.min(168,Long.parseLong(configs.getConfigValue("wx.pay.pending-close-hours","24").trim()))); }
            catch(NumberFormatException ignored) {}
            for(Payment payment:payments.list(new LambdaQueryWrapper<Payment>().eq(Payment::getStatus,"REFUNDING")
                    .in(Payment::getPayMethod,"WECHAT",WechatPayScene.MINIAPP.payMethod(),
                            WechatPayScene.OFFICIAL_ACCOUNT.payMethod(),WechatPayScene.H5.payMethod())
                    .orderByAsc(Payment::getUpdatedAt).last("LIMIT 20"))) {
                try {
                    QueryByOutRefundNoRequest query=new QueryByOutRefundNoRequest();query.setOutRefundNo(payment.getRefundNo());
                    Refund result;
                    try {result=clients.getRefundService().queryByOutRefundNo(query);}
                    catch(ServiceException e) {
                        if(!"RESOURCE_NOT_EXISTS".equals(e.getErrorCode())) throw e;
                        CreateRequest request=new CreateRequest();request.setOutTradeNo(payment.getPaymentNo());request.setOutRefundNo(payment.getRefundNo());request.setReason(payment.getRefundReason());
                        String callback=configs.getConfigValue("wx.pay.refund-notify-url","");
                        if(callback.isBlank()) callback=clients.getConfig().notifyUrl().replaceFirst("/notify$","/refund-notify");
                        request.setNotifyUrl(callback);
                        AmountReq amount=new AmountReq();amount.setTotal((long)com.delta.common.utils.Money.cents(payment.getAmount()));amount.setRefund((long)com.delta.common.utils.Money.cents(payment.getRefundAmount()));amount.setCurrency("CNY");request.setAmount(amount);
                        result=clients.getRefundService().create(request);
                    }
                    payments.acceptRefundResult(result);
                } catch(Exception e){log.error("微信退款待恢复: paymentId={}",payment.getId(),e);}
            }
            for(Payment payment:payments.list(new LambdaQueryWrapper<Payment>().eq(Payment::getStatus,"PAYING")
                    .in(Payment::getPayMethod,"WECHAT",WechatPayScene.MINIAPP.payMethod(),
                            WechatPayScene.OFFICIAL_ACCOUNT.payMethod(),WechatPayScene.H5.payMethod())
                    .lt(Payment::getCreatedAt,java.time.LocalDateTime.now().minusMinutes(1))
                    .orderByAsc(Payment::getUpdatedAt).last("LIMIT 20"))) {
                boolean closeUnpaid=payment.getCreatedAt()!=null
                        && payment.getCreatedAt().isBefore(java.time.LocalDateTime.now().minusHours(closeHours));
                try{payments.reconcilePayment(payment.getId(),closeUnpaid);}catch(Exception e){log.error("微信支付待对账: paymentId={}",payment.getId(),e);}
            }
        } finally {redis.unlock("task:wx-payment-recovery");}
    }
}
