package in.petrolflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Auth {
    public String userId;
    public String email;
    public String name;
    public String roleKey;
    public String roleName;
    public String tenantId;
    public String status;
    public String sessionId;
    public String sessionToken;
    public boolean mustChangePassword;
    public boolean demo;
    public boolean superAdmin;
    public boolean allStations;
    public String roleScope;
    public Set<String> permissions = new LinkedHashSet<>();
    public Set<String> stationIds = new LinkedHashSet<>();

    private Auth() {}

    public boolean can(String permission) {
        return superAdmin || permissions.contains(permission);
    }

    public void require(String permission) {
        if (!can(permission)) throw ApiException.forbidden("Missing permission: " + permission);
    }

    public String tenantId() {
        if (tenantId == null) throw ApiException.forbidden("Platform administrators cannot access organization data through station endpoints");
        return tenantId;
    }

    public Map<String, Object> station(String stationId) {
        if (stationId == null || stationId.isBlank()) throw ApiException.unprocessable("stationId is required");
        Map<String, Object> row = Db.one("SELECT * FROM stations WHERE id = ?", stationId);
        if (row == null || (tenantId != null && !tenantId.equals(Db.str(row, "tenant_id")))) {
            throw ApiException.notFound("Station not found");
        }
        if (!allStations && !stationIds.contains(stationId)) {
            throw ApiException.forbidden("You do not have access to this station");
        }
        return row;
    }

    public static Auth fromToken(String token) {
        if (token == null || token.isBlank()) return null;
        Map<String, Object> row = Db.one(
                """
                SELECT s.id, s.user_id, s.expires_at, s.revoked_at, u.status AS user_status,
                       u.tenant_id, t.status AS tenant_status
                  FROM sessions s
                  JOIN users u ON u.id = s.user_id
                  LEFT JOIN tenants t ON t.id = u.tenant_id
                 WHERE s.token_hash = ?
                """,
                Passwords.sha256(token));
        if (row == null || row.get("revoked_at") != null) return null;
        String expires = Db.str(row, "expires_at");
        if (expires == null || expires.compareTo(Clock.nowIso()) <= 0) return null;
        if (!"ACTIVE".equals(Db.str(row, "user_status"))) return null;
        if (row.get("tenant_id") != null && !"ACTIVE".equals(Db.str(row, "tenant_status"))) return null;
        Auth auth = load(Db.str(row, "user_id"), Db.str(row, "id"));
        if (auth != null) auth.sessionToken = token;
        return auth;
    }

    public static Auth load(String userId, String sessionId) {
        Map<String, Object> row = Db.one(
                """
                SELECT u.id, u.email, u.name, u.role_key, u.tenant_id, u.status, u.must_change_password,
                       u.last_login_at, u.is_demo, u.granted_permissions, r.name AS role_name, r.scope
                  FROM users u LEFT JOIN roles r ON r.`key` = u.role_key
                 WHERE u.id = ?
                """,
                userId);
        if (row == null || !"ACTIVE".equals(Db.str(row, "status"))) return null;
        Auth auth = new Auth();
        auth.userId = userId;
        auth.sessionId = sessionId;
        auth.email = Db.str(row, "email");
        auth.name = Db.str(row, "name");
        auth.roleKey = Db.str(row, "role_key");
        auth.roleName = Db.str(row, "role_name") == null ? auth.roleKey : Db.str(row, "role_name");
        auth.tenantId = Db.str(row, "tenant_id");
        auth.status = Db.str(row, "status");
        auth.mustChangePassword = Db.num(row, "must_change_password") == 1;
        auth.demo = Db.num(row, "is_demo") == 1;
        auth.roleScope = Db.str(row, "scope") == null ? "STATION" : Db.str(row, "scope");
        auth.superAdmin = "SUPER_ADMIN".equals(auth.roleKey);
        auth.allStations = "TENANT".equals(auth.roleScope) || auth.superAdmin;
        auth.permissions.addAll(Permissions.forRole(auth.roleKey));
        if ("STAFF".equals(auth.roleKey)) auth.permissions.addAll(Permissions.sanitize(Db.str(row, "granted_permissions")));
        if (auth.tenantId != null) {
            for (Map<String, Object> access : Db.all("SELECT station_id, permissions FROM user_station_access WHERE user_id = ?", userId)) {
                auth.stationIds.add(Db.str(access, "station_id"));
            }
        }
        return auth;
    }

    public Map<String, Object> publicUser() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", userId);
        user.put("email", email);
        user.put("name", name);
        user.put("roleKey", roleKey);
        user.put("roleName", roleName);
        user.put("tenantId", tenantId);
        user.put("status", status);
        user.put("mustChangePassword", mustChangePassword);
        user.put("isDemo", demo);
        return user;
    }

    public static Map<String, Object> session(Auth auth, Config config, String ip, String userAgent) {
        String token = Passwords.randomToken();
        String expires = Clock.addHoursIso(Clock.nowIso(), config.sessionHours);
        String id = Clock.newId("ses");
        Db.exec(
                """
                INSERT INTO sessions (id, user_id, token_hash, expires_at, ip, user_agent, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, auth.userId, Passwords.sha256(token), expires, ip,
                userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())),
                Clock.nowIso(), Clock.nowIso());
        auth.sessionId = id;
        auth.sessionToken = token;
        return Map.of("token", token, "expiresAt", expires);
    }

    public static void audit(Auth auth, String action, String entity, String entityId, String stationId, String summary) {
        try {
            auditRequired(auth, action, entity, entityId, stationId, summary, null, null);
        } catch (RuntimeException ignored) {
            /* Legacy non-financial audit remains best effort. Financial callers use auditRequired. */
        }
    }

    /** Financial audit write participates in the caller's transaction; failure rolls the operation back. */
    public static void auditRequired(Auth auth, String action, String entity, String entityId, String stationId,
                                     String summary, Map<String, Object> before, Map<String, Object> after) {
        Db.exec(
                    """
                    INSERT INTO audit_logs
                      (id, tenant_id, station_id, actor_id, actor_email, actor_role, action, entity, entity_id,
                       summary, before_data, after_data, ip, user_agent, is_demo, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)
                    """,
                    Clock.newId("aud"),
                    auth == null ? null : auth.tenantId,
                    stationId,
                    auth == null ? null : auth.userId,
                    auth == null ? null : auth.email,
                    auth == null ? null : auth.roleKey,
                    action, entity, entityId, summary,
                    before == null ? null : Json.stringify(Json.shape(before)),
                    after == null ? null : Json.stringify(Json.shape(after)),
                    auth != null && auth.demo ? 1 : 0,
                    Clock.nowIso());
    }

    public static List<String> grants(Object raw) {
        if (raw == null) return List.of();
        Object parsed = raw instanceof String text ? parseQuiet(text) : raw;
        if (!(parsed instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) out.add(String.valueOf(item));
        return out;
    }

    private static Object parseQuiet(String text) {
        try {
            return Json.parse(text);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
