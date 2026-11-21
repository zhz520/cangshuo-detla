package com.delta.finance.service;

import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Order settlement ledger queries with a bounded date range. */
@Service
@RequiredArgsConstructor
public class SettlementLedgerService {
    private final JdbcTemplate jdbc;

    public List<Map<String, Object>> rows(LocalDate from, LocalDate to, int limit) {
        if (to.isBefore(from) || from.plusDays(366).isBefore(to))
            throw new BusinessException(400, "请选择不超过一年的有效日期范围");
        return jdbc.queryForList("SELECT l.*,COALESCE(r.refund_amount,0) AS refund_amount,COALESCE(r.club_refund,0) AS club_refund,COALESCE(r.player_refund,0) AS player_refund,COALESCE(r.cs_refund,0) AS cs_refund,(SELECT COALESCE(SUM(remaining),0) FROM player_refund_recovery debt WHERE debt.order_id=l.order_id) AS pending_recovery,o.order_no,p.nickname AS player_name,a.nickname AS cs_name FROM order_settlement_ledger l LEFT JOIN order_settlement_refund r ON r.order_id=l.order_id JOIN `order` o ON o.id=l.order_id LEFT JOIN player p ON p.id=o.player_id LEFT JOIN admin a ON a.id=l.cs_id WHERE l.created_at>=? AND l.created_at<? ORDER BY l.created_at DESC LIMIT ?",
                from.atStartOfDay(), to.plusDays(1).atStartOfDay(), limit);
    }
}
