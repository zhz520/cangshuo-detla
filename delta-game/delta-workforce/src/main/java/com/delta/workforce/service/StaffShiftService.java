package com.delta.workforce.service;

import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manual duty status used to gate CS operational notifications. */
@Service
@RequiredArgsConstructor
public class StaffShiftService {
    private final JdbcTemplate jdbc;

    public Map<String, Object> status(Long staffId, String role) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT online,changed_at FROM staff_shift WHERE admin_id=?", staffId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", role);
        result.put("online", !rows.isEmpty() && Number.class.cast(rows.get(0).get("online")).intValue() == 1);
        result.put("changedAt", rows.isEmpty() ? null : rows.get(0).get("changed_at"));
        result.put("onDutyCs", jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin a JOIN staff_shift s ON s.admin_id=a.id WHERE a.deleted=0 AND a.status=1 AND UPPER(a.role)='CS' AND s.online=1", Long.class));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> toggle(Long staffId, String role, boolean online) {
        if (!"CS".equals(role)) throw new BusinessException(403, "仅客服账号可以切换上班状态");
        jdbc.queryForList("SELECT id FROM admin WHERE id=? AND deleted=0 AND status=1 FOR UPDATE", staffId);
        jdbc.update("DELETE FROM staff_shift WHERE admin_id=?", staffId);
        jdbc.update("INSERT INTO staff_shift(admin_id,online,changed_at) VALUES (?,?,CURRENT_TIMESTAMP)", staffId, online ? 1 : 0);
        return status(staffId, role);
    }
}
