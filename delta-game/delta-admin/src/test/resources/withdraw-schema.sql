DROP TABLE IF EXISTS `withdraw`;
DROP TABLE IF EXISTS `player_wallet`;
DROP TABLE IF EXISTS `transaction`;

CREATE TABLE `withdraw` (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_id BIGINT NOT NULL,
  account_id BIGINT,
  account_type_snapshot VARCHAR(32),
  account_name_snapshot VARCHAR(100),
  account_no_snapshot VARCHAR(200),
  account_qrcode_snapshot VARCHAR(512),
  amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(20) NOT NULL,
  pay_method VARCHAR(32),
  proof_image VARCHAR(512),
  reject_reason VARCHAR(500),
  processed_by BIGINT,
  processed_at TIMESTAMP NULL,
  last_notify_at TIMESTAMP NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE `player_wallet` (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_id BIGINT NOT NULL UNIQUE,
  balance DECIMAL(12,2) DEFAULT 0,
  pending_income DECIMAL(12,2) DEFAULT 0,
  frozen_amount DECIMAL(12,2) DEFAULT 0,
  total_income DECIMAL(12,2) DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE `transaction` (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  type VARCHAR(32),
  user_type VARCHAR(16),
  user_id BIGINT,
  amount DECIMAL(12,2),
  balance_before DECIMAL(12,2),
  balance_after DECIMAL(12,2),
  related_order_id BIGINT,
  related_payment_id BIGINT,
  related_withdraw_id BIGINT,
  remark VARCHAR(500),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

