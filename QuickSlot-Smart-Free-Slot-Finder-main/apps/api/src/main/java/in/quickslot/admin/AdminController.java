package in.quickslot.admin;

import in.quickslot.identity.AuthService;
import in.quickslot.shared.ApiException;
import in.quickslot.shared.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final JdbcTemplate jdbc;
  private final AuthService auth;

  public AdminController(JdbcTemplate jdbc, AuthService auth) {
    this.jdbc = jdbc;
    this.auth = auth;
  }

  public record AllocationInput(String userId, String roomId) {}

  @GetMapping("/overview-summary")
  public Map<String, Object> overviewSummary(HttpServletRequest request) {
    auth.admin(request);
    Integer totalVenues = jdbc.queryForObject("SELECT count(*) FROM rooms WHERE active", Integer.class);
    Integer pendingRequests = jdbc.queryForObject("SELECT count(*) FROM booking_requests WHERE status='PENDING'", Integer.class);
    Integer confirmedBookings = jdbc.queryForObject("SELECT count(*) FROM occupancies WHERE active AND kind='BOOKING'", Integer.class);
    String activeTimetable = "CSE Timetable (Odd Sem 2026)";

    var result = new LinkedHashMap<String, Object>();
    result.put("total_venues", totalVenues != null ? totalVenues : 0);
    result.put("pending_requests", pendingRequests != null ? pendingRequests : 0);
    result.put("confirmed_bookings", confirmedBookings != null ? confirmedBookings : 0);
    result.put("active_timetable", activeTimetable);
    result.put("academic_session", "2026-2027");
    result.put("institution", "Graphic Era Hill University, Dehradun");
    result.put("department", "Department of Computer Science & Engineering");
    return result;
  }

  @GetMapping("/stats")
  public Map<String, Object> adminStats(HttpServletRequest request) {
    AuthenticatedUser user = auth.require(request);
    if (!Set.of("ADMIN", "COORDINATOR", "HOD").contains(user.role())) {
      throw new ApiException(403, "Administrator permission required.");
    }

    Integer totalVenues = jdbc.queryForObject("SELECT count(*) FROM rooms", Integer.class);
    Integer totalUsers = jdbc.queryForObject("SELECT count(*) FROM users WHERE active", Integer.class);
    Integer pendingRequests = jdbc.queryForObject("SELECT count(*) FROM booking_requests WHERE status='PENDING'", Integer.class);
    Integer confirmedBookings = jdbc.queryForObject("SELECT count(*) FROM occupancies WHERE active AND kind='BOOKING'", Integer.class);

    List<Map<String, Object>> recentAudits = jdbc.query(
        "SELECT a.id, a.action, a.entity_id, a.details, a.created_at, u.full_name "
            + "FROM audit_events a LEFT JOIN users u ON u.id = a.actor_id "
            + "ORDER BY a.created_at DESC LIMIT 15",
        (rs, idx) -> {
          var item = new LinkedHashMap<String, Object>();
          item.put("id", rs.getString("id"));
          item.put("action", rs.getString("action"));
          item.put("entity", rs.getString("entity_id"));
          item.put("actor", rs.getString("full_name"));
          item.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
          return item;
        });

    var result = new LinkedHashMap<String, Object>();
    result.put("total_venues", totalVenues != null ? totalVenues : 0);
    result.put("total_users", totalUsers != null ? totalUsers : 0);
    result.put("pending_requests", pendingRequests != null ? pendingRequests : 0);
    result.put("confirmed_bookings", confirmedBookings != null ? confirmedBookings : 0);
    result.put("recent_audits", recentAudits);
    return result;
  }

  @GetMapping("/audit-logs")
  public List<Map<String, Object>> auditLogs(HttpServletRequest request) {
    AuthenticatedUser user = auth.require(request);
    if (!Set.of("ADMIN", "COORDINATOR").contains(user.role())) {
      throw new ApiException(403, "Administrator permission required.");
    }

    return jdbc.query(
        "SELECT a.id, a.action, a.entity_id, a.created_at, u.full_name, u.role "
            + "FROM audit_events a LEFT JOIN users u ON u.id = a.actor_id "
            + "ORDER BY a.created_at DESC LIMIT 50",
        (rs, idx) -> {
          var item = new LinkedHashMap<String, Object>();
          item.put("id", rs.getString("id"));
          item.put("action", rs.getString("action"));
          item.put("entity", rs.getString("entity_id"));
          item.put("actor", rs.getString("full_name") != null ? rs.getString("full_name") : "System");
          item.put("role", rs.getString("role") != null ? rs.getString("role") : "SYSTEM");
          item.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
          return item;
        });
  }

  @GetMapping("/allocations")
  public List<Map<String, Object>> allocations(HttpServletRequest request) {
    auth.admin(request);
    return jdbc.query(
        "SELECT a.user_id, a.room_id, u.full_name, u.email, r.code, r.building "
            + "FROM allocations a "
            + "JOIN users u ON u.id = a.user_id "
            + "JOIN rooms r ON r.id = a.room_id "
            + "ORDER BY r.code",
        (rs, idx) -> {
          var item = new LinkedHashMap<String, Object>();
          item.put("user_id", rs.getString("user_id"));
          item.put("room_id", rs.getString("room_id"));
          item.put("user_name", rs.getString("full_name"));
          item.put("user_email", rs.getString("email"));
          item.put("room_code", rs.getString("code"));
          item.put("building", rs.getString("building"));
          return item;
        });
  }

  @PostMapping("/allocations")
  public Map<String, Object> addAllocation(
      @RequestBody AllocationInput input, HttpServletRequest request) {
    auth.admin(request);
    jdbc.update(
        "INSERT INTO allocations(user_id, room_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        input.userId(), input.roomId());
    return Map.of("success", true, "user_id", input.userId(), "room_id", input.roomId());
  }
}

