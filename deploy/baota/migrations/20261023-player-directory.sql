-- Additive migration; the deployer checks information_schema before each ALTER.
ALTER TABLE `player` ADD COLUMN `level` int NOT NULL DEFAULT 1 COMMENT '平台打手等级' AFTER `game_level`;
ALTER TABLE `player` ADD COLUMN `introduction` varchar(1000) NOT NULL DEFAULT '' COMMENT '公开个人简介' AFTER `level`;
