package com.delta.pay.refund;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.event.BusinessEvent;
import com.delta.common.event.OrderConfirmedEvent;
import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.utils.IdGenerator;
import com.delta.common.utils.Money;
import com.delta.pay.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Two-stage order refund workflow: customer/CS preparation, then ADMIN final approval. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRefundWorkflowService {
    private static final Set<String> REFUNDABLE_ORDER_STATUSES = Set.of(
            "PAID", "ASSIGNED", "ACCEPTED", "WAITING_TEAMMATE", "IN_PROGRESS",
            "COMPLETED", "CONFIRMED", "REVIEWED", "DISPUTED", "ARBITRATED");
    private static final Set<String> STAFF_TYPES = Set.of("ADMIN", "CS");

    private final JdbcTemplate jdbc;
    private final PaymentService payments;
    private final ApplicationEventPublisher events;
    private final CrossModuleMapper crossModuleMapper;

    @Transactional(rollbackFor = Exception.class)
    public RefundRequest requestByCustomer(Long orderId, Long actorId, String actorType, RefundCreateRequest input) {
        return create(orderId, actorType, actorId, input, "CUSTOMER", null, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public RefundRequest createByStaff(Long orderId, Long actorId, String actorType, RefundCreateRequest input) {
        return create(orderId, actorType, actorId, input, "STAFF", null, false);
    }

    /** Entry point used by legacy order cancellation so it can never bypass approval again. */
    @Transactional(rollbackFor = Exception.class)
    public RefundRequest requestFromCancellation(Long orderId, Long actorId, String actorType, String reason) {
        RefundCreateRequest input = new RefundCreateRequest();
        input.setReason(reason);
        return create(orderId, actorType, actorId, input, "CANCELLATION", null, false);
    }

    /** Complaint arbitration may prepare and pre-review a refund, but cannot execute it. */
    @Transactional(rollbackFor = Exception.class)
    public RefundRequest createReviewedForComplaint(Long orderId, Long csId, BigDecimal amount,
                                                     String reason, Long complaintId) {
        RefundCreateRequest input = new RefundCreateRequest();
        input.setAmount(amount);
        input.setReason(reason);
        input.setClientRequestId("complaint-" + complaintId);
        RefundRequest request = create(orderId, "CS", csId, input, "COMPLAINT", complaintId, false);
        if (RefundStatus.REQUESTED.equals(request.getStatus())) {
            RefundReviewRequest review = new RefundReviewRequest();
            review.setRecommendation("APPROVE");
            review.setNote("投诉仲裁建议退款，等待管理员终审");
            request = submitPreliminaryReview(request.getId(), csId, "CS", review);
        }
        return request;
    }

    private RefundRequest create(Long orderId, String actorType, Long actorId, RefundCreateRequest input,
                                 String sourceType, Long sourceId, boolean requireOwner) {
        if (orderId == null || actorId == null) throw new BusinessException(400, "订单或操作人不能为空");
        String type = normalizeActorType(actorType);
        if (!Set.of("USER", "GUEST", "ADMIN", "CS").contains(type))
            throw new BusinessException(403, "当前角色不能申请退款");
        if (input == null) throw new BusinessException(400, "退款申请不能为空");

        List<Map<String, Object>> orderRows = jdbc.queryForList(
                "SELECT id,order_no,user_id,amount,status FROM `order` WHERE id=? FOR UPDATE", orderId);
        if (orderRows.isEmpty()) throw new BusinessException(404, "订单不存在");
        Map<String, Object> order = orderRows.get(0);
        long userId = number(order.get("user_id"));
        if (requireOwner) requireOrderOwner(userId, actorId, type);

        String orderStatus = String.valueOf(order.get("status"));
        if (!REFUNDABLE_ORDER_STATUSES.contains(orderStatus))
            throw new BusinessException(409, "当前订单状态不允许申请退款");
        BigDecimal orderAmountPreview = money(order.get("amount"));
        BigDecimal requestedAmount = input.getAmount() == null
                ? normalizeAmount(orderAmountPreview) : normalizeAmount(input.getAmount());
        String requestedReason = normalizeReason(input.getReason());

        String clientRequestId = normalizeClientRequestId(input.getClientRequestId());
        if (clientRequestId != null) {
            RefundRequest repeated = findRequestByClientKey(type, actorId, clientRequestId);
            if (repeated != null) {
                assertSameRequest(repeated, orderId, requestedAmount, requestedReason);
                return repeated;
            }
        }

        RefundRequest active = findActive(orderId);
        if (active != null) return active;

        List<Map<String, Object>> paymentRows = jdbc.queryForList(
                "SELECT id,amount,pay_method,status FROM payment WHERE order_id=? AND biz_type='ORDER' " +
                        "ORDER BY id FOR UPDATE", orderId);
        if (paymentRows.size() != 1)
            throw new BusinessException(409, paymentRows.isEmpty() ? "订单没有可退款支付记录" : "订单存在多笔支付记录，请人工核账");
        Map<String, Object> payment = paymentRows.get(0);
        if (!"PAID".equals(String.valueOf(payment.get("status"))))
            throw new BusinessException(409, "支付记录不是可退款状态");

        BigDecimal orderAmount = money(order.get("amount"));
        BigDecimal paymentAmount = money(payment.get("amount"));
        BigDecimal amount = requestedAmount;
        Money.positive(amount);
        if (amount.compareTo(orderAmount) > 0) throw new BusinessException(400, "退款金额不能超过订单金额");
        if (amount.compareTo(paymentAmount) > 0) throw new BusinessException(400, "退款金额不能超过实付金额");
        if (input.getAmount() != null && amount.compareTo(paymentAmount) < 0)
            throw new BusinessException(409, "暂不支持部分退款，请按实付金额申请整单退款");
        String reason = requestedReason;

        LocalDateTime now = LocalDateTime.now();
        RefundRequest request = new RefundRequest();
        request.setRefundNo(IdGenerator.nextRefundNo());
        request.setOrderId(orderId);
        request.setOrderNo(String.valueOf(order.get("order_no")));
        request.setUserId(userId);
        request.setPaymentId(number(payment.get("id")));
        request.setPaymentMethod(String.valueOf(payment.get("pay_method")));
        request.setAmount(amount);
        request.setReason(reason);
        request.setStatus(RefundStatus.REQUESTED);
        request.setOrderStatusBefore(orderStatus);
        request.setRequesterType(type);
        request.setRequesterId(actorId);
        request.setClientRequestId(clientRequestId);
        request.setSourceType(sourceType);
        request.setSourceId(sourceId);
        request.setSubmittedAt(now);
        try {
            jdbc.update("INSERT INTO refund_request(refund_no,order_id,order_no,user_id,payment_id,payment_method," +
                            "amount,reason,status,order_status_before,requester_type,requester_id,client_request_id," +
                            "source_type,source_id,active_order_id,submitted_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    request.getRefundNo(), request.getOrderId(), request.getOrderNo(), request.getUserId(),
                    request.getPaymentId(), request.getPaymentMethod(), request.getAmount(), request.getReason(),
                    request.getStatus(), request.getOrderStatusBefore(), request.getRequesterType(),
                    request.getRequesterId(), request.getClientRequestId(), request.getSourceType(),
                    request.getSourceId(), request.getOrderId(), request.getSubmittedAt());
        } catch (DuplicateKeyException duplicate) {
            // The order row lock normally serializes this path. This is a final guard for replicas.
            RefundRequest existing = findActive(orderId);
            if (existing != null) return existing;
            throw new BusinessException(409, "退款申请正在处理，请刷新后重试");
        }
        request = getRequired(findActive(orderId).getId());
        audit(request, "SUBMIT", null, RefundStatus.REQUESTED, type, actorId, reason);
        publish(request, RefundEvents.REQUESTED, "退款申请已提交，等待客服初审");
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public RefundRequest startReview(Long id, Long operatorId, String operatorType) {
        requireStaff(operatorType);
        RefundRequest request = lockForUpdate(id);
        if (RefundStatus.CS_REVIEWING.equals(request.getStatus())) return request;
        if (RefundStatus.CS_REVIEWED.equals(request.getStatus())) return request;
        requireStatus(request, Set.of(RefundStatus.REQUESTED), "只有待审核退款申请可以开始初审");
        String before = request.getStatus();
        jdbc.update("UPDATE refund_request SET status=?,cs_reviewer_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=?",
                RefundStatus.CS_REVIEWING, operatorId, id, before);
        audit(request, "CS_START_REVIEW", before, RefundStatus.CS_REVIEWING,
                normalizeActorType(operatorType), operatorId, "客服开始核验退款申请");
        request = getRequired(id);
        publish(request, RefundEvents.CS_REVIEWING, "客服正在核验退款申请");
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public RefundRequest submitPreliminaryReview(Long id, Long operatorId, String operatorType,
                                                  RefundReviewRequest review) {
        requireStaff(operatorType);
        if (review == null) throw new BusinessException(400, "初审结果不能为空");
        String recommendation = normalizeRecommendation(review.getRecommendation());
        String note = normalizeOptionalNote(review.getNote(), "初审说明");
        if ("REJECT".equals(recommendation) && (note == null || note.length() < 2))
            throw new BusinessException(400, "建议驳回时必须填写至少2字初审说明");

        RefundRequest request = lockForUpdate(id);
        if (RefundStatus.CS_REVIEWED.equals(request.getStatus())) {
            if (recommendation.equals(request.getCsRecommendation())) return request;
            throw new BusinessException(409, "该退款申请已经提交初审");
        }
        requireStatus(request, Set.of(RefundStatus.REQUESTED, RefundStatus.CS_REVIEWING),
                "当前状态不能提交初审");
        String before = request.getStatus();
        jdbc.update("UPDATE refund_request SET status=?,cs_reviewer_id=?,cs_recommendation=?,cs_note=?," +
                        "cs_reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=?",
                RefundStatus.CS_REVIEWED, operatorId, recommendation, note, id, before);
        audit(request, "CS_SUBMIT_REVIEW", before, RefundStatus.CS_REVIEWED,
                normalizeActorType(operatorType), operatorId,
                note == null ? "初审建议：" + recommendation : "初审建议：" + recommendation + "；" + note);
        request = getRequired(id);
        publish(request, RefundEvents.CS_REVIEWED, "客服初审已完成，等待管理员终审");
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public RefundRequest approve(Long id, Long adminId, String operatorType, RefundDecisionRequest decision) {
        requireAdmin(operatorType);
        String note = normalizeOptionalNote(decision == null ? null : decision.getNote(), "终审说明");
        RefundRequest request = lockForUpdate(id);
        if (Set.of(RefundStatus.PROCESSING, RefundStatus.REFUNDED).contains(request.getStatus())
                && "APPROVED".equals(request.getAdminDecision())) return request;
        if (RefundStatus.ADMIN_APPROVED.equals(request.getStatus())) {
            return executeApproved(request, adminId, note);
        }
        requireStatus(request, Set.of(RefundStatus.CS_REVIEWED), "退款申请必须完成客服初审后才能终审");
        String before = request.getStatus();
        jdbc.update("UPDATE refund_request SET status=?,admin_id=?,admin_decision='APPROVED',admin_note=?," +
                        "admin_decided_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=?",
                RefundStatus.ADMIN_APPROVED, adminId, note, id, before);
        audit(request, "ADMIN_APPROVE", before, RefundStatus.ADMIN_APPROVED, "ADMIN", adminId, note);
        request = getRequired(id);
        publish(request, RefundEvents.ADMIN_APPROVED, "管理员终审通过，准备原路退款");
        return executeApproved(request, adminId, note);
    }

    private RefundRequest executeApproved(RefundRequest request, Long adminId, String note) {
        String before = request.getStatus();
        assertPaymentRefundable(request);
        jdbc.update("UPDATE refund_request SET status=?,processing_at=COALESCE(processing_at,CURRENT_TIMESTAMP)," +
                        "last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('ADMIN_APPROVED','PROCESSING')",
                RefundStatus.PROCESSING, request.getId());
        audit(request, "PROCESSING", before, RefundStatus.PROCESSING, "ADMIN", adminId, note);
        request = getRequired(request.getId());
        publish(request, RefundEvents.PROCESSING, "退款已进入支付渠道处理中");

        payments.executeApprovedRefund(request.getId());
        List<Map<String, Object>> paymentRows = jdbc.queryForList(
                "SELECT refund_no,status FROM payment WHERE id=?", request.getPaymentId());
        if (!paymentRows.isEmpty()) {
            Object refundNo = paymentRows.get(0).get("refund_no");
            jdbc.update("UPDATE refund_request SET channel_refund_no=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    refundNo == null ? null : String.valueOf(refundNo), request.getId());
        }
        request = getRequired(request.getId());
        if (RefundStatus.REFUNDED.equals(request.getStatus())) {
            return request;
        }
        Object paymentStatus = paymentRows.isEmpty() ? null : paymentRows.get(0).get("status");
        if ("REFUND_FAILED".equals(String.valueOf(paymentStatus))) {
            markPaymentFailed(request.getOrderId(), request.getPaymentId(), "支付渠道退款失败，请管理员重试或核查");
        }
        return getRequired(request.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public RefundRequest reject(Long id, Long adminId, String operatorType, RefundDecisionRequest decision) {
        requireAdmin(operatorType);
        String note = normalizeRequiredNote(decision == null ? null : decision.getNote(), "管理员驳回退款必须填写说明");
        RefundRequest request = lockForUpdate(id);
        if (RefundStatus.ADMIN_REJECTED.equals(request.getStatus())) return request;
        if (Set.of(RefundStatus.PROCESSING, RefundStatus.REFUNDED).contains(request.getStatus()))
            throw new BusinessException(409, "退款已进入渠道处理，不能直接驳回");
        if (!RefundStatus.ACTIVE.contains(request.getStatus()))
            throw new BusinessException(409, "当前退款申请已经结束");
        String before = request.getStatus();
        jdbc.update("UPDATE refund_request SET status=?,admin_id=?,admin_decision='REJECTED',admin_note=?," +
                        "admin_decided_at=CURRENT_TIMESTAMP,active_order_id=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=?",
                RefundStatus.ADMIN_REJECTED, adminId, note, id, before);
        if (!closeRejectedComplaint(request, note)) restoreOrderStatusIfUnchanged(request);
        audit(request, "ADMIN_REJECT", before, RefundStatus.ADMIN_REJECTED, "ADMIN", adminId, note);
        request = getRequired(id);
        publish(request, RefundEvents.ADMIN_REJECTED, "管理员驳回退款申请：" + note);
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public RefundRequest retry(Long id, Long adminId, String operatorType) {
        requireAdmin(operatorType);
        RefundRequest request = lockForUpdate(id);
        requireStatus(request, Set.of(RefundStatus.FAILED), "只有失败退款可以重试");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM payment WHERE id=? FOR UPDATE", request.getPaymentId());
        if (rows.isEmpty()) throw new BusinessException(409, "原支付记录不存在");
        String before = request.getStatus();
        String paymentStatus = String.valueOf(rows.get(0).get("status"));
        jdbc.update("UPDATE refund_request SET status=?,last_error=NULL,processing_at=CURRENT_TIMESTAMP," +
                        "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=?",
                RefundStatus.PROCESSING, id, before);
        if ("REFUND_FAILED".equals(paymentStatus)) {
            jdbc.update("UPDATE payment SET status='REFUNDING',updated_at=CURRENT_TIMESTAMP " +
                    "WHERE id=? AND status='REFUND_FAILED' AND refund_no IS NOT NULL", request.getPaymentId());
        } else if ("PAID".equals(paymentStatus)) {
            payments.executeApprovedRefund(id);
        } else if (!"REFUNDING".equals(paymentStatus)) {
            throw new BusinessException(409, "支付记录状态不允许重试退款");
        }
        audit(request, "ADMIN_RETRY", before, RefundStatus.PROCESSING, "ADMIN", adminId,
                "管理员重试已批准退款");
        request = getRequired(id);
        publish(request, RefundEvents.PROCESSING, "退款重试已提交支付渠道");
        return request;
    }

    public RefundRequest getRequired(Long id) {
        List<RefundRequest> rows = jdbc.query("SELECT * FROM refund_request WHERE id=?", this::map, id);
        if (rows.isEmpty()) throw new BusinessException(404, "退款申请不存在");
        return rows.get(0);
    }

    public List<Map<String, Object>> auditTrail(Long id) {
        getRequired(id);
        return jdbc.queryForList("SELECT id,action,from_status,to_status,operator_type,operator_id,note,created_at " +
                "FROM refund_request_audit WHERE refund_request_id=? ORDER BY id", id);
    }

    public List<RefundRequest> historyForCustomer(Long orderId, Long actorId, String actorType) {
        List<Map<String, Object>> orderRows = jdbc.queryForList(
                "SELECT user_id FROM `order` WHERE id=?", orderId);
        if (orderRows.isEmpty()) throw new BusinessException(404, "订单不存在");
        requireOrderOwner(number(orderRows.get(0).get("user_id")), actorId, normalizeActorType(actorType));
        return jdbc.query("SELECT * FROM refund_request WHERE order_id=? ORDER BY created_at DESC,id DESC",
                this::map, orderId);
    }

    public Page<RefundRequest> page(int pageNum, int pageSize, String status, String orderNo, Long csId) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status=?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" AND order_no LIKE ?");
            args.add("%" + orderNo.trim().substring(0, Math.min(64, orderNo.trim().length())) + "%");
        }
        if (csId != null) {
            where.append(" AND order_id IN (SELECT id FROM `order` WHERE cs_id=? OR cs_id IS NULL)");
            args.add(csId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM refund_request" + where, Long.class, args.toArray());
        args.add(safeSize);
        args.add((long) (safePage - 1) * safeSize);
        List<RefundRequest> records = jdbc.query(
                "SELECT * FROM refund_request" + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::map, args.toArray());
        Page<RefundRequest> result = new Page<>(safePage, safeSize, total == null ? 0 : total);
        result.setRecords(records);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void onPaymentRefunded(Long orderId, Long paymentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,status FROM refund_request WHERE order_id=? AND status IN ('ADMIN_APPROVED','PROCESSING') " +
                        "ORDER BY id DESC LIMIT 1 FOR UPDATE", orderId);
        if (rows.isEmpty()) return;
        long id = number(rows.get(0).get("id"));
        RefundRequest request = getRequired(id);
        if (paymentId != null && request.getPaymentId() != null && !paymentId.equals(request.getPaymentId())) return;
        if (paymentId == null && !paymentMatches(request, "REFUNDED", true)) return;
        String before = request.getStatus();
        jdbc.update("UPDATE refund_request SET status=?,active_order_id=NULL,completed_at=CURRENT_TIMESTAMP," +
                        "last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('ADMIN_APPROVED','PROCESSING')",
                RefundStatus.REFUNDED, id);
        audit(request, "CHANNEL_SUCCESS", before, RefundStatus.REFUNDED, "SYSTEM", null, "支付渠道退款成功");
        if ("COMPLAINT".equals(request.getSourceType()) && request.getSourceId() != null) {
            jdbc.update("UPDATE complaint SET status='RESOLVED',resolved_at=CURRENT_TIMESTAMP WHERE id=? " +
                    "AND status='PROCESSING'", request.getSourceId());
        }
        request = getRequired(id);
        publish(request, RefundEvents.SUCCEEDED, "退款成功，金额 ¥" + amountText(request.getAmount()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markPaymentFailed(Long orderId, Long paymentId, String error) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,status,payment_id FROM refund_request WHERE order_id=? " +
                        "AND status IN ('ADMIN_APPROVED','PROCESSING') ORDER BY id DESC LIMIT 1 FOR UPDATE", orderId);
        if (rows.isEmpty()) return;
        long id = number(rows.get(0).get("id"));
        Object expectedPayment = rows.get(0).get("payment_id");
        if (paymentId != null && expectedPayment != null && paymentId != number(expectedPayment)) return;
        RefundRequest request = getRequired(id);
        if (paymentId == null && !paymentMatches(request, "REFUND_FAILED", false)) return;
        String before = request.getStatus();
        String message = error == null || error.isBlank() ? "支付渠道退款失败" : error.substring(0, Math.min(500, error.length()));
        jdbc.update("UPDATE refund_request SET status=?,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                RefundStatus.FAILED, message, id);
        audit(request, "CHANNEL_FAILED", before, RefundStatus.FAILED, "SYSTEM", null, message);
        request = getRequired(id);
        publish(request, RefundEvents.FAILED, message);
    }

    private void assertPaymentRefundable(RefundRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT order_id,amount,status FROM payment WHERE id=? FOR UPDATE", request.getPaymentId());
        if (rows.isEmpty()) throw new BusinessException(409, "原支付记录不存在");
        Map<String, Object> payment = rows.get(0);
        if (!request.getOrderId().equals(number(payment.get("order_id"))))
            throw new BusinessException(409, "退款申请与支付订单不一致");
        String paymentStatus = String.valueOf(payment.get("status"));
        if ("REFUNDING".equals(paymentStatus)) return;
        if (!"PAID".equals(paymentStatus)) throw new BusinessException(409, "支付记录已经不再可退款");
        Money.positive(request.getAmount());
        if (request.getAmount().compareTo(money(payment.get("amount"))) > 0)
            throw new BusinessException(409, "退款金额超过原支付金额");
    }

    private RefundRequest lockForUpdate(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT order_id FROM refund_request WHERE id=?", id);
        if (rows.isEmpty()) throw new BusinessException(404, "退款申请不存在");
        long orderId = number(rows.get(0).get("order_id"));
        jdbc.queryForList("SELECT id FROM `order` WHERE id=? FOR UPDATE", orderId);
        List<RefundRequest> locked = jdbc.query("SELECT * FROM refund_request WHERE id=? FOR UPDATE", this::map, id);
        if (locked.isEmpty()) throw new BusinessException(404, "退款申请不存在");
        return locked.get(0);
    }

    private RefundRequest findActive(Long orderId) {
        List<RefundRequest> rows = jdbc.query(
                "SELECT * FROM refund_request WHERE active_order_id=? FOR UPDATE", this::map, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private RefundRequest findRequestByClientKey(String type, Long actorId, String clientRequestId) {
        List<RefundRequest> rows = jdbc.query(
                "SELECT * FROM refund_request WHERE requester_type=? AND requester_id=? AND client_request_id=? " +
                        "ORDER BY id DESC LIMIT 1 FOR UPDATE", this::map, type, actorId, clientRequestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void assertSameRequest(RefundRequest repeated, Long orderId, BigDecimal amount, String reason) {
        if (!orderId.equals(repeated.getOrderId()) || amount.compareTo(repeated.getAmount()) != 0
                || !reason.equals(repeated.getReason()))
            throw new BusinessException(409, "幂等键已用于不同的退款申请");
        if (RefundStatus.ACTIVE.contains(repeated.getStatus()) || RefundStatus.REFUNDED.equals(repeated.getStatus())) return;
        throw new BusinessException(409, "该幂等请求已经结束，如需再次申请请生成新请求号");
    }

    private void restoreOrderStatusIfUnchanged(RefundRequest request) {
        if (!"REFUNDING".equals(request.getOrderStatusBefore())) {
            jdbc.update("UPDATE `order` SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='REFUNDING'",
                    request.getOrderStatusBefore(), request.getOrderId());
        }
    }

    private boolean closeRejectedComplaint(RefundRequest request, String note) {
        if (!"COMPLAINT".equals(request.getSourceType()) || request.getSourceId() == null) return false;
        int complaintUpdated = jdbc.update(
                "UPDATE complaint SET status='RESOLVED',result='REJECT'," +
                        "result_reason=CASE WHEN result_reason IS NULL OR result_reason='' THEN ? " +
                        "ELSE CONCAT(result_reason,'；',?) END,resolved_at=CURRENT_TIMESTAMP " +
                        "WHERE id=? AND status='PROCESSING'",
                "管理员驳回退款：" + note, "管理员驳回退款：" + note, request.getSourceId());
        jdbc.update("UPDATE `order` SET status='CONFIRMED',confirm_time=COALESCE(confirm_time,CURRENT_TIMESTAMP)," +
                "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='ARBITRATED'", request.getOrderId());
        if (complaintUpdated > 0) {
            events.publishEvent(new OrderConfirmedEvent(this, request.getOrderId(), "ADMIN"));
            return true;
        }
        return false;
    }

    private boolean paymentMatches(RefundRequest request, String expectedStatus, boolean requireAmount) {
        if (request.getPaymentId() == null) return false;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status,refund_amount FROM payment WHERE id=?", request.getPaymentId());
        if (rows.isEmpty() || !expectedStatus.equals(String.valueOf(rows.get(0).get("status")))) return false;
        if (!requireAmount) return true;
        Object refunded = rows.get(0).get("refund_amount");
        return refunded != null && money(refunded).compareTo(request.getAmount()) >= 0;
    }

    private void requireOrderOwner(long ownerId, long actorId, String actorType) {
        if (ownerId == actorId) return;
        if ("USER".equals(actorType) && crossModuleMapper.countGuestLink(actorId, ownerId) > 0) return;
        throw new BusinessException(404, "订单不存在");
    }

    private void requireStaff(String operatorType) {
        if (!STAFF_TYPES.contains(normalizeActorType(operatorType)))
            throw new BusinessException(403, "只有管理员或客服可以审核退款");
    }

    private void requireAdmin(String operatorType) {
        if (!"ADMIN".equals(normalizeActorType(operatorType)))
            throw new BusinessException(403, "退款终审仅限管理员");
    }

    private void requireStatus(RefundRequest request, Set<String> allowed, String message) {
        if (!allowed.contains(request.getStatus())) throw new BusinessException(409, message);
    }

    private String normalizeActorType(String actorType) {
        return actorType == null ? "" : actorType.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRecommendation(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVE", "REJECT").contains(normalized))
            throw new BusinessException(400, "初审建议必须为 APPROVE 或 REJECT");
        return normalized;
    }

    private BigDecimal normalizeAmount(BigDecimal value) {
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new BusinessException(400, "退款金额最多保留两位小数");
        }
    }

    private String normalizeReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.length() < 2 || reason.length() > 500)
            throw new BusinessException(400, "退款原因须为2至500字");
        return reason;
    }

    private String normalizeClientRequestId(String value) {
        if (value == null || value.isBlank()) return null;
        String id = value.trim();
        if (id.length() > 64) throw new BusinessException(400, "请求编号不能超过64字");
        return id;
    }

    private String normalizeOptionalNote(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String note = value.trim();
        if (note.length() > 500) throw new BusinessException(400, label + "不能超过500字");
        return note;
    }

    private String normalizeRequiredNote(String value, String message) {
        String note = normalizeOptionalNote(value, "终审说明");
        if (note == null || note.length() < 2) throw new BusinessException(400, message);
        return note;
    }

    private void audit(RefundRequest request, String action, String from, String to,
                       String operatorType, Long operatorId, String note) {
        jdbc.update("INSERT INTO refund_request_audit(refund_request_id,action,from_status,to_status," +
                        "operator_type,operator_id,note) VALUES(?,?,?,?,?,?,?)",
                request.getId(), action, from, to, operatorType, operatorId,
                note == null ? null : note.substring(0, Math.min(500, note.length())));
    }

    private void publish(RefundRequest request, String eventType, String message) {
        events.publishEvent(new BusinessEvent(this, eventType, "USER", request.getUserId(), request.getOrderId(),
                message + "（退款单 " + request.getRefundNo() + "，订单 " + request.getOrderNo() + "）"));
    }

    private RefundRequest map(ResultSet rs, int rowNum) throws SQLException {
        RefundRequest request = new RefundRequest();
        request.setId(rs.getLong("id"));
        request.setRefundNo(rs.getString("refund_no"));
        request.setOrderId(rs.getLong("order_id"));
        request.setOrderNo(rs.getString("order_no"));
        request.setUserId(rs.getLong("user_id"));
        request.setPaymentId(nullableLong(rs, "payment_id"));
        request.setPaymentMethod(rs.getString("payment_method"));
        request.setAmount(rs.getBigDecimal("amount"));
        request.setReason(rs.getString("reason"));
        request.setStatus(rs.getString("status"));
        request.setOrderStatusBefore(rs.getString("order_status_before"));
        request.setRequesterType(rs.getString("requester_type"));
        request.setRequesterId(rs.getLong("requester_id"));
        request.setClientRequestId(rs.getString("client_request_id"));
        request.setCsReviewerId(nullableLong(rs, "cs_reviewer_id"));
        request.setCsRecommendation(rs.getString("cs_recommendation"));
        request.setCsNote(rs.getString("cs_note"));
        request.setAdminId(nullableLong(rs, "admin_id"));
        request.setAdminDecision(rs.getString("admin_decision"));
        request.setAdminNote(rs.getString("admin_note"));
        request.setSourceType(rs.getString("source_type"));
        request.setSourceId(nullableLong(rs, "source_id"));
        request.setChannelRefundNo(rs.getString("channel_refund_no"));
        request.setLastError(rs.getString("last_error"));
        request.setSubmittedAt(timestamp(rs, "submitted_at"));
        request.setCsReviewedAt(timestamp(rs, "cs_reviewed_at"));
        request.setAdminDecidedAt(timestamp(rs, "admin_decided_at"));
        request.setProcessingAt(timestamp(rs, "processing_at"));
        request.setCompletedAt(timestamp(rs, "completed_at"));
        request.setCreatedAt(timestamp(rs, "created_at"));
        request.setUpdatedAt(timestamp(rs, "updated_at"));
        return request;
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    private BigDecimal money(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private String amountText(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
