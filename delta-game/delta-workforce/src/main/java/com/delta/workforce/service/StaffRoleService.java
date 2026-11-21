package com.delta.workforce.service;

import com.alibaba.fastjson2.JSON;
import com.delta.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Staff role, permission and member assignment rules. */
@Service
@RequiredArgsConstructor
public class StaffRoleService {
    public static final List<String> CODES = List.of("*", "order.read", "order.manage", "order.refund", "product.manage", "player.manage", "media.review", "user.read", "user.manage", "finance.read", "finance.manage", "finance.export", "withdraw.review", "chat.manage", "system.manage");
    private final JdbcTemplate jdbc;

    public Map<String, Object> list() {
        var roles = jdbc.queryForList("SELECT * FROM staff_role ORDER BY builtin DESC,code");
        roles.forEach(r -> r.put("permissions", JSON.parseArray((String) r.get("permissions"), String.class)));
        return Map.of("roles", roles, "permissionCodes", CODES,
                "members", jdbc.queryForList("SELECT a.id,a.username,a.nickname,a.role,m.role_code FROM admin a LEFT JOIN staff_role_member m ON m.admin_id=a.id WHERE a.deleted=0"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(String code, String name, List<String> permissions, boolean enabled) {
        if (!CODES.containsAll(permissions)) throw new BusinessException(400, "包含未知权限");
        if ("ADMIN".equals(code) && (!enabled || !permissions.contains("*")))
            throw new BusinessException(400, "内置管理员必须保留系统管理权限");
        var rows = jdbc.queryForList("SELECT * FROM staff_role WHERE code=? FOR UPDATE", code);
        if (rows.isEmpty()) jdbc.update("INSERT INTO staff_role(code,name,permissions,enabled) VALUES (?,?,?,?)",
                code, name, JSON.toJSONString(permissions), enabled ? 1 : 0);
        else jdbc.update("UPDATE staff_role SET name=?,permissions=?,enabled=? WHERE code=?",
                name, JSON.toJSONString(permissions), enabled ? 1 : 0, code);
    }

    @Transactional(rollbackFor = Exception.class)
    public void assign(Long id, String code, Long actorId) {
        var roles = jdbc.queryForList("SELECT * FROM staff_role WHERE code=? AND enabled=1", code);
        if (roles.isEmpty()) throw new BusinessException(400, "角色不存在或已停用");
        if (id.equals(actorId)) throw new BusinessException(400, "不能修改自己的权限角色，请由另一位管理员操作");
        var staff = jdbc.queryForList("SELECT role FROM admin WHERE id=? AND deleted=0", id);
        if (staff.isEmpty()) throw new BusinessException(404, "员工不存在");
        var grants = JSON.parseArray((String) roles.get(0).get("permissions"), String.class);
        if ("CS".equalsIgnoreCase((String) staff.get(0).get("role")) && (grants.contains("*") || grants.contains("system.manage")))
            throw new BusinessException(400, "客服不能分配系统管理角色；请先由管理员调整基础身份");
        jdbc.update("DELETE FROM staff_role_member WHERE admin_id=?", id);
        jdbc.update("INSERT INTO staff_role_member(admin_id,role_code) VALUES (?,?)", id, code);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String code) {
        var rows = jdbc.queryForList("SELECT builtin FROM staff_role WHERE code=? FOR UPDATE", code);
        if (rows.isEmpty()) throw new BusinessException(404, "角色不存在");
        if (((Number) rows.get(0).get("builtin")).intValue() == 1) throw new BusinessException(400, "内置角色不能删除");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM staff_role_member WHERE role_code=?", Long.class, code) > 0)
            throw new BusinessException(409, "角色下仍有员工，请先重新分配");
        jdbc.update("DELETE FROM staff_role WHERE code=?", code);
    }
}
