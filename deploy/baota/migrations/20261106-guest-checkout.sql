-- Guest shadow-account checkout and purchase gate.
-- DatabaseCompatibility performs the same additive checks at backend startup.

SET @guest_user_column = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='user' AND column_name='is_guest')=0,
  'ALTER TABLE `user` ADD COLUMN is_guest TINYINT NOT NULL DEFAULT 0 COMMENT ''游客影子账户: 1-是 0-否''',
  'SELECT 1');
PREPARE guest_user_stmt FROM @guest_user_column;
EXECUTE guest_user_stmt;
DEALLOCATE PREPARE guest_user_stmt;

CREATE TABLE IF NOT EXISTS guest_checkout_profile (
  user_id BIGINT NOT NULL COMMENT '游客影子用户ID',
  phone_hash CHAR(64) NOT NULL COMMENT '手机号HMAC摘要',
  phone_mask VARCHAR(20) NOT NULL COMMENT '脱敏手机号',
  lookup_hash CHAR(64) NOT NULL COMMENT '查单码HMAC摘要',
  privacy_version VARCHAR(32) NOT NULL DEFAULT '' COMMENT '同意的隐私政策版本',
  privacy_accepted_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_guest_phone (phone_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='游客下单凭证';

CREATE TABLE IF NOT EXISTS guest_user_link (
  guest_user_id BIGINT NOT NULL COMMENT '游客影子用户ID，不随绑定改写',
  registered_user_id BIGINT NOT NULL COMMENT '绑定的正式用户ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (guest_user_id),
  KEY idx_guest_link_registered (registered_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='游客影子账户与正式用户关联';

INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark)
VALUES
('order.purchase_enabled','启用前台购买','true','boolean','订单配置','关闭后前台隐藏全部立即购买和立即点单按钮，并禁止创建新订单；已有订单仍可支付和处理'),
('order.guest_checkout_enabled','允许游客下单','false','boolean','订单配置','开启后未登录用户可用手机号和查单码创建游客订单；游客订单使用独立影子账户，不写入正式会员统计')
ON DUPLICATE KEY UPDATE config_name=VALUES(config_name),value_type=VALUES(value_type),config_group=VALUES(config_group),remark=VALUES(remark);
