package com.delta.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** JDBC persistence for identity submissions and review queries. */
@Service
@RequiredArgsConstructor
public class IdentityVerificationStore {
    private final JdbcTemplate jdbc;

    public List<Map<String, Object>> status(Long userId) {
        return jdbc.queryForList("SELECT real_name,id_number_masked,status,review_note,submitted_at,reviewed_at,front_image IS NOT NULL AS has_front,back_image IS NOT NULL AS has_back FROM identity_verification WHERE user_id=?", userId);
    }

    public List<Map<String, Object>> lockStatus(Long userId) {
        return jdbc.queryForList("SELECT status FROM identity_verification WHERE user_id=? FOR UPDATE", userId);
    }

    public List<Map<String, Object>> lockStatusAndImages(Long userId) {
        return jdbc.queryForList("SELECT status,front_image,back_image FROM identity_verification WHERE user_id=? FOR UPDATE", userId);
    }

    public void saveImage(Long userId, String side, String base64, String mimeType) {
        if ("front".equals(side)) {
            jdbc.update("INSERT INTO identity_verification(user_id,status,front_image,front_mime) VALUES (?,'DRAFT',?,?) ON DUPLICATE KEY UPDATE front_image=VALUES(front_image),front_mime=VALUES(front_mime),status=CASE WHEN status='REJECTED' THEN 'DRAFT' ELSE status END,updated_at=CURRENT_TIMESTAMP", userId, base64, mimeType);
        } else {
            jdbc.update("INSERT INTO identity_verification(user_id,status,back_image,back_mime) VALUES (?,'DRAFT',?,?) ON DUPLICATE KEY UPDATE back_image=VALUES(back_image),back_mime=VALUES(back_mime),status=CASE WHEN status='REJECTED' THEN 'DRAFT' ELSE status END,updated_at=CURRENT_TIMESTAMP", userId, base64, mimeType);
        }
    }

    public List<Map<String, Object>> findByHash(String idHash, Long userId) {
        return jdbc.queryForList("SELECT user_id FROM identity_verification WHERE id_number_hash=? AND user_id<>? LIMIT 1", idHash, userId);
    }

    public int submit(Long userId, String realName, String idHash, String maskedId, String privacyVersion) {
        return jdbc.update("UPDATE identity_verification SET real_name=?,id_number_hash=?,id_number_masked=?,privacy_version=?,privacy_accepted_at=CURRENT_TIMESTAMP,status='PENDING',review_note='',reviewer_id=NULL,submitted_at=CURRENT_TIMESTAMP,reviewed_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", realName, idHash, maskedId, privacyVersion, userId);
    }

    public List<Map<String, Object>> statusOnly(Long userId) {
        return jdbc.queryForList("SELECT status FROM identity_verification WHERE user_id=?", userId);
    }

    public void insertApproved(Long userId, String realName, String idHash, String maskedId, String privacyVersion) {
        jdbc.update("INSERT INTO identity_verification(user_id,real_name,id_number_hash,id_number_masked,status,privacy_version,privacy_accepted_at,review_note,submitted_at,reviewed_at) VALUES(?,?,?,?,'APPROVED',?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", userId, realName, idHash, maskedId, privacyVersion, "授权中心二要素核验通过");
    }

    public void updateApproved(Long userId, String realName, String idHash, String maskedId, String privacyVersion) {
        jdbc.update("UPDATE identity_verification SET real_name=?,id_number_hash=?,id_number_masked=?,status='APPROVED',privacy_version=?,privacy_accepted_at=CURRENT_TIMESTAMP,review_note=?,reviewer_id=NULL,submitted_at=CURRENT_TIMESTAMP,reviewed_at=CURRENT_TIMESTAMP,front_image=NULL,front_mime=NULL,back_image=NULL,back_mime=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", realName, idHash, maskedId, privacyVersion, "授权中心二要素核验通过", userId);
    }

    public List<Map<String, Object>> applications(String normalizedStatus, Long taskId) {
        return jdbc.queryForList("SELECT iv.user_id,iv.real_name,iv.id_number_masked,iv.status,iv.review_note,iv.submitted_at,iv.reviewed_at,u.nickname,u.phone FROM identity_verification iv JOIN `user` u ON u.id=iv.user_id WHERE iv.status<>'DRAFT' AND (?='' OR iv.status=?) AND (? IS NULL OR iv.user_id=?) ORDER BY CASE WHEN iv.status='PENDING' THEN 0 ELSE 1 END,iv.submitted_at DESC LIMIT 500", normalizedStatus, normalizedStatus, taskId, taskId);
    }

    public List<Map<String, Object>> detail(Long userId) {
        return jdbc.queryForList("SELECT iv.*,u.nickname,u.phone FROM identity_verification iv JOIN `user` u ON u.id=iv.user_id WHERE iv.user_id=?", userId);
    }

    public void review(Long userId, boolean approved, String note, Long reviewerId) {
        jdbc.update("UPDATE identity_verification SET status=?,review_note=?,reviewer_id=?,reviewed_at=CURRENT_TIMESTAMP,front_image=NULL,front_mime=NULL,back_image=NULL,back_mime=NULL,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", approved ? "APPROVED" : "REJECTED", note, reviewerId, userId);
    }
}
