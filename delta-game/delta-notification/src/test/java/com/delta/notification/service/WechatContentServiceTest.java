package com.delta.notification.service;

import com.delta.common.exception.BusinessException;
import com.delta.system.service.SysConfigService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WechatContentServiceTest {
    private JdbcTemplate jdbc;
    private SysConfigService configs;
    private WechatContentService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wechat_content;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS wechat_article_draft");
        jdbc.execute("DROP TABLE IF EXISTS wechat_mass_send_task");
        jdbc.execute("CREATE TABLE wechat_article_draft(id BIGINT AUTO_INCREMENT PRIMARY KEY,account_id VARCHAR(36),title VARCHAR(120)," +
                "author VARCHAR(64),digest VARCHAR(500),content_html TEXT,cover_url VARCHAR(512),cover_media_id VARCHAR(128)," +
                "remote_media_id VARCHAR(128),publish_id VARCHAR(128),status VARCHAR(16)," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE wechat_mass_send_task(id BIGINT AUTO_INCREMENT PRIMARY KEY,account_id VARCHAR(36),name VARCHAR(80)," +
                "target_type VARCHAR(16),target_json TEXT,msg_type VARCHAR(16),content_json TEXT,status VARCHAR(24),created_by BIGINT,approved_by BIGINT," +
                "wechat_msg_id VARCHAR(128),error_text VARCHAR(500),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        WechatAccountService accounts = mock(WechatAccountService.class);
        when(accounts.accountSummary("acc")).thenReturn(Map.of("id", "acc"));
        configs = mock(SysConfigService.class);
        service = new WechatContentService(jdbc, accounts, configs);
    }

    @Test
    void articleDraftIsLocalAndMassSendRequiresServerSwitch() throws Exception {
        Map<String, Object> article = service.saveArticle("acc", null, Map.of(
                "title", "活动公告", "author", "运营", "digest", "摘要", "contentHtml", "<p>正文</p>"));
        assertThat(article).containsEntry("status", "DRAFT");
        Map<String, Object> task = service.saveTask("acc", null, Map.of(
                "name", "测试群发", "targetType", "ALL", "msgType", "TEXT", "content", Map.of("text", "测试内容")), 7L);
        long id = ((Number) task.get("id")).longValue();
        service.transition("acc", id, "submit", 7L);
        service.transition("acc", id, "approve", 8L);
        when(configs.getConfigValue("wechat.mass_send_enabled", "false")).thenReturn("false");
        assertThatThrownBy(() -> service.send("acc", id, 8L, true))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(403));
    }
}
