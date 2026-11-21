package com.delta.workforce.controller;

import com.alibaba.fastjson2.JSON;
import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.service.StaffPermissions;
import com.delta.common.security.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class StaffRoleController {
    private final JdbcTemplate jdbc;
    private final StaffPermissions permissions;
    public static final List<String> CODES=List.of("*","order.read","order.manage","order.refund","product.manage","player.manage","media.review","user.read","user.manage","finance.read","finance.manage","finance.export","withdraw.review","chat.manage","system.manage");
    @GetMapping("/cs/permissions") public R<List<String>> mine(){return R.ok(permissions.forStaff(SecurityUtils.getUserId(),SecurityUtils.getUserType()));}
    @GetMapping("/system/roles")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<Map<String,Object>> list(){var roles=jdbc.queryForList("SELECT * FROM staff_role ORDER BY builtin DESC,code");roles.forEach(r->r.put("permissions",JSON.parseArray((String)r.get("permissions"),String.class)));return R.ok(Map.of("roles",roles,"permissionCodes",CODES,"members",jdbc.queryForList("SELECT a.id,a.username,a.nickname,a.role,m.role_code FROM admin a LEFT JOIN staff_role_member m ON m.admin_id=a.id WHERE a.deleted=0")));}
    public record RoleBody(@NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_-]{0,63}") String code,@NotBlank @Size(max=100) String name,@NotNull @Size(max=30) List<String> permissions,boolean enabled){}
    @PutMapping("/system/roles")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module="roles",operation="配置角色及退款权限")
    @Transactional(rollbackFor=Exception.class)
    public R<Void> save(@Valid @RequestBody RoleBody body){
        if(!CODES.containsAll(body.permissions()))throw new BusinessException(400,"包含未知权限");
        if("ADMIN".equals(body.code())&&(!body.enabled()||!body.permissions().contains("*")))throw new BusinessException(400,"内置管理员必须保留系统管理权限");
        var rows=jdbc.queryForList("SELECT * FROM staff_role WHERE code=? FOR UPDATE",body.code());
        if(rows.isEmpty())jdbc.update("INSERT INTO staff_role(code,name,permissions,enabled) VALUES (?,?,?,?)",body.code(),body.name(),JSON.toJSONString(body.permissions()),body.enabled()?1:0);
        else jdbc.update("UPDATE staff_role SET name=?,permissions=?,enabled=? WHERE code=?",body.name(),JSON.toJSONString(body.permissions()),body.enabled()?1:0,body.code());
        return R.ok();
    }
    @PutMapping("/system/roles/members/{id}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module="roles",operation="分配员工权限角色")
    @Transactional(rollbackFor=Exception.class)
    public R<Void> assign(@PathVariable Long id,@RequestBody Map<String,String> body){
        String code=body.get("roleCode");
        var roles=jdbc.queryForList("SELECT * FROM staff_role WHERE code=? AND enabled=1",code);
        if(roles.isEmpty())throw new BusinessException(400,"角色不存在或已停用");
        if(id.equals(SecurityUtils.getUserId()))throw new BusinessException(400,"不能修改自己的权限角色，请由另一位管理员操作");
        var staff=jdbc.queryForList("SELECT role FROM admin WHERE id=? AND deleted=0",id);if(staff.isEmpty())throw new BusinessException(404,"员工不存在");
        var grants=JSON.parseArray((String)roles.get(0).get("permissions"),String.class);
        if("CS".equalsIgnoreCase((String)staff.get(0).get("role"))&&(grants.contains("*")||grants.contains("system.manage")))throw new BusinessException(400,"客服不能分配系统管理角色；请先由管理员调整基础身份");
        jdbc.update("DELETE FROM staff_role_member WHERE admin_id=?",id);jdbc.update("INSERT INTO staff_role_member(admin_id,role_code) VALUES (?,?)",id,code);return R.ok();
    }
    @DeleteMapping("/system/roles/{code}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module="roles",operation="删除权限角色")
    @Transactional(rollbackFor=Exception.class)
    public R<Void> delete(@PathVariable String code){
        var rows=jdbc.queryForList("SELECT builtin FROM staff_role WHERE code=? FOR UPDATE",code);
        if(rows.isEmpty())throw new BusinessException(404,"角色不存在");
        if(((Number)rows.get(0).get("builtin")).intValue()==1)throw new BusinessException(400,"内置角色不能删除");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM staff_role_member WHERE role_code=?",Long.class,code)>0)throw new BusinessException(409,"角色下仍有员工，请先重新分配");
        jdbc.update("DELETE FROM staff_role WHERE code=?",code);return R.ok();
    }
}
