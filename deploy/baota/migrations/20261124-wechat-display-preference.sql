CREATE TABLE IF NOT EXISTS wechat_mp_display_preference (
  account_id VARCHAR(36) NOT NULL, template_id VARCHAR(128) NOT NULL, field_key VARCHAR(64) NOT NULL,
  PRIMARY KEY(account_id,template_id)
);
