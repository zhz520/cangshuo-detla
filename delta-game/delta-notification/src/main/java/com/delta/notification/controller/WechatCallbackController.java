package com.delta.notification.controller;

import com.delta.notification.service.WechatCallbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class WechatCallbackController {
    private final WechatCallbackService service;

    @GetMapping("/common/wechat/callback/{accountId}")
    public String verify(@PathVariable String accountId,
                         @RequestParam(required = false) String signature,
                         @RequestParam(required = false) String timestamp,
                         @RequestParam(required = false) String nonce,
                         @RequestParam(required = false) String echostr) {
        return service.verify(accountId, signature, timestamp, nonce, echostr);
    }

    @PostMapping("/common/wechat/callback/{accountId}")
    public String message(@PathVariable String accountId,
                          @RequestParam(required = false) String signature,
                          @RequestParam(required = false) String msg_signature,
                          @RequestParam(required = false) String timestamp,
                          @RequestParam(required = false) String nonce,
                          @RequestParam(required = false) String encrypt_type,
                          @RequestBody(required = false) String body) {
        return service.handle(accountId, signature, msg_signature, timestamp, nonce, encrypt_type, body);
    }
}
