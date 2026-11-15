-- 分类图标显示尺寸（rpx）
SET @db = DATABASE();
SET @has_icon_size = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='category' AND COLUMN_NAME='icon_size');
SET @sql = IF(@has_icon_size=0, 'ALTER TABLE category ADD COLUMN icon_size INT NOT NULL DEFAULT 56', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
