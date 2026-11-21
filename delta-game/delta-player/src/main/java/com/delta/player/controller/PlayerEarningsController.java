package com.delta.player.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.delta.common.domain.PageQuery;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.pay.entity.Transaction;
import com.delta.player.config.PlayerEarningsServiceConfiguration;
import com.delta.player.service.PlayerEarningsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 打手收益统计
 */
@RestController
@RequestMapping("/player/earnings")
@RequiredArgsConstructor
@Import(PlayerEarningsServiceConfiguration.class)
public class PlayerEarningsController {
    private final PlayerEarningsService earningsService;

    /** 收益汇总 */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(earningsService.summary(SecurityUtils.getUserId()));
    }

    /** 售后观察期内尚未转为可提现余额的订单收益。 */
    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending() {
        return R.ok(earningsService.pending(SecurityUtils.getUserId()));
    }

    /** 收益明细（支持日期范围过滤） */
    @GetMapping("/detail")
    public R<Page<Transaction>> detail(PageQuery query,
                                       @RequestParam(required = false) String startDate,
                                       @RequestParam(required = false) String endDate) {
        return R.ok(earningsService.detail(SecurityUtils.getUserId(), query, startDate, endDate));
    }
}
