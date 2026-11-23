package com.delta.notification.service;

import com.alibaba.fastjson2.JSONObject;
import com.delta.common.exception.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WechatOperationsServiceTest {
    private static final String ACCOUNT = "test-account";
    private static final String OPENID_LIST = "/cgi-bin/user/get";
    private static final String USER_DETAILS = "/cgi-bin/user/info/batchget";
    private final WechatAccountService accounts = mock(WechatAccountService.class);
    private final WechatOpenPlatformClient openPlatform = mock(WechatOpenPlatformClient.class);
    private JdbcTemplate jdbc;
    private WechatOperationsService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE wechat_fan(account_id VARCHAR(36),openid VARCHAR(128),nickname VARCHAR(128),avatar VARCHAR(512)," +
                "subscribed TINYINT,tags_json TEXT,last_sync_at TIMESTAMP,PRIMARY KEY(account_id,openid))");
        jdbc.execute("CREATE TABLE wechat_menu_draft(account_id VARCHAR(36) PRIMARY KEY,buttons_json TEXT,status VARCHAR(16)," +
                "remote_json TEXT,published_at TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        service = new WechatOperationsService(jdbc, accounts, openPlatform);
    }

    @Test
    void largeFollowerListIsSplitIntoBatchesAndCappedAt500() throws Exception {
        when(accounts.apiGet(ACCOUNT, OPENID_LIST)).thenReturn(followerPage(ids(0, 620), "more"));
        List<Integer> batchSizes = new ArrayList<>();
        mockWechatDetailLimit(batchSizes);

        assertThat(service.syncFans(ACCOUNT)).containsEntry("synced", 500);

        assertThat(batchSizes).containsExactly(100, 100, 100, 100, 100);
        assertThat(count(ACCOUNT)).isEqualTo(500);
        verify(accounts, never()).apiGet(ACCOUNT, OPENID_LIST + "?next_openid=more");
    }

    @Test
    void duplicateFollowersAndRepeatedCursorDoNotCauseDuplicateReadsOrAnotherAccountsWrite() throws Exception {
        when(accounts.apiGet(ACCOUNT, OPENID_LIST)).thenReturn(followerPage(ids(0, 50), "cursor"));
        when(accounts.apiGet(ACCOUNT, OPENID_LIST + "?next_openid=cursor"))
                .thenReturn(followerPage(ids(40, 110), "cursor"));
        jdbc.update("INSERT INTO wechat_fan(account_id,openid,nickname) VALUES(?,?,?)", "other", id(0), "original");
        List<Integer> batchSizes = new ArrayList<>();
        mockWechatDetailLimit(batchSizes);

        assertThat(service.syncFans(ACCOUNT)).containsEntry("synced", 110);

        assertThat(batchSizes).containsExactly(100, 10);
        verify(accounts).apiGet(ACCOUNT, OPENID_LIST + "?next_openid=cursor");
        assertThat(count("other")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT nickname FROM wechat_fan WHERE account_id='other'", String.class))
                .isEqualTo("original");
    }

    @Test
    void detailBatchFailureDoesNotPersistPartialResults() throws Exception {
        when(accounts.apiGet(ACCOUNT, OPENID_LIST)).thenReturn(followerPage(ids(0, 101), ""));
        when(accounts.apiPost(eq(ACCOUNT), eq(USER_DETAILS), any())).thenReturn(
                profiles(ids(0, 100)), new JSONObject(Map.of("errcode", 45009)));

        assertThatThrownBy(() -> service.syncFans(ACCOUNT))
                .isInstanceOf(BusinessException.class).hasMessageContaining("45009");
        assertThat(count(ACCOUNT)).isZero();
    }

    @Test
    void emptyListDoesNotRequestDetails() throws Exception {
        when(accounts.apiGet(ACCOUNT, OPENID_LIST)).thenReturn(followerPage(List.of(), ""));

        assertThat(service.syncFans(ACCOUNT)).containsEntry("synced", 0);

        verify(accounts, never()).apiPost(eq(ACCOUNT), eq(USER_DETAILS), any());
    }

    @Test
    void savesOnlyActionsForThisAccountsMenuAndRejectsStalePublish() throws Exception {
        var old = Map.of("button", List.of(Map.of("name", "首页", "type", "view", "url", "https://example.com/old")));
        var draft = Map.of("button", List.of(Map.of("name", "商城", "sub_button",
                List.of(Map.of("name", "客服", "type", "click", "key", "help", "url", "https://example.com/unused")))));
        service.saveMenu(ACCOUNT, Map.of("buttons", old));
        service.saveMenu(ACCOUNT, Map.of("buttons", draft));
        assertThatThrownBy(() -> service.publishMenu(ACCOUNT, old))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
        verify(accounts, never()).apiPost(eq(ACCOUNT), eq("/cgi-bin/menu/create"), any());
        when(accounts.apiPost(eq(ACCOUNT), eq("/cgi-bin/menu/create"), any())).thenReturn(new JSONObject());
        assertThat(service.publishMenu(ACCOUNT, draft)).containsEntry("status", "PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT buttons_json FROM wechat_menu_draft WHERE account_id=?", String.class, ACCOUNT))
                .contains("\"key\":\"help\"").doesNotContain("unused");
        assertThat(service.menu("other")).containsEntry("buttons", Map.of("button", List.of()));
    }

    @Test
    void importReadsDefaultMenuEnvelopeAndDoesNotReplaceDraftWithAnEmptyResponse() throws Exception {
        var button = Map.of("name", "首页", "type", "view", "url", "https://example.com/");
        when(accounts.apiGet(ACCOUNT, "/cgi-bin/menu/get")).thenReturn(
                new JSONObject(Map.of("menu", Map.of("button", List.of(button)), "conditionalmenu", List.of())),
                new JSONObject(Map.of("menu", Map.of("button", List.of()))));
        assertThat(service.importMenu(ACCOUNT)).containsEntry("status", "PUBLISHED");
        assertThatThrownBy(() -> service.importMenu(ACCOUNT)).isInstanceOf(BusinessException.class);
        assertThat(service.menu(ACCOUNT)).containsEntry("buttons", new JSONObject(Map.of("button", List.of(button))));
    }

    @Test
    void rejectsInvalidActionsAndSupportsAnEmptyUnpublishedDraft() {
        for (var invalid : List.of(
                Map.of("name", "链接", "type", "view", "url", "not-a-link"),
                Map.of("name", "一级菜单超过字节", "type", "click", "key", "foo"),
                Map.of("name", "拍照", "type", "pic_sysphoto"),
                Map.of("name", "一级", "sub_button", List.of(Map.of("name", "二级", "sub_button",
                        List.of(Map.of("name", "三级", "type", "click", "key", "foo"))))))) {
            assertThatThrownBy(() -> service.saveMenu(ACCOUNT, Map.of("buttons", Map.of("button", List.of(invalid)))))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        }
        assertThat(service.saveMenu(ACCOUNT, Map.of("buttons", Map.of("button", List.of())))).containsEntry("status", "DRAFT");
    }

    @Test
    void unboundAccountDoesNotCallWechat() throws Exception {
        when(accounts.accountSummary("unbound")).thenThrow(new BusinessException(404, "公众号不存在"));

        assertThatThrownBy(() -> service.syncFans("unbound")).isInstanceOf(BusinessException.class);

        verify(accounts, never()).apiGet(eq("unbound"), any());
    }

    @Test
    void managedRulesReadAndWriteOnlyTheAuthorizedAccountsRevision() {
        String appId = "wx0000000000000001";
        when(accounts.accountSummary(ACCOUNT)).thenReturn(Map.of("mode", "OPEN_PLATFORM", "appId", appId));
        var existing = Map.<String, Object>of("id", 10, "ruleType", "DEFAULT", "enabled", false);
        when(openPlatform.replyRules(appId)).thenReturn(new WechatOpenPlatformClient.ReplyRules(7, List.of(existing)));
        when(openPlatform.replaceReplyRules(eq(appId), eq(7L), any())).thenAnswer(invocation ->
                new WechatOpenPlatformClient.ReplyRules(8L, invocation.getArgument(2)));

        assertThat(service.autoReplies(ACCOUNT)).containsExactly(existing);
        assertThat(service.saveAutoReply(ACCOUNT, 10L, Map.of("ruleType", "KEYWORD", "keyword", "帮助", "content", "请进入帮助中心")))
                .containsEntry("id", 10L).containsEntry("content", "请进入帮助中心").containsEntry("replyType", "TEXT");
        service.deleteAutoReply(ACCOUNT, 10L);
        verify(openPlatform).replaceReplyRules(appId, 7L, List.of());
    }

    @Test
    void managedRulesRejectMediaAndOtherAccountRuleIds() {
        String appId = "wx0000000000000002";
        when(accounts.accountSummary(ACCOUNT)).thenReturn(Map.of("mode", "OPEN_PLATFORM", "appId", appId));
        when(openPlatform.replyRules(appId)).thenReturn(new WechatOpenPlatformClient.ReplyRules(3, List.of()));
        assertThatThrownBy(() -> service.saveAutoReply(ACCOUNT, null,
                Map.of("ruleType", "DEFAULT", "replyType", "IMAGE", "mediaId", "test-media")))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.deleteAutoReply(ACCOUNT, 10L))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        verify(openPlatform, never()).replaceReplyRules(eq(appId), any(Long.class), any());
    }

    private void mockWechatDetailLimit(List<Integer> sizes) throws Exception {
        when(accounts.apiPost(eq(ACCOUNT), eq(USER_DETAILS), any())).thenAnswer(invocation -> {
            Map<?, ?> body = invocation.getArgument(2);
            List<?> users = (List<?>) body.get("user_list");
            sizes.add(users.size());
            if (users.size() > 100) return new JSONObject(Map.of("errcode", 40032));
            return profiles(users.stream().map(row -> (String) ((Map<?, ?>) row).get("openid")).toList());
        });
    }

    private int count(String accountId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM wechat_fan WHERE account_id=?", Integer.class, accountId);
    }

    private static String id(int i) {
        return "test-openid-" + i;
    }

    private static List<String> ids(int start, int end) {
        return IntStream.range(start, end).mapToObj(WechatOperationsServiceTest::id).toList();
    }

    private static JSONObject followerPage(List<String> ids, String next) {
        return new JSONObject(Map.of("data", Map.of("openid", ids), "next_openid", next));
    }

    private static JSONObject profiles(List<String> ids) {
        return new JSONObject(Map.of("user_info_list", ids.stream().map(id -> Map.of("openid", id, "subscribe", 1)).toList()));
    }
}
