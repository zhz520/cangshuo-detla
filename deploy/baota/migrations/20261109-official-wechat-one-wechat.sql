CREATE TABLE IF NOT EXISTS wechat_official_binding_conflict_backup (
  user_type VARCHAR(16) NOT NULL,
  user_id BIGINT NOT NULL,
  openid VARCHAR(128) NOT NULL,
  archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_type, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公众号绑定重复 OpenID 冲突备份';

INSERT IGNORE INTO wechat_official_binding_conflict_backup(user_type,user_id,openid)
SELECT b.user_type,b.user_id,b.openid
FROM wechat_official_binding b
JOIN (
  SELECT openid FROM wechat_official_binding GROUP BY openid HAVING COUNT(*) > 1
) duplicate_openid ON duplicate_openid.openid=b.openid;

SET @quota_statement = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='wechat_official_binding'
     AND index_name='uk_wechat_official_openid')=0,
  'ALTER TABLE wechat_official_binding ADD UNIQUE KEY uk_wechat_official_openid(openid)',
  'SELECT 1'
);
PREPARE official_wechat_unique_statement FROM @quota_statement;
EXECUTE official_wechat_unique_statement;
DEALLOCATE PREPARE official_wechat_unique_statement;
