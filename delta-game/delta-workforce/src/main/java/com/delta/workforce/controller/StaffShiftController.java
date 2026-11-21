package com.delta.workforce.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.workforce.service.StaffShiftService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Manual duty status used to gate operational notifications for customer service. */
@RestController
@RequestMapping("/cs/shift")
@RequiredArgsConstructor
public class StaffShiftController {
    private final StaffShiftService shifts;

    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        return R.ok(shifts.status(SecurityUtils.getUserId(), SecurityUtils.getUserType()));
    }

    @PostMapping("/toggle")
    @OpLog(module = "shift", operation = "切换客服上班状态")
    public R<Map<String, Object>> toggle(@RequestBody Map<String, Object> body) {
        Object submitted = body == null ? null : body.get("online");
        if (!(submitted instanceof Boolean online)) throw new BusinessException(400, "上班状态格式不正确");
        return R.ok(shifts.toggle(SecurityUtils.getUserId(), SecurityUtils.getUserType(), online));
    }
}
