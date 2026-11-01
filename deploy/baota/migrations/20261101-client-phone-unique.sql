-- MySQL 8: repair the generated phone column without importing customer rows.
-- Back up first. Duplicate non-empty phone numbers must be resolved before adding a unique index.
SET @has_column = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='phone_unique');
SET @phone_ddl = IF(@has_column=0, 'ALTER TABLE `user` ADD COLUMN `phone_unique` VARCHAR(20) GENERATED ALWAYS AS (NULLIF(`phone`,'''')) STORED', 'SELECT 1');
PREPARE phone_statement FROM @phone_ddl;
EXECUTE phone_statement;
DEALLOCATE PREPARE phone_statement;
SET @has_index = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='user' AND index_name='uk_phone_unique');
SET @phone_index_ddl = IF(@has_index=0, 'CREATE UNIQUE INDEX `uk_phone_unique` ON `user` (`phone_unique`)', 'SELECT 1');
PREPARE phone_index_statement FROM @phone_index_ddl;
EXECUTE phone_index_statement;
DEALLOCATE PREPARE phone_index_statement;
