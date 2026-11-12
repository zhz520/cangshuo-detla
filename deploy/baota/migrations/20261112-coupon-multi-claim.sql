-- Allow multiple claims of the same coupon template when per_user_limit > 1.
-- The unique (template_id,user_id) index is a legacy constraint from the first release.
SET @has_legacy_coupon_index = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='user_coupon'
      AND index_name='uk_coupon_user_template'
);
SET @drop_coupon_index_ddl = IF(
    @has_legacy_coupon_index > 0,
    'ALTER TABLE `user_coupon` DROP INDEX `uk_coupon_user_template`',
    'SELECT 1'
);
PREPARE drop_coupon_index_statement FROM @drop_coupon_index_ddl;
EXECUTE drop_coupon_index_statement;
DEALLOCATE PREPARE drop_coupon_index_statement;

SET @has_coupon_user_template_index = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='user_coupon'
      AND index_name='idx_user_coupon_user_template'
);
SET @coupon_user_template_index_ddl = IF(
    @has_coupon_user_template_index = 0,
    'CREATE INDEX `idx_user_coupon_user_template` ON `user_coupon` (`user_id`,`template_id`)',
    'SELECT 1'
);
PREPARE coupon_user_template_index_statement FROM @coupon_user_template_index_ddl;
EXECUTE coupon_user_template_index_statement;
DEALLOCATE PREPARE coupon_user_template_index_statement;

SET @has_coupon_user_status_index = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='user_coupon'
      AND index_name='idx_user_coupon_user_status'
);
SET @coupon_user_status_index_ddl = IF(
    @has_coupon_user_status_index = 0,
    'CREATE INDEX `idx_user_coupon_user_status` ON `user_coupon` (`user_id`,`status`)',
    'SELECT 1'
);
PREPARE coupon_user_status_index_statement FROM @coupon_user_status_index_ddl;
EXECUTE coupon_user_status_index_statement;
DEALLOCATE PREPARE coupon_user_status_index_statement;
