package com.delta.notification.service;

import com.delta.common.notification.NotificationTemplateSources;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** A signed suggestion is not an account template. Resolve every variable against the actual body. */
record WechatProvisioningScheme(JsonNode spec) {
    private static final Pattern VARIABLE = Pattern.compile(
            "\\{\\{((thing|character_string|time|amount|phone_number|car_number|const)[0-9]*)\\.DATA}}");

    String role() { return spec.path("role").asText("").toUpperCase(Locale.ROOT); }
    String scenario() { return spec.path("scenario").asText("").toLowerCase(Locale.ROOT); }
    boolean semantic() { return "SEMANTIC".equals(spec.path("mappingMode").asText()); }
    String scope() { return role()+":"+scenario(); }
    String url() { return spec.path("url").asText(""); }
    String shortId() { return spec.path("templateIdShort").asText(""); }

    Set<String> titles() {
        Set<String> result = new HashSet<>();
        result.add(normalize(spec.path("name").asText("")));
        for (String hint : spec.path("templateHint").asText("").replaceFirst("^搜索关键词\\s*[:：]\\s*", "")
                .split("[、,，;；]")) result.add(normalize(hint));
        result.remove("");
        return result;
    }

    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", "").toLowerCase(Locale.ROOT);
    }

    boolean recoveryCandidate(WechatProvisioningCatalog.Entry entry, Set<String> relatedIds, List<String> keywords) {
        return semantic() && (relatedIds.contains(entry.id()) || titles().contains(normalize(entry.title())))
                && !fields(entry.content(), keywords).isEmpty();
    }

    boolean validUrl() {
        if (spec.has("url") && !spec.get("url").isTextual()) return false;
        if (url().isEmpty()) return true;
        try {
            URI uri = URI.create(url());
            return url().length() <= 2000 && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException e) { return false; }
    }

    /** Empty is a scheme without a keyword selection; malformed data must not become "absent". */
    List<String> orderedKeywords() {
        JsonNode names = spec.path("keywordNames");
        if (names.isMissingNode()) return List.of();
        if (!names.isArray() || names.size() > 20) return null;
        List<String> keywords = new ArrayList<>();
        for (JsonNode name : names) {
            if (!name.isTextual() || name.asText().isBlank() || name.asText().length() > 64
                    || name.asText().chars().anyMatch(Character::isISOControl) || keywords.contains(name.asText())) return null;
            keywords.add(name.asText());
        }
        return List.copyOf(keywords);
    }

    /** Never guess library parameters from a title. */
    Map<String, Object> addBody(List<String> keywords) {
        if (!spec.path("templateIdShort").isTextual() || !shortId().matches("[A-Za-z0-9_-]{1,128}") || keywords.isEmpty()) return null;
        return Map.of("template_id_short", shortId(), "keyword_name_list", keywords);
    }

    boolean hasFieldPlan() {
        JsonNode fields = spec.path("fields"), suggestions = spec.path("fieldSuggestions");
        if ((!fields.isMissingNode() && !fields.isObject()) || fields.size() > 20 ||
                (!suggestions.isMissingNode() && !suggestions.isArray()) || suggestions.size() > 20 ||
                (fields.isEmpty() && suggestions.isEmpty())) return false;
        if (semantic() && (!fields.isEmpty() || suggestions.isEmpty())) return false;
        var it = fields.fields();
        while (it.hasNext()) {
            var field = it.next();
            if (!VARIABLE.matcher("{{" + field.getKey() + ".DATA}}").matches()
                    || !NotificationTemplateSources.KEYS.contains(field.getValue().asText(""))) return false;
        }
        for (var s : suggestions) {
            if (s.path("label").asText("").isBlank() || !NotificationTemplateSources.KEYS.contains(s.path("source").asText(""))
                    || !VARIABLE.matcher("{{" + s.path("type").asText("") + ".DATA}}").matches()) return false;
        }
        return true;
    }

    /** A manual mapping need not use the scheme's preferred title, sources or URL. */
    static boolean validExistingFields(String content, String storedJson) {
        var matcher = VARIABLE.matcher(content);
        var keys = new HashSet<String>();
        matcher.results().forEach(match -> keys.add(match.group(1)));
        String literals = matcher.replaceAll("");
        if (keys.isEmpty() || keys.size() > 20 || literals.contains("{{") || literals.contains("}}")) return false;
        try {
            var stored = JSON.parseObject(storedJson);
            return stored != null && keys.equals(stored.keySet())
                    && stored.values().stream().allMatch(source -> source instanceof String && NotificationTemplateSources.KEYS.contains(source));
        } catch (RuntimeException invalid) { return false; }
    }

    // Expose only operator-configured labels, never the raw WeChat message body.
    String mismatch(String content) {
        var wanted=new TreeMap<String,String>();
        for(var field:spec.path("fieldSuggestions")) wanted.put(normalize(field.path("label").asText()),field.path("type").asText());
        var seen=new HashSet<String>(); var keys=new HashSet<String>();
        var m=VARIABLE.matcher(content); int offset=0;
        while(m.find()){
            String prefix=content.substring(offset,m.start()); offset=m.end();
            String label=normalize(prefix.substring(prefix.lastIndexOf('\n')+1).strip().replaceFirst("[:：]$",""));
            if(!seen.add(label)||!keys.add(m.group(1))) return "公众号模板的关键词或变量名重复，请人工核对";
            if(wanted.containsKey(label)&&!wanted.get(label).equals(m.group(2))) return "关键词「"+label+"」的微信字段类型与方案不一致";
        }
        for(var key:wanted.keySet()) if(!seen.contains(key)) return "公众号模板缺少关键词「"+key+"」";
        return "公众号模板包含未映射字段或无法识别的变量，请核对字段清单";
    }
    Map<String, String> fields(String content, List<String> orderedKeywords) {
        JsonNode explicit = spec.path("fields"), suggestions = spec.path("fieldSuggestions");
        Map<String, String> result = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        var matcher = VARIABLE.matcher(content);
        // Do not silently ignore unsupported types or a partially malformed placeholder.
        String literals = matcher.replaceAll("");
        if (literals.contains("{{") || literals.contains("}}")) return Map.of();
        matcher.reset();
        int end = 0;
        while (matcher.find()) {
            String key = matcher.group(1), source = explicit.path(key).asText("");
            String prefix = content.substring(end, matcher.start());
            String actualLabel = prefix.substring(prefix.lastIndexOf('\n') + 1).strip().replaceFirst("[:：]$", "").strip();
            labels.add(actualLabel);
            String label = normalize(actualLabel);
            if (!suggestions.isEmpty()) {
                Set<String> matches = new HashSet<>();
                for (JsonNode suggestion : suggestions)
                    if (!label.isEmpty() && label.equals(normalize(suggestion.path("label").asText("")))
                            && matcher.group(2).equals(suggestion.path("type").asText()))
                        matches.add(suggestion.path("source").asText(""));
                if (matches.size() != 1) return Map.of();
                String matched = matches.iterator().next();
                if (!source.isEmpty() && !source.equals(matched)) return Map.of();
                source = matched;
            }
            if (!NotificationTemplateSources.KEYS.contains(source)) return Map.of();
            String previous = result.put(key, source);
            if ((previous != null && !previous.equals(source)) || result.size() > 20) return Map.of();
            end = matcher.end();
        }
        if (suggestions.isEmpty() && result.size() != explicit.size()) return Map.of();
        if (semantic()) {
            var expected = new HashSet<String>();
            for (var item : suggestions) if (!expected.add(normalize(item.path("label").asText()))) return Map.of();
            var actual = labels.stream().map(WechatProvisioningScheme::normalize).toList();
            if (expected.isEmpty() || actual.size()!=expected.size() || !expected.equals(new HashSet<>(actual)) || result.size()!=actual.size()) return Map.of();
        } else if (!orderedKeywords.isEmpty() && !orderedKeywords.equals(labels)) return Map.of();
        return result;
    }
}
