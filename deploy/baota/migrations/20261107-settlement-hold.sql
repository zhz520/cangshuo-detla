SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='player_wallet' AND column_name='pending_income')=0,
  'ALTER TABLE player_wallet ADD COLUMN pending_income DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT ''待结算收益(售后观察期内)'' AFTER balance',
  'SELECT 1');
PREPARE settlement_column_stmt FROM @sql;
EXECUTE settlement_column_stmt;
DEALLOCATE PREPARE settlement_column_stmt;

CREATE TABLE IF NOT EXISTS player_income_settlement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  player_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'PRIMARY',
  amount DECIMAL(12,2) NOT NULL,
  reversed_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  release_at DATETIME NOT NULL,
  released_at DATETIME NULL,
  attempts INT NOT NULL DEFAULT 0,
  last_error VARCHAR(255) NULL,
  next_attempt_at DATETIME NOT NULL,
  remark VARCHAR(255) NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_income_settlement_order_player (order_id, player_id),
  KEY idx_income_settlement_due (status, next_attempt_at),
  KEY idx_income_settlement_player (player_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打手待结算收益';

INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark)
SELECT 'settlement.hold_hours','订单收益售后观察期','72','number','结算配置','订单确认后先进入待结算收益，观察期结束且无投诉、退款时转为可提现余额；单位小时，0至720'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='settlement.hold_hours');
INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark)
SELECT 'withdraw.require_identity_match','提现校验收款人实名','true','boolean','提现配置','开启后收款账户姓名必须与已通过实名或入驻真实姓名一致，避免换绑他人账户收款'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='withdraw.require_identity_match');
