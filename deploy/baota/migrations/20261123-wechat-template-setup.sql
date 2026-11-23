CREATE TABLE IF NOT EXISTS wechat_template_setup_job (
  id VARCHAR(70) PRIMARY KEY, kind VARCHAR(8) NOT NULL, status VARCHAR(16) NOT NULL,
  domain_name VARCHAR(255) NOT NULL, state_cipher VARCHAR(256) NULL, account_id VARCHAR(36) NULL,
  expires_at TIMESTAMP NULL, opened TINYINT NOT NULL DEFAULT 0, next_run TIMESTAMP NOT NULL,
  lease_id VARCHAR(36) NULL, report_json TEXT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_wechat_setup_due(status,next_run)
);
