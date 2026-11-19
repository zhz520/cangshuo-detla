package com.delta.pay.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.delta.common.enums.OrderStatusEnum;
import com.delta.common.event.BusinessEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.utils.IdGenerator;
import com.delta.order.entity.Order;
import com.delta.order.service.OrderService;
import com.delta.pay.entity.Payment;
import com.delta.pay.mapper.PaymentMapper;
import com.delta.pay.service.PaymentService;
import com.delta.pay.service.TransactionService;
import com.delta.pay.service.WechatPayScene;
import com.delta.pay.config.WxPayConfiguration;
import com.delta.order.service.OrderProgressService;
import com.delta.product.entity.Product;
import com.delta.product.service.ProductService;
import com.delta.system.service.SysConfigService;
import com.delta.system.dto.RechargePackage;
import com.delta.user.entity.Wallet;
import com.delta.user.service.WalletService;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.delta.common.tenant.TenantKeyEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl extends ServiceImpl<PaymentMapper, Payment> implements PaymentService {
    private final TransactionService transactionService;
    private final OrderService orderService;
    private final OrderProgressService orderProgressService;
    private final WalletService walletService;
    private final ProductService productService;
    private final SysConfigService sysConfigService;
    private final WxPayConfiguration wxPayConfiguration;
    private final StringRedisTemplate redisTemplate;
    @Autowired(required = false) private TenantKeyEncoder tenantKeys;
    private final ApplicationEventPublisher eventPublisher;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Payment createWxPayment(Long orderId, Long userId, WechatPayScene scene, String wxAppId) {
        Order order = orderService.getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) throw new BusinessException(404, "订单不存在");
        if (scene == WechatPayScene.MINIAPP && isGuestUser(userId))
            throw new BusinessException(403, "游客订单不能使用小程序支付，请使用网页微信支付");
        if (!OrderStatusEnum.PENDING_PAYMENT.name().equals(order.getStatus()))
            throw new BusinessException("订单状态不允许支付");
        com.delta.common.utils.Money.positive(order.getAmount());
        if (order.getPayDeadline() != null && order.getPayDeadline().isBefore(LocalDateTime.now()))
            throw new BusinessException("订单已超时，请重新下单");

        Payment paidPayment = getOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderId, orderId)
                .eq(Payment::getStatus, "PAID")
                .last("LIMIT 1"));
        if (paidPayment != null) {
            throw new BusinessException(4010, "该订单已支付，请勿重复支付");
        }

        Payment existingPayment = getOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderId, orderId)
                .eq(Payment::getStatus, "PAYING")
                .eq(Payment::getPayMethod, scene.payMethod())
                .eq(Payment::getWxAppId, normalizedAppId(wxAppId)));
        if (existingPayment != null) {
            return existingPayment;
        }

        Payment payment = new Payment();
        payment.setPaymentNo(IdGenerator.nextPaymentNo());
        payment.setOrderId(orderId);
        payment.setUserId(userId);
        payment.setBizType("ORDER");
        payment.setAmount(order.getAmount());
        payment.setPayMethod(scene.payMethod());
        payment.setWxAppId(normalizedAppId(wxAppId));
        payment.setStatus("PAYING");
        save(payment);
        return payment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Payment createPlayerDepositPayment(Long userId, WechatPayScene scene, String wxAppId) {
        lockUser(userId);
        BigDecimal amount = playerDepositRequiredAmount(userId);
        if(amount.signum()<=0) throw new BusinessException("当前无需缴纳打手押金");
        BigDecimal paid=playerDepositPaidAmount(userId);
        if (paid.compareTo(amount)>=0) throw new BusinessException("您已足额缴纳押金，无需重复支付");
        BigDecimal payable=amount.subtract(paid);
        closeOtherPendingPlayerDeposits(userId, scene, wxAppId, payable);
        Payment paying = getOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getUserId, userId)
                .eq(Payment::getBizType, "PLAYER_DEPOSIT")
                .eq(Payment::getStatus, "PAYING")
                .eq(Payment::getAmount, payable)
                .eq(Payment::getPayMethod, scene.payMethod())
                .eq(Payment::getWxAppId, normalizedAppId(wxAppId))
                .last("LIMIT 1"));
        if (paying != null) return paying;
        Payment payment = new Payment();
        payment.setPaymentNo(IdGenerator.nextPaymentNo());
        payment.setOrderId(null);
        payment.setUserId(userId);
        payment.setBizType("PLAYER_DEPOSIT");
        payment.setAmount(payable);
        payment.setPayMethod(scene.payMethod());
        payment.setWxAppId(normalizedAppId(wxAppId));
        payment.setStatus("PAYING");
        save(payment);
        return payment;
    }

    @Override
    public BigDecimal playerDepositRequiredAmount(Long userId) {
        BigDecimal override = jdbc.query(
                "SELECT p.deposit_override_amount FROM `user` u JOIN player p ON p.openid=u.openid AND p.deleted=0 " +
                        "WHERE u.id=? AND u.deleted=0 LIMIT 1",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, userId);
        if (override != null) return override.setScale(2, RoundingMode.HALF_UP);
        String mode=sysConfigService.getConfigValue("player.deposit_mode",
                "true".equalsIgnoreCase(sysConfigService.getConfigValue("player.deposit_required","true"))?"REQUIRED":"NONE").trim().toUpperCase();
        if("NONE".equals(mode))return BigDecimal.ZERO;
        if("REQUIRED".equals(mode))return getPlayerDepositAmount();
        if("BEFORE_WITHDRAW".equals(mode)) {
            var rows=jdbc.queryForList("SELECT COALESCE(l.withdraw_deposit_amount,0) AS amount FROM `user` u JOIN player p ON p.openid=u.openid AND p.deleted=0 LEFT JOIN player_level l ON l.id=p.level AND l.deleted=0 WHERE u.id=? AND u.deleted=0 LIMIT 1",userId);
            return rows.isEmpty()?BigDecimal.ZERO:new BigDecimal(String.valueOf(rows.get(0).get("amount")));
        }
        return getPlayerDepositAmount();
    }

    @Override
    public BigDecimal playerDepositPaidAmount(Long userId) {
        BigDecimal value=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM payment WHERE user_id=? AND biz_type='PLAYER_DEPOSIT' AND status='PAID'",BigDecimal.class,userId);
        return value==null?BigDecimal.ZERO:value;
    }

    @Override
    public String latestPaidPlayerDepositNo(Long userId) {
        var rows = jdbc.queryForList("SELECT payment_no FROM payment WHERE user_id=? AND biz_type='PLAYER_DEPOSIT' " +
                "AND status='PAID' ORDER BY paid_at DESC,id DESC LIMIT 1", String.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Payment createRechargePayment(String packageId, Long userId, WechatPayScene scene, String wxAppId) {
        lockUser(userId);
        RechargePackage rechargePackage = sysConfigService.getRechargePackages(true).stream()
                .filter(item -> packageId != null && packageId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("充值套餐不存在或已下架"));
        com.delta.common.utils.Money.positive(rechargePackage.getAmount());
        if(rechargePackage.getBonus()==null || rechargePackage.getBonus().signum()<0 || rechargePackage.getBonus().compareTo(new BigDecimal("1000000"))>0
                || rechargePackage.getBonus().stripTrailingZeros().scale()>2) throw new BusinessException(400,"充值赠送金额无效");

        Payment existing = getOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getUserId, userId)
                .eq(Payment::getBizType, "RECHARGE")
                .eq(Payment::getStatus, "PAYING")
                .eq(Payment::getAmount, rechargePackage.getAmount())
                .eq(Payment::getRechargeBonus, rechargePackage.getBonus())
                .eq(Payment::getPayMethod, scene.payMethod())
                .eq(Payment::getWxAppId, normalizedAppId(wxAppId))
                .last("LIMIT 1"));
        if (existing != null) return existing;

        Payment payment = new Payment();
        payment.setPaymentNo(IdGenerator.nextPaymentNo());
        payment.setOrderId(null);
        payment.setUserId(userId);
        payment.setBizType("RECHARGE");
        payment.setAmount(rechargePackage.getAmount());
        payment.setRechargeBonus(rechargePackage.getBonus());
        payment.setPayMethod(scene.payMethod());
        payment.setWxAppId(normalizedAppId(wxAppId));
        payment.setStatus("PAYING");
        save(payment);
        return payment;
    }

    @Override
    public boolean verifyPlayerDepositPayment(String paymentNo, Long userId) {
        if (paymentNo == null || paymentNo.isBlank()) return false;
        BigDecimal expectedAmount = playerDepositRequiredAmount(userId);
        Payment payment = getOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPaymentNo, paymentNo));
        if (payment == null) return false;
        if (!"PLAYER_DEPOSIT".equals(payment.getBizType())) return false;
        if (!"PAID".equals(payment.getStatus())) return false;
        if (!userId.equals(payment.getUserId())) return false;
        // Deposit creation charges only the outstanding amount, so a top-up receipt
        // need not equal the entire current requirement.
        return playerDepositPaidAmount(userId).compareTo(expectedAmount) >= 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundPlayerDeposit(Long paymentId, Long userId, BigDecimal amount, String reason) {
        if (paymentId == null || userId == null) throw new BusinessException(400, "押金退款参数不完整");
        com.delta.common.utils.Money.positive(amount);
        Payment payment = lockPayment(paymentId);
        if (!"PLAYER_DEPOSIT".equals(payment.getBizType()) || !userId.equals(payment.getUserId()))
            throw new BusinessException(403, "押金支付记录不属于当前打手");
        if (amount.compareTo(payment.getAmount()) != 0) throw new BusinessException(400, "押金须按原支付单整笔退款");
        if ("REFUNDED".equals(payment.getStatus())) return;
        if ("REFUNDING".equals(payment.getStatus())) {
            if (amount.compareTo(payment.getRefundAmount() == null ? BigDecimal.ZERO : payment.getRefundAmount()) == 0
                    && java.util.Objects.equals(reason, payment.getRefundReason())) return;
            throw new BusinessException(409, "该押金已有退款正在处理");
        }
        if ("REFUND_FAILED".equals(payment.getStatus())) {
            // Only a confirmed CLOSED result permits allocating a new refund number.
            if (jdbc.queryForObject("SELECT COUNT(*) FROM player_deposit_refund WHERE payment_id=? " +
                            "AND status='REFUNDING' AND active_payment_id=? AND channel_refund_no=?", Long.class,
                    paymentId, paymentId, payment.getRefundNo()) != 1)
                throw new BusinessException(409, "原押金退款状态未核实");
            payment.setStatus("PAID");
        } else if (!"PAID".equals(payment.getStatus())) {
            throw new BusinessException(409, "该押金当前不可退款");
        }
        processRefund(payment, amount, reason, false);
        syncDepositRefund(payment, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Payment createBalancePayment(Long orderId, Long userId) {
        Order order = orderService.getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) throw new BusinessException(404, "订单不存在");
        if (isGuestUser(userId))
            throw new BusinessException(403, "游客订单仅支持在线微信支付");
        if (!OrderStatusEnum.PENDING_PAYMENT.name().equals(order.getStatus()))
            throw new BusinessException("订单状态不允许支付");
        if (order.getPayDeadline() != null && order.getPayDeadline().isBefore(LocalDateTime.now()))
            throw new BusinessException("订单已超时，请重新下单");

        com.delta.common.utils.Money.positive(order.getAmount());
        if(count(new LambdaQueryWrapper<Payment>().eq(Payment::getOrderId,orderId).eq(Payment::getStatus,"PAYING")
                .in(Payment::getPayMethod,"WECHAT",WechatPayScene.MINIAPP.payMethod(),
                        WechatPayScene.OFFICIAL_ACCOUNT.payMethod(),WechatPayScene.H5.payMethod()))>0)
            throw new BusinessException(409,"订单已发起微信支付，请关闭该笔微信支付后再使用余额");
        return doBalancePay(orderId, userId, order);
    }

    private boolean isGuestUser(Long userId) {
        Integer guestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE id=? AND deleted=0 AND status=1 AND is_guest=1",
                Integer.class, userId);
        return guestCount != null && guestCount > 0;
    }

    private Payment doBalancePay(Long orderId, Long userId, Order order) {
        Wallet wallet = walletService.getByUserId(userId);
        if (wallet == null) throw new BusinessException("钱包不存在");
        BigDecimal available = wallet.getBalance().subtract(wallet.getFrozenAmount());
        if (available.compareTo(order.getAmount()) < 0) {
            throw new BusinessException("余额不足");
        }

        BigDecimal balanceBefore = wallet.getBalance();
        wallet.setBalance(wallet.getBalance().subtract(order.getAmount()));
        walletService.updateById(wallet);

        Payment payment = new Payment();
        payment.setPaymentNo(IdGenerator.nextPaymentNo());
        payment.setOrderId(orderId);
        payment.setUserId(userId);
        payment.setBizType("ORDER");
        payment.setAmount(order.getAmount());
        payment.setPayMethod("BALANCE");
        payment.setStatus("PAID");
        payment.setPaidAt(LocalDateTime.now());
        save(payment);

        transactionService.record("CONSUMPTION", "USER", userId, order.getAmount().negate(),
                balanceBefore, wallet.getBalance(), orderId, payment.getId(), null, "余额支付");

        String fromStatus = order.getStatus();
        order.setStatus(OrderStatusEnum.PAID.name());
        order.setUpdatedAt(LocalDateTime.now());
        orderService.updateById(order);
        recordProgress(orderId, fromStatus, OrderStatusEnum.PAID.name(), "USER", userId, "余额支付成功");

        incrementSalesCount(order.getProductId());

        // 支付成功，删除Redis超时取消key
        redisTemplate.delete(namespaced("order:pay_timeout:" + orderId));

        eventPublisher.publishEvent(new BusinessEvent(this, "ORDER_PAID",
                "USER", userId, orderId, "订单支付成功，等待打手接单"));

        // 若下单时指定了打手，支付成功后自动指派
        if (order.getDesignatedPlayerId() != null) {
            try {
                orderService.assignOrder(orderId, order.getDesignatedPlayerId());
            } catch (Exception e) {
                log.warn("支付成功后自动指派失败 orderId={} playerId={}", orderId, order.getDesignatedPlayerId(), e);
            }
        }

        return payment;
    }

    @Override
    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void handleWxPayV3Notify(String jsonData, String serial, String nonce, String timestamp, String signature) {
        log.info("处理微信支付V3回调");
        WxPayConfiguration.CachedClients clients = wxPayConfiguration.getClients();
        if (clients == null) {
            throw new BusinessException("微信支付未启用，无法处理支付回调");
        }
        NotificationParser notificationParser = clients.getNotificationParser();
        try {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(serial)
                    .nonce(nonce)
                    .signature(signature)
                    .timestamp(timestamp)
                    .body(jsonData)
                    .build();

            Transaction transaction = notificationParser.parse(requestParam, Transaction.class);

            applyVerifiedTransaction(transaction,clients);
        } catch (Exception e) {
            log.error("处理微信支付V3回调失败", e);
            throw new BusinessException("回调处理失败");
        }
    }

    void applyVerifiedTransaction(Transaction transaction,WxPayConfiguration.CachedClients clients) {
            String outTradeNo = transaction.getOutTradeNo();
            String transactionId = transaction.getTransactionId();
            Transaction.TradeStateEnum tradeState = transaction.getTradeState();

            log.info("V3回调解密: outTradeNo={}, transactionId={}, tradeState={}", outTradeNo, transactionId, tradeState);

            if (tradeState != Transaction.TradeStateEnum.SUCCESS) {
                log.warn("支付未成功, tradeState={}", tradeState);
                return;
            }

            Payment payment = getOne(new LambdaQueryWrapper<Payment>()
                    .eq(Payment::getPaymentNo, outTradeNo));
            if(payment==null) throw new BusinessException("支付单不存在");
            if(payment.getOrderId()!=null) orderService.getById(payment.getOrderId()); else lockUser(payment.getUserId());
            payment=getOne(new LambdaQueryWrapper<Payment>().eq(Payment::getId,payment.getId()).last("FOR UPDATE"));
            var config=clients.getConfig();
            String paymentAppId = payment.getWxAppId();
            if(paymentAppId==null || paymentAppId.isBlank()) paymentAppId = legacyAppId(payment, config);
            if(!isWechatPayment(payment) || paymentAppId==null || paymentAppId.isBlank()
                    || !paymentAppId.equals(transaction.getAppid()) || !config.mchId().equals(transaction.getMchid())
                    || transaction.getAmount()==null || !"CNY".equals(transaction.getAmount().getCurrency())
                    || transaction.getAmount().getTotal()==null || transaction.getAmount().getTotal()!=com.delta.common.utils.Money.cents(payment.getAmount())
                    || transactionId==null || transactionId.isBlank())
                throw new BusinessException("支付通知与本地支付单不一致");
            if("PAID".equals(payment.getStatus()) || ("PLAYER_DEPOSIT".equals(payment.getBizType())
                    && java.util.Set.of("REFUNDING", "REFUNDED", "REFUND_FAILED").contains(payment.getStatus()))) {
                if(!transactionId.equals(payment.getWxTransactionId())) throw new BusinessException("支付交易号不一致");
                return;
            }
            if(!"PAYING".equals(payment.getStatus())) throw new BusinessException("支付单状态不允许入账，请核查订单");
            if(count(new LambdaQueryWrapper<Payment>().eq(Payment::getWxTransactionId,transactionId).ne(Payment::getId,payment.getId()))>0)
                throw new BusinessException("支付交易号已使用");
            if("ORDER".equals(payment.getBizType())) {
                Order local=orderService.getById(payment.getOrderId());
                if(local==null || !local.getUserId().equals(payment.getUserId()) || local.getAmount().compareTo(payment.getAmount())!=0)
                    throw new BusinessException("订单状态或金额不匹配，等待支付对账处理");
                if(java.util.Set.of("CANCELLED","REFUNDING","REFUNDED").contains(local.getStatus()) || count(new LambdaQueryWrapper<Payment>().eq(Payment::getOrderId,payment.getOrderId()).eq(Payment::getStatus,"PAID"))>0) {
                    payment.setStatus("PAID");payment.setWxTransactionId(transactionId);payment.setPaidAt(LocalDateTime.now());updateById(payment);
                    processRefund(payment,payment.getAmount(),"迟到或重复支付自动原路退款",false);return;
                }
                if(local.getPayDeadline()!=null && local.getPayDeadline().isBefore(LocalDateTime.now())) {
                    payment.setStatus("PAID");payment.setWxTransactionId(transactionId);payment.setPaidAt(LocalDateTime.now());updateById(payment);
                    processRefund(payment,payment.getAmount(),"订单已超过支付期限，自动原路退款",false);return;
                }
                if(!OrderStatusEnum.PENDING_PAYMENT.name().equals(local.getStatus())) throw new BusinessException("订单状态异常，等待支付对账处理");
            } else if(!java.util.Set.of("RECHARGE","PLAYER_DEPOSIT").contains(payment.getBizType())) throw new BusinessException("支付业务类型无效");

            if("PLAYER_DEPOSIT".equals(payment.getBizType())) {
                BigDecimal required=playerDepositRequiredAmount(payment.getUserId());
                BigDecimal alreadyPaid=playerDepositPaidAmount(payment.getUserId());
                if(alreadyPaid.add(payment.getAmount()).compareTo(required)>0) {
                    payment.setStatus("PAID");
                    payment.setWxTransactionId(transactionId);
                    payment.setPaidAt(LocalDateTime.now());
                    updateById(payment);
                    processRefund(payment,payment.getAmount(),"押金已足额或超额，自动原路退款",false);
                    log.warn("打手押金超额自动退款: paymentId={}, userId={}, required={}, alreadyPaid={}, amount={}",
                            payment.getId(),payment.getUserId(),required,alreadyPaid,payment.getAmount());
                    return;
                }
            }

            payment.setStatus("PAID");
            payment.setWxTransactionId(transactionId);
            payment.setPaidAt(LocalDateTime.now());
            updateById(payment);

            if ("PLAYER_DEPOSIT".equals(payment.getBizType())) {
                // Keep the legacy player field pointing at the latest confirmed receipt;
                // the authoritative amount is always summed from paid payment rows.
                jdbc.update("UPDATE player p JOIN `user` u ON u.openid=p.openid " +
                                "SET p.deposit_payment_no=? WHERE u.id=? AND p.deleted=0",
                        payment.getPaymentNo(), payment.getUserId());
            }

            if ("RECHARGE".equals(payment.getBizType())) {
                creditRecharge(payment);
                return;
            }

            Order order = payment.getOrderId() != null ? orderService.getById(payment.getOrderId()) : null;
            if (order != null && OrderStatusEnum.PENDING_PAYMENT.name().equals(order.getStatus())) {
                String fromStatus = order.getStatus();
                order.setStatus(OrderStatusEnum.PAID.name());
                order.setUpdatedAt(LocalDateTime.now());
                orderService.updateById(order);
                recordProgress(order.getId(), fromStatus, OrderStatusEnum.PAID.name(),
                        "SYSTEM", null, "微信支付成功");
                incrementSalesCount(order.getProductId());

                // 支付成功，删除Redis超时取消key
                redisTemplate.delete(namespaced("order:pay_timeout:" + order.getId()));

                eventPublisher.publishEvent(new BusinessEvent(this, "ORDER_PAID",
                        "USER", order.getUserId(), order.getId(), "订单支付成功，等待打手接单"));

                // 若下单时指定了打手，支付成功后自动指派
                if (order.getDesignatedPlayerId() != null) {
                    try {
                        orderService.assignOrder(order.getId(), order.getDesignatedPlayerId());
                    } catch (Exception ex) {
                        log.warn("支付成功后自动指派失败 orderId={} playerId={}", order.getId(), order.getDesignatedPlayerId(), ex);
                    }
                }
            }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeApprovedRefund(Long refundRequestId) {
        if(refundRequestId==null) throw new BusinessException(400,"退款申请ID不能为空");
        var requests=jdbc.queryForList("SELECT order_id,payment_id,amount,reason,status,admin_id,admin_decision " +
                "FROM refund_request WHERE id=? FOR UPDATE",refundRequestId);
        if(requests.isEmpty()) throw new BusinessException(404,"退款申请不存在");
        var request=requests.get(0);
        String requestStatus=String.valueOf(request.get("status"));
        if(!java.util.Set.of("ADMIN_APPROVED","PROCESSING").contains(requestStatus)
                || request.get("admin_id")==null || !"APPROVED".equals(String.valueOf(request.get("admin_decision"))))
            throw new BusinessException(403,"退款申请尚未取得管理员批准");
        Object paymentId=request.get("payment_id");
        if(paymentId==null) throw new BusinessException(409,"退款申请没有关联支付记录");
        BigDecimal refundAmount=(BigDecimal)request.get("amount");
        com.delta.common.utils.Money.positive(refundAmount);
        Payment payment=lockPayment(((Number)paymentId).longValue());
        if(payment.getOrderId()==null || !payment.getOrderId().equals(((Number)request.get("order_id")).longValue()))
            throw new BusinessException(409,"退款申请与支付订单不一致");
        if("REFUNDED".equals(payment.getStatus())) return;
        if("REFUNDING".equals(payment.getStatus())) {
            if(refundAmount.compareTo(payment.getRefundAmount()==null?BigDecimal.ZERO:payment.getRefundAmount())==0
                    && String.valueOf(request.get("reason")).equals(payment.getRefundReason())) return;
            throw new BusinessException(409,"支付记录已有其他退款正在处理");
        }
        if(!"PAID".equals(payment.getStatus())) throw new BusinessException(409,"该支付记录不可退款");
        if(refundAmount.compareTo(payment.getAmount())>0) throw new BusinessException("退款金额不可超过支付金额");
        processRefund(payment,refundAmount,String.valueOf(request.get("reason")),true);
    }

    private Payment lockPayment(Long id) {
        Payment candidate=getById(id);
        if(candidate==null) throw new BusinessException("支付记录不存在");
        if(candidate.getOrderId()!=null) orderService.getById(candidate.getOrderId()); else lockUser(candidate.getUserId());
        return getOne(new LambdaQueryWrapper<Payment>().eq(Payment::getId,id).last("FOR UPDATE"));
    }

    private void processRefund(Payment payment, BigDecimal refundAmount, String reason, boolean refreshOrder) {
        com.delta.common.utils.Money.positive(refundAmount);
        payment.setRefundNo(IdGenerator.nextPaymentNo());payment.setRefundAmount(refundAmount);payment.setRefundReason(reason);
        if("BALANCE".equals(payment.getPayMethod())) {
            Wallet wallet=walletService.getByUserId(payment.getUserId());
            if(wallet==null) throw new BusinessException("钱包不存在");
            BigDecimal before=wallet.getBalance();wallet.setBalance(before.add(refundAmount));walletService.updateById(wallet);
            transactionService.record("REFUND","USER",payment.getUserId(),refundAmount,before,wallet.getBalance(),payment.getOrderId(),payment.getId(),null,"退款: "+reason);
            payment.setStatus("REFUNDED");payment.setRefundTime(LocalDateTime.now());
        } else if(isWechatPayment(payment)) {
            // This row is a durable outbox. External calls happen only after the DB transaction commits.
            payment.setStatus("REFUNDING");
        } else throw new BusinessException("支付方式无效");
        updateById(payment);if(refreshOrder) updateRefundOrder(payment);
    }

    private void updateRefundOrder(Payment payment) {
        if(payment.getOrderId()==null) return;
        if(count(new LambdaQueryWrapper<Payment>().eq(Payment::getOrderId,payment.getOrderId()).eq(Payment::getStatus,"PAID").ne(Payment::getId,payment.getId()))>0) return;
        Order order=orderService.getById(payment.getOrderId());if(order==null) return;
        String next="REFUNDED".equals(payment.getStatus())?"REFUNDED":"REFUNDING";
        String previous=order.getStatus();if(next.equals(previous)) return;
        order.setStatus(next);orderService.updateById(order);
        recordProgress(order.getId(),previous,next,"SYSTEM",null,"REFUNDED".equals(next)?"退款成功":"退款处理中");
        if("REFUNDED".equals(next)) eventPublisher.publishEvent(new BusinessEvent(this,"ORDER_REFUNDED","USER",order.getUserId(),order.getId(),"订单已退款"));
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void handleWxRefundV3Notify(String body,String serial,String nonce,String timestamp,String signature) {
        var clients=wxPayConfiguration.getClients();if(clients==null) throw new BusinessException("支付配置不可用");
        var request=new RequestParam.Builder().serialNumber(serial).nonce(nonce).signature(signature).timestamp(timestamp).body(body).build();
        var notification=clients.getNotificationParser().parse(request,com.wechat.pay.java.service.refund.model.RefundNotification.class);
        applyRefundResult(notification.getOutTradeNo(),notification.getOutRefundNo(),notification.getTransactionId(),notification.getRefundStatus(),notification.getAmount());
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void acceptRefundResult(com.wechat.pay.java.service.refund.model.Refund result) {
        applyRefundResult(result.getOutTradeNo(),result.getOutRefundNo(),result.getTransactionId(),result.getStatus(),result.getAmount());
    }

    private void applyRefundResult(String paymentNo,String refundNo,String transactionId,com.wechat.pay.java.service.refund.model.Status status,com.wechat.pay.java.service.refund.model.Amount amount) {
        Payment candidate=getOne(new LambdaQueryWrapper<Payment>().eq(Payment::getPaymentNo,paymentNo));
        if(candidate==null) throw new BusinessException("退款支付单不存在");
        Payment payment=lockPayment(candidate.getId());
        validateRefundNotification(payment,refundNo,transactionId,amount);
        if("REFUNDED".equals(payment.getStatus())) return;
        boolean depositFailed = "PLAYER_DEPOSIT".equals(payment.getBizType()) && "REFUND_FAILED".equals(payment.getStatus());
        if(!"REFUNDING".equals(payment.getStatus()) && !depositFailed) throw new BusinessException("退款状态不允许处理");
        if(depositFailed && status != com.wechat.pay.java.service.refund.model.Status.SUCCESS) return;
        if(status==com.wechat.pay.java.service.refund.model.Status.SUCCESS) {
            payment.setStatus("REFUNDED");payment.setRefundTime(LocalDateTime.now());updateById(payment);updateRefundOrder(payment);
            syncDepositRefund(payment, null);
        } else if(status==com.wechat.pay.java.service.refund.model.Status.CLOSED || status==com.wechat.pay.java.service.refund.model.Status.ABNORMAL) {
            payment.setStatus("REFUND_FAILED");updateById(payment);
            boolean closed = status == com.wechat.pay.java.service.refund.model.Status.CLOSED;
            syncDepositRefund(payment, closed ? DEPOSIT_REFUND_CLOSED : "渠道退款异常，请在商户平台处理原退款单");
            if (!"PLAYER_DEPOSIT".equals(payment.getBizType()))
                eventPublisher.publishEvent(new BusinessEvent(this,"ORDER_REFUND_FAILED","USER",payment.getUserId(),
                        payment.getOrderId(),"微信退款失败或关闭，请维护人员核查后重试"));
            log.error("微信退款需人工核查: paymentId={}, status={}",payment.getId(),status);
        }
    }

    private void syncDepositRefund(Payment payment, String error) {
        if (!"PLAYER_DEPOSIT".equals(payment.getBizType())) return;
        if ("REFUNDING".equals(payment.getStatus())) {
            jdbc.update("UPDATE player_deposit_refund SET status='REFUNDING',channel_refund_no=?," +
                            "last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE payment_id=? AND status IN ('PENDING','REFUNDING')",
                    payment.getRefundNo(), payment.getId());
            return;
        }
        String status = "REFUNDED".equals(payment.getStatus()) ? "REFUNDED" : "FAILED";
        boolean success = "REFUNDED".equals(status);
        int changed = jdbc.update("UPDATE player_deposit_refund SET status=?,active_payment_id=?,channel_refund_no=?," +
                        "last_error=?,completed_at=?,updated_at=CURRENT_TIMESTAMP WHERE payment_id=? AND " +
                        "(status IN ('PENDING','REFUNDING') OR (status='FAILED' AND channel_refund_no=?))",
                status, success ? null : payment.getId(), payment.getRefundNo(), error,
                success ? LocalDateTime.now() : null, payment.getId(), payment.getRefundNo());
        if (changed > 0 && isWechatPayment(payment)) {
            Long playerId = jdbc.queryForObject("SELECT player_id FROM player_deposit_refund WHERE payment_id=? " +
                    "AND channel_refund_no=?", Long.class, payment.getId(), payment.getRefundNo());
            eventPublisher.publishEvent(new BusinessEvent(this, success ? "REFUND_SUCCEEDED" : "REFUND_FAILED",
                    "PLAYER", playerId, null, success ? "押金退款已原路退回" : "押金退款未完成，请查看处理进度"));
        }
    }

    void validateRefundNotification(Payment payment,String refundNo,String transactionId,
                                    com.wechat.pay.java.service.refund.model.Amount amount) {
        boolean detailsMatch=isWechatPayment(payment)
                && java.util.Objects.equals(refundNo,payment.getRefundNo())
                && amount!=null && payment.getAmount()!=null && payment.getRefundAmount()!=null
                && "CNY".equals(amount.getCurrency())
                && Long.valueOf(com.delta.common.utils.Money.cents(payment.getAmount())).equals(amount.getTotal())
                && Long.valueOf(com.delta.common.utils.Money.cents(payment.getRefundAmount())).equals(amount.getRefund());
        if(!detailsMatch) rejectRefundNotification(payment,refundNo,transactionId,amount,"field_mismatch");
        // A completed refund can receive delayed duplicate notifications. Some historical rows
        // predate wx_transaction_id persistence, so a missing stored or incoming id is accepted
        // only when the refund number and both amounts already match the completed local record.
        if("REFUNDED".equals(payment.getStatus())) {
            if(transactionId!=null && !transactionId.isBlank() && payment.getWxTransactionId()!=null
                    && !transactionId.equals(payment.getWxTransactionId()))
                rejectRefundNotification(payment,refundNo,transactionId,amount,"completed_transaction_mismatch");
            log.info("退款重复通知已幂等忽略: paymentId={}, localRefundNo={}",
                    payment.getId(),maskRefundNo(payment.getRefundNo()));
            return;
        }
        if(transactionId==null || !transactionId.equals(payment.getWxTransactionId()))
            rejectRefundNotification(payment,refundNo,transactionId,amount,"inflight_transaction_mismatch");
    }

    private void rejectRefundNotification(Payment payment,String notifyRefundNo,String notifyTransactionId,
                                          com.wechat.pay.java.service.refund.model.Amount notifyAmount,String reason) {
        log.warn("退款通知校验失败: reason={}, paymentId={}, localStatus={}, localRefundNo={}, notifyRefundNo={}, "
                        + "localAmountCents={}, localRefundCents={}, notifyTotalCents={}, notifyRefundCents={}, "
                        + "notifyCurrency={}, notifyTransactionIdPresent={}, storedTransactionIdPresent={}, "
                        + "transactionIdMatches={}",
                reason,payment.getId(),payment.getStatus(),maskRefundNo(payment.getRefundNo()),
                maskRefundNo(notifyRefundNo),centsOrNull(payment.getAmount()),centsOrNull(payment.getRefundAmount()),
                notifyAmount==null?null:notifyAmount.getTotal(),notifyAmount==null?null:notifyAmount.getRefund(),
                notifyAmount==null?null:notifyAmount.getCurrency(),
                notifyTransactionId!=null&&!notifyTransactionId.isBlank(),payment.getWxTransactionId()!=null,
                java.util.Objects.equals(notifyTransactionId,payment.getWxTransactionId()));
        throw new BusinessException("退款通知与本地退款单不一致");
    }

    private static Long centsOrNull(BigDecimal amount) {
        return amount==null?null:Long.valueOf(com.delta.common.utils.Money.cents(amount));
    }

    private static String maskRefundNo(String refundNo) {
        if(refundNo==null||refundNo.isBlank()) return "null";
        if(refundNo.length()<=8) return refundNo.substring(0,Math.min(2,refundNo.length()))+"***";
        return refundNo.substring(0,4)+"***"+refundNo.substring(refundNo.length()-4);
    }

    @Override
    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void reconcilePayment(Long paymentId) {
        reconcilePayment(paymentId,false);
    }

    @Override
    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void reconcilePayment(Long paymentId, boolean closeUnpaid) {
        Payment payment=lockPayment(paymentId);
        if(!"PAYING".equals(payment.getStatus()) || !isWechatPayment(payment)) return;
        var clients=wxPayConfiguration.getClients();if(clients==null) return;
        resolvePendingPayment(payment,clients,closeUnpaid);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public boolean closeWxPayment(Long orderId,Long userId) {
        Order order=orderService.getById(orderId);
        if(order==null || !userId.equals(order.getUserId())) throw new BusinessException(404,"订单不存在");
        var pending=list(new LambdaQueryWrapper<Payment>().eq(Payment::getOrderId,orderId).eq(Payment::getStatus,"PAYING")
                .in(Payment::getPayMethod,"WECHAT",WechatPayScene.MINIAPP.payMethod(),
                        WechatPayScene.OFFICIAL_ACCOUNT.payMethod(),WechatPayScene.H5.payMethod()).last("FOR UPDATE"));
        if(pending.isEmpty()) return true;
        var clients=wxPayConfiguration.getClients();if(clients==null) throw new BusinessException(503,"暂时无法确认微信支付状态，请稍后重试");
        for(Payment payment:pending) {
            PendingPaymentResolution resolution=resolvePendingPayment(payment,clients,true);
            if(resolution==PendingPaymentResolution.PAID) return false;
            if(resolution==PendingPaymentResolution.PROCESSING)
                throw new BusinessException(409,"微信支付正在处理中，请稍后刷新");
        }
        return true;
    }

    private void creditRecharge(Payment payment) {
        Wallet wallet = walletService.getByUserId(payment.getUserId());
        if (wallet == null) {
            walletService.initWallet(payment.getUserId());
            wallet = walletService.getByUserId(payment.getUserId());
        }
        if (wallet == null) throw new BusinessException("钱包不存在");
        BigDecimal bonus = payment.getRechargeBonus() == null ? BigDecimal.ZERO : payment.getRechargeBonus();
        BigDecimal creditAmount = payment.getAmount().add(bonus);
        BigDecimal balanceBefore = wallet.getBalance() == null ? BigDecimal.ZERO : wallet.getBalance();
        wallet.setBalance(balanceBefore.add(creditAmount));
        walletService.updateById(wallet);
        transactionService.record("RECHARGE", "USER", payment.getUserId(), creditAmount,
                balanceBefore, wallet.getBalance(), null, payment.getId(), null,
                "余额充值：充" + payment.getAmount().stripTrailingZeros().toPlainString()
                        + "送" + bonus.stripTrailingZeros().toPlainString());
    }

    private String normalizedAppId(String appId) {
        if(appId==null || appId.isBlank()) throw new BusinessException(400,"微信支付 AppID 未配置");
        return appId.trim();
    }

    private void closeOtherPendingPlayerDeposits(Long userId, WechatPayScene scene, String wxAppId, BigDecimal amount) {
        String targetAppId=normalizedAppId(wxAppId);
        java.util.List<Payment> pending=list(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getUserId,userId)
                .eq(Payment::getBizType,"PLAYER_DEPOSIT")
                .eq(Payment::getStatus,"PAYING")
                .last("FOR UPDATE"));
        java.util.List<Payment> otherScenes=pending.stream().filter(payment ->
                !scene.payMethod().equals(payment.getPayMethod()) || !targetAppId.equals(payment.getWxAppId())
                        || amount.compareTo(payment.getAmount()) != 0)
                .toList();
        if(otherScenes.isEmpty()) return;
        var clients=wxPayConfiguration.getClients();
        if(clients==null) throw new BusinessException(503,"暂时无法确认原押金支付状态，请稍后重试");
        for(Payment payment:otherScenes) {
            PendingPaymentResolution resolution=resolvePendingPayment(payment,clients,true);
            if(resolution==PendingPaymentResolution.PAID)
                throw new BusinessException(409,"押金已支付，请刷新状态");
            if(resolution==PendingPaymentResolution.PROCESSING)
                throw new BusinessException(409,"原押金支付正在处理中，请稍后刷新");
        }
    }

    enum PendingPaymentResolution { PAID, CLOSED, PROCESSING }

    PendingPaymentResolution resolvePendingPayment(Payment payment, WxPayConfiguration.CachedClients clients,
                                                   boolean closeUnpaid) {
        Transaction result;
        try {
            var query=new com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest();
            query.setMchid(clients.getConfig().mchId());
            query.setOutTradeNo(payment.getPaymentNo());
            result=clients.getJsapiService().queryOrderByOutTradeNo(query);
        } catch(com.wechat.pay.java.core.exception.ServiceException error) {
            if(remoteOrderAbsentOrClosed(error)) {
                markPaymentClosed(payment.getId());
                return PendingPaymentResolution.CLOSED;
            }
            throw new BusinessException(503,"暂时无法确认微信支付状态，请稍后重试");
        }
        Transaction.TradeStateEnum state=result==null ? null : result.getTradeState();
        if(state==null) throw new BusinessException(503,"微信支付状态暂不可确认，请稍后重试");
        if(state==Transaction.TradeStateEnum.SUCCESS) {
            applyVerifiedTransaction(result,clients);
            return PendingPaymentResolution.PAID;
        }
        if(state==Transaction.TradeStateEnum.CLOSED || state==Transaction.TradeStateEnum.REVOKED
                || state==Transaction.TradeStateEnum.PAYERROR || state==Transaction.TradeStateEnum.REFUND) {
            markPaymentClosed(payment.getId());
            return PendingPaymentResolution.CLOSED;
        }
        if(state==Transaction.TradeStateEnum.USERPAYING || state==Transaction.TradeStateEnum.ACCEPT)
            return PendingPaymentResolution.PROCESSING;
        if(state!=Transaction.TradeStateEnum.NOTPAY)
            throw new BusinessException(503,"微信支付状态暂不可确认，请稍后重试");
        if(!closeUnpaid) return PendingPaymentResolution.PROCESSING;
        try {
            var close=new com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest();
            close.setMchid(clients.getConfig().mchId());
            close.setOutTradeNo(payment.getPaymentNo());
            clients.getJsapiService().closeOrder(close);
        } catch(com.wechat.pay.java.core.exception.ServiceException error) {
            if(!remoteOrderAbsentOrClosed(error)) {
                if("ORDERPAID".equals(error.getErrorCode()))
                    throw new BusinessException(409,"微信支付可能已完成，请稍后刷新");
                throw new BusinessException(503,"暂时无法关闭微信支付，请稍后重试");
            }
        }
        markPaymentClosed(payment.getId());
        return PendingPaymentResolution.CLOSED;
    }

    private boolean remoteOrderAbsentOrClosed(com.wechat.pay.java.core.exception.ServiceException error) {
        return "ORDER_NOT_EXIST".equals(error.getErrorCode()) || "RESOURCE_NOT_EXISTS".equals(error.getErrorCode())
                || "ORDER_CLOSED".equals(error.getErrorCode());
    }

    private void markPaymentClosed(Long paymentId) {
        Payment update=new Payment();
        update.setId(paymentId);
        update.setStatus("CLOSED");
        updateById(update);
    }

    private boolean isWechatPayment(Payment payment) {
        return "WECHAT".equals(payment.getPayMethod())
                || java.util.Set.of(WechatPayScene.MINIAPP.payMethod(),WechatPayScene.OFFICIAL_ACCOUNT.payMethod(),
                        WechatPayScene.H5.payMethod()).contains(payment.getPayMethod());
    }

    private String legacyAppId(Payment payment, WxPayConfiguration.RuntimeConfig config) {
        if(WechatPayScene.OFFICIAL_ACCOUNT.payMethod().equals(payment.getPayMethod())) {
            return sysConfigService.getConfigValue("wx.pay.official-appid", "").trim();
        }
        return config.appId();
    }

    private void lockUser(Long userId) {
        if(jdbc.queryForList("SELECT id FROM `user` WHERE id=? AND deleted=0 FOR UPDATE",userId).isEmpty())
            throw new BusinessException("用户不存在");
    }

    private void incrementSalesCount(Long productId) {
        if (productId == null) return;
        Product product = productService.getById(productId);
        if (product != null) {
            product.setSalesCount((product.getSalesCount() == null ? 0 : product.getSalesCount()) + 1);
            productService.updateById(product);
        }
    }

    private void recordProgress(Long orderId, String from, String to, String operatorType, Long operatorId, String content) {
        com.delta.order.entity.OrderProgress progress = new com.delta.order.entity.OrderProgress();
        progress.setOrderId(orderId);
        progress.setType("STATUS_CHANGE");
        progress.setFromStatus(from);
        progress.setToStatus(to);
        progress.setOperatorType(operatorType);
        progress.setOperatorId(operatorId);
        progress.setContent(content);
        progress.setCreatedAt(LocalDateTime.now());
        orderProgressService.save(progress);
    }

    private BigDecimal getPlayerDepositAmount() {
        String amountStr = sysConfigService.getConfigValue("player.deposit_amount", "100");
        try {
            return new BigDecimal(amountStr);
        } catch (Exception e) {
            log.warn("打手押金金额配置无效，使用默认值100，config={}", amountStr);
            return new BigDecimal("100");
        }
    }

    private String namespaced(String key) { return tenantKeys == null ? key : tenantKeys.key(key); }
}
