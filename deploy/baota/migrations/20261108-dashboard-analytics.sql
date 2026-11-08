CREATE TABLE IF NOT EXISTS analytics_daily_visitor (
  visit_date DATE NOT NULL COMMENT 'Asia/Shanghai 业务日期',
  visitor_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'HMAC 访客键',
  device_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'HMAC IP/UA 摘要',
  user_type VARCHAR(16) NULL COMMENT '登录身份 USER/PLAYER/ADMIN/CS',
  user_id BIGINT NULL COMMENT '登录用户 ID',
  first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  hits BIGINT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (visit_date, visitor_key),
  KEY idx_analytics_daily_visitor_user (visit_date, user_type, user_id),
  KEY idx_analytics_daily_visitor_last_seen (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='匿名访客与登录活跃日汇总';
