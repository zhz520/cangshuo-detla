CREATE TABLE IF NOT EXISTS flash_sale_activity (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  product_id BIGINT NOT NULL,
  price DECIMAL(12,2) NOT NULL,
  total_stock INT NOT NULL,
  remaining_stock INT NOT NULL,
  starts_at TIMESTAMP NOT NULL,
  ends_at TIMESTAMP NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_flash_sale_active(product_id, status, starts_at, ends_at)
);

CREATE TABLE IF NOT EXISTS flash_sale_claim (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  order_id BIGINT NULL,
  price DECIMAL(12,2) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_flash_sale_user(activity_id, user_id),
  KEY idx_flash_sale_claim_order(order_id, status)
);
