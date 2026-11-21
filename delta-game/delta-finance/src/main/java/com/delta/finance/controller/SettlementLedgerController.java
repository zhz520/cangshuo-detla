package com.delta.finance.controller;

import com.alibaba.excel.EasyExcel;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.finance.service.SettlementLedgerService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/admin/settlements")
@RequiredArgsConstructor
public class SettlementLedgerController {
    private final SettlementLedgerService ledger;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('finance.read')")
    public R<List<Map<String, Object>>> list(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return R.ok(ledger.rows(from, to, 1000));
    }

    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('finance.export')")
    @OpLog(module = "finance", operation = "导出订单分账Excel对账台账")
    public void export(@RequestParam LocalDate from, @RequestParam LocalDate to, HttpServletResponse response) throws Exception {
        var records = ledger.rows(from, to, 10001);
        if (records.size() > 10000) throw new BusinessException(400, "记录超过一万条，请缩小日期范围");
        var head = List.of("订单号", "订单金额", "俱乐部净抽成", "打手总分成", "客服提成", "实际退款", "俱乐部冲正", "打手冲正", "客服冲正", "待追回款", "打手", "客服", "结算时间").stream().map(List::of).toList();
        var data = records.stream().map(row -> List.of(safe(row.get("order_no")), row.get("amount"), row.get("club_amount"), row.get("player_amount"), row.get("cs_amount"), row.get("refund_amount"), row.get("club_refund"), row.get("player_refund"), row.get("cs_refund"), row.get("pending_recovery"), safe(row.get("player_name")), safe(row.get("cs_name")), safe(row.get("created_at")))).toList();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=settlements-" + from + "-" + to + ".xlsx");
        EasyExcel.write(response.getOutputStream()).head(head).autoCloseStream(false).sheet("订单分账对账").doWrite(data);
    }

    private String safe(Object value) {
        String s = Objects.toString(value, "");
        return s.matches("^[=+@\\-\\t\\r].*") ? "'" + s : s;
    }
}
