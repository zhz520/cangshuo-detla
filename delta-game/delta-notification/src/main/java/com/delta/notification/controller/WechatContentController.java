package com.delta.notification.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.notification.service.OfficialWechatService;
import com.delta.notification.service.WechatContentService;
import com.delta.platform.PlatformService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class WechatContentController {
    private final WechatContentService service;
    private final PlatformService platform;

    @GetMapping("/admin/wechat/accounts/{id}/articles")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> articles(@PathVariable String id, HttpServletRequest request) {
        required(request); return R.ok(service.articles(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/articles")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "保存公众号图文草稿")
    public R<Map<String, Object>> saveArticle(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        required(request); return R.ok(service.saveArticle(id, null, body));
    }

    @PutMapping("/admin/wechat/accounts/{id}/articles/{articleId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "编辑公众号图文草稿")
    public R<Map<String, Object>> updateArticle(@PathVariable String id, @PathVariable Long articleId,
                                                 @RequestBody Map<String, Object> body, HttpServletRequest request) {
        required(request); return R.ok(service.saveArticle(id, articleId, body));
    }

    @DeleteMapping("/admin/wechat/accounts/{id}/articles/{articleId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "删除公众号图文草稿")
    public R<Void> deleteArticle(@PathVariable String id, @PathVariable long articleId, HttpServletRequest request) {
        required(request); service.deleteArticle(id, articleId); return R.ok();
    }

    @PostMapping("/admin/wechat/accounts/{id}/articles/{articleId}/push")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "推送公众号图文草稿")
    public R<Map<String, Object>> pushArticle(@PathVariable String id, @PathVariable long articleId,
                                              HttpServletRequest request) throws Exception {
        required(request); return R.ok(service.pushArticle(id, articleId));
    }

    @PostMapping("/admin/wechat/accounts/{id}/articles/{articleId}/publish")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "发布公众号图文")
    public R<Map<String, Object>> publishArticle(@PathVariable String id, @PathVariable long articleId,
                                                 HttpServletRequest request) throws Exception {
        required(request); return R.ok(service.publishArticle(id, articleId));
    }

    @GetMapping("/admin/wechat/accounts/{id}/mass-sends")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> tasks(@PathVariable String id, HttpServletRequest request) {
        required(request); return R.ok(service.tasks(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/mass-sends")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "新增公众号群发任务")
    public R<Map<String, Object>> createTask(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        required(request); return R.ok(service.saveTask(id, null, body, SecurityUtils.getUserId()));
    }

    @PutMapping("/admin/wechat/accounts/{id}/mass-sends/{taskId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "编辑公众号群发任务")
    public R<Map<String, Object>> updateTask(@PathVariable String id, @PathVariable Long taskId,
                                              @RequestBody Map<String, Object> body, HttpServletRequest request) {
        required(request); return R.ok(service.saveTask(id, taskId, body, SecurityUtils.getUserId()));
    }

    @PostMapping("/admin/wechat/accounts/{id}/mass-sends/{taskId}/{action}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "处理公众号群发审批")
    public R<Void> transition(@PathVariable String id, @PathVariable long taskId, @PathVariable String action,
                              HttpServletRequest request) {
        required(request); service.transition(id, taskId, action, SecurityUtils.getUserId()); return R.ok();
    }

    @PostMapping("/admin/wechat/accounts/{id}/mass-sends/{taskId}/send")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "执行公众号群发")
    public R<Map<String, Object>> send(@PathVariable String id, @PathVariable long taskId,
                                       @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) throws Exception {
        required(request);
        boolean confirm = body != null && Boolean.parseBoolean(String.valueOf(body.get("confirm")));
        return R.ok(service.send(id, taskId, SecurityUtils.getUserId(), confirm));
    }

    private void required(HttpServletRequest request) {
        platform.requireFeature(OfficialWechatService.FEATURE_ID, request.getServerName());
    }
}
