package com.delta.admin.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manual duty status used to gate operational notifications for customer service. */
@RestController
@RequestMapping("/cs/shift")
@RequiredArgsConstructor
public class StaffShiftController {
    private final JdbcTemplate jdbc;

    @GetMapping("/status")
    public R<Map<String,Object>> status() {
        Long staffId=SecurityUtils.getUserId();
        String role=SecurityUtils.getUserType();
        List<Map<String,Object>> rows=jdbc.queryForList(
                "SELECT online,changed_at FROM staff_shift WHERE admin_id=?",staffId);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("role",role);
        result.put("online",!rows.isEmpty()&&Number.class.cast(rows.get(0).get("online")).intValue()==1);
        result.put("changedAt",rows.isEmpty()?null:rows.get(0).get("changed_at"));
        result.put("onDutyCs",jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin a JOIN staff_shift s ON s.admin_id=a.id WHERE a.deleted=0 AND a.status=1 AND UPPER(a.role)='CS' AND s.online=1",
                Long.class));
        return R.ok(result);
    }

    @PostMapping("/toggle")
    @Transactional(rollbackFor=Exception.class)
    @OpLog(module="shift",operation="切换客服上班状态")
    public R<Map<String,Object>> toggle(@RequestBody Map<String,Object> body) {
        if(!"CS".equals(SecurityUtils.getUserType()))
            throw new BusinessException(403,"仅客服账号可以切换上班状态");
        Object submitted=body==null?null:body.get("online");
        if(!(submitted instanceof Boolean online))
            throw new BusinessException(400,"上班状态格式不正确");
        Long staffId=SecurityUtils.getUserId();
        jdbc.queryForList("SELECT id FROM admin WHERE id=? AND deleted=0 AND status=1 FOR UPDATE",staffId);
        jdbc.update("DELETE FROM staff_shift WHERE admin_id=?",staffId);
        jdbc.update("INSERT INTO staff_shift(admin_id,online,changed_at) VALUES (?,?,CURRENT_TIMESTAMP)",staffId,online?1:0);
        return status();
    }
}
