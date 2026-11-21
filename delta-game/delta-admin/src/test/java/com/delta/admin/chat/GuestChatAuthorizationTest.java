package com.delta.admin.chat;

import com.delta.common.chat.controller.ChatController;
import com.delta.common.chat.domain.entity.ChatSession;
import com.delta.common.chat.service.ChatAccessService;
import com.delta.common.chat.service.ChatServiceImpl;
import com.delta.common.chat.service.ChatSmsReminderService;
import com.delta.common.chat.util.ChatParticipantId;
import com.delta.common.customer.CsRoutingService;
import com.delta.common.security.service.RateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuestChatAuthorizationTest {
    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void guestCannotStartMemberConversation() {
        ChatAccessService access = mock(ChatAccessService.class);
        ChatController controller = new ChatController(mock(ChatServiceImpl.class), mock(ChatSmsReminderService.class),
                access, mock(RateLimitService.class), mock(CsRoutingService.class));
        loginGuest();

        var result = controller.createSession(ChatParticipantId.encodeUser(7), ChatParticipantId.encodeUser(8));

        assertThat(result.getCode()).isEqualTo(403);
        verifyNoInteractions(access);
    }

    @Test void guestCannotStartUnrelatedPlayerConversation() {
        ChatAccessService access = mock(ChatAccessService.class);
        when(access.canGuestContactPlayer(7L, 1L)).thenReturn(false);
        ChatController controller = new ChatController(mock(ChatServiceImpl.class), mock(ChatSmsReminderService.class),
                access, mock(RateLimitService.class), mock(CsRoutingService.class));
        loginGuest();

        var result = controller.createSession(ChatParticipantId.encodeUser(7), ChatParticipantId.encodePlayer(1));

        assertThat(result.getCode()).isEqualTo(403);
    }

    @Test void userCanRateClosedCustomerServiceSession() {
        ChatServiceImpl chatService = mock(ChatServiceImpl.class);
        ChatSession session = new ChatSession();
        session.setId(11L);
        session.setId1(ChatParticipantId.encodeUser(7));
        session.setId2(ChatParticipantId.encodeCs(9));
        session.setStatus("CLOSED");
        when(chatService.getById(11L)).thenReturn(session);
        ChatController controller = new ChatController(chatService, mock(ChatSmsReminderService.class),
                mock(ChatAccessService.class), mock(RateLimitService.class), mock(CsRoutingService.class));
        loginUser();

        var result = controller.rateSession(11L, Map.of("rating", 5, "comment", "响应及时"), null);

        assertThat(result.getCode()).isEqualTo(200);
        verify(chatService).updateById(argThat(saved ->
                saved.getRating() == 5 && "响应及时".equals(saved.getRatingComment()) && saved.getRatedAt() != null));
    }

    @Test void customerServiceRatingRequiresClosedSessionAndOnlyOneReview() {
        ChatServiceImpl chatService = mock(ChatServiceImpl.class);
        ChatSession session = new ChatSession();
        session.setId(12L);
        session.setId1(ChatParticipantId.encodeUser(7));
        session.setId2(ChatParticipantId.encodeCs(9));
        session.setStatus("ACTIVE");
        when(chatService.getById(12L)).thenReturn(session);
        ChatController controller = new ChatController(chatService, mock(ChatSmsReminderService.class),
                mock(ChatAccessService.class), mock(RateLimitService.class), mock(CsRoutingService.class));
        loginUser();

        assertThat(controller.rateSession(12L, Map.of("rating", 5), null).getCode()).isEqualTo(409);
        verify(chatService, never()).updateById(any(ChatSession.class));
    }

    private void loginGuest() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 7L, "userType", "GUEST"), null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
    }

    private void loginUser() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 7L, "userType", "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
