-- Allow multiple claims of a coupon template when per_user_limit is greater than one.
SET @has_legacy_coupon_index = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='user_coupon'
      AND index_name='uk_coupon_user_template'
);
SET @drop_coupon_index_ddl = IF(@has_legacy_coupon_index > 0,
    'ALTER TABLE `user_coupon` DROP INDEX `uk_coupon_user_template`', 'SELECT 1');
PREPARE drop_coupon_index_statement FROM @drop_coupon_index_ddl;
EXECUTE drop_coupon_index_statement;
DEALLOCATE PREPARE drop_coupon_index_statement;
