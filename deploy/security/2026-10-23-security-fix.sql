-- Idempotent migration for the security release. Run inside delta_game.
SET NAMES utf8mb4;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='withdraw' AND column_name='account_type_snapshot')=0,
  'ALTER TABLE `withdraw` ADD COLUMN `account_type_snapshot` varchar(20) DEFAULT NULL COMMENT ''申请时收款账户类型快照'' AFTER `account_id`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='withdraw' AND column_name='account_name_snapshot')=0,
  'ALTER TABLE `withdraw` ADD COLUMN `account_name_snapshot` varchar(128) DEFAULT NULL COMMENT ''申请时收款人快照'' AFTER `account_type_snapshot`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='withdraw' AND column_name='account_no_snapshot')=0,
  'ALTER TABLE `withdraw` ADD COLUMN `account_no_snapshot` varchar(256) DEFAULT NULL COMMENT ''申请时收款账号快照'' AFTER `account_name_snapshot`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='withdraw' AND column_name='account_qrcode_snapshot')=0,
  'ALTER TABLE `withdraw` ADD COLUMN `account_qrcode_snapshot` varchar(512) DEFAULT NULL COMMENT ''申请时收款码快照'' AFTER `account_no_snapshot`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='payment' AND index_name='uk_wx_transaction_id')=0,
  'ALTER TABLE `payment` ADD UNIQUE KEY `uk_wx_transaction_id` (`wx_transaction_id`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='payment' AND index_name='uk_refund_no')=0,
  'ALTER TABLE `payment` ADD UNIQUE KEY `uk_refund_no` (`refund_no`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark,created_at,updated_at)
SELECT 'sms.aliyun.otp_enabled','启用短信验证码登录','false','boolean','短信配置','仅在验证码模板和短信风控验收后开启',NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='sms.aliyun.otp_enabled');
INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark,created_at,updated_at)
SELECT 'sms.aliyun.template_code.verification','短信验证码模板 Code','','text','短信配置','已审核模板，使用code作为验证码参数名',NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='sms.aliyun.template_code.verification');
INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark,created_at,updated_at)
SELECT 'wx.pay.refund-notify-url','退款回调地址','','text','微信支付配置','可选；留空时自动推导 /api/pay/wx/refund-notify',NOW(),NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='wx.pay.refund-notify-url');

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='phone_unique')=0,
  'ALTER TABLE `user` ADD COLUMN `phone_unique` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci GENERATED ALWAYS AS (if((`phone` = \'\'),NULL,`phone`)) STORED COMMENT \'非空手机号唯一索引\'',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='user' AND index_name='uk_phone_unique')=0,
  'ALTER TABLE `user` ADD UNIQUE KEY `uk_phone_unique` (`phone_unique`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
