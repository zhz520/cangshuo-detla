package com.delta.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Removes abandoned, unsubmitted ID photos without touching pending review records. */
@Component
@RequiredArgsConstructor
public class IdentityVerificationCleanup {
    private final JdbcTemplate jdbc;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void clearAbandonedDrafts() {
        jdbc.update("UPDATE identity_verification SET front_image=NULL,front_mime=NULL,back_image=NULL,back_mime=NULL,updated_at=CURRENT_TIMESTAMP WHERE status='DRAFT' AND updated_at<?",
                LocalDateTime.now().minusHours(24));
        jdbc.update("UPDATE identity_verification SET status='REJECTED',review_note='审核等待超过30天，请重新提交',front_image=NULL,front_mime=NULL,back_image=NULL,back_mime=NULL,reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE status='PENDING' AND submitted_at<?",
                LocalDateTime.now().minusDays(30));
    }
}
