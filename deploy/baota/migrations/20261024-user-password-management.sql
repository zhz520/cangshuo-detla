-- Apply before replacing the backend. Existing users set their own password after SMS verification.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='password')=0,
 'ALTER TABLE `user` ADD COLUMN `password` varchar(100) DEFAULT NULL COMMENT ''BCrypt hash''', 'SELECT 1');
PREPARE user_auth_stmt FROM @sql; EXECUTE user_auth_stmt; DEALLOCATE PREPARE user_auth_stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='auth_version')=0,
 'ALTER TABLE `user` ADD COLUMN auth_version bigint NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE user_auth_stmt FROM @sql; EXECUTE user_auth_stmt; DEALLOCATE PREPARE user_auth_stmt;
