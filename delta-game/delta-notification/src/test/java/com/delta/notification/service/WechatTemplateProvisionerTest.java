package com.delta.notification.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.delta.common.exception.BusinessException;
import com.delta.common.tenant.*;
import com.delta.platform.PlatformService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatTemplateProvisionerTest {
    static final String ACCOUNT = "managed-a", GET = "/cgi-bin/template/get_all_private_template", ADD = "/cgi-bin/template/api_add_template";
    final ObjectMapper json = new ObjectMapper();
    final PlatformService platform = mock(PlatformService.class);
    final Map<String,String> receipts = new ConcurrentHashMap<>();
    WechatAccountService accounts;
    WechatTemplateProvisioner service;
    JdbcTemplate jdbc;
    ObjectNode spec;
    TenantContext.Scope tenant;

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() throws Exception {
        var router = new TenantRoutingDataSource(Map.of("one", database(), "two", database()));
        jdbc = new JdbcTemplate(router);
        tenant = TenantContext.open("one");
        var registry = mock(TenantRegistry.class);
        when(registry.isSharedEnabled()).thenReturn(true);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> receipts.get(i.getArgument(0)));
        when(ops.setIfAbsent(anyString(), anyString())).thenAnswer(i -> receipts.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        doAnswer(i -> receipts.put(i.getArgument(0), i.getArgument(1))).when(ops).set(anyString(), anyString());
        when(redis.delete(anyString())).thenAnswer(i -> receipts.remove(i.getArgument(0)) != null);
        accounts = spy(new WechatAccountService(jdbc, null, platform, null));
        // Unmatched calls (including a send endpoint) fail the test without hitting the network.
        doAnswer(i -> { throw new AssertionError(i.getArgument(1, String.class)); }).when(accounts).apiGet(anyString(), anyString());
        doAnswer(i -> { throw new AssertionError(i.getArgument(1, String.class)); }).when(accounts).apiPost(anyString(), anyString(), any());
        service = new WechatTemplateProvisioner(accounts, new WechatProvisioningCatalog(accounts,jdbc),redis,new TenantKeyEncoder(registry));
        spec = (ObjectNode)json.readTree("""
                {"role":"ADMIN","scenario":"new_order","name":"新订单提醒","templateHint":"搜索关键词：订单待处理、其他",
                 "fields":{"thing1":"productName"},"fieldSuggestions":[{"label":"商品名称","type":"thing","source":"productName"}],
                 "templateIdShort":"47123","keywordNames":["商品名称"],"url":"https://example.org/orders"}
                """);
        schemes(spec);
    }

    @AfterEach void close() { tenant.close(); }

    private JdbcDataSource database() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE wechat_mp_account(id VARCHAR PRIMARY KEY,name VARCHAR,app_id VARCHAR,account_mode VARCHAR,is_default INT DEFAULT 0," +
                "enabled INT DEFAULT 1,last_checked_at TIMESTAMP,last_error VARCHAR,app_secret_encrypted VARCHAR,created_at TIMESTAMP)");
        db.update("INSERT INTO wechat_mp_account(id,name,app_id,account_mode) VALUES(?,?,?,'OPEN_PLATFORM')", ACCOUNT,"fixture","wx-test");
        db.execute("CREATE TABLE wechat_mp_template_catalog(account_id VARCHAR,template_id VARCHAR,title VARCHAR,content VARCHAR," +
                "primary_industry VARCHAR,deputy_industry VARCHAR,PRIMARY KEY(account_id,template_id))");
        db.execute("CREATE TABLE wechat_mp_template(account_id VARCHAR,role VARCHAR,scenario VARCHAR,template_id VARCHAR,fields_json VARCHAR," +
                "url VARCHAR,source VARCHAR,enabled INT,PRIMARY KEY(account_id,role,scenario))");
        return ds;
    }

    private void schemes(ObjectNode... specs) {
        var project = json.createObjectNode();
        var rows = project.putObject("wechatTemplates").putArray("schemes");
        for (var s : specs) rows.add(s);
        when(platform.projectSnapshot()).thenReturn(project);
    }
    private JSONObject snapshot(String... ids) {
        List<Map<String,String>> rows = new ArrayList<>();
        for (var id : ids) rows.add(Map.of("template_id",id,"title","订单待处理","content","商品名称：{{thing8.DATA}}"));
        return JSON.parseObject(JSON.toJSONString(Map.of("template_list",rows)));
    }
    private List<Map<String,Object>> mappings() { return jdbc.queryForList("SELECT * FROM wechat_mp_template"); }
    private Map<String,Object> run() { return service.provision(ACCOUNT); }
    private void listed(JSONObject... replies) throws Exception {
        var stub = doReturn(replies[0]);
        for (int i=1; i<replies.length;i++) stub = stub.doReturn(replies[i]);
        stub.when(accounts).apiGet(ACCOUNT,GET);
    }
    private void addReply(JSONObject reply) throws Exception {
        doReturn(reply).when(accounts).apiPost(ACCOUNT,ADD, Map.of("template_id_short","47123","keyword_name_list",List.of("商品名称")));
    }

    @Test void liveSiblingReceiptProvesOfficialTitleAndIndustryButNotArbitraryTemplates() throws Exception {
        spec.put("mappingMode","SEMANTIC").putObject("fields");
        var other=spec.deepCopy().put("role","PLAYER"); other.putArray("keywordNames").add("完成时间");
        other.putArray("fieldSuggestions").addObject().put("label","完成时间").put("type","time").put("source","time");
        schemes(spec,other);
        var live=snapshot("player-id","new-admin-id");
        for(var obj:live.getJSONArray("template_list")){
            var row=(JSONObject)obj;row.put("title","微信实际业务标题");row.put("primary_industry","主行业");
        }
        live.getJSONArray("template_list").getJSONObject(0).put("content","完成时间：{{time1.DATA}}");
        doReturn(JSONObject.of("errcode",0,"template_id","retired-id")).when(accounts).apiPost(ACCOUNT,ADD,Map.of("template_id_short","47123","keyword_name_list",List.of("商品名称")));
        doReturn(JSONObject.of("errcode",0,"template_id","player-id")).when(accounts).apiPost(ACCOUNT,ADD,Map.of("template_id_short","47123","keyword_name_list",List.of("完成时间")));
        listed(snapshot(),snapshot(),live);run();
        var diffIndustry=JSON.parseObject(live.toJSONString());diffIndustry.getJSONArray("template_list").getJSONObject(1).put("primary_industry","无关行业");
        listed(diffIndustry);
        assertThat(service.provision(ACCOUNT,accounts.provisioningSchemes(),Set.of("ADMIN:new_order")).get("issues").toString()).contains("RECEIPT_NOT_IN_CATALOG");
        listed(live);
        assertThat(service.provision(ACCOUNT,accounts.provisioningSchemes(),Set.of("ADMIN:new_order"))).containsEntry("configured",1).containsEntry("issues",List.of());
        assertThat(mappings()).anySatisfy(r->assertThat(r).containsEntry("ROLE","ADMIN").containsEntry("TEMPLATE_ID","new-admin-id"));
        verify(accounts,times(2)).apiPost(eq(ACCOUNT),eq(ADD),any());
    }

    @Test void staleReceiptCanRecoverOnlyOneVerifiedReplacementWithoutAddingAgain() throws Exception {
        spec.put("mappingMode","SEMANTIC").putObject("fields");schemes(spec);
        listed(snapshot(), snapshot("original-id"));addReply(JSONObject.of("errcode",0,"template_id","original-id"));
        assertThat(run()).containsEntry("configured",1);
        listed(snapshot("similar-id","another-id"));
        assertThat(run().get("issues").toString()).contains("RECOVERY_AMBIGUOUS");
        assertThat(mappings().get(0)).containsEntry("TEMPLATE_ID","original-id");
        var foreign=snapshot("wrong-title");foreign.getJSONArray("template_list").getJSONObject(0).put("title","其他用途");
        listed(foreign);assertThat(run().get("issues").toString()).contains("RECEIPT_NOT_IN_CATALOG");
        var bad=snapshot("wrong-fields");bad.getJSONArray("template_list").getJSONObject(0).put("content","别的字段：{{thing3.DATA}}");
        listed(bad);assertThat(run().get("issues").toString()).contains("RECEIPT_NOT_IN_CATALOG");
        listed(snapshot("replacement-id"));
        assertThat(run()).containsEntry("configured",1).containsEntry("issues",List.of());
        assertThat(run()).containsEntry("preserved",1);
        assertThat(mappings().get(0)).containsEntry("TEMPLATE_ID","replacement-id");
        verify(accounts,times(1)).apiPost(eq(ACCOUNT),eq(ADD),any());
    }

    @Test void staleReceiptUsesOtherConfirmedSelectionOfSameLibraryEvenOnScopedRetry() throws Exception {
        spec.put("mappingMode","SEMANTIC").putObject("fields");spec.withArray("keywordNames").add("时间");
        spec.withArray("fieldSuggestions").addObject().put("label","时间").put("type","time").put("source","time");
        var other=spec.deepCopy().put("role","CS");other.putArray("keywordNames").add("时间").add("商品名称");
        schemes(spec,other);
        doReturn(JSONObject.of("errcode",0,"template_id","previous-id")).when(accounts).apiPost(ACCOUNT,ADD,Map.of("template_id_short","47123","keyword_name_list",List.of("商品名称","时间")));
        doReturn(JSONObject.of("errcode",0,"template_id","active-id")).when(accounts).apiPost(ACCOUNT,ADD,Map.of("template_id_short","47123","keyword_name_list",List.of("时间","商品名称")));
        var fresh=twoFields("active-id",true);fresh.getJSONArray("template_list").getJSONObject(0).put("title","真实微信标题");
        listed(snapshot(),snapshot(),fresh);run();
        listed(fresh);
        var result=service.provision(ACCOUNT,accounts.provisioningSchemes(),Set.of("ADMIN:new_order"));
        assertThat(result).containsEntry("configured",1).containsEntry("issues",List.of());
        assertThat(mappings()).hasSize(2).allSatisfy(row->assertThat(row).containsEntry("TEMPLATE_ID","active-id"));
        verify(accounts,times(2)).apiPost(eq(ACCOUNT),eq(ADD),any());
    }

    @Test void semanticKeywordsUseActualKeysPerAccountAndNeverRewriteManualFields() throws Exception {
        spec.put("mappingMode","SEMANTIC").putObject("fields");schemes(spec);
        var body=snapshot("owned-template"); body.getJSONArray("template_list").getJSONObject(0).put("content","商品名称：{{thing7.DATA}}");
        listed(body);assertThat(run()).containsEntry("configured",1);
        jdbc.update("INSERT INTO wechat_mp_account(id,name,app_id,account_mode,enabled) VALUES('testb','test','test-b','OPEN_PLATFORM',1)");
        doReturn(snapshot("second-template")).when(accounts).apiGet("testb",GET);
        assertThat(service.provision("testb")).containsEntry("configured",1);
        assertThat(jdbc.queryForList("SELECT fields_json FROM wechat_mp_template ORDER BY account_id",String.class))
                .containsExactly("{\"thing7\":\"productName\"}","{\"thing8\":\"productName\"}");
        jdbc.update("UPDATE wechat_mp_template SET source='LOCAL',fields_json=? WHERE account_id=?","{\"thing7\":\"message\"}",ACCOUNT);
        assertThat(run()).containsEntry("preserved",1);
        assertThat(jdbc.queryForObject("SELECT fields_json FROM wechat_mp_template WHERE account_id=?",String.class,ACCOUNT)).contains("message");
        verify(accounts,never()).apiPost(anyString(),anyString(),any());
    }
    @Test void verifiedAddReceiptBeatsDisplayTitleButStillRequiresActualBody() throws Exception {
        spec.put("mappingMode","SEMANTIC").putObject("fields");schemes(spec);
        var body=snapshot("new-owned-id");body.getJSONArray("template_list").getJSONObject(0).put("title","不同的微信官方标题");
        listed(snapshot(),body);addReply(JSONObject.of("errcode",0,"template_id","new-owned-id"));
        assertThat(run()).containsEntry("configured",1).containsEntry("issues",List.of());
    }
    @Test void exactTitleAndActualFieldsNotSchemeOrLibraryIds() throws Exception {
        listed(snapshot("owned-template"));
        assertThat(run()).containsEntry("configured",1).containsEntry("issues",List.of());
        assertThat(mappings()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("TEMPLATE_ID","owned-template").containsEntry("FIELDS_JSON","{\"thing8\":\"productName\"}")
                .containsEntry("SOURCE","AUTHORITY").containsEntry("URL","https://example.org/orders"));
        assertThat(run()).containsEntry("preserved",1).containsEntry("configured",0);
        verify(accounts,never()).apiPost(anyString(),anyString(),any());
    }

    @Test void addsOnceEvenWithDelayedCatalogAndNeverAcceptsWrongReturnedId() throws Exception {
        listed(snapshot(),snapshot("wrong-owned"),snapshot(),snapshot("added-template"));
        addReply(JSONObject.of("errcode",0,"template_id","added-template"));
        assertThat(run().get("issues").toString()).contains("ADDED_TEMPLATE_NOT_VERIFIED");
        assertThat(run().get("issues").toString()).contains("ADDED_TEMPLATE_NOT_VERIFIED");
        assertThat(mappings()).isEmpty();
        assertThat(run()).containsEntry("configured",1);
        verify(accounts,times(1)).apiPost(eq(ACCOUNT),eq(ADD),any());
        verify(accounts,times(4)).apiGet(ACCOUNT,GET);
    }

    @Test void sameIdInOtherTenantCannotReadOurBindingOrReceipt() throws Exception {
        listed(snapshot(),snapshot("tenant1-template"),snapshot(),snapshot("tenant2-template"));
        doReturn(JSONObject.of("errcode",0,"template_id","tenant1-template"),JSONObject.of("errcode",0,"template_id","tenant2-template"))
                .when(accounts).apiPost(eq(ACCOUNT),eq(ADD),any());
        assertThat(run()).containsEntry("configured",1);
        tenant.close();
        assertThatThrownBy(this::run).isInstanceOf(BusinessException.class);
        tenant = TenantContext.open("two");
        assertThat(mappings()).isEmpty();
        assertThat(run()).containsEntry("configured",1);
        assertThat(mappings().get(0)).containsEntry("TEMPLATE_ID","tenant2-template");
        assertThat(receipts.keySet()).hasSize(2).anyMatch(k -> k.startsWith("delta:one:")).anyMatch(k -> k.startsWith("delta:two:"));
    }

    @Test void rejectsDirectDisabledAndUnknownBeforeAnyProviderCall() throws Exception {
        assertThatThrownBy(() -> service.provision("foreign-account")).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE wechat_mp_account SET enabled=0");
        assertThatThrownBy(this::run).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE wechat_mp_account SET enabled=1,account_mode='DIRECT'");
        assertThatThrownBy(this::run).isInstanceOf(BusinessException.class);
        verify(accounts,never()).apiGet(anyString(),anyString());
        verify(accounts,never()).apiPost(anyString(),anyString(),any());
    }

    @Test void preservesManualAndDisabledRowsEvenWhenStale() throws Exception {
        jdbc.update("INSERT INTO wechat_mp_template VALUES(?,'ADMIN','new_order','manual-template','{}','https://example.org/manual','LOCAL',1)",ACCOUNT);
        listed(snapshot("another-template"));
        var before = mappings();
        var stale = run();
        assertThat(stale).containsEntry("preserved",1);
        assertThat(stale.get("issues").toString()).contains("MAPPED_TEMPLATE_MISSING");
        assertThat(mappings()).isEqualTo(before);
        listed(snapshot("manual-template"));
        assertThat(run().get("issues").toString()).contains("MAPPED_FIELDS_CHANGED");
        assertThat(mappings()).isEqualTo(before);
        jdbc.update("UPDATE wechat_mp_template SET fields_json=?","{\"thing8\":\"message\"}");
        assertThat(run()).containsEntry("issues",List.of()).containsEntry("preserved",1);
        assertThat(mappings().get(0)).containsEntry("FIELDS_JSON","{\"thing8\":\"message\"}").containsEntry("URL","https://example.org/manual");
        jdbc.update("UPDATE wechat_mp_template SET enabled=0");
        assertThat(run().get("issues").toString()).contains("MAPPING_DISABLED");
        assertThat(mappings().get(0)).containsEntry("ENABLED",0).containsEntry("SOURCE","LOCAL");
        verify(accounts,never()).apiPost(anyString(),anyString(),any());
    }

    @Test void duplicateSchemeOrTitleNeverChoosesFirst() throws Exception {
        schemes(spec,spec.deepCopy());
        assertThat(run().get("issues").toString()).contains("DUPLICATE_SCHEME");
        schemes(spec);
        listed(snapshot("owned-first","owned-second"));
        assertThat(run().get("issues").toString()).contains("DUPLICATE_TITLE");
        assertThat(mappings()).isEmpty();
        verify(accounts,never()).apiPost(anyString(),anyString(),any());
    }

    @Test void ambiguousOrIncompleteFieldsNeverBindOrAdd() throws Exception {
        for (String content : List.of("商品名称：{{thing8.DATA}}\n未知：{{thing2.DATA}}","商品名称：{{time1.DATA}}",
                "商品名称：{{thing8.DATA}} {{malformed.DATA}}","商品名称：{{thing8.DATA}} {{","没有变量")) {
            var bad = snapshot("owned-template");
            bad.getJSONArray("template_list").getJSONObject(0).put("content",content);
            listed(bad);
            assertThat(run().get("issues").toString()).contains("FIELD_CONFLICT");
        }
        spec.withArray("fieldSuggestions").addObject().put("type","thing").put("label","商品名称").put("source","message");
        listed(snapshot("owned-template"));
        assertThat(run().get("issues").toString()).contains("FIELD_CONFLICT");
        assertThat(mappings()).isEmpty();
        verify(accounts,never()).apiPost(anyString(),anyString(),any());
    }

    @Test void timeoutDoesNotLeakErrorOrCauseSecondPost() throws Exception {
        listed(snapshot());
        doThrow(new IOException("provider request contained confidential material")).when(accounts).apiPost(eq(ACCOUNT),eq(ADD),any());
        assertThat(run().toString()).doesNotContain("confidential");
        assertThat(run().get("issues").toString()).contains("ADD_OUTCOME_UNKNOWN");
        assertThat(mappings()).isEmpty();
        verify(accounts,times(1)).apiPost(eq(ACCOUNT),eq(ADD),any());
    }

    @Test void errorOrMalformedCatalogIsNotEvidenceOfAbsence() throws Exception {
        jdbc.update("INSERT INTO wechat_mp_template_catalog(account_id,template_id,title) VALUES(?,'keep-template','existing')", ACCOUNT);
        listed(JSONObject.of("errcode",40001),new JSONObject());
        for (int i=0;i<2;i++) assertThat(run()).containsEntry("configured",0);
        assertThat(jdbc.queryForObject("SELECT template_id FROM wechat_mp_template_catalog",String.class)).isEqualTo("keep-template");
        verify(accounts,never()).apiPost(anyString(),anyString(),any());
    }

    @Test void definiteRejectionCanRetryAfterCorrection() throws Exception {
        listed(snapshot());
        addReply(JSONObject.of("errcode",40246,"errmsg","provider details"));
        assertThat(run().get("issues").toString()).contains("40246").doesNotContain("provider details");
        assertThat(receipts).isEmpty();
        listed(snapshot(),snapshot("added-template"));
        addReply(JSONObject.of("errcode",0,"template_id","added-template"));
        assertThat(run()).containsEntry("configured",1);
    }

    @Test void existingKeysMustExactlyMatchWhenOnlyExplicitFieldsWereSigned() throws Exception {
        spec.remove("fieldSuggestions");
        var owned = snapshot("owned-template");
        listed(owned);
        assertThat(run().get("issues").toString()).contains("FIELD_CONFLICT");
        spec.putObject("fields").put("thing8","productName");
        assertThat(run()).containsEntry("configured",1);
        verify(accounts,never()).apiPost(anyString(),anyString(),any());
    }

    @Test void verifyAddedKeywordListAndFieldsNotJustTheReturnedId() throws Exception {
        spec.withArray("keywordNames").add("时间");
        listed(snapshot(),snapshot("added-template"));
        doReturn(JSONObject.of("errcode",0,"template_id","added-template")).when(accounts).apiPost(eq(ACCOUNT),eq(ADD),any());
        assertThat(run().get("issues").toString()).contains("FIELD_CONFLICT");
        assertThat(run().get("issues").toString()).contains("FIELD_CONFLICT");
        assertThat(mappings()).isEmpty();
        verify(accounts,times(1)).apiPost(eq(ACCOUNT),eq(ADD),any());
    }

    @Test void neverPostsIfSchemeIsDisabledUnsafeOrIncomplete() throws Exception {
        listed(snapshot());
        spec.put("enabled",false);
        assertThat(run().get("issues").toString()).contains("SCHEME_DISABLED");
        spec.remove("enabled");
        spec.put("url","javascript:alert(1)");
        assertThat(run().get("issues").toString()).contains("INVALID_SCHEME");
        spec.remove("url");
        spec.remove("keywordNames");
        assertThat(run().get("issues").toString()).contains("LIBRARY_PARAMETERS_MISSING");
        spec.remove("fields");
        spec.remove("fieldSuggestions");
        assertThat(run().get("issues").toString()).contains("FIELD_CONFLICT");
        schemes();
        assertThat(run().get("issues").toString()).contains("SCHEME_UNAVAILABLE");
        verify(accounts,never()).apiPost(anyString(),anyString(),any());
    }

    @Test void concurrentManualSaveWinsAndOtherAccountIsUntouched() throws Exception {
        jdbc.update("INSERT INTO wechat_mp_template_catalog(account_id,template_id,title,content) VALUES('other-account','foreign-template','订单待处理','foreign')");
        doAnswer(i -> {
            jdbc.update("INSERT INTO wechat_mp_template VALUES(?,'ADMIN','new_order','manual-template','{}','unchanged','LOCAL',0)",ACCOUNT);
            return snapshot("owned-template");
        }).when(accounts).apiGet(ACCOUNT,GET);
        var report = run();
        assertThat(report).containsEntry("preserved",1).containsEntry("configured",0);
        assertThat(report.get("issues").toString()).contains("MAPPING_DISABLED");
        assertThat(mappings()).singleElement().satisfies(r -> assertThat(r).containsEntry("SOURCE","LOCAL")
                .containsEntry("TEMPLATE_ID","manual-template").containsEntry("ENABLED",0));
        assertThat(jdbc.queryForObject("SELECT content FROM wechat_mp_template_catalog WHERE account_id='other-account'",String.class))
                .isEqualTo("foreign");
    }

    private JSONObject twoFields(String id, boolean timeFirst) {
        var list = snapshot(id);
        var lines = List.of("商品名称：{{thing8.DATA}}", "时间：{{time2.DATA}}");
        list.getJSONArray("template_list").getJSONObject(0).put("content",
                timeFirst ? lines.get(1) + "\n" + lines.get(0) : String.join("\n", lines));
        return list;
    }

    @Test void sameLibraryIdDifferentOrdersHaveSeparateReceiptsAndRetriesUseOnlyTheirOwn() throws Exception {
        spec.withArray("keywordNames").add("时间");
        spec.withArray("fieldSuggestions").addObject().put("label","时间").put("type","time").put("source","time");
        var reversed = spec.deepCopy().put("role","CS");
        reversed.putArray("keywordNames").add("时间").add("商品名称");
        schemes(spec, reversed);
        var forwardBody = Map.of("template_id_short","47123","keyword_name_list",List.of("商品名称","时间"));
        var reverseBody = Map.of("template_id_short","47123","keyword_name_list",List.of("时间","商品名称"));
        doReturn(JSONObject.of("errcode",0,"template_id","forward-id")).when(accounts).apiPost(ACCOUNT,ADD,forwardBody);
        doReturn(JSONObject.of("errcode",0,"template_id","reverse-id")).when(accounts).apiPost(ACCOUNT,ADD,reverseBody);
        // Both adds succeeded before either template appeared in the account catalogue.
        listed(snapshot(),snapshot(),snapshot());
        assertThat(run()).containsEntry("configured",0);
        assertThat(receipts.values()).containsExactlyInAnyOrder("forward-id","reverse-id");
        verify(accounts).apiPost(ACCOUNT,ADD,forwardBody);
        verify(accounts).apiPost(ACCOUNT,ADD,reverseBody);
        listed(twoFields("reverse-id",true));
        var result = run();
        assertThat(result).containsEntry("configured",1);
        assertThat(result.get("issues").toString()).contains("ADDED_TEMPLATE_NOT_VERIFIED","ADMIN");
        assertThat(mappings()).singleElement().satisfies(row -> assertThat(row).containsEntry("ROLE","CS").containsEntry("TEMPLATE_ID","reverse-id"));
        listed(twoFields("forward-id",false));
        assertThat(run()).containsEntry("configured",1).containsEntry("preserved",1);
        verify(accounts,times(2)).apiPost(anyString(),anyString(),any());
    }

    @Test void withoutLibraryIdExistingTemplateStillRequiresOrderedKeywordsAndSafeSources() throws Exception {
        spec.remove("templateIdShort");
        spec.withArray("keywordNames").add("时间");
        spec.withArray("fieldSuggestions").addObject().put("label","时间").put("type","time").put("source","time");
        listed(twoFields("local-template",true));
        assertThat(run().get("issues").toString()).contains("FIELD_CONFLICT");
        listed(twoFields("local-template",false));
        spec.put("keywordNames","商品名称,时间"); // malformed is not a scheme without keywords
        assertThat(run().get("issues").toString()).contains("INVALID_SCHEME");
        spec.putArray("keywordNames").add("商品名称").add("时间");
        spec.withObject("/fields").put("time2","message"); // labels alone are insufficient
        assertThat(run().get("issues").toString()).contains("FIELD_CONFLICT");
        assertThat(mappings()).isEmpty();
        spec.withObject("/fields").remove("time2");
        assertThat(run()).containsEntry("configured",1).containsEntry("issues",List.of());
        assertThat(mappings().get(0)).containsEntry("TEMPLATE_ID","local-template");
        assertThat(receipts).isEmpty();
        verify(accounts,never()).apiPost(anyString(),anyString(),any());
    }
}
