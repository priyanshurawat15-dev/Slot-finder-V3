package in.quickslot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
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
  // WP-2: Operating Hours & Window Validation Tests
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void validBoundarySlotsSucceed() throws Exception {
    LocalDate tomorrow = LocalDate.now(INDIA).plusDays(1);
    timetableLookup.setCovered(true);

    // Morning boundary: 08:00 to 09:00
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "08:00")
                .param("end_time", "09:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[0].state").value("AVAILABLE"));

    // Evening boundary: 19:00 to 20:00
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "19:00")
                .param("end_time", "20:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[0].state").value("AVAILABLE"));
  }

  @Test
  void invalidEarlyAndLateSlotsAreRejectedWith422() throws Exception {
    LocalDate tomorrow = LocalDate.now(INDIA).plusDays(1);

    // Starting before 08:00 (07:00 to 08:00)
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "07:00")
                .param("end_time", "08:00"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Campus operating hours are 08:00 to 20:00")));

    // Starting before 08:00 (07:30 to 09:00)
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "07:30")
                .param("end_time", "09:00"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Campus operating hours are 08:00 to 20:00")));

    // Ending after 20:00 (19:30 to 20:30)
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "19:30")
                .param("end_time", "20:30"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Campus operating hours are 08:00 to 20:00")));

    // Ending after 20:00 (20:00 to 21:00)
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "20:00")
                .param("end_time", "21:00"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Campus operating hours are 08:00 to 20:00")));
  }

  @Test
  void equalOrReversedTimeWindowsAreRejectedWith422() throws Exception {
    LocalDate tomorrow = LocalDate.now(INDIA).plusDays(1);

    // Equal start and end (10:00 to 10:00)
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "10:00")
                .param("end_time", "10:00"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Start time must be before end time")));

    // Start later than end (11:00 to 10:00)
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "11:00")
                .param("end_time", "10:00"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Start time must be before end time")));
  }

  @Test
  void validMultiHourSlotSucceeds() throws Exception {
    LocalDate tomorrow = LocalDate.now(INDIA).plusDays(1);
    timetableLookup.setCovered(true);

    // Multi-hour slot: 14:00 to 16:00 (Lab block)
    mvc.perform(
            get("/api/availability")
                .param("date", tomorrow.toString())
                .param("start_time", "14:00")
                .param("end_time", "16:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[0].state").value("AVAILABLE"));
  }

  @Test
  void checkClashValidatesOperatingHoursAndWindow() throws Exception {
    LocalDate tomorrow = LocalDate.now(INDIA).plusDays(1);

    // check-clash before 08:00
    mvc.perform(
            get("/api/availability/check-clash")
                .param("room_id", "room-101")
                .param("date", tomorrow.toString())
                .param("start_time", "07:00")
                .param("end_time", "08:00"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Campus operating hours are 08:00 to 20:00")));

    // check-clash after 20:00
    mvc.perform(
            get("/api/availability/check-clash")
                .param("room_id", "room-101")
                .param("date", tomorrow.toString())
                .param("start_time", "20:00")
                .param("end_time", "21:00"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Campus operating hours are 08:00 to 20:00")));

    // check-clash equal start and end
    mvc.perform(
            get("/api/availability/check-clash")
                .param("room_id", "room-101")
                .param("date", tomorrow.toString())
                .param("start_time", "10:00")
                .param("end_time", "10:00"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Start time must be before end time")));

    // check-clash start after end
    mvc.perform(
            get("/api/availability/check-clash")
                .param("room_id", "room-101")
                .param("date", tomorrow.toString())
                .param("start_time", "12:00")
                .param("end_time", "11:00"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Start time must be before end time")));
  }

  @Test
  void checkClashDetectsOccupiedAndClosedRooms() throws Exception {
    LocalDate tomorrow = LocalDate.now(INDIA).plusDays(1);
    timetableLookup.setCovered(true);

    // 1. Available check
    mvc.perform(
            get("/api/availability/check-clash")
                .param("room_id", "room-101")
                .param("date", tomorrow.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("AVAILABLE"))
        .andExpect(jsonPath("$.has_clash").value(false))
        .andExpect(jsonPath("$.reason").value(containsString("verified free")));

    // 2. Occupied check via timetable busy interval
    timetableLookup.addBusy("room-101", new BusyInterval("10:00", "11:00", "CLASS"));
    mvc.perform(
            get("/api/availability/check-clash")
                .param("room_id", "room-101")
                .param("date", tomorrow.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("OCCUPIED"))
        .andExpect(jsonPath("$.has_clash").value(true))
        .andExpect(jsonPath("$.reason").value(containsString("clashes with an existing class or confirmed booking")));

    // 3. Closed check via active room closure
    OffsetDateTime closureStart = tomorrow.atTime(14, 0).atZone(INDIA).toOffsetDateTime();
    OffsetDateTime closureEnd = tomorrow.atTime(18, 0).atZone(INDIA).toOffsetDateTime();
    jdbc.update(
        "INSERT INTO room_closures(id,room_id,starts_at,ends_at,reason,created_by,active,closure_type) VALUES (?,?,?,?,?,?,true,'TEMPORARY')",
        UUID.randomUUID().toString(),
        "room-102",
        closureStart,
        closureEnd,
        "Electrical Wiring Repair",
        "admin-1");

    mvc.perform(
            get("/api/availability/check-clash")
                .param("room_id", "room-102")
                .param("date", tomorrow.toString())
                .param("start_time", "15:00")
                .param("end_time", "16:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CLOSED"))
        .andExpect(jsonPath("$.has_clash").value(true))
        .andExpect(jsonPath("$.reason").value(containsString("Electrical Wiring Repair")));

    // 4. Uncovered check: returns UNKNOWN with has_clash=false
    timetableLookup.setCovered(false);
    mvc.perform(
            get("/api/availability/check-clash")
                .param("room_id", "room-102")
                .param("date", tomorrow.toString())
                .param("start_time", "09:00")
                .param("end_time", "10:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("UNKNOWN"))
        .andExpect(jsonPath("$.has_clash").value(false))
        .andExpect(jsonPath("$.reason").value(containsString("unverified")));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // WP-4: End-to-End Filter Combinations & Edge-Case Tests
  // ─────────────────────────────────────────────────────────────────────────────

  private void seedFilterCatalog() {
    jdbc.execute("TRUNCATE rooms, allocations, room_closures, occupancies CASCADE");
    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,floor,capacity,campus_id,department,active,verified,has_projector,has_ac) VALUES "
            + "('room-cr1','CR 101','CLASSROOM','Aryabhata Block','1st Floor',60,'gehu-hill-dehradun','CS',true,true,true,true),"
            + "('room-cr2','CR 201','CLASSROOM','Aryabhata Block','2nd Floor',30,'gehu-hill-dehradun','CS',true,true,false,false),"
            + "('room-lt1','LT 1','LT','Chanakya Block','Ground Floor',120,'gehu-hill-dehradun','CS',true,true,true,true),"
            + "('room-lab1','LAB 1','LAB','Aryabhata Block','1st Floor',40,'gehu-hill-dehradun','CS',true,true,true,false),"
            + "('room-aud1','AUD 1','AUDITORIUM','Kautilya Block','Basement',500,'gehu-hill-dehradun','CS',true,true,true,true),"
            + "('room-sem1','SH 1','SEMINAR_HALL','Chanakya Block','3rd Floor',80,'gehu-hill-dehradun','CS',true,true,false,true),"
            + "('room-nocap','CR 301','CLASSROOM','Aryabhata Block','3rd Floor',NULL,'gehu-hill-dehradun','CS',true,false,false,false),"
            + "('room-nofloor','CR 401','CLASSROOM','Aryabhata Block',NULL,50,'gehu-hill-dehradun','CS',true,true,true,true),"
            + "('room-inactive','CR 102','CLASSROOM','Aryabhata Block','1st Floor',60,'gehu-hill-dehradun','CS',false,true,true,true)");
  }

  // --- Section A: Individual Filters ---

  @Test
  void filterByKindIndependentlyReturnsMatchingRooms() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // 1. LT only
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "LT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(1)))
        .andExpect(jsonPath("$.rooms[0].id").value("room-lt1"));

    // 2. LAB only
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "LAB"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(1)))
        .andExpect(jsonPath("$.rooms[0].id").value("room-lab1"));

    // 3. SEMINAR_HALL only
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "SEMINAR_HALL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(1)))
        .andExpect(jsonPath("$.rooms[0].id").value("room-sem1"));

    // 4. AUDITORIUM only
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "AUDITORIUM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(1)))
        .andExpect(jsonPath("$.rooms[0].id").value("room-aud1"));

    // 5. CLASSROOM only
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "CLASSROOM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(5)))
        .andExpect(
            jsonPath(
                "$.rooms[*].id",
                containsInAnyOrder(
                    "room-cr1", "room-cr2", "room-nocap", "room-nofloor", "room-inactive")));
  }

  @Test
  void filterByBuildingIndependentlyReturnsMatchingRooms() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // 1. Chanakya Block
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("building", "Chanakya Block"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(2)))
        .andExpect(jsonPath("$.rooms[*].id", containsInAnyOrder("room-lt1", "room-sem1")));

    // 2. Kautilya Block
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("building", "Kautilya Block"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(1)))
        .andExpect(jsonPath("$.rooms[0].id").value("room-aud1"));

    // 3. Aryabhata Block
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("building", "Aryabhata Block"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(6)))
        .andExpect(
            jsonPath(
                "$.rooms[*].id",
                containsInAnyOrder(
                    "room-cr1", "room-cr2", "room-lab1", "room-nocap", "room-nofloor", "room-inactive")));
  }

  @Test
  void filterByFloorIndependentlyReturnsMatchingRooms() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // 1. Ground Floor
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("floor", "Ground Floor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(1)))
        .andExpect(jsonPath("$.rooms[0].id").value("room-lt1"));

    // 2. 2nd Floor
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("floor", "2nd Floor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(1)))
        .andExpect(jsonPath("$.rooms[0].id").value("room-cr2"));

    // 3. 1st Floor (excludes room-nofloor where floor is null)
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("floor", "1st Floor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(3)))
        .andExpect(
            jsonPath(
                "$.rooms[*].id",
                containsInAnyOrder("room-cr1", "room-lab1", "room-inactive")));
  }

  @Test
  void filterByMinCapacityReturnsRoomsMeetingThreshold() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // 1. min_capacity=100 (excludes null, 30, 40, 50, 60, 80)
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("min_capacity", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(2)))
        .andExpect(jsonPath("$.rooms[*].id", containsInAnyOrder("room-lt1", "room-aud1")));

    // 2. min_capacity=60 (includes exact 60, 80, 120, 500)
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("min_capacity", "60"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(5)))
        .andExpect(
            jsonPath(
                "$.rooms[*].id",
                containsInAnyOrder(
                    "room-cr1", "room-inactive", "room-sem1", "room-lt1", "room-aud1")));
  }

  @Test
  void filterByHasProjectorReturnsEquippedRoomsOnly() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("has_projector", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(6)))
        .andExpect(
            jsonPath(
                "$.rooms[*].id",
                containsInAnyOrder(
                    "room-cr1", "room-lt1", "room-lab1", "room-aud1", "room-nofloor", "room-inactive")));
  }

  @Test
  void filterByHasAcReturnsEquippedRoomsOnly() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("has_ac", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(6)))
        .andExpect(
            jsonPath(
                "$.rooms[*].id",
                containsInAnyOrder(
                    "room-cr1", "room-lt1", "room-aud1", "room-sem1", "room-nofloor", "room-inactive")));
  }

  // --- Section B: Combined Filters ---

  @Test
  void filterByKindAndBuilding() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "CLASSROOM")
                .param("building", "Aryabhata Block"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(5)))
        .andExpect(
            jsonPath(
                "$.rooms[*].id",
                containsInAnyOrder(
                    "room-cr1", "room-cr2", "room-nocap", "room-nofloor", "room-inactive")));
  }

  @Test
  void filterByBuildingAndFloor() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("building", "Aryabhata Block")
                .param("floor", "1st Floor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(3)))
        .andExpect(
            jsonPath(
                "$.rooms[*].id",
                containsInAnyOrder("room-cr1", "room-lab1", "room-inactive")));
  }

  @Test
  void filterByKindAndMinCapacity() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "CLASSROOM")
                .param("min_capacity", "55"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(2)))
        .andExpect(
            jsonPath("$.rooms[*].id", containsInAnyOrder("room-cr1", "room-inactive")));
  }

  @Test
  void filterByProjectorAndAc() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("has_projector", "true")
                .param("has_ac", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(5)))
        .andExpect(
            jsonPath(
                "$.rooms[*].id",
                containsInAnyOrder(
                    "room-cr1", "room-lt1", "room-aud1", "room-nofloor", "room-inactive")));
  }

  @Test
  void filterByBuildingFloorAndCapacity() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("building", "Aryabhata Block")
                .param("floor", "1st Floor")
                .param("min_capacity", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(2)))
        .andExpect(
            jsonPath("$.rooms[*].id", containsInAnyOrder("room-cr1", "room-inactive")));
  }

  @Test
  void filterByAllSupportedCriteriaTogether() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "CLASSROOM")
                .param("building", "Aryabhata Block")
                .param("floor", "1st Floor")
                .param("min_capacity", "55")
                .param("has_projector", "true")
                .param("has_ac", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(2)))
        .andExpect(
            jsonPath("$.rooms[*].id", containsInAnyOrder("room-cr1", "room-inactive")));
  }

  // --- Section C: Edge Cases & Boundary Values ---

  @Test
  void noMatchingRoomsReturnsEmptyList() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // 1. Kind not in building
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "AUDITORIUM")
                .param("building", "Aryabhata Block"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", empty()));

    // 2. Capacity beyond all rooms
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("min_capacity", "1000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", empty()));
  }

  @Test
  void capacityBoundaryConditions() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // Exact match: capacity=60 matches min_capacity=60
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "CLASSROOM")
                .param("min_capacity", "60"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(2)))
        .andExpect(
            jsonPath("$.rooms[*].id", containsInAnyOrder("room-cr1", "room-inactive")));

    // Boundary exceed: capacity=60 excluded when min_capacity=61
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "CLASSROOM")
                .param("min_capacity", "61"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", empty()));
  }

  @Test
  void roomsWithNullCapacityExcludedWhenMinCapacityQueried() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // When min_capacity is set, room-nocap is excluded
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("floor", "3rd Floor")
                .param("min_capacity", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(1)))
        .andExpect(jsonPath("$.rooms[0].id").value("room-sem1"));

    // When min_capacity is omitted, room-nocap is included
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("floor", "3rd Floor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(2)))
        .andExpect(
            jsonPath("$.rooms[*].id", containsInAnyOrder("room-sem1", "room-nocap")));
  }

  @Test
  void roomsWithNullFloorExcludedWhenFloorQueried() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // When floor is set to 1st Floor, room-nofloor is excluded
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("floor", "1st Floor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[*].id", not(hasItem("room-nofloor"))));

    // When floor is omitted, room-nofloor is included
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "CLASSROOM"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[*].id", hasItem("room-nofloor")));
  }

  @Test
  void caseInsensitiveAndWhitespaceTolerance() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "  classroom  ")
                .param("building", "  aryabhata block  ")
                .param("floor", "  1st floor  "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(2)))
        .andExpect(
            jsonPath("$.rooms[*].id", containsInAnyOrder("room-cr1", "room-inactive")));
  }

  @Test
  void omittedOptionalFiltersReturnFullInventory() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(9)))
        .andExpect(
            jsonPath(
                "$.rooms[*].id",
                containsInAnyOrder(
                    "room-cr1",
                    "room-cr2",
                    "room-lt1",
                    "room-lab1",
                    "room-aud1",
                    "room-sem1",
                    "room-nocap",
                    "room-nofloor",
                    "room-inactive")));
  }

  @Test
  void falseAmenityFlagsDoNotExcludeRooms() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // has_projector=false and has_ac=false mean amenity is not required (all rooms returned)
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("has_projector", "false")
                .param("has_ac", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(9)));
  }

  // --- Section D: State & Reliability Regression Under Filters ---

  @Test
  void roomStateCalculationsPreservedUnderFilters() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // 1. Set timetable covered
    timetableLookup.setCovered(true);

    // 2. Set room-lab1 busy via timetable
    timetableLookup.addBusy("room-lab1", new BusyInterval("10:00", "11:00", "CLASS"));

    // 3. Set room-cr1 temporarily closed
    OffsetDateTime closureStart = testDate.atTime(9, 0).atZone(INDIA).toOffsetDateTime();
    OffsetDateTime closureEnd = testDate.atTime(12, 0).atZone(INDIA).toOffsetDateTime();
    jdbc.update(
        "INSERT INTO room_closures(id,room_id,starts_at,ends_at,reason,created_by,active,closure_type) VALUES (?,?,?,?,?,?,true,'TEMPORARY')",
        UUID.randomUUID().toString(),
        "room-cr1",
        closureStart,
        closureEnd,
        "Network Switch Replacement",
        "admin-1");

    // Query 1st floor rooms in Aryabhata Block: room-cr1, room-lab1, room-inactive
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("building", "Aryabhata Block")
                .param("floor", "1st Floor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(3)))
        .andExpect(jsonPath("$.rooms[?(@.id=='room-cr1')].state").value(hasItem("CLOSED")))
        .andExpect(
            jsonPath("$.rooms[?(@.id=='room-cr1')].reason")
                .value(hasItem(containsString("Network Switch Replacement"))))
        .andExpect(jsonPath("$.rooms[?(@.id=='room-lab1')].state").value(hasItem("OCCUPIED")))
        .andExpect(
            jsonPath("$.rooms[?(@.id=='room-lab1')].reason")
                .value(hasItem(containsString("scheduled class or confirmed booking"))))
        .andExpect(jsonPath("$.rooms[?(@.id=='room-inactive')].state").value(hasItem("CLOSED")))
        .andExpect(
            jsonPath("$.rooms[?(@.id=='room-inactive')].reason")
                .value(hasItem(containsString("permanently closed or inactive"))));

    // 4. When timetable is not covered, remaining rooms report UNKNOWN
    timetableLookup.setCovered(false);
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00")
                .param("kind", "LT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms", hasSize(1)))
        .andExpect(jsonPath("$.rooms[0].id").value("room-lt1"))
        .andExpect(jsonPath("$.rooms[0].state").value("UNKNOWN"))
        .andExpect(jsonPath("$.rooms[0].reason").value(containsString("not yet been verified")));
  }

  @Test
  void operatingHoursValidationRemainsEnforcedWithFilters() throws Exception {
    seedFilterCatalog();
    LocalDate testDate = LocalDate.now(INDIA).plusDays(1);

    // Invalid start time (< 08:00) with filters
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "07:00")
                .param("end_time", "08:00")
                .param("kind", "CLASSROOM")
                .param("building", "Aryabhata Block"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.detail")
                .value(containsString("Campus operating hours are 08:00 to 20:00")));

    // Invalid window (start >= end) with filters
    mvc.perform(
            get("/api/availability")
                .param("date", testDate.toString())
                .param("start_time", "11:00")
                .param("end_time", "10:00")
                .param("kind", "CLASSROOM")
                .param("building", "Aryabhata Block"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(containsString("Start time must be before end time")));
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
