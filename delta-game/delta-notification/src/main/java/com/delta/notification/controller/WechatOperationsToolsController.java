package com.delta.notification.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.notification.service.OfficialWechatService;
import com.delta.notification.service.WechatOperationsService;
import com.delta.platform.PlatformService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class WechatOperationsToolsController {
    private final WechatOperationsService service;
    private final PlatformService platform;

    @GetMapping("/admin/wechat/accounts/{id}/menu")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<Map<String, Object>> menu(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.menu(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/menu")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "保存公众号菜单")
    public R<Map<String, Object>> saveMenu(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.saveMenu(id, body));
    }

    @PostMapping("/admin/wechat/accounts/{id}/menu/publish")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "发布公众号菜单")
    public R<Map<String, Object>> publishMenu(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) throws Exception {
        requireFeature(request);
        return R.ok(service.publishMenu(id, body.get("buttons")));
    }

    @PostMapping("/admin/wechat/accounts/{id}/menu/import")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "读取公众号菜单")
    public R<Map<String, Object>> importMenu(@PathVariable String id, HttpServletRequest request) throws Exception {
        requireFeature(request);
        return R.ok(service.importMenu(id));
    }

    @GetMapping("/admin/wechat/accounts/{id}/autoreplies")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> autoReplies(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.autoReplies(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/autoreplies")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "新增公众号自动回复")
    public R<Map<String, Object>> createAutoReply(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.saveAutoReply(id, null, body));
    }

    @PutMapping("/admin/wechat/accounts/{id}/autoreplies/{ruleId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "编辑公众号自动回复")
    public R<Map<String, Object>> updateAutoReply(@PathVariable String id, @PathVariable long ruleId,
                                                   @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.saveAutoReply(id, ruleId, body));
    }

    @DeleteMapping("/admin/wechat/accounts/{id}/autoreplies/{ruleId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "删除公众号自动回复")
    public R<Void> deleteAutoReply(@PathVariable String id, @PathVariable long ruleId, HttpServletRequest request) {
        requireFeature(request);
        service.deleteAutoReply(id, ruleId);
        return R.ok();
    }

    @GetMapping("/admin/wechat/accounts/{id}/qrcodes")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> qrCodes(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.qrCodes(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/qrcodes")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "创建公众号渠道二维码")
    public R<Map<String, Object>> createQr(@PathVariable String id, @RequestBody Map<String, Object> body,
                                           HttpServletRequest request) throws Exception {
        requireFeature(request);
        return R.ok(service.createQr(id, body));
    }

    @DeleteMapping("/admin/wechat/accounts/{id}/qrcodes/{qrId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "删除公众号渠道二维码")
    public R<Void> deleteQr(@PathVariable String id, @PathVariable String qrId, HttpServletRequest request) {
        requireFeature(request);
        service.deleteQr(id, qrId);
        return R.ok();
    }

    @GetMapping("/admin/wechat/accounts/{id}/fans")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> fans(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request);
        return R.ok(service.fans(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/fans/sync")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "同步公众号粉丝")
    public R<Map<String, Object>> syncFans(@PathVariable String id, HttpServletRequest request) throws Exception {
        requireFeature(request);
        return R.ok(service.syncFans(id));
    }

    @GetMapping("/admin/wechat/accounts/{id}/tags")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> tags(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request); return R.ok(service.tags(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/tags/sync")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "同步公众号标签")
    public R<List<Map<String, Object>>> syncTags(@PathVariable String id, HttpServletRequest request) throws Exception {
        requireFeature(request); return R.ok(service.syncTags(id));
    }

    @PostMapping("/admin/wechat/accounts/{id}/tags")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "新增公众号标签")
    public R<Map<String, Object>> createTag(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) throws Exception {
        requireFeature(request); return R.ok(service.saveTag(id, null, String.valueOf(body.get("name"))));
    }

    @PutMapping("/admin/wechat/accounts/{id}/tags/{tagId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "编辑公众号标签")
    public R<Map<String, Object>> updateTag(@PathVariable String id, @PathVariable long tagId,
                                            @RequestBody Map<String, Object> body, HttpServletRequest request) throws Exception {
        requireFeature(request); return R.ok(service.saveTag(id, tagId, String.valueOf(body.get("name"))));
    }

    @DeleteMapping("/admin/wechat/accounts/{id}/tags/{tagId}")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "删除公众号标签")
    public R<Void> deleteTag(@PathVariable String id, @PathVariable long tagId, HttpServletRequest request) throws Exception {
        requireFeature(request); service.deleteTag(id, tagId); return R.ok();
    }

    @PostMapping("/admin/wechat/accounts/{id}/tags/{tagId}/batch")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "批量标记公众号粉丝")
    public R<Void> batchTag(@PathVariable String id, @PathVariable long tagId, @RequestBody Map<String, Object> body, HttpServletRequest request) throws Exception {
        requireFeature(request);
        Object raw = body.get("openids");
        List<String> openids = raw instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        service.batchTagFans(id, tagId, openids); return R.ok();
    }

    @GetMapping("/admin/wechat/accounts/{id}/materials")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<List<Map<String, Object>>> materials(@PathVariable String id, @RequestParam(required = false) String type, HttpServletRequest request) {
        requireFeature(request); return R.ok(service.materials(id, type));
    }

    @PostMapping("/admin/wechat/accounts/{id}/materials/sync")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "同步公众号素材")
    public R<Map<String, Object>> syncMaterials(@PathVariable String id, @RequestParam(required = false) String type, HttpServletRequest request) throws Exception {
        requireFeature(request); return R.ok(service.syncMaterials(id, type));
    }

    @PostMapping("/admin/wechat/accounts/{id}/materials/upload")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    @OpLog(module = "wechat", operation = "上传公众号素材")
    public R<Map<String, Object>> uploadMaterial(@PathVariable String id, @RequestParam MultipartFile file,
                                                  @RequestParam(required = false) String type,
                                                  HttpServletRequest request) throws Exception {
        requireFeature(request);
        return R.ok(service.uploadMaterial(id, type, file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    @GetMapping("/admin/wechat/accounts/{id}/stats")
    @PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
    public R<Map<String, Object>> stats(@PathVariable String id, HttpServletRequest request) {
        requireFeature(request); return R.ok(service.stats(id));
    }

    private void requireFeature(HttpServletRequest request) {
        platform.requireFeature(OfficialWechatService.FEATURE_ID, request.getServerName());
    }
}
