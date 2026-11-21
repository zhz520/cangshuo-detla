package com.delta.order.service;

import com.delta.order.dto.ComplaintAttachmentContent;
import com.delta.order.dto.ComplaintAttachmentView;
import com.delta.order.entity.Complaint;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

/** Private complaint evidence upload, token binding and owner/staff reads. */
public interface ComplaintAttachmentService {
    String upload(Long orderId, Long actorId, String actorType, MultipartFile file) throws Exception;

    String bindToComplaint(Long orderId, Long actorId, String actorType,
                           List<String> attachmentIds, String legacyImages);

    /** CS-created complaints may attach only tokens whose uploader belongs to the order owner. */
    String bindToStaffCreatedComplaint(Long orderId, List<String> attachmentIds, String legacyImages);

    List<ComplaintAttachmentView> views(String storedTokens, Long orderId);

    Optional<ComplaintAttachmentContent> loadForOwner(String token, Long actorId, String actorType);

    Optional<ComplaintAttachmentContent> loadForStaff(Complaint complaint, String token);
}
