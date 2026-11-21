package com.delta.order.service.impl;

import com.delta.common.exception.BusinessException;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.tenant.TenantContext;
import com.delta.order.dto.ComplaintAttachmentContent;
import com.delta.order.dto.ComplaintAttachmentView;
import com.delta.order.entity.Complaint;
import com.delta.order.entity.Order;
import com.delta.order.service.ComplaintAttachmentTokenCodec;
import com.delta.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComplaintAttachmentServiceImplTest {
    @Mock OrderService orderService;
    @Mock CrossModuleMapper crossModuleMapper;
    @Mock JdbcTemplate jdbc;
    @TempDir Path uploads;

    private ComplaintAttachmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ComplaintAttachmentServiceImpl(orderService, crossModuleMapper, jdbc,
                new ComplaintAttachmentTokenCodec("test-secret-that-is-long-enough-for-attachment-tokens-2026"));
        ReflectionTestUtils.setField(service, "uploadPath", uploads.toString());
    }

    @Test
    void uploadReencodesIntoTheTenantPrivateDirectoryAndRequiresBinding() throws Exception {
        when(orderService.getById(10L)).thenReturn(order(10L, 7L));
        String token;
        try (var ignored = TenantContext.open("tenant-a")) {
            token = service.upload(10L, 7L, "USER", png());

            Path directory = uploads.resolve("tenant-a/.private/complaints");
            List<Path> files;
            try (var stream = Files.list(directory)) { files = stream.toList(); }
            assertThat(files).hasSize(1);
            assertThat(files.get(0).getFileName().toString()).matches("[a-f0-9]{32}\\.(jpg|png)");
            assertThat(ImageIO.read(files.get(0).toFile())).isNotNull();
            assertThat(token).startsWith("ca1.").doesNotContain("/file/").doesNotContain("http");
            assertThat(service.loadForOwner(token, 8L, "USER")).isEmpty();
        }

        when(orderService.getById(10L)).thenReturn(order(10L, 7L));
        when(jdbc.queryForList(anyString(), eq(10L))).thenReturn(List.of());
        try (var ignored = TenantContext.open("tenant-a")) {
            assertThat(service.loadForOwner(token, 7L, "USER")).isEmpty();
        }

        when(jdbc.queryForList(anyString(), eq(10L))).thenReturn(List.of(Map.of(
                "images", token, "appeal_images", "")));
        try (var ignored = TenantContext.open("tenant-a")) {
            Optional<ComplaintAttachmentContent> content = service.loadForOwner(token, 7L, "USER");
            assertThat(content).isPresent();
            assertThat(content.get().contentType()).isEqualTo("image/png");
            assertThat(content.get().bytes()).isNotEmpty();
            assertThat(service.loadForOwner(token, 8L, "USER")).isEmpty();
            assertThat(service.loadForOwner(token, 7L, "GUEST")).isEmpty();
        }
    }

    @Test
    void tokensCannotCrossOrdersTenantsOrLegacyPublicUrls() throws Exception {
        when(orderService.getById(10L)).thenReturn(order(10L, 7L));
        String token;
        try (var ignored = TenantContext.open("tenant-a")) {
            token = service.upload(10L, 7L, "USER", png());
            assertThatThrownBy(() -> service.bindToComplaint(11L, 7L, "USER", List.of(token), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("投诉附件不属于该订单");
            assertThatThrownBy(() -> service.bindToComplaint(10L, 8L, "USER", List.of(token), null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("投诉附件不属于当前用户");
            assertThatThrownBy(() -> service.bindToComplaint(10L, 7L, "USER", null,
                    "/file/2026/11/21/public.png"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("投诉附件无效，请重新上传");
        }
        try (var ignored = TenantContext.open("tenant-b")) {
            assertThat(service.loadForOwner(token, 7L, "USER")).isEmpty();
        }
    }

    @Test
    void detailMarksLegacyPublicImagesUnavailableWithoutReturningTheirUrl() throws Exception {
        when(orderService.getById(10L)).thenReturn(order(10L, 7L));
        String token;
        try (var ignored = TenantContext.open("tenant-a")) {
            token = service.upload(10L, 7L, "USER", png());
            List<ComplaintAttachmentView> views = service.views(
                    token + ",/file/2026/11/21/public.png", 10L);

            assertThat(views).hasSize(2);
            assertThat(views.get(0).getToken()).isEqualTo(token);
            assertThat(views.get(0).getKind()).isEqualTo(ComplaintAttachmentView.KIND_IMAGE);
            assertThat(views.get(1).getToken()).isNull();
            assertThat(views.get(1).getKind()).isEqualTo(ComplaintAttachmentView.KIND_UNAVAILABLE);
        }
    }

    @Test
    void nonImageUploadIsRejectedBeforeWritingAPrivateFile() {
        when(orderService.getById(10L)).thenReturn(order(10L, 7L));
        try (var ignored = TenantContext.open("tenant-a")) {
            assertThatThrownBy(() -> service.upload(10L, 7L, "USER",
                    new MockMultipartFile("file", "proof.png", "image/png", "not-an-image".getBytes())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("仅支持图片证据");
        }
        assertThat(uploads.resolve("tenant-a/.private/complaints")).doesNotExist();
        verify(jdbc, never()).queryForList(anyString(), eq(10L));
    }

    @Test
    void staffReadRequiresTheTokenToBeBoundToThatComplaintAndOrder() throws Exception {
        when(orderService.getById(10L)).thenReturn(order(10L, 7L));
        String token;
        try (var ignored = TenantContext.open("tenant-a")) {
            token = service.upload(10L, 7L, "USER", png());
            Complaint complaint = new Complaint();
            complaint.setOrderId(10L);

            assertThat(service.loadForStaff(complaint, token)).isEmpty();

            Complaint otherOrder = new Complaint();
            otherOrder.setOrderId(11L);
            otherOrder.setImages(token);
            assertThat(service.loadForStaff(otherOrder, token)).isEmpty();

            complaint.setImages(token);
            assertThat(service.loadForStaff(complaint, token)).isPresent();
        }
    }

    private static Order order(Long id, Long userId) {
        Order order = new Order();
        order.setId(id);
        order.setUserId(userId);
        order.setStatus("IN_PROGRESS");
        return order;
    }

    private static MockMultipartFile png() throws Exception {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0x80ff0000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new MockMultipartFile("file", "evidence.png", "image/png", bytes.toByteArray());
    }
}
