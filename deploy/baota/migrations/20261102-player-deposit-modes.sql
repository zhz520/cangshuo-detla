-- Run against the existing business database before deploying the matching backend.
-- Repeatable; preserves configured values and business records.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='player_level' AND column_name='withdraw_deposit_amount')=0,
 'ALTER TABLE player_level ADD COLUMN withdraw_deposit_amount decimal(10,2) NOT NULL DEFAULT 0 COMMENT ''该等级提现前需缴纳的押金''', 'SELECT 1');
PREPARE deposit_stmt FROM @sql; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;

INSERT INTO sys_config(config_key,config_name,config_value,value_type,config_group,remark)
SELECT 'player.deposit_mode','打手押金政策',
       IF((SELECT config_value FROM sys_config WHERE config_key='player.deposit_required' LIMIT 1)='false','NONE','REQUIRED'),
       'text','打手配置','REQUIRED：入驻必须缴纳；NONE：全部不收；BEFORE_WITHDRAW：入驻免交，提现前按等级补交'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='player.deposit_mode');
