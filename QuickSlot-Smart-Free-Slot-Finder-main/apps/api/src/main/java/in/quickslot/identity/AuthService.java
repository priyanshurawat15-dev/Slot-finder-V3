package in.quickslot.identity;

import in.quickslot.shared.ApiException;
import in.quickslot.shared.AuthenticatedUser;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  public static final String COOKIE = "quickslot_session";
  private final JdbcTemplate jdbc;
  private final boolean secure;
  private final SecureRandom random = new SecureRandom();
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

  public AuthService(JdbcTemplate jdbc, @Value("${quickslot.cookie-secure:true}") boolean secure) {
    this.jdbc = jdbc;
    this.secure = secure;
  }

  public record LoginResult(AuthenticatedUser user, String csrfToken, String cookie) {}

  public static AuthenticatedUser user(ResultSet row, int index) throws SQLException {
    return new AuthenticatedUser(
        row.getString("id"),
        row.getString("full_name"),
        row.getString("email"),
        row.getString("role"),
        row.getString("campus_id"),
        row.getString("department"));
  }

  public Set<String> rolesForUser(String userId, String primaryRole) {
    Set<String> roles = new LinkedHashSet<>();
    if (primaryRole != null && !primaryRole.isBlank()) {
      roles.add(primaryRole.toUpperCase(java.util.Locale.ROOT));
    }
    try {
      List<String> assigned =
          jdbc.queryForList("SELECT role FROM user_roles WHERE user_id=?", String.class, userId);
      for (String r : assigned) {
        if (r != null && !r.isBlank()) roles.add(r.toUpperCase(java.util.Locale.ROOT));
      }
    } catch (Exception ignored) {
      // Fallback before migration or if table is empty
    }
    return roles;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public String token() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String passwordHash(String value) {
    return passwords.encode(value);
  }

  private String cookieValue(HttpServletRequest request) {
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if (COOKIE.equals(cookie.getName())) return cookie.getValue();
      }
    }
    return "";
  }

  public AuthenticatedUser require(HttpServletRequest request) {
    if (request.getAttribute("authenticated_user") instanceof AuthenticatedUser user) return user;
    String token = cookieValue(request);
    var sessions =
        jdbc.queryForList(
            "SELECT s.csrf_token, u.* FROM login_sessions s JOIN users u ON u.id=s.user_id WHERE"
                + " s.token_hash=? AND s.expires_at>now() AND u.active",
            hash(token));
    if (token.isEmpty() || sessions.isEmpty()) throw new ApiException(401, "Please sign in.");
    var row = sessions.get(0);

    // Explicitly reject deactivated status if status column exists
    Object statusObj = row.get("status");
    if (statusObj != null && "DEACTIVATED".equalsIgnoreCase(statusObj.toString())) {
      invalidateUserSessions(row.get("id").toString());
      throw new ApiException(401, "Account is deactivated. Contact administrator.");
    }

    if (!java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
      String csrf = request.getHeader("X-CSRF-Token");
      if (csrf == null
          || !MessageDigest.isEqual(
              csrf.getBytes(StandardCharsets.UTF_8),
              row.get("csrf_token").toString().getBytes(StandardCharsets.UTF_8))) {
        throw new ApiException(403, "Invalid CSRF token.");
      }
    }
    request.setAttribute("csrf_token", row.get("csrf_token"));

    String userId = row.get("id").toString();
    String primaryRole = row.get("role").toString();
    Set<String> roles = rolesForUser(userId, primaryRole);

    var user =
        new AuthenticatedUser(
            userId,
            row.get("full_name").toString(),
            row.get("email").toString(),
            primaryRole,
            roles,
            row.get("campus_id").toString(),
            row.get("department").toString());
    request.setAttribute("authenticated_user", user);
    return user;
  }

  public AuthenticatedUser admin(HttpServletRequest request) {
    var user = require(request);
    if (!user.hasRole("ADMIN"))
      throw new ApiException(403, "Administrator permission required.");
    return user;
  }

  public AuthenticatedUser admin(AuthenticatedUser user) {
    if (user == null || !user.hasRole("ADMIN"))
      throw new ApiException(403, "Administrator permission required.");
    return user;
  }

  public LoginResult login(String email, String password, HttpServletRequest request) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiException(422, "Password exceeds the supported byte length.");
    String normalized = email.strip().toLowerCase(java.util.Locale.ROOT);
    String key = hash(request.getRemoteAddr() + ":" + normalized);
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM login_attempts WHERE key=? AND created_at>now()-interval '15"
                + " minutes'",
            Integer.class,
            key);
    if (count != null && count >= 10)
      throw new ApiException(429, "Too many attempts. Try again in 15 minutes.");
    var rows = jdbc.queryForList("SELECT * FROM users WHERE email=?", normalized);
    if (rows.isEmpty()
        || !Boolean.TRUE.equals(rows.get(0).get("active"))
        || "DEACTIVATED".equalsIgnoreCase(String.valueOf(rows.get(0).get("status")))
        || !passwords.matches(password, rows.get(0).get("password_hash").toString())) {
      jdbc.update(
          "INSERT INTO login_attempts(id,key) VALUES (?,?)", UUID.randomUUID().toString(), key);
      throw new ApiException(401, "Invalid email or password.");
    }

    var row = rows.get(0);
    String userId = row.get("id").toString();
    String primaryRole = row.get("role").toString();
    Set<String> roles = rolesForUser(userId, primaryRole);

    var user =
        new AuthenticatedUser(
            userId,
            row.get("full_name").toString(),
            row.get("email").toString(),
            primaryRole,
            roles,
            row.get("campus_id").toString(),
            row.get("department").toString());

    String token = token();
    String csrf = token();
    jdbc.update("DELETE FROM login_sessions WHERE token_hash=?", hash(cookieValue(request)));
    jdbc.update(
        "INSERT INTO login_sessions(token_hash,user_id,csrf_token,expires_at) VALUES (?,?,?,?)",
        hash(token),
        user.id(),
        csrf,
        OffsetDateTime.now().plusHours(12));
    jdbc.update("DELETE FROM login_attempts WHERE key=?", key);
    return new LoginResult(
        user,
        csrf,
        ResponseCookie.from(COOKIE, token)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/api")
            .maxAge(43200)
            .build()
            .toString());
  }

  public String logout(HttpServletRequest request) {
    require(request);
    jdbc.update("DELETE FROM login_sessions WHERE token_hash=?", hash(cookieValue(request)));
    return ResponseCookie.from(COOKIE, "")
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/api")
        .maxAge(0)
        .build()
        .toString();
  }

  public void invalidateUserSessions(String userId) {
    jdbc.update("DELETE FROM login_sessions WHERE user_id=?", userId);
  }

  @Transactional
  public String createInvitation(String userId) {
    String rawToken = token();
    String tokenHash = hash(rawToken);
    String id = UUID.randomUUID().toString();
    OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(7);
    jdbc.update(
        "INSERT INTO invitations (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
        id,
        userId,
        tokenHash,
        expiresAt);
    jdbc.update(
        "UPDATE users SET status='INVITED', invited_at=now(), updated_at=now() WHERE id=?",
        userId);
    return rawToken;
  }

  @Transactional
  public Map<String, Object> activateAccount(String rawToken, String newPassword) {
    if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 72) {
      throw new ApiException(422, "Password must be between 8 and 72 characters.");
    }
    String tokenHash = hash(rawToken.trim());
    var invitations =
        jdbc.queryForList(
            "SELECT * FROM invitations WHERE token_hash=? AND used_at IS NULL AND expires_at > now()",
            tokenHash);
    if (invitations.isEmpty()) {
      throw new ApiException(400, "Invalid or expired activation invitation token.");
    }
    var inv = invitations.get(0);
    String userId = inv.get("user_id").toString();
    String newHash = passwordHash(newPassword);

    jdbc.update(
        "UPDATE users SET password_hash=?, status='ACTIVE', active=true, activated_at=now(), updated_at=now() WHERE id=?",
        newHash,
        userId);
    jdbc.update("UPDATE invitations SET used_at=now() WHERE id=?", inv.get("id").toString());
    invalidateUserSessions(userId);

    var userRow = jdbc.queryForMap("SELECT * FROM users WHERE id=?", userId);
    return Map.of(
        "success", true,
        "message", "Account successfully activated. You can now sign in.",
        "email", userRow.get("email"));
  }

  @Transactional
  public Map<String, Object> bootstrapAdmin(
      String fullName, String email, String password, String employeeId, String department) {
    Integer existingAdmins =
        jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE role='ADMIN' AND active", Integer.class);
    if (existingAdmins != null && existingAdmins > 0) {
      throw new ApiException(
          403, "Administrator account is already initialized. Bootstrap is permanently locked.");
    }
    if (password == null || password.length() < 8 || password.length() > 72) {
      throw new ApiException(422, "Admin password must be between 8 and 72 characters.");
    }
    String normalizedEmail = email.strip().toLowerCase(java.util.Locale.ROOT);
    String userId = UUID.randomUUID().toString();
    String dept = (department == null || department.isBlank()) ? "CS" : department.strip().toUpperCase(java.util.Locale.ROOT);
    String empId = (employeeId == null || employeeId.isBlank()) ? null : employeeId.strip();

    jdbc.update(
        "INSERT INTO users (id, full_name, email, password_hash, role, campus_id, department, active, status, employee_id, activated_at)"
            + " VALUES (?, ?, ?, ?, 'ADMIN', 'gehu-hill-dehradun', ?, true, 'ACTIVE', ?, now())",
        userId,
        fullName.strip(),
        normalizedEmail,
        passwordHash(password),
        dept,
        empId);

    jdbc.update(
        "INSERT INTO user_roles (id, user_id, role, department, scope_type) VALUES (?, ?, 'ADMIN', ?, 'INSTITUTION') ON CONFLICT DO NOTHING",
        UUID.randomUUID().toString(),
        userId,
        dept);

    return Map.of(
        "success", true,
        "id", userId,
        "full_name", fullName.strip(),
        "email", normalizedEmail,
        "role", "ADMIN",
        "message", "Initial Administrator account created successfully.");
  }

  public List<Map<String, Object>> activeUsers() {
    return jdbc.query(
        "SELECT id, full_name, email, role, campus_id, department, employee_id, status FROM users WHERE active ORDER BY role, full_name",
        (rs, idx) ->
            Map.of(
                "id", rs.getString("id"),
                "full_name", rs.getString("full_name"),
                "email", rs.getString("email"),
                "role", rs.getString("role"),
                "campus_id", rs.getString("campus_id"),
                "department", rs.getString("department"),
                "employee_id", rs.getString("employee_id") != null ? rs.getString("employee_id") : "",
                "status", rs.getString("status") != null ? rs.getString("status") : "ACTIVE"));
  }
}
