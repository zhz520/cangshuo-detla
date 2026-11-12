package com.delta.admin;

import com.delta.common.mapper.ScheduledTaskMapper;
import org.apache.ibatis.annotations.Insert;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskStatisticsSqlTest {
    @Test
    void dailyStatisticsSqlWritesTheSchemaUsedByTheApplication() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:statistics-sql-" + UUID.randomUUID()
                + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE user(id BIGINT PRIMARY KEY,is_guest INT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT,player_id BIGINT,amount DECIMAL(12,2)," +
                "refund_amount DECIMAL(12,2),commission_rate DECIMAL(5,4),status VARCHAR(24),complete_time TIMESTAMP," +
                "created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE payment(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT,amount DECIMAL(12,2)," +
                "refund_amount DECIMAL(12,2),status VARCHAR(24),paid_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE player(id BIGINT PRIMARY KEY,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE complaint(id BIGINT PRIMARY KEY,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE statistics_daily(id BIGINT AUTO_INCREMENT PRIMARY KEY,stat_date DATE NOT NULL," +
                "new_user_count INT NOT NULL DEFAULT 0,active_user_count INT NOT NULL DEFAULT 0," +
                "new_order_count INT NOT NULL DEFAULT 0,completed_order_count INT NOT NULL DEFAULT 0," +
                "cancelled_order_count INT NOT NULL DEFAULT 0,total_amount DECIMAL(12,2) NOT NULL DEFAULT 0," +
                "platform_income DECIMAL(12,2) NOT NULL DEFAULT 0,player_income DECIMAL(12,2) NOT NULL DEFAULT 0," +
                "new_player_count INT NOT NULL DEFAULT 0,active_player_count INT NOT NULL DEFAULT 0," +
                "new_complaint_count INT NOT NULL DEFAULT 0,avg_order_amount DECIMAL(10,2) NOT NULL DEFAULT 0," +
                "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(stat_date))");
        jdbc.update("INSERT INTO user VALUES(1,0,DATEADD('DAY',-1,CURRENT_TIMESTAMP))");
        jdbc.update("INSERT INTO `order` VALUES(10,1,2,10,-1,0.2,'COMPLETED',DATEADD('DAY',-1,CURRENT_TIMESTAMP)," +
                "DATEADD('DAY',-1,CURRENT_TIMESTAMP),DATEADD('DAY',-1,CURRENT_TIMESTAMP))");
        jdbc.update("INSERT INTO payment(order_id,amount,refund_amount,status,paid_at) " +
                "VALUES(10,10,1,'PAID',DATEADD('DAY',-1,CURRENT_TIMESTAMP))");
        jdbc.update("INSERT INTO player VALUES(2,DATEADD('DAY',-1,CURRENT_TIMESTAMP))");
        jdbc.update("INSERT INTO complaint VALUES(1,DATEADD('DAY',-1,CURRENT_TIMESTAMP))");

        Method method = ScheduledTaskMapper.class.getMethod("insertDailyStatistics");
        String sql = String.join(" ", method.getAnnotation(Insert.class).value());
        assertThat(sql).contains("new_order_count", "completed_order_count", "new_user_count")
                .doesNotContain("new_orders", "completed_orders", "new_users");
        jdbc.execute(sql.replace("DATE_SUB(CURDATE(), INTERVAL 1 DAY)", "DATEADD('DAY',-1,CURRENT_DATE())"));

        var row = jdbc.queryForMap("SELECT * FROM statistics_daily");
        assertThat(((Number) row.get("new_order_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("completed_order_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("active_user_count")).intValue()).isEqualTo(1);
        assertThat((java.math.BigDecimal) row.get("total_amount")).isEqualByComparingTo("9.00");
        assertThat((java.math.BigDecimal) row.get("platform_income")).isEqualByComparingTo("1.80");
        assertThat((java.math.BigDecimal) row.get("player_income")).isEqualByComparingTo("7.20");
        assertThat(((Number) row.get("new_complaint_count")).intValue()).isEqualTo(1);
    }
}
