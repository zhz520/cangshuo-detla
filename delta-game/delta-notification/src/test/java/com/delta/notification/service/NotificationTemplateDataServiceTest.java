package com.delta.notification.service;

import com.delta.common.notification.NotificationTemplateSources;
import com.delta.notification.service.NotificationTemplateDataService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplateDataServiceTest {
    private NotificationTemplateDataService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:notification-data;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,LEVEL");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,nickname VARCHAR(64),phone VARCHAR(20),is_guest TINYINT,status TINYINT)");
        jdbc.execute("CREATE TABLE player(id BIGINT PRIMARY KEY,nickname VARCHAR(64),phone VARCHAR(20),level INT,status VARCHAR(20),is_online TINYINT,order_count INT)");
        jdbc.execute("CREATE TABLE player_level(id INT PRIMARY KEY,name VARCHAR(30))");
        jdbc.execute("""
                CREATE TABLE `order`(id BIGINT PRIMARY KEY,order_no VARCHAR(64),user_id BIGINT,product_id BIGINT,product_name VARCHAR(128),
                    spec_info VARCHAR(255),amount DECIMAL(10,2),base_amount DECIMAL(10,2),price_increase_amount DECIMAL(10,2),
                    game_account VARCHAR(128),contact VARCHAR(64),remark VARCHAR(500),extra_fields VARCHAR(500),required_player_count INT,
                    player_level_name VARCHAR(30),player_id BIGINT,status VARCHAR(30),pay_deadline TIMESTAMP,assign_time TIMESTAMP,
                    accept_time TIMESTAMP,start_time TIMESTAMP,complete_time TIMESTAMP,confirm_time TIMESTAMP,settle_amount DECIMAL(10,2),
                    settle_time TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE refund_request(id BIGINT PRIMARY KEY,order_id BIGINT,refund_no VARCHAR(40),amount DECIMAL(12,2),reason VARCHAR(500),
                    status VARCHAR(32),payment_method VARCHAR(32),submitted_at TIMESTAMP,cs_reviewed_at TIMESTAMP,admin_decided_at TIMESTAMP,
                    processing_at TIMESTAMP,completed_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE complaint(id BIGINT PRIMARY KEY,order_id BIGINT,type VARCHAR(30),content VARCHAR(1000),expected_result VARCHAR(30),
                    status VARCHAR(20),result VARCHAR(30),refund_amount DECIMAL(10,2),result_reason VARCHAR(1000),player_penalty VARCHAR(30),
                    resolved_at TIMESTAMP,appeal_reason VARCHAR(1000),appeal_result VARCHAR(30))
                """);
        jdbc.execute("""
                CREATE TABLE withdraw(id BIGINT PRIMARY KEY,player_id BIGINT,amount DECIMAL(10,2),status VARCHAR(20),account_type_snapshot VARCHAR(20),
                    account_name_snapshot VARCHAR(128),account_no_snapshot VARCHAR(256),pay_method VARCHAR(50),proof_image VARCHAR(512),
                    reject_reason VARCHAR(255),processed_at TIMESTAMP,created_at TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE admin(id BIGINT PRIMARY KEY,username VARCHAR(64),nickname VARCHAR(64),phone VARCHAR(20))");

        jdbc.update("INSERT INTO `user` VALUES(7,'阿岚','13800138000',0,1)");
        jdbc.update("INSERT INTO player VALUES(18,'林川','13900139000',3,'ACTIVE',1,126)");
        jdbc.update("INSERT INTO player_level VALUES(3,'魔王')");
        jdbc.update("""
                INSERT INTO `order` VALUES(1,'DG202611080001',7,12,'绝密双护','{\"区服\":\"微信\"}',68.00,58.00,10.00,
                    'demo_001','微信号 demo001','晚上八点后开始','{\"区服\":\"微信\"}',2,'魔王',18,'ACCEPTED',
                    TIMESTAMP '2026-11-08 20:50:00',TIMESTAMP '2026-11-08 20:31:00',TIMESTAMP '2026-11-08 20:32:00',
                    TIMESTAMP '2026-11-08 20:35:00',TIMESTAMP '2026-11-08 22:10:00',TIMESTAMP '2026-11-08 22:20:00',54.40,
                    TIMESTAMP '2026-11-08 22:30:00',TIMESTAMP '2026-11-08 20:20:00',TIMESTAMP '2026-11-08 22:30:00')
                """);
        jdbc.update("INSERT INTO admin VALUES(99,'kefu','值班客服','13700137000')");
        service = new NotificationTemplateDataService(jdbc);
    }

    @Test
    void resolvesOrderCustomerPlayerAndFullRecipientPhone() {
        Map<String, String> values = service.resolve(Map.of(
                "event_type", "ORDER_ACCEPTED", "user_type", "CS", "user_id", 99L, "order_id", 1L,
                "message", "打手已接单", "created_at", java.time.LocalDateTime.of(2026, 11, 8, 20, 33)));

        assertThat(values).containsEntry("customerPhone", "13800138000")
                .containsEntry("productName", "绝密双护")
                .containsEntry("orderAmount", "68.00")
                .containsEntry("grabTime", "2026-11-08 20:32:00")
                .containsEntry("playerPhone", "13900139000")
                .containsEntry("playerLevel", "魔王")
                .containsEntry("recipientName", "值班客服")
                .containsEntry("recipientPhone", "13700137000");
        assertThat(values.keySet()).containsExactlyInAnyOrderElementsOf(NotificationTemplateSources.KEYS);
    }

    @Test
    void resolvesRefundAndComplaintContextForTheirEvents() {
        jdbc.update("""
                INSERT INTO refund_request VALUES(3,1,'RF202611080001',30.00,'服务时长不足','CS_REVIEWED',
                    'WECHAT_OFFICIAL',TIMESTAMP '2026-11-08 20:40:00',TIMESTAMP '2026-11-08 20:45:00',NULL,NULL,NULL)
                """);
        jdbc.update("""
                INSERT INTO complaint VALUES(4,1,'SERVICE_QUALITY','服务未按约定完成','REDO','RESOLVED','PARTIAL_REFUND',
                    30.00,'服务时长不足，按比例退款','WARNING',TIMESTAMP '2026-11-08 21:00:00','客户不认可','REJECT')
                """);

        Map<String, String> refund = service.resolve(Map.of("event_type", "REFUND_CS_REVIEWED", "user_type", "USER", "user_id", 7L, "order_id", 1L));
        assertThat(refund).containsEntry("refundNo", "RF202611080001")
                .containsEntry("refundAmount", "30.00")
                .containsEntry("refundStatus", "客服已初审")
                .containsEntry("refundCsReviewedAt", "2026-11-08 20:45:00");

        Map<String, String> complaint = service.resolve(Map.of("event_type", "COMPLAINT_RESOLVED", "user_type", "USER", "user_id", 7L, "order_id", 1L));
        assertThat(complaint).containsEntry("complaintType", "服务质量")
                .containsEntry("complaintResult", "部分退款")
                .containsEntry("complaintRefundAmount", "30.00")
                .containsEntry("complaintPlayerPenalty", "警告")
                .containsEntry("complaintAppealResult", "驳回投诉");
    }

    @Test
    void withdrawalEventUsesWithdrawalIdAndNeverLooksUpAnOrderWithTheSameNumber() {
        jdbc.update("""
                INSERT INTO withdraw VALUES(55,18,120.00,'COMPLETED','ALIPAY','张三','demo@example.com','支付宝转账',
                    'https://cdn.example/proof.jpg',NULL,TIMESTAMP '2026-11-08 18:30:00',TIMESTAMP '2026-11-08 18:00:00')
                """);

        Map<String, String> values = service.resolve(Map.of(
                "event_type", "WITHDRAW_COMPLETED", "user_type", "PLAYER", "user_id", 18L, "order_id", 55L));

        assertThat(values).containsEntry("withdrawId", "55")
                .containsEntry("withdrawAmount", "120.00")
                .containsEntry("amount", "120.00")
                .containsEntry("withdrawAccountName", "张三")
                .containsEntry("withdrawStatus", "已完成")
                .containsEntry("orderNo", "-")
                .containsEntry("playerName", "林川");
    }

    @Test
    void frontendAndBackendExposeExactlyTheSameDataSources() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        while (root != null && !Files.exists(root.resolve("delta-admin-ui/src/utils/notification-examples.js"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
        String source = Files.readString(root.resolve("delta-admin-ui/src/utils/notification-examples.js"));
        Matcher matcher = Pattern.compile("source: '([^']+)'").matcher(source);
        LinkedHashSet<String> frontend = new LinkedHashSet<>();
        while (matcher.find()) frontend.add(matcher.group(1));
        assertThat(frontend).containsExactlyInAnyOrderElementsOf(NotificationTemplateSources.KEYS);
    }

}
