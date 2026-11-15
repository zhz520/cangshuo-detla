-- Both the old and new application rely on one active conversation per pair.
SET @db = DATABASE();
SET @sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE chat_session ADD COLUMN active_pair_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status=''ACTIVE'' THEN 1 ELSE NULL END)',
  'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='chat_session' AND COLUMN_NAME='active_pair_slot');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE chat_session ADD UNIQUE KEY uk_chat_session_active_pair(id1,id2,active_pair_slot)',
  'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='chat_session' AND INDEX_NAME='uk_chat_session_active_pair');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @drop_indexes = (SELECT GROUP_CONCAT(DISTINCT CONCAT('DROP INDEX `',INDEX_NAME,'`') ORDER BY INDEX_NAME SEPARATOR ', ')
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='chat_session'
  AND INDEX_NAME IN ('uk_id1_id2','uk_id1_id2_status','uk_id1_id2_active'));
SET @sql = IF(@drop_indexes IS NULL,'SELECT 1',CONCAT('ALTER TABLE chat_session ',@drop_indexes));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
