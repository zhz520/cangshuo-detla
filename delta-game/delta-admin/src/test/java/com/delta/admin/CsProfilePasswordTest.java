package com.delta.admin;

import com.delta.common.exception.BusinessException;
import com.delta.cs.controller.CsProfileController;
import com.delta.cs.dto.AdminPasswordChangeRequest;
import com.delta.system.entity.Admin;
import com.delta.system.service.AdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CsProfilePasswordTest {
    private AdminService adminService;
    private PasswordEncoder passwordEncoder;
    private CsProfileController controller;

    @BeforeEach
    void setUp() {
        adminService = mock(AdminService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        controller = new CsProfileController(adminService, passwordEncoder);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                Map.of("userId", 2L, "userType", "CS"), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void changesOnlyCurrentStaffPasswordAndResetsLockState() {
        Admin current = new Admin();
        current.setId(2L);
        current.setPassword("old-hash");
        when(adminService.getById(2L)).thenReturn(current);
        when(passwordEncoder.matches("OldPass123", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("NewPass456", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewPass456")).thenReturn("new-hash");

        controller.changePassword(new AdminPasswordChangeRequest("OldPass123", "NewPass456", "NewPass456"));

        ArgumentCaptor<Admin> update = ArgumentCaptor.forClass(Admin.class);
        verify(adminService).updateById(update.capture());
        assertThat(update.getValue().getId()).isEqualTo(2L);
        assertThat(update.getValue().getPassword()).isEqualTo("new-hash");
        assertThat(update.getValue().getLoginFailCount()).isZero();
        assertThat(update.getValue().getLockTime()).isNull();
        verify(passwordEncoder, never()).encode("OldPass123");
    }

    @Test
    void rejectsWrongOldPasswordMismatchAndWeakPasswordWithoutUpdating() {
        Admin current = new Admin();
        current.setId(2L);
        current.setPassword("old-hash");
        when(adminService.getById(2L)).thenReturn(current);
        when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);
        when(passwordEncoder.matches("OldPass123", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> controller.changePassword(
                new AdminPasswordChangeRequest("wrong", "NewPass456", "NewPass456")))
                .isInstanceOf(BusinessException.class).hasMessage("原密码错误");
        assertThatThrownBy(() -> controller.changePassword(
                new AdminPasswordChangeRequest("OldPass123", "NewPass456", "Different456")))
                .isInstanceOf(BusinessException.class).hasMessage("两次输入的新密码不一致");
        assertThatThrownBy(() -> controller.changePassword(
                new AdminPasswordChangeRequest("OldPass123", "weak", "weak")))
                .isInstanceOf(BusinessException.class);

        verify(adminService, never()).updateById(any());
    }
}
