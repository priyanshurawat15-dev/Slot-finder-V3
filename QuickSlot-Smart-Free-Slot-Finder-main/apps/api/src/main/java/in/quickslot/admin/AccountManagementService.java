package in.quickslot.admin;

import in.quickslot.identity.AuthService;
import in.quickslot.shared.ApiException;
import in.quickslot.shared.AuditService;
import in.quickslot.shared.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountManagementService {
  private final JdbcTemplate jdbc;
  private final AuthService auth;
  private final AuditService audit;

  public AccountManagementService(JdbcTemplate jdbc, AuthService auth, AuditService audit) {
    this.jdbc = jdbc;
    this.auth = auth;
    this.audit = audit;
  }

  public record CreateAccountInput(
      String full_name,
      String email,
      String employee_id,
      String department,
      String role,
      List<String> roles,
      List<String> room_ids,
      Boolean send_invitation,
      String temporary_password) {}

  public record UpdateAccountInput(
      String full_name,
      String employee_id,
      String department,
      String role,
      List<String> roles) {}

  public record StatusInput(String status) {}

  public record AllocationUpdateInput(List<String> room_ids) {}

  public record HodAssignInput(String department, String user_id, String campus_id) {}

  public List<Map<String, Object>> listAccounts(
      AuthenticatedUser admin, String query, String role, String department, String status) {
    auth.admin(admin);

    var sql = new StringBuilder("SELECT u.* FROM users u WHERE 1=1 ");
    var params = new ArrayList<Object>();

    if (query != null && !query.isBlank()) {
      String q = "%" + query.strip().toLowerCase(Locale.ROOT) + "%";
      sql.append(
          "AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? OR LOWER(COALESCE(u.employee_id, '')) LIKE ?) ");
      params.add(q);
      params.add(q);
      params.add(q);
    }

    if (department != null && !department.isBlank()) {
      sql.append("AND LOWER(u.department) = LOWER(?) ");
      params.add(department.strip());
    }

    if (status != null && !status.isBlank()) {
      sql.append("AND LOWER(u.status) = LOWER(?) ");
      params.add(status.strip());
    }

    if (role != null && !role.isBlank()) {
      sql.append(
          "AND (u.role = ? OR EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.role = ?)) ");
      params.add(role.strip());
      params.add(role.strip());
    }

    sql.append("ORDER BY u.created_at DESC, u.full_name ASC");

    List<Map<String, Object>> users = jdbc.query(
        sql.toString(),
        (rs, idx) -> {
          var map = new LinkedHashMap<String, Object>();
          String userId = rs.getString("id");
          map.put("id", userId);
          map.put("full_name", rs.getString("full_name"));
          map.put("email", rs.getString("email"));
          map.put("employee_id", rs.getString("employee_id") != null ? rs.getString("employee_id") : "");
          map.put("role", rs.getString("role"));
          map.put("campus_id", rs.getString("campus_id"));
          map.put("department", rs.getString("department"));
          map.put("active", rs.getBoolean("active"));
          map.put("status", rs.getString("status") != null ? rs.getString("status") : "ACTIVE");
          map.put("invited_at", rs.getObject("invited_at", OffsetDateTime.class));
          map.put("activated_at", rs.getObject("activated_at", OffsetDateTime.class));
          map.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
          return map;
        },
        params.toArray());

    for (var u : users) {
      String uid = (String) u.get("id");
      u.put("roles", getRolesForUser(uid, (String) u.get("role")));
      u.put("allocated_rooms", getAllocatedRooms(uid));
      u.put("is_hod", isUserActiveHod(uid));
    }

    return users;
  }

  public Map<String, Object> getAccount(AuthenticatedUser admin, String userId) {
    auth.admin(admin);
    var list = jdbc.query(
        "SELECT * FROM users WHERE id=?",
        (rs, idx) -> {
          var map = new LinkedHashMap<String, Object>();
          map.put("id", rs.getString("id"));
          map.put("full_name", rs.getString("full_name"));
          map.put("email", rs.getString("email"));
          map.put("employee_id", rs.getString("employee_id") != null ? rs.getString("employee_id") : "");
          map.put("role", rs.getString("role"));
          map.put("campus_id", rs.getString("campus_id"));
          map.put("department", rs.getString("department"));
          map.put("active", rs.getBoolean("active"));
          map.put("status", rs.getString("status") != null ? rs.getString("status") : "ACTIVE");
          map.put("invited_at", rs.getObject("invited_at", OffsetDateTime.class));
          map.put("activated_at", rs.getObject("activated_at", OffsetDateTime.class));
          map.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
          return map;
        },
        userId);

    if (list.isEmpty()) {
      throw new ApiException(404, "Account not found.");
    }

    var account = list.get(0);
    account.put("roles", getRolesForUser(userId, (String) account.get("role")));
    account.put("allocated_rooms", getAllocatedRooms(userId));
    account.put("hod_departments", getHodDepartments(userId));

    List<Map<String, Object>> recentAudits = jdbc.query(
        "SELECT a.id, a.action, a.entity_id, a.details, a.created_at, u.full_name "
            + "FROM audit_events a LEFT JOIN users u ON u.id = a.actor_id "
            + "WHERE a.actor_id = ? OR a.entity_id = ? "
            + "ORDER BY a.created_at DESC LIMIT 20",
        (rs, idx) -> {
          var item = new LinkedHashMap<String, Object>();
          item.put("id", rs.getString("id"));
          item.put("action", rs.getString("action"));
          item.put("entity_id", rs.getString("entity_id"));
          item.put("actor_name", rs.getString("full_name"));
          item.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
          return item;
        },
        userId,
        userId);
    account.put("recent_audits", recentAudits);

    return account;
  }

  @Transactional
  public Map<String, Object> createAccount(AuthenticatedUser admin, CreateAccountInput input) {
    auth.admin(admin);

    if (input.full_name() == null || input.full_name().isBlank()) {
      throw new ApiException(422, "Full name is required.");
    }
    if (input.email() == null || input.email().isBlank() || !input.email().contains("@")) {
      throw new ApiException(422, "Valid email address is required.");
    }

    String normalizedEmail = input.email().strip().toLowerCase(Locale.ROOT);
    Integer emailCount =
        jdbc.queryForObject("SELECT count(*) FROM users WHERE LOWER(email)=?", Integer.class, normalizedEmail);
    if (emailCount != null && emailCount > 0) {
      throw new ApiException(409, "An account with email " + normalizedEmail + " already exists.");
    }

    String empId = (input.employee_id() != null && !input.employee_id().isBlank())
        ? input.employee_id().strip()
        : null;
    if (empId != null) {
      Integer empCount =
          jdbc.queryForObject("SELECT count(*) FROM users WHERE employee_id=?", Integer.class, empId);
      if (empCount != null && empCount > 0) {
        throw new ApiException(409, "An account with Employee ID " + empId + " already exists.");
      }
    }

    String primaryRole = (input.role() != null && !input.role().isBlank())
        ? input.role().strip().toUpperCase(Locale.ROOT)
        : "FACULTY";

    Set<String> validRoles = Set.of("STUDENT", "FACULTY", "CLUB_HEAD", "COORDINATOR", "HOD", "ADMIN");
    if (!validRoles.contains(primaryRole)) {
      throw new ApiException(422, "Invalid role: " + primaryRole);
    }

    Set<String> assignedRoles = new LinkedHashSet<>();
    assignedRoles.add(primaryRole);
    if (input.roles() != null) {
      for (String r : input.roles()) {
        if (r != null && !r.isBlank()) {
          String normalizedR = r.strip().toUpperCase(Locale.ROOT);
          if (validRoles.contains(normalizedR)) {
            assignedRoles.add(normalizedR);
          }
        }
      }
    }

    String department = (input.department() != null && !input.department().isBlank())
        ? input.department().strip().toUpperCase(Locale.ROOT)
        : "CS";

    String userId = UUID.randomUUID().toString();
    boolean hasTempPassword = input.temporary_password() != null && !input.temporary_password().isBlank();
    String status = hasTempPassword ? "ACTIVE" : "INVITED";
    boolean active = hasTempPassword;
    String passwordHash = hasTempPassword
        ? auth.passwordHash(input.temporary_password().strip())
        : auth.passwordHash(UUID.randomUUID().toString());

    jdbc.update(
        "INSERT INTO users (id, full_name, email, password_hash, role, campus_id, department, active, status, employee_id, activated_at)"
            + " VALUES (?, ?, ?, ?, ?, 'gehu-hill-dehradun', ?, ?, ?, ?, ?)",
        userId,
        input.full_name().strip(),
        normalizedEmail,
        passwordHash,
        primaryRole,
        department,
        active,
        status,
        empId,
        hasTempPassword ? OffsetDateTime.now() : null);

    for (String r : assignedRoles) {
      String scope = r.equals("ADMIN") ? "INSTITUTION" : "DEPARTMENT";
      jdbc.update(
          "INSERT INTO user_roles (id, user_id, role, department, scope_type) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
          UUID.randomUUID().toString(),
          userId,
          r,
          department,
          scope);
    }

    if (input.room_ids() != null && !input.room_ids().isEmpty()) {
      for (String roomId : input.room_ids()) {
        if (roomId != null && !roomId.isBlank()) {
          jdbc.update(
              "INSERT INTO allocations (user_id, room_id, assigned_by, assigned_at) VALUES (?, ?, ?, now()) ON CONFLICT DO NOTHING",
              userId,
              roomId.strip(),
              admin.id());
        }
      }
    }

    if (assignedRoles.contains("HOD")) {
      jdbc.update(
          "UPDATE department_hods SET active=false, revoked_at=now() WHERE department=? AND campus_id='gehu-hill-dehradun' AND active=true",
          department);
      jdbc.update(
          "INSERT INTO department_hods (id, department, campus_id, user_id, active, assigned_at, assigned_by) VALUES (?, ?, 'gehu-hill-dehradun', ?, true, now(), ?)",
          UUID.randomUUID().toString(),
          department,
          userId,
          admin.id());
    }

    String invitationToken = null;
    if ("INVITED".equals(status)) {
      invitationToken = auth.createInvitation(userId);
    }

    audit.record(
        admin,
        "ACCOUNT_CREATED",
        userId,
        Map.of(
            "email", normalizedEmail,
            "role", primaryRole,
            "roles", assignedRoles,
            "department", department,
            "status", status));

    var result = new LinkedHashMap<String, Object>();
    result.put("success", true);
    result.put("id", userId);
    result.put("full_name", input.full_name().strip());
    result.put("email", normalizedEmail);
    result.put("employee_id", empId != null ? empId : "");
    result.put("role", primaryRole);
    result.put("roles", new ArrayList<>(assignedRoles));
    result.put("department", department);
    result.put("status", status);
    if (invitationToken != null) {
      result.put("invitation_token", invitationToken);
      result.put("activation_url", "/activate?token=" + invitationToken);
    }
    return result;
  }

  @Transactional
  public Map<String, Object> updateAccount(
      AuthenticatedUser admin, String userId, UpdateAccountInput input) {
    auth.admin(admin);

    var existing = jdbc.queryForList("SELECT * FROM users WHERE id=?", userId);
    if (existing.isEmpty()) {
      throw new ApiException(404, "Account not found.");
    }
    var oldUser = existing.get(0);

    String newEmpId = (input.employee_id() != null && !input.employee_id().isBlank())
        ? input.employee_id().strip()
        : null;
    if (newEmpId != null) {
      Integer empCount = jdbc.queryForObject(
          "SELECT count(*) FROM users WHERE employee_id=? AND id<>?",
          Integer.class,
          newEmpId,
          userId);
      if (empCount != null && empCount > 0) {
        throw new ApiException(409, "An account with Employee ID " + newEmpId + " already exists.");
      }
    }

    String fullName = (input.full_name() != null && !input.full_name().isBlank())
        ? input.full_name().strip()
        : oldUser.get("full_name").toString();

    String department = (input.department() != null && !input.department().isBlank())
        ? input.department().strip().toUpperCase(Locale.ROOT)
        : oldUser.get("department").toString();

    String primaryRole = (input.role() != null && !input.role().isBlank())
        ? input.role().strip().toUpperCase(Locale.ROOT)
        : oldUser.get("role").toString();

    Set<String> validRoles = Set.of("STUDENT", "FACULTY", "CLUB_HEAD", "COORDINATOR", "HOD", "ADMIN");
    if (!validRoles.contains(primaryRole)) {
      throw new ApiException(422, "Invalid role: " + primaryRole);
    }

    jdbc.update(
        "UPDATE users SET full_name=?, employee_id=?, department=?, role=?, updated_at=now() WHERE id=?",
        fullName,
        newEmpId,
        department,
        primaryRole,
        userId);

    if (input.roles() != null && !input.roles().isEmpty()) {
      Set<String> newRoles = new LinkedHashSet<>();
      newRoles.add(primaryRole);
      for (String r : input.roles()) {
        if (r != null && !r.isBlank()) {
          String normalized = r.strip().toUpperCase(Locale.ROOT);
          if (validRoles.contains(normalized)) {
            newRoles.add(normalized);
          }
        }
      }

      jdbc.update("DELETE FROM user_roles WHERE user_id=?", userId);
      for (String r : newRoles) {
        String scope = r.equals("ADMIN") ? "INSTITUTION" : "DEPARTMENT";
        jdbc.update(
            "INSERT INTO user_roles (id, user_id, role, department, scope_type) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            UUID.randomUUID().toString(),
            userId,
            r,
            department,
            scope);
      }
      auth.invalidateUserSessions(userId);
    }

    audit.record(
        admin,
        "ACCOUNT_UPDATED",
        userId,
        Map.of(
            "full_name", fullName,
            "employee_id", newEmpId != null ? newEmpId : "",
            "department", department,
            "role", primaryRole));

    return getAccount(admin, userId);
  }

  @Transactional
  public Map<String, Object> setStatus(AuthenticatedUser admin, String userId, String newStatus) {
    auth.admin(admin);

    if (newStatus == null || (!newStatus.equalsIgnoreCase("ACTIVE") && !newStatus.equalsIgnoreCase("DEACTIVATED"))) {
      throw new ApiException(422, "Status must be either ACTIVE or DEACTIVATED.");
    }
    String status = newStatus.strip().toUpperCase(Locale.ROOT);

    if (admin.id().equals(userId) && "DEACTIVATED".equals(status)) {
      throw new ApiException(400, "You cannot deactivate your own administrative account.");
    }

    var existing = jdbc.queryForList("SELECT * FROM users WHERE id=?", userId);
    if (existing.isEmpty()) {
      throw new ApiException(404, "Account not found.");
    }

    boolean active = "ACTIVE".equals(status);
    jdbc.update(
        "UPDATE users SET status=?, active=?, updated_at=now() WHERE id=?",
        status,
        active,
        userId);

    if (!active) {
      auth.invalidateUserSessions(userId);
      audit.record(admin, "ACCOUNT_DEACTIVATED", userId, Map.of("reason", "Administrative deactivation"));
    } else {
      audit.record(admin, "ACCOUNT_ACTIVATED", userId, Map.of("reason", "Administrative activation"));
    }

    return Map.of(
        "success", true,
        "id", userId,
        "status", status,
        "active", active,
        "message", active ? "Account successfully activated." : "Account deactivated and all sessions terminated.");
  }

  @Transactional
  public Map<String, Object> updateAllocations(
      AuthenticatedUser admin, String userId, List<String> roomIds) {
    auth.admin(admin);

    var existing = jdbc.queryForList("SELECT * FROM users WHERE id=?", userId);
    if (existing.isEmpty()) {
      throw new ApiException(404, "Account not found.");
    }

    jdbc.update("DELETE FROM allocations WHERE user_id=?", userId);

    if (roomIds != null) {
      for (String roomId : roomIds) {
        if (roomId != null && !roomId.isBlank()) {
          jdbc.update(
              "INSERT INTO allocations (user_id, room_id, assigned_by, assigned_at) VALUES (?, ?, ?, now()) ON CONFLICT DO NOTHING",
              userId,
              roomId.strip(),
              admin.id());
        }
      }
    }

    audit.record(
        admin,
        "ALLOCATIONS_UPDATED",
        userId,
        Map.of("rooms", roomIds != null ? roomIds : List.of()));

    return Map.of(
        "success", true,
        "user_id", userId,
        "allocated_rooms", getAllocatedRooms(userId));
  }

  @Transactional
  public Map<String, Object> assignHod(
      AuthenticatedUser admin, String department, String userId, String campusId) {
    auth.admin(admin);

    if (department == null || department.isBlank()) {
      throw new ApiException(422, "Department is required.");
    }
    if (userId == null || userId.isBlank()) {
      throw new ApiException(422, "User ID is required.");
    }

    String dept = department.strip().toUpperCase(Locale.ROOT);
    String campus = (campusId != null && !campusId.isBlank()) ? campusId.strip() : "gehu-hill-dehradun";

    var userList = jdbc.queryForList("SELECT * FROM users WHERE id=?", userId);
    if (userList.isEmpty()) {
      throw new ApiException(404, "User not found.");
    }

    jdbc.update(
        "UPDATE department_hods SET active=false, revoked_at=now() WHERE department=? AND campus_id=? AND active=true",
        dept,
        campus);

    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO department_hods (id, department, campus_id, user_id, active, assigned_at, assigned_by) VALUES (?, ?, ?, ?, true, now(), ?)",
        id,
        dept,
        campus,
        userId,
        admin.id());

    jdbc.update(
        "INSERT INTO user_roles (id, user_id, role, department, scope_type) VALUES (?, ?, 'HOD', ?, 'DEPARTMENT') ON CONFLICT DO NOTHING",
        UUID.randomUUID().toString(),
        userId,
        dept);

    audit.record(
        admin,
        "HOD_ASSIGNED",
        userId,
        Map.of("department", dept, "campus_id", campus));

    return Map.of(
        "success", true,
        "department", dept,
        "campus_id", campus,
        "user_id", userId,
        "assigned_at", OffsetDateTime.now());
  }

  public List<String> listDepartments() {
    return jdbc.queryForList(
        "SELECT DISTINCT department FROM ("
            + "SELECT department FROM rooms WHERE department IS NOT NULL AND trim(department) <> '' "
            + "UNION SELECT department FROM users WHERE department IS NOT NULL AND trim(department) <> ''"
            + ") depts ORDER BY department",
        String.class);
  }

  public List<Map<String, Object>> listDepartmentHods() {
    return jdbc.query(
        "SELECT dh.id, dh.department, dh.campus_id, dh.user_id, dh.assigned_at, u.full_name, u.email, u.employee_id "
            + "FROM department_hods dh "
            + "JOIN users u ON u.id = dh.user_id "
            + "WHERE dh.active "
            + "ORDER BY dh.department",
        (rs, idx) -> {
          var map = new LinkedHashMap<String, Object>();
          map.put("id", rs.getString("id"));
          map.put("department", rs.getString("department"));
          map.put("campus_id", rs.getString("campus_id"));
          map.put("user_id", rs.getString("user_id"));
          map.put("user_name", rs.getString("full_name"));
          map.put("user_email", rs.getString("email"));
          map.put("employee_id", rs.getString("employee_id") != null ? rs.getString("employee_id") : "");
          map.put("assigned_at", rs.getObject("assigned_at", OffsetDateTime.class));
          return map;
        });
  }

  private List<String> getRolesForUser(String userId, String primaryRole) {
    List<String> list =
        jdbc.queryForList("SELECT role FROM user_roles WHERE user_id=? ORDER BY role", String.class, userId);
    Set<String> set = new LinkedHashSet<>(list);
    if (primaryRole != null && !primaryRole.isBlank()) {
      set.add(primaryRole);
    }
    return new ArrayList<>(set);
  }

  private List<Map<String, Object>> getAllocatedRooms(String userId) {
    return jdbc.query(
        "SELECT r.id, r.code, r.kind, r.building, a.assigned_at "
            + "FROM allocations a "
            + "JOIN rooms r ON r.id = a.room_id "
            + "WHERE a.user_id=? "
            + "ORDER BY r.code",
        (rs, idx) -> {
          var m = new LinkedHashMap<String, Object>();
          m.put("id", rs.getString("id"));
          m.put("code", rs.getString("code"));
          m.put("kind", rs.getString("kind"));
          m.put("building", rs.getString("building"));
          m.put("assigned_at", rs.getObject("assigned_at", OffsetDateTime.class));
          return m;
        },
        userId);
  }

  private List<String> getHodDepartments(String userId) {
    return jdbc.queryForList(
        "SELECT department FROM department_hods WHERE user_id=? AND active=true ORDER BY department",
        String.class,
        userId);
  }

  private boolean isUserActiveHod(String userId) {
    Boolean exists = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM department_hods WHERE user_id=? AND active=true)",
        Boolean.class,
        userId);
    return Boolean.TRUE.equals(exists);
  }
}
