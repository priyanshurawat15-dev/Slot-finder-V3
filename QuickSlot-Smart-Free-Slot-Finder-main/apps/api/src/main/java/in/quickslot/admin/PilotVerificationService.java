package in.quickslot.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.quickslot.shared.ApiException;
import in.quickslot.shared.AuditService;
import in.quickslot.shared.AuthenticatedUser;
import in.quickslot.timetables.ImportData.Session;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PilotVerificationService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final AuditService audit;

  public PilotVerificationService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit) {
    this.jdbc = jdbc;
    this.json = json;
    this.audit = audit;
  }

  public record FacultyMappingInput(
      String userId,
      String status,
      String notes) {}

  public Map<String, Object> verificationReport(AuthenticatedUser user) {
    var report = new LinkedHashMap<String, Object>();

    // 1. Room Summary
    Integer totalRooms = jdbc.queryForObject(
        "SELECT count(*) FROM rooms WHERE campus_id=?", Integer.class, user.campusId());
    Integer verifiedRooms = jdbc.queryForObject(
        "SELECT count(*) FROM rooms WHERE campus_id=? AND verified=true", Integer.class, user.campusId());
    Integer unverifiedRooms = jdbc.queryForObject(
        "SELECT count(*) FROM rooms WHERE campus_id=? AND verified=false", Integer.class, user.campusId());
    Integer inactiveRooms = jdbc.queryForObject(
        "SELECT count(*) FROM rooms WHERE campus_id=? AND active=false", Integer.class, user.campusId());
    Integer activeClosures = jdbc.queryForObject(
        "SELECT count(DISTINCT room_id) FROM room_closures WHERE active=true", Integer.class);

    var byKindRows = jdbc.queryForList(
        "SELECT kind, count(*) AS cnt FROM rooms WHERE campus_id=? GROUP BY kind ORDER BY kind",
        user.campusId());
    var byKind = new LinkedHashMap<String, Integer>();
    for (var row : byKindRows) {
      byKind.put((String) row.get("kind"), ((Number) row.get("cnt")).intValue());
    }

    var roomSummary = new LinkedHashMap<String, Object>();
    roomSummary.put("total_rooms", totalRooms != null ? totalRooms : 0);
    roomSummary.put("verified_rooms", verifiedRooms != null ? verifiedRooms : 0);
    roomSummary.put("unverified_rooms", unverifiedRooms != null ? unverifiedRooms : 0);
    roomSummary.put("closed_rooms", (inactiveRooms != null ? inactiveRooms : 0) + (activeClosures != null ? activeClosures : 0));
    roomSummary.put("by_kind", byKind);
    report.put("room_summary", roomSummary);

    // 2. Faculty Summary
    Integer totalFaculty = jdbc.queryForObject(
        "SELECT count(*) FROM timetable_faculty WHERE campus_id=?", Integer.class, user.campusId());
    Integer mappedFaculty = jdbc.queryForObject(
        "SELECT count(*) FROM faculty_account_mappings fam JOIN timetable_faculty tf ON tf.id=fam.timetable_faculty_id "
            + "WHERE tf.campus_id=? AND fam.status='MAPPED'",
        Integer.class, user.campusId());
    Integer unmappedFaculty = jdbc.queryForObject(
        "SELECT count(*) FROM faculty_account_mappings fam JOIN timetable_faculty tf ON tf.id=fam.timetable_faculty_id "
            + "WHERE tf.campus_id=? AND fam.status='UNMAPPED'",
        Integer.class, user.campusId());
    Integer ambiguousFaculty = jdbc.queryForObject(
        "SELECT count(*) FROM faculty_account_mappings fam JOIN timetable_faculty tf ON tf.id=fam.timetable_faculty_id "
            + "WHERE tf.campus_id=? AND fam.status='AMBIGUOUS'",
        Integer.class, user.campusId());

    var facultySummary = new LinkedHashMap<String, Object>();
    facultySummary.put("total_timetable_faculty", totalFaculty != null ? totalFaculty : 0);
    facultySummary.put("mapped_count", mappedFaculty != null ? mappedFaculty : 0);
    facultySummary.put("unmapped_count", unmappedFaculty != null ? unmappedFaculty : 0);
    facultySummary.put("ambiguous_count", ambiguousFaculty != null ? ambiguousFaculty : 0);
    report.put("faculty_summary", facultySummary);

    // 3. Timetable Coverage & Conflict Summary
    Integer totalRevisions = jdbc.queryForObject(
        "SELECT count(*) FROM timetable_revisions tr JOIN timetable_scopes ts ON ts.id=tr.scope_id "
            + "WHERE ts.campus_id=?",
        Integer.class, user.campusId());
    Integer publishedRevisions = jdbc.queryForObject(
        "SELECT count(*) FROM timetable_revisions tr JOIN timetable_scopes ts ON ts.id=tr.scope_id "
            + "WHERE ts.campus_id=? AND tr.status='PUBLISHED'",
        Integer.class, user.campusId());

    // Inspect sessions in published revisions
    var publishedRevs = jdbc.queryForList(
        "SELECT tr.sessions, tr.coverage_room_ids FROM timetable_revisions tr JOIN timetable_scopes ts ON ts.id=tr.scope_id "
            + "WHERE ts.campus_id=? AND tr.status='PUBLISHED'",
        user.campusId());

    int totalSessions = 0;
    int sessionsInVerifiedRooms = 0;
    int sessionsInUnverifiedRooms = 0;
    int onlineSessions = 0;
    int sessionsWithUnmappedFaculty = 0;
    int conflictsCount = 0;

    var verifiedRoomIds = new HashSet<>(jdbc.queryForList(
        "SELECT id FROM rooms WHERE campus_id=? AND verified=true", String.class, user.campusId()));
    var mappedFacultyIds = new HashSet<>(jdbc.queryForList(
        "SELECT timetable_faculty_id FROM faculty_account_mappings WHERE status='MAPPED'", String.class));

    // Conflict detection across sessions in the same room on the same day/time
    var sessionKeyMap = new HashMap<String, String>();

    for (var revRow : publishedRevs) {
      String jsonSessions = extractJson(revRow.get("sessions"));
      if (jsonSessions != null && !jsonSessions.isBlank()) {
        try {
          List<Session> sessions = json.readValue(jsonSessions, new TypeReference<List<Session>>() {});
          totalSessions += sessions.size();
          for (var s : sessions) {
            if (s.roomId() != null) {
              if (verifiedRoomIds.contains(s.roomId())) {
                sessionsInVerifiedRooms++;
              } else {
                sessionsInUnverifiedRooms++;
              }
            } else {
              onlineSessions++;
            }
            if (s.facultyIds() != null && s.facultyIds().stream().anyMatch(fId -> !mappedFacultyIds.contains(fId))) {
              sessionsWithUnmappedFaculty++;
            }

            // Check conflict: room + weekday + time (physical rooms only)
            if (s.roomId() != null) {
              String slotKey = s.roomId() + "-" + s.weekday() + "-" + s.startTime() + "-" + s.endTime();
              if (sessionKeyMap.containsKey(slotKey)) {
                conflictsCount++;
              } else {
                sessionKeyMap.put(slotKey, s.subject());
              }
            }
          }
        } catch (IOException ignored) {}
      }
    }

    var timetableSummary = new LinkedHashMap<String, Object>();
    timetableSummary.put("total_revisions", totalRevisions != null ? totalRevisions : 0);
    timetableSummary.put("published_revisions", publishedRevisions != null ? publishedRevisions : 0);
    timetableSummary.put("total_sessions", totalSessions);
    timetableSummary.put("sessions_in_verified_rooms", sessionsInVerifiedRooms);
    timetableSummary.put("sessions_in_unverified_rooms", sessionsInUnverifiedRooms);
    timetableSummary.put("online_sessions", onlineSessions);
    timetableSummary.put("sessions_with_unmapped_faculty", sessionsWithUnmappedFaculty);
    timetableSummary.put("conflicts_count", conflictsCount);
    report.put("timetable_summary", timetableSummary);

    // 4. Issues list
    var issues = new ArrayList<Map<String, Object>>();

    // Ambiguous faculty issues
    var ambiguousList = jdbc.queryForList(
        "SELECT fam.timetable_faculty_id, tf.name, fam.notes FROM faculty_account_mappings fam "
            + "JOIN timetable_faculty tf ON tf.id=fam.timetable_faculty_id "
            + "WHERE fam.status='AMBIGUOUS'");
    for (var row : ambiguousList) {
      issues.add(Map.of(
          "type", "AMBIGUOUS_FACULTY",
          "severity", "WARNING",
          "entity_id", row.get("timetable_faculty_id"),
          "message", "Faculty '" + row.get("name") + "' is ambiguous: " + row.get("notes")));
    }

    // Unmapped faculty issues
    var unmappedList = jdbc.queryForList(
        "SELECT fam.timetable_faculty_id, tf.name FROM faculty_account_mappings fam "
            + "JOIN timetable_faculty tf ON tf.id=fam.timetable_faculty_id "
            + "WHERE fam.status='UNMAPPED' LIMIT 15");
    for (var row : unmappedList) {
      issues.add(Map.of(
          "type", "UNMAPPED_FACULTY",
          "severity", "INFO",
          "entity_id", row.get("timetable_faculty_id"),
          "message", "Faculty '" + row.get("name") + "' is not yet mapped to an active user account"));
    }

    // Unverified rooms issues
    var unverifiedList = jdbc.queryForList(
        "SELECT id, code, building, floor FROM rooms WHERE campus_id=? AND verified=false",
        user.campusId());
    for (var row : unverifiedList) {
      issues.add(Map.of(
          "type", "UNVERIFIED_ROOM",
          "severity", "INFO",
          "entity_id", row.get("id"),
          "message", "Room '" + row.get("code") + "' (" + row.get("building") + ", " + row.get("floor") + ") requires physical capacity & amenity verification"));
    }

    // Active room closures
    var closureList = jdbc.queryForList(
        "SELECT rc.id, r.code, rc.closure_type, rc.reason FROM room_closures rc "
            + "JOIN rooms r ON r.id=rc.room_id WHERE rc.active=true");
    for (var row : closureList) {
      issues.add(Map.of(
          "type", "ACTIVE_CLOSURE",
          "severity", "WARNING",
          "entity_id", row.get("id"),
          "message", "Room '" + row.get("code") + "' has active " + row.get("closure_type") + " closure: " + row.get("reason")));
    }

    report.put("issues", issues);
    boolean hasError = conflictsCount > 0;
    report.put("ready_for_pilot", (totalRooms != null && totalRooms > 0) && (verifiedRooms != null && verifiedRooms > 0) && !hasError);

    return report;
  }

  public List<Map<String, Object>> facultyMappings(String statusFilter) {
    String sql = "SELECT fam.timetable_faculty_id, tf.name AS faculty_name, "
        + "fam.user_id, u.full_name AS user_name, u.email AS user_email, "
        + "fam.status, fam.notes, fam.mapped_at, mb.full_name AS mapped_by_name "
        + "FROM faculty_account_mappings fam "
        + "JOIN timetable_faculty tf ON tf.id = fam.timetable_faculty_id "
        + "LEFT JOIN users u ON u.id = fam.user_id "
        + "LEFT JOIN users mb ON mb.id = fam.mapped_by ";
    if (statusFilter != null && !statusFilter.isBlank()) {
      sql += "WHERE fam.status = ? ORDER BY tf.name";
      return jdbc.queryForList(sql, statusFilter.trim().toUpperCase());
    } else {
      sql += "ORDER BY tf.name";
      return jdbc.queryForList(sql);
    }
  }

  @Transactional
  public Map<String, Object> updateFacultyMapping(
      String timetableFacultyId,
      FacultyMappingInput input,
      AuthenticatedUser user) {
    if (input.status() == null || (!input.status().equals("MAPPED") && !input.status().equals("UNMAPPED") && !input.status().equals("AMBIGUOUS"))) {
      throw new ApiException(422, "Status must be MAPPED, UNMAPPED, or AMBIGUOUS.");
    }

    String userId = null;
    if (input.status().equals("MAPPED")) {
      if (input.userId() == null || input.userId().isBlank()) {
        throw new ApiException(422, "A valid user ID is required when status is MAPPED.");
      }
      userId = input.userId().strip();
      var userRows = jdbc.queryForList("SELECT id, role, active, campus_id FROM users WHERE id=?", userId);
      if (userRows.isEmpty()) {
        throw new ApiException(404, "User not found.");
      }
      var uRow = userRows.get(0);
      if (!Boolean.TRUE.equals(uRow.get("active"))) {
        throw new ApiException(400, "Cannot map to an inactive user.");
      }
      if (!user.campusId().equals(uRow.get("campus_id"))) {
        throw new ApiException(403, "User belongs to another campus.");
      }
      // Check if user has FACULTY, COORDINATOR, or HOD role
      String role = (String) uRow.get("role");
      Boolean hasFacultyRole = jdbc.queryForObject(
          "SELECT EXISTS(SELECT 1 FROM user_roles WHERE user_id=? AND role IN ('FACULTY', 'COORDINATOR', 'HOD'))",
          Boolean.class, userId);
      if (!Set.of("FACULTY", "COORDINATOR", "HOD").contains(role) && !Boolean.TRUE.equals(hasFacultyRole)) {
        throw new ApiException(400, "User must hold a faculty, coordinator, or HOD role.");
      }
    }

    String notes = input.notes() != null ? input.notes().strip() : "";

    int updated = jdbc.update(
        "UPDATE faculty_account_mappings SET user_id=?, status=?, notes=?, mapped_at=now(), mapped_by=? "
            + "WHERE timetable_faculty_id=?",
        userId,
        input.status(),
        notes,
        user.id(),
        timetableFacultyId);

    if (updated == 0) {
      // Insert if not existing
      jdbc.update(
          "INSERT INTO faculty_account_mappings(timetable_faculty_id, user_id, status, notes, mapped_at, mapped_by) "
              + "VALUES (?, ?, ?, ?, now(), ?)",
          timetableFacultyId,
          userId,
          input.status(),
          notes,
          user.id());
    }

    audit.record(user, "FACULTY_MAPPING_UPDATED", timetableFacultyId, Map.of(
        "status", input.status(),
        "user_id", userId != null ? userId : "",
        "notes", notes));

    var list = facultyMappings(null).stream()
        .filter(m -> timetableFacultyId.equals(m.get("timetable_faculty_id")))
        .toList();
    return list.isEmpty() ? Map.of() : list.get(0);
  }

  static String extractJson(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof String str) {
      return str;
    }
    try {
      var method = raw.getClass().getMethod("getValue");
      Object val = method.invoke(raw);
      if (val instanceof String strVal) {
        return strVal;
      }
      if (val != null) {
        return val.toString();
      }
    } catch (ReflectiveOperationException ignored) {}
    return raw.toString();
  }
}
