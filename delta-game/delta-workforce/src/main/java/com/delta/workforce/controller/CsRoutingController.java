package com.delta.workforce.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.exception.BusinessException;
import com.delta.workforce.service.CsRoutingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/cs-routing")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
public class CsRoutingController {
    private final CsRoutingAdminService csRoutingAdminService;

    @GetMapping("/staff")
    public R<List<Map<String, Object>>> staff() {
        return R.ok(csRoutingAdminService.staff());
    }

    @GetMapping("/rules")
    public R<List<Map<String, Object>>> rules() {
        return R.ok(csRoutingAdminService.rules());
    }

    @OpLog(module = "cs-routing", operation = "保存客服分配规则")
    @PostMapping("/rules")
    public R<Long> saveRule(@RequestBody RuleRequest body) {
        if (body == null) throw new BusinessException(400, "请求内容无效");
        return R.ok(csRoutingAdminService.saveRule(body.id(), body.matchType(), body.targetId(), body.assigneeType(),
                body.assigneeId(), body.enabled(), body.remark()));
    }

    @OpLog(module = "cs-routing", operation = "批量保存分类分配规则")
    @PostMapping("/rules/batch")
    public R<Integer> saveRules(@RequestBody BatchRuleRequest body) {
        if (body == null) throw new BusinessException(400, "请求内容无效");
        return R.ok(csRoutingAdminService.saveRules(body.matchType(), body.targetIds(), body.assigneeType(),
                body.assigneeId(), body.enabled(), body.remark()));
    }

    @OpLog(module = "cs-routing", operation = "删除客服分配规则")
    @DeleteMapping("/rules/{id}")
    public R<Void> deleteRule(@PathVariable Long id) {
        csRoutingAdminService.deleteRule(id);
        return R.ok();
    }

    @GetMapping("/groups")
    public R<List<Map<String, Object>>> groups() {
        return R.ok(csRoutingAdminService.groups());
    }

    @GetMapping("/ratings")
    public R<Map<String, Object>> ratings() {
        return R.ok(csRoutingAdminService.ratings());
    }

    @OpLog(module = "cs-routing", operation = "保存客服组")
    @PostMapping("/groups")
    public R<Long> saveGroup(@RequestBody GroupRequest body) {
        return R.ok(csRoutingAdminService.saveGroup(body.id(), body.name(), body.leaderId(), body.memberIds(),
                body.enabled(), body.remark()));
    }

    @OpLog(module = "cs-routing", operation = "删除客服组")
    @DeleteMapping("/groups/{id}")
    public R<Void> deleteGroup(@PathVariable Long id) {
        csRoutingAdminService.deleteGroup(id);
        return R.ok();
    }

    public record BatchRuleRequest(String matchType, List<Long> targetIds, String assigneeType,
                                   Long assigneeId, Integer enabled, String remark) {}

    public record RuleRequest(Long id, String matchType, Long targetId, String assigneeType,
                              Long assigneeId, Integer enabled, String remark) {}

    public record GroupRequest(Long id, String name, Long leaderId, List<Long> memberIds,
                               Integer enabled, String remark) {}
}
