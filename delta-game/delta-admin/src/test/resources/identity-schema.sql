DROP TABLE IF EXISTS identity_verification;
DROP TABLE IF EXISTS staff_role_member;
DROP TABLE IF EXISTS staff_role;
DROP TABLE IF EXISTS admin;
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`(id BIGINT PRIMARY KEY,nickname VARCHAR(64),phone VARCHAR(20),status INT DEFAULT 1,is_guest TINYINT DEFAULT 0,deleted INT DEFAULT 0);
INSERT INTO `user`(id,nickname,phone,status,deleted) VALUES(7,'测试用户','13900000000',1,0),(8,'另一用户','13800000000',1,0);
CREATE TABLE staff_role(code VARCHAR(64) PRIMARY KEY,name VARCHAR(100),permissions TEXT,enabled INT DEFAULT 1,builtin INT DEFAULT 0);
CREATE TABLE staff_role_member(admin_id BIGINT PRIMARY KEY,role_code VARCHAR(64));
CREATE TABLE admin(id BIGINT PRIMARY KEY,username VARCHAR(64),nickname VARCHAR(64),role VARCHAR(16),status INT,deleted INT DEFAULT 0);
INSERT INTO staff_role(code,name,permissions,enabled,builtin) VALUES('ADMIN','管理员','["*"]',1,1),('CS','客服','[]',1,1);
INSERT INTO staff_role_member(admin_id,role_code) VALUES(1,'ADMIN');
INSERT INTO admin(id,username,nickname,role,status,deleted) VALUES(1,'admin','管理员','ADMIN',1,0),(2,'cs','客服','CS',1,0);
CREATE TABLE identity_verification(
 user_id BIGINT PRIMARY KEY,
 real_name VARCHAR(64) NOT NULL DEFAULT '',
 id_number_hash CHAR(64),
 id_number_masked VARCHAR(32) NOT NULL DEFAULT '',
 front_image LONGTEXT,
 front_mime VARCHAR(64),
 back_image LONGTEXT,
 back_mime VARCHAR(64),
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 privacy_version VARCHAR(32) NOT NULL DEFAULT '',
 privacy_accepted_at TIMESTAMP,
 review_note VARCHAR(500) NOT NULL DEFAULT '',
 reviewer_id BIGINT,
 submitted_at TIMESTAMP,
 reviewed_at TIMESTAMP,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(id_number_hash)
);
