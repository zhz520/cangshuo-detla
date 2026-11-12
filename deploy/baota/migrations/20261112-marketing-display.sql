CREATE TABLE IF NOT EXISTS `invite_reward_debt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `reward_id` BIGINT NOT NULL,
  `inviter_id` BIGINT NOT NULL,
  `amount` DECIMAL(12,2) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `recovered_at` TIMESTAMP NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invite_reward_debt` (`reward_id`),
  KEY `idx_invite_reward_debt_inviter` (`inviter_id`,`status`)
);

CREATE TABLE IF NOT EXISTS `display_privacy` (
  `user_id` BIGINT NOT NULL,
  `spend_rank_visible` TINYINT NOT NULL DEFAULT 1,
  `purchase_feed_consent` TINYINT NOT NULL DEFAULT 0,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
);
