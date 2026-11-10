package com.delta.admin.workbench;

import com.delta.admin.platform.PlatformRemote;
import com.delta.admin.platform.PlatformService;
import com.delta.common.exception.BusinessException;
import com.delta.common.chat.util.ChatParticipantId;
import com.delta.common.security.service.StaffPermissions;
import com.delta.message.service.SystemNotificationService;
import com.delta.pay.service.PaymentService;
import com.delta.player.service.WithdrawDepositPolicy;
import com.delta.system.service.SysConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.delta.common.tenant.TenantRegistry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WorkbenchService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> STAFF = Set.of("ADMIN", "CS");
    private final JdbcTemplate jdbc;
    private final StaffPermissions permissions;
    private final SystemNotificationService notices;
    private final PlatformService platform;
    private final PlatformRemote remote;
    private final SysConfigService config;
    private final PaymentService payments;
    @Autowired(required = false)
    private TenantRegistry tenantRegistry;
    private boolean mysql;

    @PostConstruct void configureCollation() {
        if (tenantRegistry != null && tenantRegistry.isSharedEnabled()) {
            mysql = true;
            return;
        }
        mysql = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            String engine = connection.getMetaData().getDatabaseProductName();
            return "MySQL".equalsIgnoreCase(engine) || "MariaDB".equalsIgnoreCase(engine);
        }));
    }

    // Legacy business tables and new installations can use different utf8mb4 collations.
    // Normalize textual projections before UNION and before joining receipt keys.
    private String text(String expression) {
        return mysql ? "(CONVERT(" + expression + " USING utf8mb4) COLLATE utf8mb4_bin)" : expression;
    }

    public enum Filter { ALL, URGENT, UNREAD }
    record Scope(String type, long id, List<Kind> kinds, List<PlatformTask> platformTasks) {}
    record PlatformTask(Kind kind, String id, String version, String label, LocalDateTime time, boolean urgent) {}
    public record TaskRef(String kind, String id, String version) {}
    public record Task(String kind, String id, String version, String title, String reference, String state,
                       LocalDateTime since, boolean urgent, boolean unread, boolean acknowledged) {}
    public record Category(String kind, String label, long pending, long urgent, long unread) {}
    public record Summary(List<Category> categories, long pending, long urgent, long unread, long handledToday,
                          int systemUnread, long chatUnread) {}
    public record TaskPage(List<Task> tasks, boolean hasMore) {}
    record Query(String sql, List<Object> args) {}

    enum Kind {
        ASSIGN("待指派订单", "order.manage", "`order` t", "t.id", "t.order_no", "t.status",
                "COALESCE((SELECT MAX(p.created_at) FROM order_progress p WHERE p.order_id=t.id AND p.to_status='PAID'),t.updated_at)", "t.status='PAID' AND t.player_id IS NULL", "30",
                "t.assign_time", "t.assign_time IS NOT NULL"),
        COMPLAINT("投诉与仲裁", "order.manage", "complaint t", "t.id", "NULL", "t.status",
                "t.updated_at", "t.status IN ('PENDING','PROCESSING','APPEALING','APPEALED')", "0",
                "t.updated_at", "t.status='RESOLVED'"),
        WITHDRAW("提现审核", "withdraw.review", "withdraw t", "t.id", "NULL", "t.status",
                "t.created_at", "t.status='PENDING'", "1440",
                "t.processed_at", "t.status IN ('COMPLETED','REJECTED')"),
        RELAY("接力申请", "order.manage", "order_relay_request t", "t.id", "NULL", "t.status",
                "t.updated_at", "t.status='PENDING'", "60", "t.reviewed_at", "t.status<>'PENDING'"),
        REPLACE("换人申请", "order.manage", "player_replace_request t", "t.id", "NULL", "t.status",
                "t.created_at", "t.status='PENDING'", "60", "t.processed_at", "t.status<>'PENDING'"),
        MATERIAL("主页素材审核", "media.review", "player_homepage_revision t", "t.id", "NULL", "t.status",
                "t.created_at", "t.status IN ('PENDING_REVIEW','PENDING_SYSTEM')", "1440", "t.reviewed_at", "t.status IN ('APPROVED','REJECTED')"),
        ENROLLMENT("打手入驻审核", "player.manage", "player t", "t.id", "NULL", "t.status",
                "t.created_at", "t.status='PENDING' AND t.deleted=0", "1440", null, null),
        IDENTITY("实名认证审核", "user.manage", "identity_verification t", "t.user_id", "NULL", "t.status",
                "t.submitted_at", "t.status='PENDING'", "1440", "t.reviewed_at", "t.status<>'PENDING'"),
        PRIVACY("隐私与注销申请", "user.manage", "privacy_request t", "t.id", "NULL", "t.status",
                "t.created_at", "t.status='PENDING'", "1440", "t.reviewed_at", "t.status<>'PENDING'"),
        REFUND("退款审批", "order.refund", "refund_request t JOIN `order` o ON o.id=t.order_id", "t.id",
                "o.order_no", "t.status", "t.submitted_at",
                "t.status IN ('REQUESTED','CS_REVIEWING','CS_REVIEWED','FAILED')", "240",
                "COALESCE(t.completed_at,t.admin_decided_at)", "t.status IN ('REFUNDED','ADMIN_REJECTED','REJECTED')"),
        INVITE("待接受组队邀请", null, "order_player t JOIN `order` o ON o.id=t.order_id", "t.id",
                "t.order_id", "t.status", "t.created_at",
                "t.player_id=? AND t.status='INVITED' AND o.status='WAITING_TEAMMATE' AND (t.invite_deadline IS NULL OR t.invite_deadline>CURRENT_TIMESTAMP)",
                "0", null, null),
        ACCEPT("待确认指派", null, "`order` t", "t.id", "NULL", "t.status",
                "COALESCE(t.assign_time,t.updated_at)", "t.player_id=? AND t.status='ASSIGNED'", "15", null, null),
        START("接单后尚未开始", null, "`order` t", "t.id", "NULL", "t.status",
                "COALESCE(t.accept_time,t.updated_at)",
                "t.player_id=? AND t.status='ACCEPTED' AND COALESCE(t.accept_time,t.updated_at)<=TIMESTAMPADD(MINUTE,-30,CURRENT_TIMESTAMP)",
                "0", null, null),
        PAY("待支付订单", null, "`order` t", "t.id", "NULL", "t.status", "t.created_at",
                "t.user_id=? AND t.status='PENDING_PAYMENT' AND (t.pay_deadline IS NULL OR t.pay_deadline>CURRENT_TIMESTAMP)",
                null, null, null),
        CONFIRM("待确认完成", null, "`order` t", "t.id", "NULL", "t.status", "COALESCE(t.complete_time,t.updated_at)",
                "t.user_id=? AND t.status='COMPLETED'", "720", null, null),
        DEPOSIT("提现前补缴押金", null),
        CHAT("超时待回复会话", "chat.manage"),
        UPDATE("可更新正式版", "system.manage"),
        MAINTENANCE("维护期提醒", "system.manage");

        final String label, grant, table, id, reference, state, since, predicate, urgentAfter, doneAt, doneWhen;

        Kind(String label, String grant) {
            this(label, grant, null, null, null, null, null, null, null, null, null);
        }

        Kind(String label, String grant, String table, String id, String reference, String state, String since,
             String predicate, String urgentAfter, String doneAt, String doneWhen) {
            this.label = label;
            this.grant = grant;
            this.table = table;
            this.id = id;
            this.reference = reference;
            this.state = state;
            this.since = since;
            this.predicate = predicate;
            this.urgentAfter = urgentAfter;
            this.doneAt = doneAt;
            this.doneWhen = doneWhen;
        }
    }

    Scope scope(String type, long id, String domain) {
        if ("PLAYER".equals(type)) {
            var deposit = WithdrawDepositPolicy.evaluate(id, jdbc, config, payments);
            var tasks = new ArrayList<PlatformTask>();
            if (deposit.needsPayment() && jdbc.queryForObject("""
                    SELECT COUNT(*) FROM player_wallet w JOIN player p ON p.id=w.player_id
                    WHERE w.player_id=? AND w.balance>0 AND p.status='ACTIVE' AND p.deleted=0
                    """, Long.class, id) > 0) {
                String amount = deposit.outstandingAmount().stripTrailingZeros().toPlainString();
                tasks.add(new PlatformTask(Kind.DEPOSIT, "deposit", amount,
                        "需补缴 ¥" + amount, LocalDate.now(ZONE).atStartOfDay(), false));
            }
            return new Scope(type, id, List.of(Kind.INVITE, Kind.ACCEPT, Kind.START, Kind.DEPOSIT), List.copyOf(tasks));
        }
        if ("USER".equals(type)) return new Scope(type, id, List.of(Kind.PAY, Kind.CONFIRM), List.of());
        if (!STAFF.contains(type)) throw new BusinessException(403, "当前账号无法访问待办");
        boolean admin = "ADMIN".equals(type);
        Set<String> granted = new HashSet<>(permissions.forStaff(id, type));
        var kinds = new ArrayList<Kind>();
        var platformTasks = new ArrayList<PlatformTask>();
        for (Kind kind : Kind.values()) {
            if (kind.grant == null || !can(granted, kind.grant)) continue;
            if (kind == Kind.ASSIGN && !can(granted, "order.read")) continue;
            if (kind == Kind.REFUND && (!admin || !can(granted, "order.read"))) continue;
            if (kind == Kind.IDENTITY && (!admin || !platform.featureEnabled("identity_verification", domain))) continue;
            if (!admin && EnumSet.of(Kind.PRIVACY, Kind.UPDATE, Kind.MAINTENANCE).contains(kind)) continue;
            kinds.add(kind);
        }
        if (kinds.contains(Kind.UPDATE)) {
            var release = remote.pendingReleaseVersion();
            if (release != null) platformTasks.add(new PlatformTask(Kind.UPDATE, "release", release,
                    release, LocalDate.now(ZONE).atStartOfDay(), false));
            var project = remote.project();
            try {
                String end = project == null ? "" : project.path("maintenanceUntil").asText();
                var overview = end.isBlank() ? platform.overview() : null;
                Instant until = end.isBlank() ? (overview == null ? null : overview.expiresAt()) : Instant.parse(end);
                if (until != null) {
                    var date = until.atZone(ZONE).toLocalDateTime();
                    long days = ChronoUnit.DAYS.between(LocalDate.now(ZONE), date.toLocalDate());
                    if (days <= 30) {
                        String phase = until.isBefore(Instant.now()) ? "expired" : days <= 1 ? "1" : days <= 7 ? "7" : "30";
                        platformTasks.add(new PlatformTask(Kind.MAINTENANCE, "maintenance", until + ":" + phase,
                                until.isBefore(Instant.now()) ? "维护期已结束" : "维护期至 " + date.toLocalDate(), date, days <= 7));
                    }
                }
            } catch (java.time.DateTimeException ignored) { /* Ignore an unavailable maintenance date. */ }
        }
        return new Scope(type, id, List.copyOf(kinds), List.copyOf(platformTasks));
    }

    private boolean can(Set<String> granted, String code) {
        return granted.contains(code) || (!"order.refund".equals(code) && granted.contains("*"));
    }

    public Summary summary(Scope scope) {
        var query = joined(scope, "");
        var counts = new EnumMap<Kind, Category>(Kind.class);
        jdbc.query("SELECT q.kind, COUNT(*) pending, SUM(q.urgent) urgent, SUM(CASE WHEN r.read_at IS NULL THEN 1 ELSE 0 END) unread "
                        + query.sql + " GROUP BY q.kind", rs -> {
            var kind = Kind.valueOf(rs.getString("kind"));
            counts.put(kind, new Category(kind.name(), kind.label, rs.getLong("pending"), rs.getLong("urgent"), rs.getLong("unread")));
        }, query.args.toArray());
        var categories = scope.kinds.stream()
                .map(kind -> counts.getOrDefault(kind, new Category(kind.name(), kind.label, 0, 0, 0))).toList();
        return new Summary(categories, categories.stream().mapToLong(Category::pending).sum(),
                categories.stream().mapToLong(Category::urgent).sum(), categories.stream().mapToLong(Category::unread).sum(),
                handledToday(scope), notices.unreadCount(scope.type, scope.id), chatUnread(scope));
    }

    private long chatUnread(Scope scope) {
        boolean staff = STAFF.contains(scope.type);
        if (staff && !scope.kinds.contains(Kind.CHAT)) return 0;
        long participant = ChatParticipantId.encode(scope.type, scope.id);
        String senders = staff ? "'CS','ADMIN'" : "'"+scope.type+"'";
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM chat_session s JOIN chat_message m ON m.session_id=s.id
            WHERE s.status='ACTIVE' AND (s.id1=? OR s.id2=?)
              AND m.is_read=0 AND m.sender_type NOT IN (%s)
            """.formatted(senders), Long.class, participant, participant);
    }

    private long handledToday(Scope scope) {
        var branches = scope.kinds.stream().filter(k -> k.doneAt != null)
                .map(k -> "SELECT COUNT(*) n FROM " + k.table + " WHERE " + k.doneWhen + " AND " + k.doneAt
                        + ">=CURRENT_DATE AND " + k.doneAt + "<TIMESTAMPADD(DAY,1,CURRENT_DATE)").toList();
        return branches.isEmpty() ? 0 : jdbc.queryForObject("SELECT SUM(n) FROM (" + String.join(" UNION ALL ", branches) + ") completed", Long.class);
    }

    public TaskPage tasks(Scope scope, String kind, Filter filter, int page, int size) {
        int limit = Math.max(1, Math.min(size, 50));
        int offset = (Math.max(1, Math.min(page, 10_000)) - 1) * limit;
        var query = joined(scope, kind);
        var args = new ArrayList<>(query.args);
        args.add(limit + 1);
        args.add(offset);
        String condition = switch (filter) {
            case ALL -> "";
            case URGENT -> " WHERE q.urgent=1";
            case UNREAD -> " WHERE r.read_at IS NULL";
        };
        var items = jdbc.query("SELECT q.*, r.read_at, r.acknowledged_at " + query.sql + condition
                        + " ORDER BY q.urgent DESC,q.since ASC,q.kind,q.task_id LIMIT ? OFFSET ?", (rs, i) -> {
            var taskKind = Kind.valueOf(rs.getString("kind"));
            return new Task(taskKind.name(), rs.getString("task_id"), rs.getString("task_version"),
                    taskKind.label, rs.getString("reference"), rs.getString("state"),
                    rs.getObject("since", LocalDateTime.class), rs.getInt("urgent") == 1,
                    rs.getTimestamp("read_at") == null, rs.getTimestamp("acknowledged_at") != null);
        }, args.toArray());
        return new TaskPage(items.subList(0, Math.min(limit, items.size())), items.size() > limit);
    }

    public void read(Scope scope, List<TaskRef> refs, boolean acknowledge) {
        if (refs == null || refs.isEmpty() || refs.size() > 50)
            throw new BusinessException(400, "请选择 1 到 50 项待办");
        for (var ref : refs) {
            if (ref == null || ref.kind == null || ref.id == null || ref.version == null
                    || ref.kind.length() > 32 || ref.id.length() > 64 || ref.version.length() > 96)
                throw new BusinessException(400, "待办参数无效");
        }
        var query = joined(scope, refs.size() == 1 ? refs.get(0).kind : "");
        var args = new ArrayList<>(query.args);
        var matches = new ArrayList<String>();
        for (var ref : refs) {
            matches.add("(q.kind=? AND q.task_id=? AND q.task_version=?)");
            Collections.addAll(args, ref.kind, ref.id, ref.version);
        }
        save(scope, query.sql + " WHERE " + String.join(" OR ", matches), args, acknowledge);
    }

    public void readAll(Scope scope) {
        var query = joined(scope, "");
        save(scope, query.sql + " WHERE r.read_at IS NULL", query.args, false);
    }

    private void save(Scope scope, String source, List<Object> args, boolean acknowledge) {
        var params = new ArrayList<Object>();
        Collections.addAll(params, scope.type, scope.id);
        params.addAll(args);
        jdbc.update("INSERT INTO workbench_receipt(receiver_type,receiver_id,task_kind,task_id,task_version,read_at,acknowledged_at) "
                + "SELECT ?,?,q.kind,q.task_id,q.task_version,CURRENT_TIMESTAMP,"
                + (acknowledge ? "CURRENT_TIMESTAMP " : "NULL ") + source
                + " ON DUPLICATE KEY UPDATE acknowledged_at=COALESCE(workbench_receipt.acknowledged_at,VALUES(acknowledged_at))",
                params.toArray());
    }

    private Query joined(Scope scope, String kind) {
        var branches = new ArrayList<String>();
        var args = new ArrayList<Object>();
        for (var k : scope.kinds) {
            if (k.table == null || !kind.isEmpty() && !kind.equals(k.name())) continue;
            String overdue = k.urgentAfter == null ? "0" : "CASE WHEN " + k.since
                    + "<=TIMESTAMPADD(MINUTE,-" + k.urgentAfter + ",CURRENT_TIMESTAMP) THEN 1 ELSE 0 END";
            // Acknowledging a task before it becomes urgent does not consume the later urgent reminder.
            String version = "CONCAT(" + k.state + ",':',COALESCE(" + k.since
                    + ",CAST('2000-01-01 00:00:00' AS DATETIME)),':'," + overdue + ")";
            branches.add("SELECT " + text("'" + k.name() + "'") + " kind, "
                    + text("CONCAT(''," + k.id + ")") + " task_id, " + text(version) + " task_version, "
                    + text("CONCAT(''," + k.reference + ")") + " reference, " + text(k.state) + " state, "
                    + k.since + " since, " + overdue + " urgent FROM " + k.table + " WHERE " + k.predicate);
            if (k.grant == null) args.add(scope.id);
        }
        if (scope.kinds.contains(Kind.CHAT) && (kind.isEmpty() || "CHAT".equals(kind))) {
            branches.add("SELECT " + text("'CHAT'") + " kind, " + text("CONCAT('',s.id)") + " task_id, "
                    + text("CONCAT('reply:',m.id)") + " task_version, NULL reference, " + text("'WAITING'")
                    + " state, m.created_at since, 1 urgent " + """
                FROM chat_session s JOIN chat_message m ON m.id=(
                    SELECT MAX(x.id) FROM chat_message x WHERE x.session_id=s.id)
                WHERE s.status='ACTIVE' AND (s.id1=? OR s.id2=?)
                  AND m.sender_type NOT IN ('CS','ADMIN')
                  AND m.created_at <= TIMESTAMPADD(MINUTE,-15,CURRENT_TIMESTAMP)
                """);
            long participant = ChatParticipantId.encodeCs(scope.id);
            Collections.addAll(args, participant, participant);
        }
        for (var task : scope.platformTasks) {
            if (!kind.isEmpty() && !kind.equals(task.kind.name())) continue;
            branches.add("SELECT " + text("CAST(? AS CHAR(32))") + " kind, " + text("CAST(? AS CHAR(64))")
                    + " task_id, " + text("CAST(? AS CHAR(96))") + " task_version, "
                    + text("CAST(? AS CHAR(128))") + " reference, " + text("'PENDING'") + " state, CAST(? AS DATETIME) since, "
                    + (task.urgent ? "1" : "0") + " urgent");
            Collections.addAll(args, task.kind.name(), task.id, task.version, task.label, task.time);
        }
        if (branches.isEmpty()) branches.add("SELECT '' kind,'' task_id,'' task_version,'' reference,'' state,CURRENT_TIMESTAMP since,0 urgent WHERE 1=0");
        Collections.addAll(args, scope.type, scope.id);
        return new Query(" FROM (" + String.join(" UNION ALL ", branches) + ") q LEFT JOIN workbench_receipt r ON "
                + "r.receiver_type=? AND r.receiver_id=? AND r.task_kind=q.kind AND r.task_id=q.task_id AND r.task_version=q.task_version", args);
    }
}
