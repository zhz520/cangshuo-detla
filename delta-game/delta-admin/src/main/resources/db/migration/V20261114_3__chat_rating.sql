-- 客服会话结束评分
SET @db = DATABASE();
SET @sql = (SELECT IF(COUNT(*)=0, 'ALTER TABLE chat_session ADD COLUMN rating TINYINT DEFAULT NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='chat_session' AND COLUMN_NAME='rating');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*)=0, 'ALTER TABLE chat_session ADD COLUMN rating_comment VARCHAR(500) DEFAULT NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='chat_session' AND COLUMN_NAME='rating_comment');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*)=0, 'ALTER TABLE chat_session ADD COLUMN rated_at DATETIME DEFAULT NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='chat_session' AND COLUMN_NAME='rated_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
