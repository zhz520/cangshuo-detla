CREATE TABLE IF NOT EXISTS staff_role (
 code VARCHAR(64) PRIMARY KEY, name VARCHAR(100) NOT NULL, permissions TEXT NOT NULL,
 enabled INT NOT NULL DEFAULT 1, builtin INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS staff_role_member (
 admin_id BIGINT PRIMARY KEY, role_code VARCHAR(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS player_invitation (
 token VARCHAR(64) PRIMARY KEY, name VARCHAR(100) NOT NULL, referrer_id BIGINT NOT NULL,
 enabled INT NOT NULL DEFAULT 1, one_device INT NOT NULL DEFAULT 1,
 expires_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS player_enrollment (
 user_id BIGINT PRIMARY KEY, player_id BIGINT NULL, invite_token VARCHAR(64), referrer_id BIGINT,
 device_hash VARCHAR(64) UNIQUE, privacy_version VARCHAR(40) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS player_homepage_revision (
 id VARCHAR(36) PRIMARY KEY, player_id BIGINT NOT NULL, payload TEXT NOT NULL,
 violation INT NOT NULL DEFAULT 0, status VARCHAR(32) NOT NULL, risk_reason VARCHAR(500), reviewer_id BIGINT, review_note VARCHAR(500),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at TIMESTAMP NULL
);
CREATE TABLE IF NOT EXISTS privacy_request (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, kind VARCHAR(32) NOT NULL,
 status VARCHAR(32) NOT NULL, note VARCHAR(500), reviewer_id BIGINT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at TIMESTAMP NULL
);
CREATE TABLE IF NOT EXISTS staff_shift (
 admin_id BIGINT PRIMARY KEY, online INT NOT NULL DEFAULT 0, changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS order_finance_snapshot (
 order_id BIGINT PRIMARY KEY, cs_rate DECIMAL(8,6) NOT NULL DEFAULT 0, cs_id BIGINT NULL
);
CREATE TABLE IF NOT EXISTS order_settlement_ledger (
 order_id BIGINT PRIMARY KEY, amount DECIMAL(16,2) NOT NULL, club_amount DECIMAL(16,2) NOT NULL,
 player_amount DECIMAL(16,2) NOT NULL, cs_amount DECIMAL(16,2) NOT NULL, cs_id BIGINT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS wechat_official_binding (
 user_type VARCHAR(16) NOT NULL, user_id BIGINT NOT NULL, openid VARCHAR(128) NOT NULL,
 PRIMARY KEY(user_type,user_id), UNIQUE(openid,user_type)
);
CREATE TABLE IF NOT EXISTS identity_verification (
  user_id BIGINT PRIMARY KEY, real_name VARCHAR(64) NOT NULL DEFAULT '', id_number_hash CHAR(64) NULL,
  id_number_masked VARCHAR(32) NOT NULL DEFAULT '', front_image LONGTEXT NULL, front_mime VARCHAR(64) NULL,
  back_image LONGTEXT NULL, back_mime VARCHAR(64) NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  privacy_version VARCHAR(32) NOT NULL DEFAULT '', privacy_accepted_at TIMESTAMP NULL,
  review_note VARCHAR(500) NOT NULL DEFAULT '', reviewer_id BIGINT NULL, submitted_at TIMESTAMP NULL,
  reviewed_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_identity_document(id_number_hash)
);
CREATE TABLE IF NOT EXISTS player_refund_recovery (
 order_id BIGINT NOT NULL, player_id BIGINT NOT NULL, refund_share DECIMAL(16,2) NOT NULL,
 recovered DECIMAL(16,2) NOT NULL, remaining DECIMAL(16,2) NOT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(order_id,player_id)
);
CREATE TABLE IF NOT EXISTS order_settlement_refund (
 order_id BIGINT PRIMARY KEY, refund_amount DECIMAL(16,2) NOT NULL,
 club_refund DECIMAL(16,2) NOT NULL, player_refund DECIMAL(16,2) NOT NULL,
 cs_refund DECIMAL(16,2) NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS wechat_notice_queue (
 id VARCHAR(36) PRIMARY KEY, dedupe_key VARCHAR(64) NULL, event_type VARCHAR(64) NOT NULL, user_type VARCHAR(16) NOT NULL,
 user_id BIGINT NOT NULL, order_id BIGINT, message VARCHAR(1000) NOT NULL,
 status VARCHAR(32) NOT NULL, attempts INT NOT NULL DEFAULT 0, last_error VARCHAR(100),
 next_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_wechat_notice_dedupe(dedupe_key), KEY idx_wechat_notice_delivery(status,next_at)
);
CREATE TABLE IF NOT EXISTS sms_notice_queue (
 id VARCHAR(36) PRIMARY KEY, dedupe_key CHAR(64) NOT NULL, event_type VARCHAR(64) NOT NULL,
 event_group VARCHAR(32) NOT NULL, user_type VARCHAR(16) NOT NULL, user_id BIGINT NOT NULL,
 order_id BIGINT NULL, phone VARCHAR(32) NOT NULL, message VARCHAR(1000) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(100) NULL, next_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, sent_at TIMESTAMP NULL,
 CONSTRAINT uk_sms_notice_dedupe UNIQUE(dedupe_key)
);
CREATE TABLE IF NOT EXISTS email_notice_queue (
 id VARCHAR(36) PRIMARY KEY, event_type VARCHAR(64) NOT NULL, event_hash CHAR(64) NOT NULL,
 recipient VARCHAR(254) NOT NULL, subject VARCHAR(255) NOT NULL, plain_body TEXT NOT NULL,
 html_body TEXT NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(100), next_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, sent_at TIMESTAMP NULL,
 CONSTRAINT uk_email_notice_event_recipient UNIQUE(event_hash, recipient),
 INDEX idx_email_notice_delivery(status,next_at)
);
