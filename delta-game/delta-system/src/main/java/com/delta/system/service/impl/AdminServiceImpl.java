package com.delta.system.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.AdminRolePolicy;
import com.delta.system.entity.Admin;
import com.delta.system.mapper.AdminMapper;
import com.delta.system.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl extends ServiceImpl<AdminMapper, Admin> implements AdminService {
    private final PasswordEncoder passwordEncoder;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean save(Admin entity) {
        entity.setUsername(entity.getUsername() == null ? null : entity.getUsername().trim());
        validateUsername(entity.getUsername());
        com.delta.common.security.utils.PasswordPolicy.validate(entity.getPassword());
        entity.setRole(AdminRolePolicy.normalize(entity.getRole()));
        if (!StringUtils.hasText(entity.getNickname())) entity.setNickname(entity.getUsername());
        if (entity.getStatus() == null) entity.setStatus(1);
        if (entity.getStatus() != 0 && entity.getStatus() != 1) throw new BusinessException(400, "账号状态无效");
        if (entity.getPhone() != null) entity.setPhone(entity.getPhone().trim());
        if (entity.getAvatar() == null) entity.setAvatar("");
        entity.setId(null);entity.setDeleted(0);entity.setLoginFailCount(0);entity.setLockTime(null);
        archiveDeletedOwner(entity.getUsername(), null);
        entity.setPassword(passwordEncoder.encode(entity.getPassword()));
        try {
            return super.save(entity);
        } catch (DuplicateKeyException error) {
            throw usernameExists();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean updateAdmin(Admin entity) {
        if (entity.getUsername() != null) {
            entity.setUsername(entity.getUsername().trim());
            validateUsername(entity.getUsername());
            archiveDeletedOwner(entity.getUsername(), entity.getId());
        }
        try {
            return super.updateById(entity);
        } catch (DuplicateKeyException error) {
            throw usernameExists();
        }
    }

    private void archiveDeletedOwner(String username, Long currentId) {
        Admin existing = baseMapper.findAnyByUsernameForUpdate(username);
        if (existing == null || (currentId != null && currentId.equals(existing.getId()))) return;
        if (!Integer.valueOf(1).equals(existing.getDeleted())) throw usernameExists();
        String suffix = "~deleted~" + existing.getId();
        int prefixLength = Math.max(0, Math.min(username.length(), 64 - suffix.length()));
        String archived = username.substring(0, prefixLength) + suffix;
        if (baseMapper.archiveDeletedUsername(existing.getId(), archived) != 1) {
            throw new BusinessException(409, "用户名正在被其他操作占用，请刷新后重试");
        }
    }

    private void validateUsername(String username) {
        if (username == null || !username.matches("[A-Za-z0-9_.@-]{3,64}")) {
            throw new BusinessException(400, "账号须为3～64位字母、数字或 _ . @ -");
        }
    }

    private BusinessException usernameExists() {
        return new BusinessException(409, "用户名已存在，请更换后重试");
    }
}
