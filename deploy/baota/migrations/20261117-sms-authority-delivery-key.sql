-- Additive migration; existing rows fall back to the original queue ID.
SET @delta_sms_delivery_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
         AND table_name='sms_notice_queue' AND column_name='delivery_key'),
  'SELECT 1',
  'ALTER TABLE sms_notice_queue ADD COLUMN delivery_key VARCHAR(64) NULL'
);
PREPARE delta_sms_delivery_stmt FROM @delta_sms_delivery_ddl;
EXECUTE delta_sms_delivery_stmt;
DEALLOCATE PREPARE delta_sms_delivery_stmt;
