-- Run against the existing database before deploying the matching backend.
-- Repeatable; preserves configured values and business records.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='player_level' AND column_name='grab_captcha_required')=0,
 'ALTER TABLE player_level ADD COLUMN grab_captcha_required tinyint NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE feature_stmt FROM @sql; EXECUTE feature_stmt; DEALLOCATE PREPARE feature_stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='player' AND column_name='grab_captcha_override')=0,
 'ALTER TABLE player ADD COLUMN grab_captcha_override tinyint NOT NULL DEFAULT -1', 'SELECT 1');
PREPARE feature_stmt FROM @sql; EXECUTE feature_stmt; DEALLOCATE PREPARE feature_stmt;

CREATE TABLE IF NOT EXISTS player_reward (
 id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 reward_no varchar(40) NOT NULL,
 request_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id bigint NOT NULL, player_id bigint NOT NULL, order_id bigint NOT NULL,
 order_no varchar(64) NOT NULL,
 amount decimal(12,2) NOT NULL, fee decimal(12,2) NOT NULL, income decimal(12,2) NOT NULL,
 fee_percent decimal(5,2) NOT NULL,
 user_balance_before decimal(12,2) NOT NULL, user_balance_after decimal(12,2) NOT NULL,
 player_balance_before decimal(12,2) NOT NULL, player_balance_after decimal(12,2) NOT NULL,
 reversal_user_balance_before decimal(12,2), reversal_user_balance_after decimal(12,2),
 reversal_player_balance_before decimal(12,2), reversal_player_balance_after decimal(12,2),
 payment_method varchar(16) NOT NULL DEFAULT 'BALANCE',
 remark varchar(200), status varchar(24) NOT NULL,
 pending_recovery decimal(12,2) NOT NULL DEFAULT 0,
 reversal_reason varchar(200), reversal_admin_id bigint, reversed_at datetime,
 created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_reward_no(reward_no), UNIQUE KEY uk_order_reward(order_id),
 UNIQUE KEY uk_user_reward_request(user_id,request_id),
 KEY idx_reward_user_date(user_id,created_at), KEY idx_reward_player_date(player_id,created_at),
 KEY idx_reward_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark)
SELECT 'features.reward_grab','打赏与抢单验证规则',
 '{"rewardEnabled":false,"minAmount":1,"maxAmount":200,"validDays":30,"userDailyLimit":10,"playerDailyLimit":5,"pairDailyLimit":5,"feePercent":0,"captchaSeconds":120,"captchaAttempts":5,"captchaRefreshSeconds":10}',
 'json','reward-grab','在打赏与抢单设置页面修改'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='features.reward_grab');
