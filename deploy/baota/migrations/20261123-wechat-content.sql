CREATE TABLE IF NOT EXISTS wechat_article_draft (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  title VARCHAR(120) NOT NULL,
  author VARCHAR(64) NULL,
  digest VARCHAR(500) NULL,
  content_html LONGTEXT NULL,
  cover_url VARCHAR(512) NULL,
  remote_media_id VARCHAR(128) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_wechat_article_account(account_id, status, updated_at)
);

CREATE TABLE IF NOT EXISTS wechat_mass_send_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  name VARCHAR(80) NOT NULL,
  target_type VARCHAR(16) NOT NULL,
  target_json TEXT NULL,
  msg_type VARCHAR(16) NOT NULL,
  content_json TEXT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  created_by BIGINT NULL,
  approved_by BIGINT NULL,
  wechat_msg_id VARCHAR(128) NULL,
  error_text VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_wechat_mass_account(account_id, status, updated_at)
);
