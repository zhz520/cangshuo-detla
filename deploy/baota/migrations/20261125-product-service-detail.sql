-- Separate the short "服务内容" summary from the rich "详情与须知" body.
-- Existing description remains the fallback for detail until an admin saves the new field.
ALTER TABLE `product`
  ADD COLUMN `service_content` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '详情页服务内容短说明' AFTER `description`,
  ADD COLUMN `detail` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '详情页详情与须知富文本' AFTER `service_content`;

UPDATE `product`
SET `detail` = `description`
WHERE `detail` IS NULL AND `description` IS NOT NULL AND `description` <> '';
