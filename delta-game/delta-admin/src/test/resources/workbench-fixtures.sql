CREATE TABLE IF NOT EXISTS `order` (
 id BIGINT PRIMARY KEY, order_no VARCHAR(32), user_id BIGINT, player_id BIGINT,
 product_id BIGINT, product_name VARCHAR(128), amount DECIMAL(10,2), game_account VARCHAR(128), status VARCHAR(20),
 created_at DATETIME, updated_at DATETIME, assign_time DATETIME, accept_time DATETIME, complete_time DATETIME, pay_deadline DATETIME
);
CREATE TABLE IF NOT EXISTS order_progress (order_id BIGINT,to_status VARCHAR(20),created_at DATETIME);
CREATE TABLE IF NOT EXISTS order_player (id BIGINT PRIMARY KEY,order_id BIGINT,player_id BIGINT,status VARCHAR(20),created_at DATETIME,invite_deadline DATETIME);
CREATE TABLE IF NOT EXISTS complaint (id BIGINT PRIMARY KEY,status VARCHAR(20),updated_at DATETIME);
CREATE TABLE IF NOT EXISTS withdraw (id BIGINT PRIMARY KEY,status VARCHAR(20),created_at DATETIME,processed_at DATETIME);
CREATE TABLE IF NOT EXISTS order_relay_request (id BIGINT PRIMARY KEY,status VARCHAR(20),updated_at DATETIME,reviewed_at DATETIME);
CREATE TABLE IF NOT EXISTS player_replace_request (id BIGINT PRIMARY KEY,status VARCHAR(20),created_at DATETIME,processed_at DATETIME);
CREATE TABLE IF NOT EXISTS player_homepage_revision (id VARCHAR(36) PRIMARY KEY,player_id BIGINT,payload TEXT,status VARCHAR(20),created_at DATETIME,reviewed_at DATETIME);
CREATE TABLE IF NOT EXISTS player (id BIGINT PRIMARY KEY,openid VARCHAR(64),status VARCHAR(20),deleted INT DEFAULT 0,created_at DATETIME);
CREATE TABLE IF NOT EXISTS identity_verification (user_id BIGINT PRIMARY KEY,status VARCHAR(20),submitted_at DATETIME,reviewed_at DATETIME);
CREATE TABLE IF NOT EXISTS privacy_request (id VARCHAR(36) PRIMARY KEY,status VARCHAR(20),created_at DATETIME,reviewed_at DATETIME);
CREATE TABLE IF NOT EXISTS payment (id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(20),updated_at DATETIME);
CREATE TABLE IF NOT EXISTS chat_session (id BIGINT PRIMARY KEY,id1 BIGINT,id2 BIGINT,status VARCHAR(20));
CREATE TABLE IF NOT EXISTS chat_message (id BIGINT PRIMARY KEY,session_id BIGINT,created_at DATETIME,sender_type VARCHAR(20),is_read INT,content TEXT);
CREATE TABLE IF NOT EXISTS system_notification (id BIGINT AUTO_INCREMENT PRIMARY KEY, receiver_type VARCHAR(16), receiver_id BIGINT, title VARCHAR(100), content TEXT, biz_type VARCHAR(40), related_id BIGINT, is_read INT, created_at DATETIME);
CREATE TABLE IF NOT EXISTS `user` (id BIGINT PRIMARY KEY,openid VARCHAR(64),deleted INT DEFAULT 0);
CREATE TABLE IF NOT EXISTS player_wallet (player_id BIGINT PRIMARY KEY,balance DECIMAL(12,2),pending_income DECIMAL(12,2) DEFAULT 0,frozen_amount DECIMAL(12,2) DEFAULT 0,total_income DECIMAL(12,2) DEFAULT 0);
