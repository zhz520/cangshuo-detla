
-- 20261025 打手等级及独立涨价规则（默认不涨价）
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='player_level' AND column_name='price_increase_rate')=0, 'ALTER TABLE player_level ADD COLUMN price_increase_rate DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT ''等级涨价百分比''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='player' AND column_name='price_increase_rate')=0, 'ALTER TABLE player ADD COLUMN price_increase_rate DECIMAL(5,2) NOT NULL DEFAULT -1 COMMENT ''-1跟随等级，0不涨价，其他为百分比''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order' AND column_name='base_amount')=0, 'ALTER TABLE `order` ADD COLUMN base_amount DECIMAL(10,2) DEFAULT NULL COMMENT ''商品原价快照''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order' AND column_name='price_increase_rate')=0, 'ALTER TABLE `order` ADD COLUMN price_increase_rate DECIMAL(5,2) DEFAULT NULL COMMENT ''下单涨价比例快照''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order' AND column_name='price_increase_amount')=0, 'ALTER TABLE `order` ADD COLUMN price_increase_amount DECIMAL(10,2) DEFAULT NULL COMMENT ''下单涨价金额快照''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order' AND column_name='requested_player_level_id')=0, 'ALTER TABLE `order` ADD COLUMN requested_player_level_id INT DEFAULT NULL COMMENT ''指定接单等级''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order' AND column_name='player_level_name')=0, 'ALTER TABLE `order` ADD COLUMN player_level_name VARCHAR(30) DEFAULT NULL COMMENT ''等级名称快照''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
