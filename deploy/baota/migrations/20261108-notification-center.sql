SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='wechat_notice_queue' AND column_name='dedupe_key')=0,
  'ALTER TABLE wechat_notice_queue ADD COLUMN dedupe_key VARCHAR(64) NULL',
  'SELECT 1');
PREPARE notification_dedupe_stmt FROM @sql;
EXECUTE notification_dedupe_stmt;
DEALLOCATE PREPARE notification_dedupe_stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wechat_notice_queue' AND index_name='uk_wechat_notice_dedupe')=0,
  'ALTER TABLE wechat_notice_queue ADD UNIQUE KEY uk_wechat_notice_dedupe(dedupe_key)',
  'SELECT 1');
PREPARE notification_dedupe_index_stmt FROM @sql;
EXECUTE notification_dedupe_index_stmt;
DEALLOCATE PREPARE notification_dedupe_index_stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wechat_notice_queue' AND index_name='idx_wechat_notice_delivery')=0,
  'ALTER TABLE wechat_notice_queue ADD KEY idx_wechat_notice_delivery(status,next_at)',
  'SELECT 1');
PREPARE notification_delivery_index_stmt FROM @sql;
EXECUTE notification_delivery_index_stmt;
DEALLOCATE PREPARE notification_delivery_index_stmt;

CREATE TABLE IF NOT EXISTS sms_notice_queue (
  id VARCHAR(36) PRIMARY KEY,
  dedupe_key CHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  event_group VARCHAR(32) NOT NULL,
  user_type VARCHAR(16) NOT NULL,
  user_id BIGINT NOT NULL,
  order_id BIGINT NULL,
  phone VARCHAR(32) NOT NULL,
  message VARCHAR(1000) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(100) NULL,
  next_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_at TIMESTAMP NULL,
  UNIQUE KEY uk_sms_notice_dedupe (dedupe_key),
  KEY idx_sms_notice_delivery (status, next_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务短信通知队列';
