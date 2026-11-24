package com.delta.notification.service;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.delta.common.tenant.TenantKeyEncoder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Account-scoped setup only. Does not change senders, re-enable a mapping, or send a notification. */
@Service
@RequiredArgsConstructor
public class WechatTemplateProvisioner {
    private final WechatAccountService accounts;
    private final WechatProvisioningCatalog catalog;
    private final StringRedisTemplate redis;
    private final TenantKeyEncoder tenantKeys;
    private static final String ADD = "/cgi-bin/template/api_add_template";
    private static final String PENDING = "!pending";

    public Map<String,Object> provision(String accountId) {
        return provision(accountId, accounts.provisioningSchemes(), null);
    }
    Map<String,Object> provision(String accountId, JsonNode specs, Set<String> only) {
        return provision(accountId,specs,only,null);
    }
    Map<String,Object> provision(String accountId, JsonNode specs, Set<String> only, String repairScope) {
        catalog.checkAccount(accountId); // tenant-routed JDBC; never resolve a default account
        var report = new Report();

        if (specs == null || !specs.isArray() || specs.isEmpty() || specs.size() > 40) {
            report.issue("", "", "SCHEME_UNAVAILABLE");
            return report.summary();
        }
        var groups = new LinkedHashMap<List<String>, List<WechatProvisioningScheme>>();
        for (JsonNode spec : specs) {
            var s = new WechatProvisioningScheme(spec);
            if (!WechatAccountService.ROLES.contains(s.role()) || !WechatAccountService.SCENARIOS.contains(s.scenario())) {
                report.issue("", "", "INVALID_SCHEME");
                continue;
            }
            if (only != null && !only.contains(s.scope())) continue;
            groups.computeIfAbsent(List.of(s.role(), s.scenario()), ignored -> new ArrayList<>()).add(s);
        }
        var run = new Run(accountId, report, specs);
        var ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparing(g -> !g.get(0).scope().equals(repairScope)));
        for (var group : ordered) {
            var s = group.get(0);
            String reason;
            try {
                reason = run.configure(s, group.size() > 1, s.scope().equals(repairScope));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                report.issue(s.role(), s.scenario(), "INTERRUPTED");
                break;
            } catch (Exception unavailable) {
                // HTTP exceptions may contain a token-bearing URI. Do not return/log provider text.
                reason = "PROVISION_UNAVAILABLE";
            }
            if (reason != null) {
                if (s.semantic() && Set.of("FIELD_CONFLICT","DUPLICATE_TITLE","INVALID_SCHEME","DUPLICATE_SCHEME").contains(reason))
                    catalog.suspendOwned(accountId,s);
                report.issue(s.role(), s.scenario(), reason);
                if (run.detail!=null && reason.equals("FIELD_CONFLICT")) report.issues.set(report.issues.size()-1,Map.of("role",s.role(),"scenario",s.scenario(),"code",reason,"reason",run.detail));
                run.detail=null;
            }
        }
        return report.summary();
    }

    private final class Run {
        final String account;
        final Report report;
        List<WechatProvisioningCatalog.Entry> entries;
        boolean refreshFailed; String detail;

        final JsonNode schemes;
        Run(String account, Report report, JsonNode schemes) { this.account = account; this.report = report; this.schemes = schemes; }

        // Positive receipts only, narrowed to this tenant/account and the exact signed library short ID.
        // Include unmodified sibling scopes even when this job retries just one changed role.
        Set<String> relatedIds(WechatProvisioningScheme target) {
            var ids = new HashSet<String>();
            for (var node : schemes) {
                var sibling = new WechatProvisioningScheme(node);
                if (!sibling.semantic() || !target.shortId().equals(sibling.shortId()) ||
                        !WechatAccountService.ROLES.contains(sibling.role()) || !WechatAccountService.SCENARIOS.contains(sibling.scenario())) continue;
                var keys = sibling.orderedKeywords();
                if (keys == null || sibling.addBody(keys) == null) continue;
                var id = redis.opsForValue().get(receiptKey(account,sibling.shortId(),keys));
                if (id != null && !PENDING.equals(id)) ids.add(id);
            }
            return ids;
        }

        void refresh() throws Exception {
            entries = null;
            refreshFailed = true; // no further writes from an old snapshot if re-fetch fails
            entries = catalog.refresh(account);
            refreshFailed = false;
        }

        String configure(WechatProvisioningScheme s, boolean duplicate, boolean allowRepair) throws Exception {
            var existing = catalog.existing(account, s);
            boolean managed = existing != null && "AUTO_SEMANTIC".equals(existing.source()) && s.semantic();
            if (existing != null && !managed) report.preserved++;
            if (duplicate) return "DUPLICATE_SCHEME";
            if (existing != null && !managed && !existing.enabled()) return "MAPPING_DISABLED";
            if (s.spec().has("enabled") && !s.spec().path("enabled").asBoolean(false)) return "SCHEME_DISABLED";
            if (s.titles().isEmpty() || !s.validUrl()) return "INVALID_SCHEME";
            if (refreshFailed) return "CATALOG_UNAVAILABLE";
            if (entries == null) refresh();
            if (existing != null && !managed) return existingIssue(existing);

            if (!s.hasFieldPlan()) return "FIELD_CONFLICT";
            var titles = s.titles();
            var matches = matches(titles);
            var keywords = s.orderedKeywords();
            if (keywords == null) return "INVALID_SCHEME";
            var body = s.addBody(keywords);
            String receipt = null, key = null;
            Set<String> recoveryIds = null;
            if (body != null) {
                // Different roles can request the same library entry. Scope the receipt by tenant,
                // account and library selection, NOT by job/role. No TTL: a timeout is not a rejection.
                key = receiptKey(account, s.shortId(), keywords);
                receipt = redis.opsForValue().get(key);
                if (s.semantic()) {
                    String evidence=redis.opsForValue().get(key+":repair");
                    if(evidence!=null) {
                        String issue=verifyRepair(key,evidence,s,keywords);
                        if(issue!=null) return issue;
                        receipt=redis.opsForValue().get(key);
                    }
                }
                if (s.semantic() && !PENDING.equals(receipt)) {
                    var direct = receipt == null ? List.<WechatProvisioningCatalog.Entry>of() : byId(receipt);
                    if (!direct.isEmpty()) matches = direct;
                    else if (receipt != null || matches.isEmpty()) {
                        var ids = relatedIds(s);
                        var alternatives = s.recoveryCandidates(entries, ids, keywords);
                        if (alternatives.size() > 1) return "RECOVERY_AMBIGUOUS";
                        if (!alternatives.isEmpty()) {
                            matches = alternatives;
                            recoveryIds = ids;
                        } else if (receipt != null) {
                            // Do not clear a receipt or submit another add on the strength of a missing catalogue entry.
                            if(redis.opsForValue().get(key+":repair")!=null) return "REPAIR_UNVERIFIED";
                            if (!allowRepair) return "RECEIPT_NOT_IN_CATALOG";
                            String issue=repair(key,body,s,keywords);
                            if(issue!=null) return issue;
                            receipt=redis.opsForValue().get(key);
                            matches=byId(receipt);
                        }
                    }
                }
            }
            if (matches.isEmpty()) {
                if (body == null) return "LIBRARY_PARAMETERS_MISSING";
                if (allowRepair && receipt==null) return "CATALOG_CHANGED";
                if (receipt != null) return PENDING.equals(receipt) ? "ADD_OUTCOME_UNKNOWN" : "ADDED_TEMPLATE_NOT_VERIFIED";
                if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, PENDING))) return "ADD_PENDING";
                com.alibaba.fastjson2.JSONObject reply;
                try {
                    reply = accounts.apiPost(account, ADD, body);
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception unknown) {
                    return "ADD_OUTCOME_UNKNOWN";
                }
                if (reply == null) return "ADD_OUTCOME_UNKNOWN";
                if (reply.get("errcode") instanceof Number code && code.intValue() > 0) {
                    redis.delete(key); // definite rejection, not an ambiguous timeout
                    return "WECHAT_ADD_REJECTED_" + code.intValue();
                }
                if (!"0".equals(String.valueOf(reply.get("errcode"))) || !(reply.get("template_id") instanceof String id)
                        || !id.matches("[A-Za-z0-9_-]{6,128}")) return "ADD_OUTCOME_UNKNOWN";
                redis.opsForValue().set(key, id);
                receipt = id;
                refresh();
                matches = s.semantic()?byId(id):matches(titles);
            }
            if (matches.size() > 1) return "DUPLICATE_TITLE";
            if (matches.isEmpty()) return "ADDED_TEMPLATE_NOT_VERIFIED";
            var selected = matches.get(0);
            if (PENDING.equals(receipt)) return "ADD_OUTCOME_UNKNOWN";
            if (recoveryIds == null && receipt != null && !receipt.equals(selected.id())) return "ADDED_TEMPLATE_NOT_VERIFIED";
            var fields = s.fields(selected.content(), keywords);
            if (fields.isEmpty()) { if(s.semantic()) detail=s.mismatch(selected.content()); return "FIELD_CONFLICT"; }
            try {
                if (managed && existing.enabled() && existing.id().equals(selected.id()) && fields.equals(JSON.parseObject(existing.fields()))
                        && Objects.equals(existing.url(),s.url().isEmpty()?null:s.url())) {report.preserved++; return null;}
                if (!catalog.store(account, s, selected, fields, managed, s.semantic() && selected.id().equals(receipt), recoveryIds)) return "CATALOG_CHANGED";
                report.configured++;
                return null;
            } catch (DuplicateKeyException concurrentSave) {
                var saved = catalog.existing(account, s);
                if (saved == null) throw concurrentSave;
                report.preserved++;
                return existingIssue(saved);
            }
        }

        // Durable per-selection claim, shared by every role. Ordinary sync only reconciles it.
        Set<String> fingerprints() {
            var result=new HashSet<String>();
            for(var e:entries) result.add(UUID.nameUUIDFromBytes(JSON.toJSONBytes(Arrays.asList(e.id(),e.title(),e.content(),e.primary(),e.deputy()))).toString());
            return result;
        }
        String repair(String key,Object body,WechatProvisioningScheme s,List<String> keywords) throws Exception {
            String ledger=key+":repair";
            var record=new com.alibaba.fastjson2.JSONObject();
            record.put("guards", fingerprints());
            if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(ledger,record.toJSONString()))) return "REPAIR_PENDING";
            com.alibaba.fastjson2.JSONObject reply;
            try { reply=accounts.apiPost(account,ADD,body); }
            catch(InterruptedException interrupted){ throw interrupted; }
            catch(Exception uncertain){ return "REPAIR_PENDING"; }
            if(reply!=null && reply.get("errcode") instanceof Number n && n.intValue()>0) {
                redis.delete(ledger);
                return "WECHAT_ADD_REJECTED_"+n.intValue();
            }
            if(reply==null || !"0".equals(String.valueOf(reply.get("errcode"))) || !(reply.get("template_id") instanceof String id)
                    || !id.matches("[A-Za-z0-9_-]{6,128}")) return "REPAIR_PENDING";
            record.put("id",id);
            redis.opsForValue().set(ledger,record.toJSONString());
            refresh();
            return verifyRepair(key,record.toJSONString(),s,keywords);
        }
        String verifyRepair(String key,String evidence,WechatProvisioningScheme s,List<String> keywords) {
            var record=JSON.parseObject(evidence);
            if(record.getBooleanValue("verified")) return null;
            String id=record.getString("id");
            if(id==null) return "REPAIR_PENDING";
            if(!fingerprints().containsAll(record.getList("guards",String.class))) return "REPAIR_CHANGED";
            var actual=byId(id);
            if(actual.size()!=1 || s.fields(actual.get(0).content(),keywords).isEmpty()) return "REPAIR_UNVERIFIED";
            redis.opsForValue().set(key,id);
            record.put("verified",true);
            redis.opsForValue().set(key+":repair",record.toJSONString());
            return null;
        }

        String existingIssue(WechatProvisioningCatalog.Existing saved) {
            if (!saved.enabled()) return "MAPPING_DISABLED";
            for (var e : entries) if (e.id().equals(saved.id())) {
                return WechatProvisioningScheme.validExistingFields(e.content(), saved.fields()) ? null : "MAPPED_FIELDS_CHANGED";
            }
            return "MAPPED_TEMPLATE_MISSING";
        }

        List<WechatProvisioningCatalog.Entry> byId(String verifiedId) {
            return entries.stream().filter(e->e.id().equals(verifiedId)).toList();
        }
        List<WechatProvisioningCatalog.Entry> matches(Set<String> titles) {
            return entries.stream().filter(e -> titles.contains(WechatProvisioningScheme.normalize(e.title()))).toList();
        }
    }

    private String receiptKey(String account, String shortId, List<String> orderedKeywords) {
        // Hash exactly the keyword array sent to WeChat; swapping two positions selects another variant.
        var hash = UUID.nameUUIDFromBytes(JSON.toJSONString(orderedKeywords).getBytes(StandardCharsets.UTF_8));
        return tenantKeys.key("wechat:template-add:" + account + ":" + shortId + ":" + hash);
    }

    private static final class Report {
        int configured, preserved;
        final List<Map<String,String>> issues = new ArrayList<>();
        void issue(String role, String scenario, String code) {
            String reason = switch (code) {
                case "SCHEME_UNAVAILABLE", "INVALID_SCHEME" -> "请在授权中心检查公众号模板方案";
                case "DUPLICATE_SCHEME" -> "同一角色和场景存在多份方案，请先消除冲突";
                case "MAPPING_DISABLED" -> "已有映射已停用，保留原设置";
                case "SCHEME_DISABLED" -> "授权中心已停用此方案";
                case "CATALOG_UNAVAILABLE", "CATALOG_CHANGED" -> "模板库不可用或已变化，请重新同步";
                case "MAPPED_TEMPLATE_MISSING" -> "保留已有映射，但该模板已不在此公众号模板库中";
                case "MAPPED_FIELDS_CHANGED" -> "保留已有映射，但模板变量或数据源已不匹配，请手动核对";
                case "DUPLICATE_TITLE" -> "多个微信模板符合方案标题，请手动选择";
                case "LIBRARY_PARAMETERS_MISSING" -> "未找到匹配模板，请在授权中心配置模板库编号和关键词";
                case "ADD_OUTCOME_UNKNOWN", "ADD_PENDING" -> "微信添加结果待确认，不重复添加；请核对公众号模板库";
                case "REPAIR_PENDING" -> "补建请求已登记，微信结果尚未核实；不会重复添加，请核对本公众号模板库";
                case "REPAIR_CHANGED" -> "补建后原模板目录发生变化，已停止绑定；请核对原有模板";
                case "REPAIR_UNVERIFIED" -> "补建回执已收到，尚未核实新模板及其字段；请稍后重新同步";
                case "RECOVERY_AMBIGUOUS" -> "本公众号有多份模板同时符合方案，请在场景配置中选择正确模板";
                case "RECEIPT_NOT_IN_CATALOG" -> "原回执不在当前目录，未找到可唯一核实的替代模板；请核对本号模板库中的实际标题与关键词，或手动选择模板";
                case "ADDED_TEMPLATE_NOT_VERIFIED" -> "未能在此公众号模板库核实微信返回的模板，请稍后重试";
                case "FIELD_CONFLICT" -> "微信模板变量与方案不能唯一对应，请手动配置";
                case "INTERRUPTED" -> "配置被中断，请重试";
                default -> code.startsWith("WECHAT_ADD_REJECTED_")
                        ? "微信拒绝添加，请检查接口权限、模板编号和关键词（" + code.substring("WECHAT_ADD_REJECTED_".length()) + "）"
                        : "配置未完成，请检查授权和连接后重试";
            };
            issues.add(Map.of("role", role, "scenario", scenario, "code", code, "reason", reason));
        }
        Map<String,Object> summary() {
            return Map.of("configured", configured, "preserved", preserved, "issues", List.copyOf(issues));
        }
    }
}
