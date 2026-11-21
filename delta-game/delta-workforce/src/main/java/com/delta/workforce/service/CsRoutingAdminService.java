package com.delta.workforce.service;

import com.delta.common.customer.CsRoutingService;
import com.delta.common.exception.BusinessException;
import com.delta.common.security.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Administrative queries and transactional writes for CS routing rules and groups. */
@Service
@RequiredArgsConstructor
public class CsRoutingAdminService {
    private final JdbcTemplate jdbc;
    private final CsRoutingService routing;

    public List<Map<String, Object>> staff() {
        return jdbc.queryForList(
                "SELECT a.id,a.username,a.nickname,a.role,COALESCE(s.online,0) AS online " +
                        "FROM admin a LEFT JOIN staff_shift s ON s.admin_id=a.id " +
                        "WHERE a.status=1 AND a.deleted=0 AND UPPER(a.role) IN ('CS','BOTH') ORDER BY a.id");
    }

    public List<Map<String, Object>> rules() {
        return jdbc.queryForList(
                "SELECT r.id,r.match_type AS matchType,r.target_id AS targetId," +
                        "r.assignee_type AS assigneeType,r.assignee_id AS assigneeId," +
                        "r.enabled,r.remark,r.updated_at AS updatedAt," +
                        "CASE WHEN r.assignee_type='GROUP' THEN g.name ELSE COALESCE(NULLIF(a.nickname,''),a.username) END AS assigneeName," +
                        "CASE r.match_type WHEN 'PRODUCT' THEN p.name WHEN 'CATEGORY' THEN c.name ELSE '默认客服' END AS targetName " +
                        "FROM cs_routing_rule r " +
                        "LEFT JOIN admin a ON r.assignee_type='CS' AND a.id=r.assignee_id " +
                        "LEFT JOIN cs_group g ON r.assignee_type='GROUP' AND g.id=r.assignee_id " +
                        "LEFT JOIN product p ON r.match_type='PRODUCT' AND p.id=r.target_id " +
                        "LEFT JOIN category c ON r.match_type='CATEGORY' AND c.id=r.target_id " +
                        "ORDER BY FIELD(r.match_type,'PRODUCT','CATEGORY','DEFAULT'),r.id DESC");
    }

    @Transactional(rollbackFor = Exception.class)
    public Long saveRule(Long requestedId, String matchType, Long targetId, String assigneeType,
                         Long assigneeId, Integer enabled, String remark) {
        return upsertRule(matchType, targetId, assigneeType, assigneeId, enabled, remark, requestedId, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public int saveRules(String matchType, List<Long> targetIds, String assigneeType,
                         Long assigneeId, Integer enabled, String remark) {
        String normalizedMatchType = normalizeType(matchType, Set.of("CATEGORY"), "批量匹配方式无效");
        if (targetIds == null || targetIds.isEmpty()) throw new BusinessException(400, "请至少选择一个分类");
        if (targetIds.size() > 200) throw new BusinessException(400, "一次最多选择200个分类");
        if (targetIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new BusinessException(400, "商品分类无效");
        List<Long> normalizedTargetIds = targetIds.stream().distinct().sorted().toList();
        String slots = String.join(",", Collections.nCopies(normalizedTargetIds.size(), "?"));
        List<String> conflicts = jdbc.queryForList(
                "SELECT COALESCE(c.name,CAST(r.target_id AS CHAR)) FROM cs_routing_rule r " +
                        "LEFT JOIN category c ON c.id=r.target_id WHERE r.match_type='CATEGORY' AND r.target_id IN (" + slots + ")",
                String.class, normalizedTargetIds.toArray());
        if (!conflicts.isEmpty()) {
            throw new BusinessException(409, "已有分配规则，请单独编辑：" + String.join("、", conflicts.stream().limit(4).toList())
                    + (conflicts.size() > 4 ? " 等 " + conflicts.size() + " 个分类" : ""));
        }
        try {
            for (Long targetId : normalizedTargetIds) {
                upsertRule(normalizedMatchType, targetId, assigneeType, assigneeId, enabled, remark, null, true);
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "部分分类刚被分配，请刷新规则后重试");
        }
        return normalizedTargetIds.size();
    }

    private Long upsertRule(String rawMatchType, Long rawTargetId, String rawAssigneeType, Long assigneeId,
                            Integer rawEnabled, String rawRemark, Long requestedId, boolean createOnly) {
        String matchType = normalizeType(rawMatchType, Set.of("PRODUCT", "CATEGORY", "DEFAULT"), "匹配方式无效");
        String assigneeType = normalizeType(rawAssigneeType, Set.of("CS", "GROUP"), "分配对象无效");
        Long targetId = "DEFAULT".equals(matchType) ? 0L : rawTargetId;
        if (!"DEFAULT".equals(matchType) && (targetId == null || targetId <= 0)) {
            throw new BusinessException(400, "请选择要匹配的商品或分类");
        }
        if ("PRODUCT".equals(matchType)) {
            requireExists("SELECT COUNT(*) FROM product WHERE id=? AND deleted=0", targetId, "商品不存在");
        } else if ("CATEGORY".equals(matchType)) {
            requireExists("SELECT COUNT(*) FROM category WHERE id=? AND deleted=0", targetId, "分类不存在");
        }
        if (assigneeId == null) throw new BusinessException(400, "请选择客服或客服组");
        if ("CS".equals(assigneeType)) {
            if (!routing.isActiveCs(assigneeId)) throw new BusinessException(400, "请选择启用中的客服账号");
        } else {
            requireExists("SELECT COUNT(*) FROM cs_group WHERE id=? AND enabled=1", assigneeId, "客服组不存在或已停用");
        }
        int enabled = rawEnabled == null ? 1 : (rawEnabled == 0 ? 0 : 1);
        String remark = rawRemark == null ? "" : rawRemark.trim();
        if (remark.length() > 200) throw new BusinessException(400, "备注不能超过200字");
        Long id = requestedId;
        if (!createOnly) {
            List<Long> existing = jdbc.queryForList(
                    "SELECT id FROM cs_routing_rule WHERE match_type=? AND target_id=?", Long.class, matchType, targetId);
            if (!existing.isEmpty()) {
                if (id != null && !id.equals(existing.get(0)))
                    throw new BusinessException(409, "该匹配对象已有分配规则，请单独编辑");
                id = existing.get(0);
            }
            if (requestedId != null) {
                requireExists("SELECT COUNT(*) FROM cs_routing_rule WHERE id=?", requestedId, "分配规则已不存在");
            }
        }
        if (id == null) {
            jdbc.update("INSERT INTO cs_routing_rule(match_type,target_id,assignee_type,assignee_id,enabled,remark,created_by) " +
                            "VALUES (?,?,?,?,?,?,?)",
                    matchType, targetId, assigneeType, assigneeId, enabled, remark, SecurityUtils.getUserId());
            id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            jdbc.update("UPDATE cs_routing_rule SET match_type=?,target_id=?,assignee_type=?,assignee_id=?,enabled=?,remark=? WHERE id=?",
                    matchType, targetId, assigneeType, assigneeId, enabled, remark, id);
        }
        return id;
    }

    public void deleteRule(Long id) {
        jdbc.update("DELETE FROM cs_routing_rule WHERE id=?", id);
    }

    public List<Map<String, Object>> groups() {
        return jdbc.queryForList(
                "SELECT g.id,g.name,g.leader_id AS leaderId,g.enabled,g.remark,g.updated_at AS updatedAt," +
                        "COALESCE(NULLIF(a.nickname,''),a.username) AS leaderName," +
                        "(SELECT COUNT(*) FROM cs_group_member m WHERE m.group_id=g.id) AS memberCount," +
                        "(SELECT GROUP_CONCAT(m.admin_id ORDER BY m.admin_id) FROM cs_group_member m WHERE m.group_id=g.id) AS memberIds " +
                        "FROM cs_group g LEFT JOIN admin a ON a.id=g.leader_id ORDER BY g.id DESC");
    }

    public Map<String, Object> ratings() {
        String csJoin = "CASE " +
                "WHEN s.id1 >= 3000000000 AND s.id1 < 4000000000 THEN s.id1 - 3000000000 " +
                "WHEN s.id2 >= 3000000000 AND s.id2 < 4000000000 THEN s.id2 - 3000000000 END";
        List<Map<String, Object>> staff = jdbc.queryForList(
                "SELECT a.id, COALESCE(NULLIF(a.nickname,''),a.username) AS name, " +
                        "COUNT(*) AS ratingCount, SUM(s.rating) AS ratingSum, ROUND(AVG(s.rating),2) AS avgRating, " +
                        "SUM(CASE WHEN s.rating=5 THEN 1 ELSE 0 END) AS fiveStarCount, " +
                        "SUM(CASE WHEN s.rating>=4 THEN 1 ELSE 0 END) AS positiveCount, " +
                        "SUM(CASE WHEN s.rating<=2 THEN 1 ELSE 0 END) AS lowScoreCount " +
                        "FROM chat_session s JOIN admin a ON a.id=" + csJoin + " " +
                        "WHERE s.rating IS NOT NULL AND a.deleted=0 " +
                        "GROUP BY a.id,a.username,a.nickname ORDER BY avgRating DESC,ratingCount DESC,a.id");
        List<Map<String, Object>> recent = jdbc.queryForList(
                "SELECT s.id, a.id AS csId, COALESCE(NULLIF(a.nickname,''),a.username) AS csName, " +
                        "s.rating, s.rating_comment AS comment, s.rated_at AS ratedAt " +
                        "FROM chat_session s JOIN admin a ON a.id=" + csJoin + " " +
                        "WHERE s.rating IS NOT NULL AND a.deleted=0 " +
                        "ORDER BY s.rated_at DESC,s.id DESC LIMIT 50");
        long total = 0, ratingSum = 0, five = 0, positive = 0, low = 0;
        for (Map<String, Object> row : staff) {
            total += number(row.get("ratingCount"));
            five += number(row.get("fiveStarCount"));
            positive += number(row.get("positiveCount"));
            low += number(row.get("lowScoreCount"));
            ratingSum += number(row.get("ratingSum"));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("average", total == 0 ? 0 : Math.round((double) ratingSum / total * 100) / 100.0);
        summary.put("fiveStarRate", total == 0 ? 0 : Math.round((double) five / total * 1000) / 10.0);
        summary.put("positiveRate", total == 0 ? 0 : Math.round((double) positive / total * 1000) / 10.0);
        summary.put("lowScoreCount", low);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summary);
        data.put("staff", staff);
        data.put("recent", recent);
        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long saveGroup(Long id, String name, Long leaderId, List<Long> memberIds, Integer enabled, String remark) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 64) throw new BusinessException(400, "客服组名称须为1至64字");
        List<Long> members = memberIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(memberIds));
        members.removeIf(java.util.Objects::isNull);
        if (members.isEmpty()) throw new BusinessException(400, "客服组至少选择一名客服");
        for (Long memberId : members) {
            if (!routing.isActiveCs(memberId)) throw new BusinessException(400, "客服组包含未启用或非客服账号");
        }
        if (leaderId != null && !members.contains(leaderId)) throw new BusinessException(400, "组长必须是组内客服");
        int normalizedEnabled = enabled == null ? 1 : (enabled == 0 ? 0 : 1);
        String normalizedRemark = remark == null ? "" : remark.trim();
        if (normalizedRemark.length() > 200) throw new BusinessException(400, "备注不能超过200字");
        if (id == null) {
            jdbc.update("INSERT INTO cs_group(name,leader_id,enabled,remark,created_by) VALUES (?,?,?,?,?)",
                    normalizedName, leaderId, normalizedEnabled, normalizedRemark, SecurityUtils.getUserId());
            id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            requireExists("SELECT COUNT(*) FROM cs_group WHERE id=?", id, "客服组不存在");
            jdbc.update("UPDATE cs_group SET name=?,leader_id=?,enabled=?,remark=? WHERE id=?",
                    normalizedName, leaderId, normalizedEnabled, normalizedRemark, id);
        }
        jdbc.update("DELETE FROM cs_group_member WHERE group_id=?", id);
        for (Long memberId : members) {
            jdbc.update("INSERT INTO cs_group_member(group_id,admin_id) VALUES (?,?)", id, memberId);
        }
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(Long id) {
        Long used = jdbc.queryForObject("SELECT COUNT(*) FROM cs_routing_rule WHERE assignee_type='GROUP' AND assignee_id=?", Long.class, id);
        if (used != null && used > 0) throw new BusinessException(409, "该客服组仍被分配规则使用，请先修改规则");
        jdbc.update("DELETE FROM cs_group_member WHERE group_id=?", id);
        jdbc.update("DELETE FROM cs_group WHERE id=?", id);
    }

    private void requireExists(String sql, Long id, String message) {
        Long count = jdbc.queryForObject(sql, Long.class, id);
        if (count == null || count == 0) throw new BusinessException(400, message);
    }

    private long number(Object value) {
        return value instanceof Number n ? Math.round(n.doubleValue()) : 0L;
    }

    private String normalizeType(String value, Set<String> allowed, String message) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!allowed.contains(normalized)) throw new BusinessException(400, message);
        return normalized;
    }
}
