package com.delta.cs.controller;

import com.delta.common.customer.CsRoutingService;
import com.delta.common.exception.BusinessException;
import com.delta.order.dto.ComplaintAttachmentContent;
import com.delta.order.entity.Complaint;
import com.delta.order.service.ComplaintAttachmentService;
import com.delta.order.service.ComplaintService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsComplaintAttachmentControllerTest {
    @Mock ComplaintService complaintService;
    @Mock ComplaintAttachmentService attachments;
    @Mock CsRoutingService csRouting;

    private CsComplaintAttachmentController controller;

    @BeforeEach
    void setUp() {
        controller = new CsComplaintAttachmentController(complaintService, attachments, csRouting);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 100L, "userType", "CS"), null,
                List.of(new SimpleGrantedAuthority("ROLE_CS"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void staffRequiresTheExistingOrderManageGrant() throws Exception {
        Method method = CsComplaintAttachmentController.class.getMethod("download", Long.class, String.class);
        PreAuthorize permission = method.getAnnotation(PreAuthorize.class);

        assertThat(permission).isNotNull();
        assertThat(permission.value()).isEqualTo("@staffPermissions.allowed('order.manage')");
    }

    @Test
    void staffCannotReadAnotherCsOwnersComplaint() {
        Complaint complaint = new Complaint();
        complaint.setId(5L);
        complaint.setOrderId(10L);
        when(complaintService.getById(5L)).thenReturn(complaint);
        doThrow(new BusinessException(403, "该订单归属其他客服，无法查看或处理"))
                .when(csRouting).assertCsCanHandleOrder(10L, 100L);

        ResponseEntity<byte[]> response = controller.download(5L, "ca1.opaque");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(attachments, never()).loadForStaff(complaint, "ca1.opaque");
    }

    @Test
    void authorizedStaffReadIsNoStoreAndNosniff() {
        Complaint complaint = new Complaint();
        complaint.setId(5L);
        complaint.setOrderId(10L);
        when(complaintService.getById(5L)).thenReturn(complaint);
        when(attachments.loadForStaff(complaint, "ca1.opaque"))
                .thenReturn(Optional.of(new ComplaintAttachmentContent(new byte[]{1}, "image/jpeg")));

        ResponseEntity<byte[]> response = controller.download(5L, "ca1.opaque");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store, private");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).containsExactly(1);
    }
}
