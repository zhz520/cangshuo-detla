package com.delta.notification.controller;

import com.delta.common.annotation.OpLog;
import com.delta.common.domain.R;
import com.delta.notification.service.OfficialWechatService;
import com.delta.notification.service.WechatNotificationSender;
import com.delta.platform.PlatformService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/wechat/notification-sender")
@PreAuthorize("hasRole('ADMIN') and @staffPermissions.allowed('system.manage')")
public class WechatNotificationSenderController {
    private final WechatNotificationSender sender;
    private final PlatformService platform;

    @GetMapping
    public R<WechatNotificationSender.Selection> get(HttpServletRequest request) {
        platform.requireFeature(OfficialWechatService.FEATURE_ID,request.getServerName());
        return R.ok(sender.current());
    }

    @PutMapping
    @OpLog(module="wechat", operation="选择通知发送公众号")
    public R<WechatNotificationSender.Selection> put(@RequestBody WechatNotificationSender.Choice choice,
                                                     HttpServletRequest request) {
        platform.requireFeature(OfficialWechatService.FEATURE_ID,request.getServerName());
        return R.ok(sender.choose(choice));
    }
}
