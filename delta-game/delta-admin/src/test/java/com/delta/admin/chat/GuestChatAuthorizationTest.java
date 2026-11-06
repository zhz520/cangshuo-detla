package com.delta.admin.chat;

import com.delta.common.chat.controller.ChatController;
import com.delta.common.chat.service.ChatServiceImpl;
import com.delta.common.chat.service.ChatSmsReminderService;
import com.delta.common.chat.util.ChatParticipantId;
import com.delta.common.mapper.CrossModuleMapper;
import com.delta.common.security.service.RateLimitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ChatController controller = new ChatController(mock(ChatServiceImpl.class), mock(ChatSmsReminderService.class),
                jdbc, mock(CrossModuleMapper.class), mock(RateLimitService.class));
        loginGuest();

        var result = controller.createSession(ChatParticipantId.encodeUser(7), ChatParticipantId.encodeUser(8));

        assertThat(result.getCode()).isEqualTo(403);
        verifyNoInteractions(jdbc);
    }

    @Test void guestCannotStartUnrelatedPlayerConversation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("FROM `order` WHERE user_id"), eq(Integer.class), eq(7L), eq(1L))).thenReturn(0);
        ChatController controller = new ChatController(mock(ChatServiceImpl.class), mock(ChatSmsReminderService.class),
                jdbc, mock(CrossModuleMapper.class), mock(RateLimitService.class));
        loginGuest();

        var result = controller.createSession(ChatParticipantId.encodeUser(7), ChatParticipantId.encodePlayer(1));

        assertThat(result.getCode()).isEqualTo(403);
    }

    private void loginGuest() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 7L, "userType", "GUEST"), null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
    }
}
