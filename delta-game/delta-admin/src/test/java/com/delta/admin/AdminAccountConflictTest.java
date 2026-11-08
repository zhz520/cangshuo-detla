package com.delta.admin;

import com.delta.common.exception.BusinessException;
import com.delta.system.entity.Admin;
import com.delta.system.mapper.AdminMapper;
import com.delta.system.service.impl.AdminServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAccountConflictTest {
    @Mock AdminMapper admins;
    @Mock PasswordEncoder passwords;

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminServiceImpl(passwords);
        ReflectionTestUtils.setField(service, "baseMapper", admins);
    }

    @Test
    void reusingADeletedUsernameArchivesTheOldRecordAndCreatesAFreshAccount() {
        Admin deleted = account(6L, "kfcs", 1);
        when(admins.findAnyByUsernameForUpdate("kfcs")).thenReturn(deleted);
        when(admins.archiveDeletedUsername(6L, "kfcs~deleted~6")).thenReturn(1);
        when(passwords.encode("Abcd1234")).thenReturn("encoded-password");
        when(admins.insert(any(Admin.class))).thenReturn(1);
        Admin input = creation("kfcs");

        assertThat(service.save(input)).isTrue();

        verify(admins).archiveDeletedUsername(6L, "kfcs~deleted~6");
        ArgumentCaptor<Admin> created = ArgumentCaptor.forClass(Admin.class);
        verify(admins).insert(created.capture());
        assertThat(created.getValue().getUsername()).isEqualTo("kfcs");
        assertThat(created.getValue().getPassword()).isEqualTo("encoded-password");
        assertThat(created.getValue().getDeleted()).isZero();
    }

    @Test
    void anActiveDuplicateUsernameReturnsAClearConflictInsteadOfAnIntegrityError() {
        when(admins.findAnyByUsernameForUpdate("kfcs")).thenReturn(account(8L, "kfcs", 0));

        assertThatThrownBy(() -> service.save(creation("kfcs")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(409))
                .hasMessageContaining("用户名已存在");
        verify(admins, never()).insert(any(Admin.class));
    }

    @Test
    void editingToAUsernameHeldByADeletedAccountArchivesTheOldRecord() {
        when(admins.findAnyByUsernameForUpdate("kfcs")).thenReturn(account(6L, "kfcs", 1));
        when(admins.archiveDeletedUsername(6L, "kfcs~deleted~6")).thenReturn(1);
        when(admins.updateById(any(Admin.class))).thenReturn(1);
        Admin update = new Admin();
        update.setId(9L);
        update.setUsername("kfcs");

        assertThat(service.updateAdmin(update)).isTrue();

        verify(admins).archiveDeletedUsername(6L, "kfcs~deleted~6");
        verify(admins).updateById(update);
    }

    private static Admin creation(String username) {
        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword("Abcd1234");
        admin.setNickname(username);
        admin.setRole("CS");
        admin.setStatus(1);
        return admin;
    }

    private static Admin account(Long id, String username, int deleted) {
        Admin admin = new Admin();
        admin.setId(id);
        admin.setUsername(username);
        admin.setDeleted(deleted);
        return admin;
    }
}
