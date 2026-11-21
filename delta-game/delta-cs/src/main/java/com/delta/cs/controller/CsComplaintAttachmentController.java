package com.delta.cs.controller;

import com.delta.common.customer.CsRoutingService;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import com.delta.order.dto.ComplaintAttachmentContent;
import com.delta.order.entity.Complaint;
import com.delta.order.service.ComplaintAttachmentService;
import com.delta.order.service.ComplaintService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/cs/complaint")
@RequiredArgsConstructor
public class CsComplaintAttachmentController {
    private final ComplaintService complaintService;
    private final ComplaintAttachmentService attachments;
    private final CsRoutingService csRouting;

    @PreAuthorize("@staffPermissions.allowed('order.manage')")
    @GetMapping("/{complaintId}/attachment/{token}")
    public ResponseEntity<byte[]> download(@PathVariable Long complaintId, @PathVariable String token) {
        Complaint complaint = complaintService.getById(complaintId);
        if (complaint == null || complaint.getOrderId() == null) return ResponseEntity.notFound().build();
        try {
            csRouting.assertCsCanHandleOrder(complaint.getOrderId(), SecurityUtils.getUserId());
        } catch (BusinessException denied) {
            return ResponseEntity.status(denied.getCode()).build();
        }
        Optional<ComplaintAttachmentContent> content = attachments.loadForStaff(complaint, token);
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
