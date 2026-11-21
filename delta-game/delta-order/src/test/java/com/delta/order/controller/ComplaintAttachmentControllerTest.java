package com.delta.order.controller;

import com.delta.common.security.service.RateLimitService;
import com.delta.order.dto.ComplaintAttachmentContent;
import com.delta.order.service.ComplaintAttachmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ComplaintAttachmentControllerTest {
    private ComplaintAttachmentService attachments;
    private RateLimitService limits;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        attachments = mock(ComplaintAttachmentService.class);
        limits = mock(RateLimitService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ComplaintAttachmentController(attachments, limits)).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 7L, "userType", "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadReturnsOnlyTheOpaqueToken() throws Exception {
        when(attachments.upload(eq(10L), eq(7L), eq("USER"), any())).thenReturn("ca1.opaque");

        mvc.perform(multipart("/order/complaint/attachment")
                        .file(new MockMultipartFile("file", "proof.png", "image/png", new byte[]{1}))
                        .param("orderId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("ca1.opaque"));

        verify(limits).check("complaint-attachment-upload:7", 20, 3600);
    }

    @Test
    void downloadIsBinaryAndDisablesCachingSniffingAndReferrers() throws Exception {
        when(attachments.loadForOwner("ca1.opaque", 7L, "USER"))
                .thenReturn(Optional.of(new ComplaintAttachmentContent(new byte[]{1, 2, 3}, "image/png")));

        mvc.perform(get("/order/complaint/attachment/ca1.opaque"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void unknownOrCrossUserTokenIsNotFound() throws Exception {
        when(attachments.loadForOwner("ca1.opaque", 7L, "USER")).thenReturn(Optional.empty());

        mvc.perform(get("/order/complaint/attachment/ca1.opaque"))
                .andExpect(status().isNotFound());
    }
}
