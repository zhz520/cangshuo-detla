package com.delta.order.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.delta.order.dto.ComplaintAttachmentView;
import com.delta.order.dto.ComplaintDetailVO;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComplaintSerializationTest {
    @Test
    void legacyPublicUrlsAndRawRequestTokensNeverSerialize() throws Exception {
        Complaint complaint = new Complaint();
        complaint.setImages("/file/2026/11/21/legacy-public.png");
        complaint.setAppealImages("https://example.test/legacy-public.png");
        complaint.setAttachmentIds(List.of("ca1.request-only"));

        String json = new ObjectMapper().writeValueAsString(complaint);

        assertThat(json).doesNotContain("legacy-public", "/file/", "ca1.request-only");
        assertThat(complaint.getImages()).isEqualTo("/file/2026/11/21/legacy-public.png");
    }

    @Test
    void detailVoMarksLegacyEvidenceUnavailableWithoutReturningItsUrl() throws Exception {
        Complaint complaint = new Complaint();
        complaint.setImages("/file/2026/11/21/legacy-public.png");
        complaint.setAppealImages("https://example.test/legacy-appeal.png");
        ComplaintDetailVO detail = new ComplaintDetailVO();
        detail.setComplaint(complaint);
        detail.setAttachments(List.of(new ComplaintAttachmentView(null,
                ComplaintAttachmentView.KIND_UNAVAILABLE)));
        detail.setAppealAttachments(List.of(new ComplaintAttachmentView(null,
                ComplaintAttachmentView.KIND_UNAVAILABLE)));

        String json = new ObjectMapper().writeValueAsString(detail);

        assertThat(json).doesNotContain("legacy-public", "legacy-appeal", "/file/", "https://example.test");
        assertThat(json).contains("UNAVAILABLE");
    }

    @Test
    void attachmentIdsIsRequestOnlyAndPrivateRawFieldsAreWriteOnly() throws Exception {
        Field attachmentIds = Complaint.class.getDeclaredField("attachmentIds");
        TableField tableField = attachmentIds.getAnnotation(TableField.class);
        JsonProperty attachmentIdsJson = attachmentIds.getAnnotation(JsonProperty.class);
        JsonProperty images = Complaint.class.getDeclaredField("images").getAnnotation(JsonProperty.class);
        JsonProperty appealImages = Complaint.class.getDeclaredField("appealImages").getAnnotation(JsonProperty.class);

        assertThat(tableField).isNotNull();
        assertThat(tableField.exist()).isFalse();
        assertThat(attachmentIdsJson.access()).isEqualTo(JsonProperty.Access.WRITE_ONLY);
        assertThat(images.access()).isEqualTo(JsonProperty.Access.WRITE_ONLY);
        assertThat(appealImages.access()).isEqualTo(JsonProperty.Access.WRITE_ONLY);
    }
}
