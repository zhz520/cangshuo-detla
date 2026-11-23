CREATE TABLE IF NOT EXISTS staff_role (
 code VARCHAR(64) PRIMARY KEY, name VARCHAR(100) NOT NULL, permissions TEXT NOT NULL,
 enabled INT NOT NULL DEFAULT 1, builtin INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS schema_migrations (
  id VARCHAR(128) PRIMARY KEY, checksum CHAR(64) NOT NULL, source VARCHAR(32) NOT NULL,
  applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
 status VARCHAR(32) NOT NULL, note VARCHAR(500), review_note VARCHAR(500), reviewer_id BIGINT,
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
 PRIMARY KEY(user_type,user_id), UNIQUE KEY uk_wechat_official_openid(openid)
);
CREATE TABLE IF NOT EXISTS wechat_official_binding_conflict_backup (
  user_type VARCHAR(16) NOT NULL, user_id BIGINT NOT NULL, openid VARCHAR(128) NOT NULL,
  archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_type,user_id)
);
CREATE TABLE IF NOT EXISTS identity_verification (
  user_id BIGINT PRIMARY KEY, real_name VARCHAR(64) NOT NULL DEFAULT '', id_number_hash CHAR(64) NULL,
  id_number_masked VARCHAR(32) NOT NULL DEFAULT '', front_image LONGTEXT NULL, front_mime VARCHAR(64) NULL,
  back_image LONGTEXT NULL, back_mime VARCHAR(64) NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  failed_attempts INT NOT NULL DEFAULT 0, last_failed_at TIMESTAMP NULL, locked_until TIMESTAMP NULL,
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
CREATE TABLE IF NOT EXISTS wechat_mp_account (
  id VARCHAR(36) PRIMARY KEY, name VARCHAR(80) NOT NULL, app_id VARCHAR(64) NOT NULL,
  app_secret_encrypted VARCHAR(512) NULL, callback_token_encrypted VARCHAR(512) NULL,
  aes_key_encrypted VARCHAR(512) NULL, account_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
  is_default TINYINT NOT NULL DEFAULT 0, enabled TINYINT NOT NULL DEFAULT 1,
  last_checked_at TIMESTAMP NULL, last_error VARCHAR(160) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wechat_mp_app_id(app_id), KEY idx_wechat_mp_default(is_default,enabled)
);
CREATE TABLE IF NOT EXISTS wechat_mp_template_catalog (
  account_id VARCHAR(36) NOT NULL, template_id VARCHAR(128) NOT NULL, title VARCHAR(160) NOT NULL,
  content TEXT NULL, primary_industry VARCHAR(80) NULL, deputy_industry VARCHAR(80) NULL,
  synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(account_id,template_id)
);
CREATE TABLE IF NOT EXISTS wechat_mp_template (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id VARCHAR(36) NOT NULL,
  role VARCHAR(16) NOT NULL, scenario VARCHAR(32) NOT NULL, template_id VARCHAR(128) NOT NULL,
  fields_json TEXT NOT NULL, url VARCHAR(2000) NULL, miniprogram_json VARCHAR(1000) NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'LOCAL', enabled TINYINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wechat_mp_template(account_id,role,scenario),
  KEY idx_wechat_mp_template_account(account_id,scenario)
);
CREATE TABLE IF NOT EXISTS wechat_menu_draft (
  account_id VARCHAR(36) PRIMARY KEY, buttons_json TEXT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  remote_json TEXT NULL, published_at TIMESTAMP NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS wechat_autoreply_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id VARCHAR(36) NOT NULL, rule_type VARCHAR(16) NOT NULL DEFAULT 'KEYWORD',
  keyword VARCHAR(80) NULL, match_mode VARCHAR(16) NOT NULL DEFAULT 'CONTAINS', reply_type VARCHAR(16) NOT NULL DEFAULT 'TEXT',
  content TEXT NULL, media_id VARCHAR(128) NULL, priority INT NOT NULL DEFAULT 0, enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_wechat_autoreply_account(account_id,enabled,priority)
);
CREATE TABLE IF NOT EXISTS wechat_channel_qr (
  id VARCHAR(36) PRIMARY KEY, account_id VARCHAR(36) NOT NULL, name VARCHAR(80) NOT NULL, scene VARCHAR(64) NOT NULL,
  qr_type VARCHAR(16) NOT NULL DEFAULT 'TEMP', ticket VARCHAR(256) NULL, image_url VARCHAR(512) NULL, expire_seconds INT NULL,
  scans INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wechat_qr_scene(account_id,scene)
);
CREATE TABLE IF NOT EXISTS wechat_fan (
  account_id VARCHAR(36) NOT NULL, openid VARCHAR(128) NOT NULL, nickname VARCHAR(128) NULL, avatar VARCHAR(512) NULL,
  subscribed TINYINT NOT NULL DEFAULT 1, tags_json TEXT NULL, last_sync_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(account_id,openid)
);
CREATE TABLE IF NOT EXISTS wechat_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id VARCHAR(36) NOT NULL, msg_id VARCHAR(128) NULL,
  openid VARCHAR(128) NOT NULL, direction VARCHAR(16) NOT NULL DEFAULT 'IN', msg_type VARCHAR(24) NOT NULL,
  content TEXT NULL, event_key VARCHAR(128) NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wechat_message_msgid(account_id,msg_id), KEY idx_wechat_message_time(account_id,created_at)
);
CREATE TABLE IF NOT EXISTS sms_notice_queue (
 id VARCHAR(36) PRIMARY KEY, dedupe_key CHAR(64) NOT NULL, delivery_key VARCHAR(64) NULL, event_type VARCHAR(64) NOT NULL,
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
