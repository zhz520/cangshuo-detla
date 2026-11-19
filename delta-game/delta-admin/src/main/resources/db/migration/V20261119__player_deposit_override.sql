-- Additive per-player deposit override. NULL keeps the previous global/level policy.
SET @db = DATABASE();
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='player' AND COLUMN_NAME='deposit_override_amount'),
  'SELECT 1', 'ALTER TABLE player ADD COLUMN deposit_override_amount DECIMAL(10,2) DEFAULT NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
