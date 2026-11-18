-- 实名认证同一账号失败次数限制。可重复执行；启动兼容逻辑也会补齐这些列。
SET @db := DATABASE();

SET @sql := (SELECT IF(COUNT(*)=0,
  'ALTER TABLE identity_verification ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema=@db AND table_name='identity_verification' AND column_name='failed_attempts');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*)=0,
  'ALTER TABLE identity_verification ADD COLUMN last_failed_at TIMESTAMP NULL',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema=@db AND table_name='identity_verification' AND column_name='last_failed_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (SELECT IF(COUNT(*)=0,
  'ALTER TABLE identity_verification ADD COLUMN locked_until TIMESTAMP NULL',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema=@db AND table_name='identity_verification' AND column_name='locked_until');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
