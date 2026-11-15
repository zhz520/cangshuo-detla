-- 客服组与商品/分类客服分配规则（可重复执行）
CREATE TABLE IF NOT EXISTS cs_group (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  leader_id BIGINT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255) NOT NULL DEFAULT '',
  created_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS cs_group_member (
  group_id BIGINT NOT NULL,
  admin_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (group_id, admin_id),
  KEY idx_cs_group_member_admin (admin_id)
);

CREATE TABLE IF NOT EXISTS cs_routing_rule (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  match_type VARCHAR(16) NOT NULL,
  target_id BIGINT NOT NULL DEFAULT 0,
  assignee_type VARCHAR(16) NOT NULL DEFAULT 'CS',
  assignee_id BIGINT NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  remark VARCHAR(255) NOT NULL DEFAULT '',
  created_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cs_routing_scope (match_type, target_id),
  KEY idx_cs_routing_assignee (assignee_type, assignee_id)
);

SET @db = DATABASE();
SET @has_cs = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='order' AND COLUMN_NAME='cs_id');
SET @sql = IF(@has_cs=0, 'ALTER TABLE `order` ADD COLUMN cs_id BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='order' AND INDEX_NAME='idx_order_cs');
SET @sql = IF(@has_idx=0, 'CREATE INDEX idx_order_cs ON `order`(cs_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
