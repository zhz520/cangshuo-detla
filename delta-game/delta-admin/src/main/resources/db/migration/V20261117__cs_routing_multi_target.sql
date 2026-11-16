-- 客服分配规则支持同一规则绑定多个商品/分类（可重复执行）
SET @db = DATABASE();
SET @has_group = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='cs_routing_rule' AND COLUMN_NAME='rule_group_id');
SET @sql = IF(@has_group=0,
  'ALTER TABLE cs_routing_rule ADD COLUMN rule_group_id VARCHAR(64) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE cs_routing_rule
SET rule_group_id=CONCAT('legacy-',id)
WHERE rule_group_id IS NULL OR rule_group_id='';

SET @has_group_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='cs_routing_rule' AND INDEX_NAME='idx_cs_routing_group');
SET @sql = IF(@has_group_idx=0,
  'CREATE INDEX idx_cs_routing_group ON cs_routing_rule(rule_group_id)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
