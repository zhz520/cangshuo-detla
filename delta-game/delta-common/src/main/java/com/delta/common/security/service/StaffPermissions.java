package com.delta.common.security.service;

import com.alibaba.fastjson2.JSON;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service("staffPermissions")
@RequiredArgsConstructor
public class StaffPermissions {
    private final JdbcTemplate jdbc;
    public List<String> forStaff(Long id,String baseRole) {
        var rows=jdbc.queryForList("SELECT r.permissions FROM staff_role r WHERE r.enabled=1 AND r.code=COALESCE((SELECT role_code FROM staff_role_member WHERE admin_id=?),?)",id,baseRole);
        return rows.isEmpty()?List.of():JSON.parseArray((String)rows.get(0).get("permissions"),String.class);
    }
    public boolean allowed(String permission) {
        String type=SecurityUtils.getUserType();
        if(!Set.of("ADMIN","CS").contains(type))return false;
        var granted=forStaff(SecurityUtils.getUserId(),type);
        // Refund is an explicit grant even for roles with all other permissions.
        return granted.contains(permission)||(!"order.refund".equals(permission)&&granted.contains("*"));
    }
    public void require(String permission) {if(!allowed(permission))throw new BusinessException(403,"当前角色没有此操作权限");}
}
