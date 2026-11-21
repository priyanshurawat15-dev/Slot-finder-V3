package in.quickslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.quickslot.availability.BusyInterval;
import in.quickslot.availability.TimetableLookup;
import in.quickslot.identity.AuthService;
import in.quickslot.shared.AuthenticatedUser;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 2: WP-1 State & Closure Alignment Tests
 *
 * <p>Tests correct differentiation and handling of AVAILABLE, OCCUPIED, CLOSED, and UNKNOWN states.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
public class SlotFinderStateAndClosurePhase2Test {

  private static final String SCHEMA =
      "test_p2_" + UUID.randomUUID().toString().replace("-", "");
  private static final String URL = System.getenv("TEST_DATABASE_URL");
  private static final String USER =
      System.getenv().getOrDefault("TEST_DATABASE_USER", "quickslot");
  private static final String PASSWORD =
      System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "quickslot");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired AuthService auth;
  @Autowired MockTimetableLookup timetableLookup;

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

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

    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity,campus_id,department,active,verified,has_projector,has_ac) VALUES "
            + "('room-101','101','CLASSROOM','Aryabhata Block',60,'gehu-hill-dehradun','CS',true,true,true,true),"
            + "('room-102','102','CLASSROOM','Aryabhata Block',60,'gehu-hill-dehradun','CS',true,true,true,true)");

    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-1','room-101')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-1','room-102')");
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

  @Test
  void temporaryClosureReportsClosedStateWithActualReasonInAvailability() throws Exception {
    Login admin = login("admin@gehu.local", "password-2026");
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    OffsetDateTime start = tomorrow.atTime(10, 0).atZone(INDIA).toOffsetDateTime();
    OffsetDateTime end = tomorrow.atTime(12, 0).atZone(INDIA).toOffsetDateTime();

    // Create temporary closure
    mvc.perform(
            post("/api/rooms/room-101/closures")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "closure_type", "TEMPORARY",
                            "reason", "Annual electrical rewiring",
                            "starts_at", start.toString(),
                            "ends_at", end.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.closure_type").value("TEMPORARY"))
        .andExpect(jsonPath("$.active").value(true));

    // Availability search inside closure window must return state CLOSED with reason
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[0].id").value("room-101"))
        .andExpect(jsonPath("$.rooms[0].state").value("CLOSED"))
        .andExpect(
            jsonPath("$.rooms[0].reason")
                .value(containsString("Annual electrical rewiring")));
  }

  @Test
  void permanentClosureReportsClosedStateAndRemainsVisibleInAvailability() throws Exception {
    Login admin = login("admin@gehu.local", "password-2026");
    LocalDate tomorrow = LocalDate.now().plusDays(1);

    // Create permanent closure on room-102
    mvc.perform(
            post("/api/rooms/room-102/closures")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "closure_type", "PERMANENT",
                            "reason", "Structural roof renovation"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.closure_type").value("PERMANENT"))
        .andExpect(jsonPath("$.active").value(true));

    // Availability search must still include room-102 with state CLOSED
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[1].id").value("room-102"))
        .andExpect(jsonPath("$.rooms[1].state").value("CLOSED"))
        .andExpect(
            jsonPath("$.rooms[1].reason")
                .value(containsString("Structural roof renovation")));
  }

  @Test
  void closedRoomCannotBeBookedViaSubmitRequest() throws Exception {
    Login admin = login("admin@gehu.local", "password-2026");
    Login faculty = login("faculty@gehu.local", "password-2026");
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    OffsetDateTime start = tomorrow.atTime(10, 0).atZone(INDIA).toOffsetDateTime();
    OffsetDateTime end = tomorrow.atTime(12, 0).atZone(INDIA).toOffsetDateTime();

    // Create temporary closure on room-101
    mvc.perform(
            post("/api/rooms/room-101/closures")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "closure_type", "TEMPORARY",
                            "reason", "Flooring polish",
                            "starts_at", start.toString(),
                            "ends_at", end.toString()))))
        .andExpect(status().isOk());

    // Faculty attempts to book room-101 during closure -> must fail 409
    mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "room_id", "room-101",
                            "date", tomorrow.toString(),
                            "start_time", "10:00:00",
                            "end_time", "11:00:00",
                            "purpose", "Research presentation",
                            "attendance", 30))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(containsString("closed")));
  }

  @Test
  void occupiedSlotReportsOccupiedNotClosed() throws Exception {
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    timetableLookup.setCovered(true);
    timetableLookup.addBusy("room-101", new BusyInterval("14:00", "15:00", "CLASS"));

    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "14:00")
                .param("end_time", "15:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[0].id").value("room-101"))
        .andExpect(jsonPath("$.rooms[0].state").value("OCCUPIED"))
        .andExpect(
            jsonPath("$.rooms[0].reason")
                .value(containsString("scheduled class or confirmed booking")));
  }

  @Test
  void coveredSlotReportsAvailable() throws Exception {
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    timetableLookup.setCovered(true);

    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "16:00")
                .param("end_time", "17:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[0].id").value("room-101"))
        .andExpect(jsonPath("$.rooms[0].state").value("AVAILABLE"))
        .andExpect(
            jsonPath("$.rooms[0].reason")
                .value(containsString("No scheduled use found")));
  }

  @Test
  void uncoveredSlotReportsUnknownAndNeverAvailable() throws Exception {
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    timetableLookup.setCovered(false); // Not covered by published timetable

    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "16:00")
                .param("end_time", "17:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[0].id").value("room-101"))
        .andExpect(jsonPath("$.rooms[0].state").value("UNKNOWN"))
        .andExpect(
            jsonPath("$.rooms[0].reason")
                .value(containsString("timetables for this room have not yet been verified")));
  }

  @Test
  void checkClashReportsClosedWithClosureReason() throws Exception {
    Login admin = login("admin@gehu.local", "password-2026");
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    OffsetDateTime start = tomorrow.atTime(10, 0).atZone(INDIA).toOffsetDateTime();
    OffsetDateTime end = tomorrow.atTime(12, 0).atZone(INDIA).toOffsetDateTime();

    mvc.perform(
            post("/api/rooms/room-101/closures")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "closure_type", "TEMPORARY",
                            "reason", "AC maintenance",
                            "starts_at", start.toString(),
                            "ends_at", end.toString()))))
        .andExpect(status().isOk());

    mvc.perform(
            get("/api/availability/check-clash")
                .param("room_id", "room-101")
                .param("date", tomorrow.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CLOSED"))
        .andExpect(jsonPath("$.has_clash").value(true))
        .andExpect(jsonPath("$.reason").value(containsString("AC maintenance")));
  }

  @Test
  void liftingTemporaryClosureRestoresAvailability() throws Exception {
    Login admin = login("admin@gehu.local", "password-2026");
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    OffsetDateTime start = tomorrow.atTime(10, 0).atZone(INDIA).toOffsetDateTime();
    OffsetDateTime end = tomorrow.atTime(12, 0).atZone(INDIA).toOffsetDateTime();
    timetableLookup.setCovered(true);

    MvcResult res =
        mvc.perform(
                post("/api/rooms/room-101/closures")
                    .cookie(admin.cookie())
                    .header("X-CSRF-Token", admin.csrf())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "closure_type", "TEMPORARY",
                                "reason", "Exam hall setup",
                                "starts_at", start.toString(),
                                "ends_at", end.toString()))))
            .andExpect(status().isOk())
            .andReturn();
    String closureId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

    // Verify initially CLOSED
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[?(@.id=='room-101')].state").value("CLOSED"));

    // Lift closure
    mvc.perform(
            delete("/api/rooms/room-101/closures/" + closureId)
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf()))
        .andExpect(status().isOk());

    // Verify restored to AVAILABLE
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[?(@.id=='room-101')].state").value("AVAILABLE"));
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
