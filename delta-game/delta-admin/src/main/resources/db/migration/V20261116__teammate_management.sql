-- Additive: existing invitations retain their original meaning; no business data is rewritten.
SET @db = DATABASE();
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='order_player' AND COLUMN_NAME='invited_by_type'),
  'SELECT 1', 'ALTER TABLE order_player ADD COLUMN invited_by_type VARCHAR(16) NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='order_player' AND COLUMN_NAME='replaces_participant_id'),
  'SELECT 1', 'ALTER TABLE order_player ADD COLUMN replaces_participant_id BIGINT NULL');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
