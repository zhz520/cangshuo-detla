CREATE TABLE IF NOT EXISTS wechat_mp_account (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  app_id VARCHAR(64) NOT NULL,
  app_secret_encrypted VARCHAR(512) NULL,
  callback_token_encrypted VARCHAR(512) NULL,
  aes_key_encrypted VARCHAR(512) NULL,
  account_mode VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
  is_default TINYINT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  last_checked_at TIMESTAMP NULL,
  last_error VARCHAR(160) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wechat_mp_app_id(app_id),
  KEY idx_wechat_mp_default(is_default, enabled)
);

CREATE TABLE IF NOT EXISTS wechat_mp_template_catalog (
  account_id VARCHAR(36) NOT NULL,
  template_id VARCHAR(128) NOT NULL,
  title VARCHAR(160) NOT NULL,
  content TEXT NULL,
  primary_industry VARCHAR(80) NULL,
  deputy_industry VARCHAR(80) NULL,
  synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(account_id, template_id)
);

CREATE TABLE IF NOT EXISTS wechat_mp_template (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  role VARCHAR(16) NOT NULL,
  scenario VARCHAR(32) NOT NULL,
  template_id VARCHAR(128) NOT NULL,
  fields_json TEXT NOT NULL,
  url VARCHAR(2000) NULL,
  miniprogram_json VARCHAR(1000) NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
  enabled TINYINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wechat_mp_template(account_id, role, scenario),
  KEY idx_wechat_mp_template_account(account_id, scenario)
);

CREATE TABLE IF NOT EXISTS wechat_menu_draft (
  account_id VARCHAR(36) PRIMARY KEY,
  buttons_json TEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  remote_json TEXT NULL,
  published_at TIMESTAMP NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wechat_autoreply_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  rule_type VARCHAR(16) NOT NULL DEFAULT 'KEYWORD',
  keyword VARCHAR(80) NULL,
  match_mode VARCHAR(16) NOT NULL DEFAULT 'CONTAINS',
  reply_type VARCHAR(16) NOT NULL DEFAULT 'TEXT',
  content TEXT NULL,
  media_id VARCHAR(128) NULL,
  priority INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_wechat_autoreply_account(account_id, enabled, priority)
);

CREATE TABLE IF NOT EXISTS wechat_channel_qr (
  id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  name VARCHAR(80) NOT NULL,
  scene VARCHAR(64) NOT NULL,
  qr_type VARCHAR(16) NOT NULL DEFAULT 'TEMP',
  ticket VARCHAR(256) NULL,
  image_url VARCHAR(512) NULL,
  expire_seconds INT NULL,
  scans INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_wechat_qr_scene(account_id, scene)
);

CREATE TABLE IF NOT EXISTS wechat_fan (
  account_id VARCHAR(36) NOT NULL,
  openid VARCHAR(128) NOT NULL,
  nickname VARCHAR(128) NULL,
  avatar VARCHAR(512) NULL,
  subscribed TINYINT NOT NULL DEFAULT 1,
  tags_json TEXT NULL,
  last_sync_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(account_id, openid)
);
