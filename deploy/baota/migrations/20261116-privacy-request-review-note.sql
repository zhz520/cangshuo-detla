-- Preserve existing request notes; the separate review field starts empty.
SET @delta_privacy_review_ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
         AND table_name='privacy_request' AND column_name='review_note'),
  'SELECT 1',
  'ALTER TABLE privacy_request ADD COLUMN review_note VARCHAR(500) NULL'
);
PREPARE delta_privacy_review_stmt FROM @delta_privacy_review_ddl;
EXECUTE delta_privacy_review_stmt;
DEALLOCATE PREPARE delta_privacy_review_stmt;
