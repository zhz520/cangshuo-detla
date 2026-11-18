package com.delta.pay.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.delta.pay.entity.Payment;

public interface PaymentService extends IService<Payment> {
    String DEPOSIT_REFUND_CLOSED = "支付渠道已关闭退款，核对后可重新发起";

    Payment createWxPayment(Long orderId, Long userId, WechatPayScene scene, String wxAppId);
    Payment createBalancePayment(Long orderId, Long userId);
    /** 创建余额充值支付单，amount/rechargeBonus 取创建时的套餐快照。 */
    Payment createRechargePayment(String packageId, Long userId, WechatPayScene scene, String wxAppId);
    /** 创建打手入驻押金支付（金额来自 sys_config: player.deposit_amount），返回Payment含paymentNo */
    Payment createPlayerDepositPayment(Long userId, WechatPayScene scene, String wxAppId);
    /** 当前用户按押金策略和打手等级应缴纳的总押金。 */
    java.math.BigDecimal playerDepositRequiredAmount(Long userId);
    /** 已支付成功的打手押金合计。 */
    java.math.BigDecimal playerDepositPaidAmount(Long userId);

    String latestPaidPlayerDepositNo(Long userId);
    /** 校验打手押金支付是否有效（已支付、金额匹配当前配置、归属当前用户） */
    boolean verifyPlayerDepositPayment(String paymentNo, Long userId);
    /** 原路退还一笔已支付打手押金；支付渠道受理后由恢复任务完成。 */
    void refundPlayerDeposit(Long paymentId, Long userId, java.math.BigDecimal amount, String reason);
    void handleWxPayV3Notify(String jsonData, String serial, String nonce, String timestamp, String signature);
    /** Executes a refund only when the durable request already contains an ADMIN approval. */
    void executeApprovedRefund(Long refundRequestId);
    void handleWxRefundV3Notify(String body,String serial,String nonce,String timestamp,String signature);
    void acceptRefundResult(com.wechat.pay.java.service.refund.model.Refund result);
    void reconcilePayment(Long paymentId);
    void reconcilePayment(Long paymentId, boolean closeUnpaid);
    boolean closeWxPayment(Long orderId,Long userId);
}
