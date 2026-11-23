CREATE TABLE IF NOT EXISTS wechat_notice_sender (
  id INT PRIMARY KEY,
  mode VARCHAR(16) NOT NULL,
  account_id VARCHAR(36) NOT NULL
);

-- Only server-verified, sender-scoped OAuth identities belong here (never backfill login bindings).
CREATE TABLE IF NOT EXISTS wechat_notice_recipient (
  app_id VARCHAR(64) NOT NULL,
  user_type VARCHAR(16) NOT NULL,
  user_id BIGINT NOT NULL,
  openid VARCHAR(128) NOT NULL,
  PRIMARY KEY(app_id,user_type,user_id),
  UNIQUE(app_id,openid)
);
