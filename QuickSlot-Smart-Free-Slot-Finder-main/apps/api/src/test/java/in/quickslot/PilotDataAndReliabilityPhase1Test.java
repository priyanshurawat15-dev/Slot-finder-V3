package in.quickslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.quickslot.availability.BusyInterval;
import in.quickslot.availability.TimetableLookup;
import in.quickslot.identity.AdminBootstrap;
import in.quickslot.identity.AuthService;
import in.quickslot.shared.ApiException;
import in.quickslot.shared.AuthenticatedUser;
import in.quickslot.timetables.ImportData;
import in.quickslot.timetables.TimetableService;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 1: CSE Pilot Data Finalization & Booking Reliability
 *
 * <p>Tests Work Packages B (validate), D (UNKNOWN override), and E (admin bootstrap).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
public class PilotDataAndReliabilityPhase1Test {

  private static final String SCHEMA =
      "test_p1_" + UUID.randomUUID().toString().replace("-", "");
  private static final String URL = System.getenv("TEST_DATABASE_URL");
  private static final String USER =
      System.getenv().getOrDefault("TEST_DATABASE_USER", "quickslot");
  private static final String PASSWORD =
      System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "quickslot");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired AuthService auth;
  @Autowired TimetableService timetableService;
  @Autowired MockTimetableLookup timetableLookup;

  private final AuthenticatedUser adminUser =
      new AuthenticatedUser(
          "admin-1", "Administrator", "admin@gehu.local", "ADMIN", "gehu-hill-dehradun", "CS");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
        var command = connection.createStatement()) {
      command.execute("CREATE SCHEMA " + SCHEMA);
      command.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");
    }
    registry.add(
        "spring.datasource.url",
        () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public");
    registry.add("spring.datasource.username", () -> USER);
    registry.add("spring.datasource.password", () -> PASSWORD);
    registry.add("spring.flyway.default-schema", () -> SCHEMA);
    registry.add("spring.flyway.schemas", () -> SCHEMA);
    registry.add("quickslot.cookie-secure", () -> false);
    registry.add("quickslot.demo-seed", () -> false);
  }

  @AfterAll
  static void removeSchema() throws Exception {
    try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
        var command = connection.createStatement()) {
      command.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
  }

  @BeforeEach
  void prepare() {
    timetableLookup.reset();
    jdbc.execute(
        "TRUNCATE users, rooms, allocations, user_roles, department_hods, invitations, audit_events,"
            + " booking_requests, decisions, occupancies, notifications, idempotency_records, room_closures,"
            + " timetable_scopes, timetable_faculty CASCADE");

    insertUser("admin-1", "Administrator", "admin@gehu.local", "ADMIN", "CS");
    insertUser("faculty-1", "Prof. CSE Faculty", "faculty@gehu.local", "FACULTY", "CS");
    insertUser("coord-1", "Coordinator Aryabhata", "coord@gehu.local", "COORDINATOR", "CS");
    insertUser("hod-1", "Dr. HOD CSE", "hod@gehu.local", "HOD", "CS");

    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity,campus_id,department,active,verified) VALUES "
            + "('room-101','101','CLASSROOM','Aryabhata Block',60,'gehu-hill-dehradun','CS',true,true),"
            + "('room-102','102','CLASSROOM','Aryabhata Block',60,'gehu-hill-dehradun','CS',true,true)");

    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-1','room-101')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-1','room-102')");

    // Faculty directory for timetable validation
    timetableService.addFaculty("FAC-001", "Dr. A. Sharma", adminUser);
    timetableService.addFaculty("FAC-002", "Dr. B. Verma", adminUser);
  }

  private void insertUser(String id, String name, String email, String role, String dept) {
    jdbc.update(
        "INSERT INTO users(id,full_name,email,password_hash,role,campus_id,department,status,active)"
            + " VALUES (?,?,?,?,?,'gehu-hill-dehradun',?,'ACTIVE',true)",
        id,
        name,
        email,
        auth.passwordHash("password-2026"),
        role,
        dept);
    jdbc.update(
        "INSERT INTO user_roles(id,user_id,role,department,scope_type) VALUES (?,?,?,?,?)",
        UUID.randomUUID().toString(),
        id,
        role,
        dept,
        role.equals("ADMIN") ? "INSTITUTION" : "DEPARTMENT");
  }

  private record Login(Cookie cookie, String csrf) {}

  private Login login(String email, String password) throws Exception {
    MvcResult res =
        mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    Cookie cookie = res.getResponse().getCookie(AuthService.COOKIE);
    String csrf = json.readTree(res.getResponse().getContentAsString()).get("csrf_token").asText();
    return new Login(cookie, csrf);
  }

  /**
   * Builds a minimal structured DOCX that DocxTimetableAdapter recognises,
   * uploads it, and returns the revision ID.
   * Inlined here because TimetableAdaptersTest is package-private.
   */
  private String uploadRevision(String scope, LocalDate from, LocalDate until) throws Exception {
    try (var document = new XWPFDocument()) {
      // Metadata table (4×4) — required by adapter
      var metadata = document.createTable(4, 4);
      String[][] meta = {
        {"Campus ID", "gehu-hill-dehradun", "Programme", "B.Tech"},
        {"Department", "CS", "Semester", "III"},
        {"Group ID", "GRP-A", "Term ID", "2026-27-ODD"},
        {"Valid from", from.toString(), "Valid until", until.toString()}
      };
      for (int r = 0; r < 4; r++)
        for (int c = 0; c < 4; c++)
          metadata.getRow(r).getCell(c).setText(meta[r][c]);

      // Grid table (3×11) — DAY/TIME header + 2 day rows
      var grid = document.createTable(3, 11);
      String[] headers = {
        "DAY / TIME", "08:00 to 08:55", "08:55 to 09:50", "10:10 to 11:05",
        "11:05 to 12:00", "12:00 to 12:55", "12:55 to 13:50",
        "14:10 to 15:05", "15:05 to 16:00", "16:00 to 16:55", "16:55 to 17:50"
      };
      for (int i = 0; i < headers.length; i++) grid.getRow(0).getCell(i).setText(headers[i]);
      grid.getRow(1).getCell(0).setText("MON");
      grid.getRow(2).getCell(0).setText("TUE");

      // Course legend table (3×3)
      var courses = document.createTable(3, 3);
      courses.getRow(0).getCell(0).setText("SUBJECT CODE");
      courses.getRow(0).getCell(1).setText("SUBJECT NAME");
      courses.getRow(0).getCell(2).setText("TYPE");

      // Tag each table with the expected caption
      for (var entry : new Object[][] {
            {metadata, "TIMETABLE_METADATA_V1"},
            {grid,     "TIMETABLE_GRID_V1"},
            {courses,  "TIMETABLE_COURSES_V1"}}) {
        ((XWPFTable) entry[0]).getCTTbl().getTblPr().addNewTblCaption().setVal((String) entry[1]);
      }

      var out = new ByteArrayOutputStream();
      document.write(out);
      return timetableService
          .upload(adminUser, "template.docx", out.toByteArray(), scope, from, until, List.of())
          .get("id")
          .toString();
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Work Package B — Validation Rules
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void timetableValidationRejectsSessionsOutsideOperatingHours() throws Exception {
    // Create a revision to get a real ID, then try to review with out-of-hours sessions.
    String revId = uploadRevision("CSE-III", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));

    var early = new ImportData.Session("e1", 0, "07:30", "08:30", "room-101", "CS301", List.of("FAC-001"), Map.of());
    assertThatThrownBy(
            () ->
                timetableService.review(
                    revId,
                    new TimetableService.Review(0, List.of(early), List.of("room-101"), true),
                    adminUser))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("operating hours");

    var late = new ImportData.Session("l1", 0, "19:30", "20:30", "room-101", "CS301", List.of("FAC-001"), Map.of());
    assertThatThrownBy(
            () ->
                timetableService.review(
                    revId,
                    new TimetableService.Review(0, List.of(late), List.of("room-101"), true),
                    adminUser))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("operating hours");
  }

  @Test
  void timetableValidationRejectsDuplicateSessions() throws Exception {
    String revId = uploadRevision("CSE-III", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));

    var s1 = new ImportData.Session("d1", 1, "09:00", "10:00", "room-101", "CS301", List.of("FAC-001"), Map.of());
    var s2 = new ImportData.Session("d2", 1, "09:00", "10:00", "room-101", "CS301", List.of("FAC-001"), Map.of());

    assertThatThrownBy(
            () ->
                timetableService.review(
                    revId,
                    new TimetableService.Review(0, List.of(s1, s2), List.of("room-101"), true),
                    adminUser))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Duplicate");
  }

  @Test
  void timetableValidationRejectsFacultyOverlapAcrossRooms() throws Exception {
    String revId = uploadRevision("CSE-V", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));

    // FAC-001 in room-101 AND room-102 on Monday 10:00–11:00 — illegal overlap
    var s1 = new ImportData.Session("f1", 0, "10:00", "11:00", "room-101", "CS301", List.of("FAC-001"), Map.of());
    var s2 = new ImportData.Session("f2", 0, "10:30", "11:30", "room-102", "CS501", List.of("FAC-001"), Map.of());

    assertThatThrownBy(
            () ->
                timetableService.review(
                    revId,
                    new TimetableService.Review(0, List.of(s1, s2), List.of("room-101", "room-102"), true),
                    adminUser))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("FAC-001")
        .hasMessageContaining("overlapping classes");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Work Package D — UNKNOWN Availability & Safe Approval
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void approvingUnknownSlotWithoutNoteIsRejectedWith422() throws Exception {
    Login faculty = login("faculty@gehu.local", "password-2026");
    LocalDate targetDate = LocalDate.now().plusDays(2);

    MvcResult res =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of(
                        "room_id", "room-101",
                        "date", targetDate.toString(),
                        "start_time", "11:00:00",
                        "end_time", "12:00:00",
                        "purpose", "Student doubt clearing session",
                        "attendance", 30))))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

    // Timetable UNKNOWN (covered=false)
    timetableLookup.setCovered(false);

    Login coord = login("coord@gehu.local", "password-2026");
    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(coord.cookie())
                .header("X-CSRF-Token", coord.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", ""))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("unverified (UNKNOWN)")));
  }

  @Test
  void approvingUnknownSlotWithManualNoteSucceedsAndAuditsOverride() throws Exception {
    Login faculty = login("faculty@gehu.local", "password-2026");
    LocalDate targetDate = LocalDate.now().plusDays(3);

    MvcResult res =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of(
                        "room_id", "room-101",
                        "date", targetDate.toString(),
                        "start_time", "14:00:00",
                        "end_time", "15:00:00",
                        "purpose", "Guest seminar on cloud computing",
                        "attendance", 40))))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

    timetableLookup.setCovered(false);

    Login coord = login("coord@gehu.local", "password-2026");
    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(coord.cookie())
                .header("X-CSRF-Token", coord.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "reason", "Physically checked room 101; verified vacant with floor in-charge"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    // Occupancy must be created
    Integer occCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM occupancies WHERE source_id=? AND kind='BOOKING' AND active=true",
            Integer.class, reqId);
    assertThat(occCount).isEqualTo(1);

    // Audit log uses column entity_id, not target_id
    Integer auditCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE action='REQUEST_APPROVED_UNKNOWN_OVERRIDE' AND entity_id=?",
            Integer.class, reqId);
    assertThat(auditCount).isEqualTo(1);
  }

  @Test
  void approvingOccupiedSlotFailsEvenWithNote() throws Exception {
    Login faculty = login("faculty@gehu.local", "password-2026");
    LocalDate targetDate = LocalDate.now().plusDays(4);

    MvcResult res =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of(
                        "room_id", "room-101",
                        "date", targetDate.toString(),
                        "start_time", "10:00:00",
                        "end_time", "11:00:00",
                        "purpose", "Extra class",
                        "attendance", 30))))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

    // Mark covered AND busy — room is OCCUPIED
    timetableLookup.setCovered(true);
    timetableLookup.addBusy("room-101", new BusyInterval("09:30", "10:30", "CLASS"));

    Login coord = login("coord@gehu.local", "password-2026");
    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(coord.cookie())
                .header("X-CSRF-Token", coord.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Trying to override class"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(containsString("occupied or booked")));
  }

  @Test
  void approvingRoomWithActiveTempClosureFails() throws Exception {
    Login faculty = login("faculty@gehu.local", "password-2026");
    LocalDate targetDate = LocalDate.now().plusDays(5);

    // Step 1: Faculty submits booking request FIRST (no closure yet — must succeed 201)
    MvcResult res =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of(
                        "room_id", "room-101",
                        "date", targetDate.toString(),
                        "start_time", "10:00:00",
                        "end_time", "11:00:00",
                        "purpose", "Meeting",
                        "attendance", 25))))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

    // Step 2: THEN insert temporary closure (room gets closed after request was submitted)
    // created_by is NOT NULL — supply admin-1
    OffsetDateTime closureStart = targetDate.atStartOfDay().atOffset(ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime closureEnd   = targetDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHoursMinutes(5, 30));
    jdbc.update(
        "INSERT INTO room_closures(id, room_id, closure_type, reason, starts_at, ends_at, created_by, active)"
            + " VALUES ('closure-1', 'room-101', 'TEMPORARY', 'Renovation work', ?, ?, 'admin-1', true)",
        closureStart, closureEnd);

    // Step 3: Coordinator tries to approve → must be blocked with 409 containing "closure"
    Login coord = login("coord@gehu.local", "password-2026");
    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(coord.cookie())
                .header("X-CSRF-Token", coord.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Trying to override closure"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(containsString("closure")));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Work Package E — Admin Bootstrap
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void adminBootstrapCreatesAdminAndIsIdempotent() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("QUICKSLOT_ADMIN_EMAIL", "bootstrap.admin@gehu.ac.in");
    env.setProperty("QUICKSLOT_ADMIN_PASSWORD", "AdminSecure2026!");

    AdminBootstrap bootstrap = new AdminBootstrap(jdbc, auth, env);

    // First run → creates user
    bootstrap.run();

    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users WHERE email='bootstrap.admin@gehu.ac.in')",
            Boolean.class);
    assertThat(exists).isTrue();

    String role =
        jdbc.queryForObject(
            "SELECT role FROM users WHERE email='bootstrap.admin@gehu.ac.in'", String.class);
    assertThat(role).isEqualTo("ADMIN");

    // Second run → idempotent, no exception
    bootstrap.run();

    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE email='bootstrap.admin@gehu.ac.in'", Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void adminBootstrapNoOpsWhenEnvVarsAbsent() {
    MockEnvironment env = new MockEnvironment(); // no properties set
    AdminBootstrap bootstrap = new AdminBootstrap(jdbc, auth, env);
    // Must complete without throwing
    bootstrap.run();
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // TestConfiguration — controllable TimetableLookup stub
  // ─────────────────────────────────────────────────────────────────────────────

  @TestConfiguration
  static class FixtureConfiguration {
    @Bean
    @Primary
    MockTimetableLookup timetableLookup() {
      return new MockTimetableLookup();
    }
  }

  static class MockTimetableLookup implements TimetableLookup {
    private volatile boolean covered = false;
    private final ConcurrentHashMap<String, List<BusyInterval>> busyMap = new ConcurrentHashMap<>();

    void reset() {
      covered = false;
      busyMap.clear();
    }

    void setCovered(boolean c) {
      this.covered = c;
    }

    void addBusy(String roomId, BusyInterval interval) {
      busyMap.computeIfAbsent(roomId, k -> new ArrayList<>()).add(interval);
    }

    @Override
    public List<BusyInterval> busy(String roomId, LocalDate date) {
      return busyMap.getOrDefault(roomId, List.of());
    }

    @Override
    public boolean covered(String roomId, LocalDate date) {
      return covered;
    }
  }
}
