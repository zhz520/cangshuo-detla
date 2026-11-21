package com.delta.order.controller;

import com.delta.common.domain.R;
import com.delta.common.security.service.RateLimitService;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.order.dto.ComplaintAttachmentContent;
import com.delta.order.service.ComplaintAttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

@RestController
@RequestMapping("/order/complaint/attachment")
@RequiredArgsConstructor
public class ComplaintAttachmentController {
    private final ComplaintAttachmentService attachments;
    private final RateLimitService limits;

    @PostMapping
    public R<String> upload(@RequestParam Long orderId,
                            @RequestParam("file") MultipartFile file) throws Exception {
        Long actorId = SecurityUtils.getUserId();
        limits.check("complaint-attachment-upload:" + actorId, 20, 3600);
        return R.ok(attachments.upload(orderId, actorId, SecurityUtils.getUserType(), file));
    }

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> download(@PathVariable String token) {
        Optional<ComplaintAttachmentContent> content = attachments.loadForOwner(
                token, SecurityUtils.getUserId(), SecurityUtils.getUserType());
        return content.map(this::image).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<byte[]> image(ComplaintAttachmentContent content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(content.bytes());
    }
}
