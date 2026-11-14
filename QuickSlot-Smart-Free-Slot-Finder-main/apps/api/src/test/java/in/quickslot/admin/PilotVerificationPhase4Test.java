package in.quickslot.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.quickslot.availability.BusyInterval;
import in.quickslot.availability.TimetableLookup;
import in.quickslot.identity.AuthService;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
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

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
class PilotVerificationPhase4Test {
  private static final String SCHEMA =
      "test_p4_" + UUID.randomUUID().toString().replace("-", "");
  private static final String URL = System.getenv("TEST_DATABASE_URL");
  private static final String USER =
      System.getenv().getOrDefault("TEST_DATABASE_USER", "quickslot");
  private static final String PASSWORD =
      System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "quickslot");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired AuthService auth;
  @Autowired ScheduleFixture fixture;

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
    fixture.complete = true;
    jdbc.execute(
        "TRUNCATE users,rooms,allocations,user_roles,department_hods,invitations,audit_events,"
            + "booking_requests,decisions,occupancies,notifications,idempotency_records,clubs,club_managers,club_memberships,room_closures,faculty_account_mappings,timetable_faculty CASCADE");
    jdbc.execute("TRUNCATE login_attempts");

    // Insert Users
    insertUser("admin-1", "Campus Admin", "admin@test.local", "ADMIN", "CS", "EMP-ADM1");
    insertUser("coord-1", "CSE Coordinator", "coord@test.local", "COORDINATOR", "CS", "EMP-C1");
    insertUser("faculty-1", "Dr. Vikrant Sharma", "vikrant@test.local", "FACULTY", "CS", "EMP-FAC1");
    insertUser("faculty-2", "Dr. Sachin Sharma", "sachin@test.local", "FACULTY", "CS", "EMP-FAC2");
    insertUser("hod-1", "Dr. HOD CSE", "hod@test.local", "HOD", "CS", "EMP-HOD1");
    insertUser("student-1", "Student One", "student@test.local", "STUDENT", "CS", "EMP-STU1");

    // Insert Rooms
    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity,campus_id,department,active,verified,floor) VALUES "
            + "('room-cr104','CR 104','CLASSROOM','CS Block',60,'gehu-hill-dehradun','CS',true,true,'First floor'),"
            + "('room-cr201','CR 201','CLASSROOM','CS Block',60,'gehu-hill-dehradun','CS',true,true,'Second floor'),"
            + "('room-cr404','CR 404','CLASSROOM','CS Block',60,'gehu-hill-dehradun','CS',true,false,'Fourth floor'),"
            + "('room-lt201','LT 201','LT','CS Block',120,'gehu-hill-dehradun','CS',true,true,'Second floor'),"
            + "('room-logic','LOGIC LAB','LAB','CS Block',45,'gehu-hill-dehradun','CS',true,true,'Basement')");

    // Allocations
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-1','room-cr104')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-1','room-cr201')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-1','room-cr404')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-1','room-lt201')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-1','room-logic')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('admin-1','room-cr104')");

    // Timetable faculty
    jdbc.update(
        "INSERT INTO timetable_faculty(id,campus_id,name) VALUES "
            + "('fac-vikrant-sharma','gehu-hill-dehradun','Dr. Vikrant Sharma'),"
            + "('fac-sachin-sharma','gehu-hill-dehradun','Dr. Sachin Sharma'),"
            + "('fac-akash-chauhan','gehu-hill-dehradun','Mr. Akash Chauhan'),"
            + "('fac-rahul-chauhan','gehu-hill-dehradun','Mr. Rahul Chauhan')");

    // Faculty mappings
    jdbc.update(
        "INSERT INTO faculty_account_mappings(timetable_faculty_id,user_id,status,notes) VALUES "
            + "('fac-vikrant-sharma','faculty-1','MAPPED','Verified matching faculty account'),"
            + "('fac-sachin-sharma',null,'UNMAPPED','Pending account registration'),"
            + "('fac-akash-chauhan',null,'AMBIGUOUS','Multiple faculty matches with surname Chauhan'),"
            + "('fac-rahul-chauhan',null,'AMBIGUOUS','Multiple faculty matches with surname Chauhan')");
  }

  private void insertUser(String id, String name, String email, String role, String dept, String empId) {
    jdbc.update(
        "INSERT INTO users(id,full_name,email,password_hash,role,campus_id,department,status,active,employee_id) "
            + "VALUES (?,?,?,?,?,'gehu-hill-dehradun',?,'ACTIVE',true,?)",
        id,
        name,
        email,
        auth.passwordHash("password-2026"),
        role,
        dept,
        empId);
    jdbc.update(
        "INSERT INTO user_roles(id,user_id,role,department,scope_type) VALUES (?,?,?,?,?)",
        UUID.randomUUID().toString(),
        id,
        role,
        dept,
        role.equals("ADMIN") ? "INSTITUTION" : "DEPARTMENT");
  }

  private record SessionInfo(Cookie cookie, String csrf) {}

  private SessionInfo login(String email, String password) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("email", email, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    Cookie sessionCookie = result.getResponse().getCookie(AuthService.COOKIE);
    JsonNode body = json.readTree(result.getResponse().getContentAsString());
    return new SessionInfo(sessionCookie, body.get("csrf_token").asText());
  }

  @Test
  void testRoomInventoryAndVerification() throws Exception {
    var admin = login("admin@test.local", "password-2026");

    // List rooms and check verified attribute
    MvcResult result = mvc.perform(get("/api/rooms").cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode rooms = json.readTree(result.getResponse().getContentAsString());
    assertThat(rooms.size()).isEqualTo(5);

    JsonNode cr404 = null;
    for (JsonNode r : rooms) {
      if ("CR 404".equals(r.get("code").asText())) {
        cr404 = r;
      }
    }
    assertThat(cr404).isNotNull();
    assertThat(cr404.get("verified").asBoolean()).isFalse();

    // Patch verification status of CR 404
    mvc.perform(
            patch("/api/rooms/room-cr404/verification")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "verified", true,
                    "capacity", 65,
                    "floor", "Fourth floor",
                    "has_ac", true))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(true))
        .andExpect(jsonPath("$.capacity").value(65))
        .andExpect(jsonPath("$.has_ac").value(true));
  }

  @Test
  void testTemporaryRoomClosureBlocksAvailabilityAndBooking() throws Exception {
    var admin = login("admin@test.local", "password-2026");
    var faculty = login("vikrant@test.local", "password-2026");

    LocalDate tomorrow = LocalDate.now().plusDays(1);
    OffsetDateTime closureStart = tomorrow.atTime(10, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
    OffsetDateTime closureEnd = tomorrow.atTime(14, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime();

    // Admin creates temporary closure on CR 104
    mvc.perform(
            post("/api/rooms/room-cr104/closures")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "closure_type", "TEMPORARY",
                    "reason", "Electrical maintenance in CS Block",
                    "starts_at", closureStart.toString(),
                    "ends_at", closureEnd.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.closure_type").value("TEMPORARY"))
        .andExpect(jsonPath("$.active").value(true));

    // Availability search for tomorrow 11:00 to 12:00 must show OCCUPIED
    mvc.perform(
            get("/api/availability")
                .cookie(faculty.cookie())
                .param("date", tomorrow.toString())
                .param("start_time", "11:00")
                .param("end_time", "12:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[?(@.code=='CR 104')].state").value("OCCUPIED"));

    // Submitting a booking request during the closure window must fail with 409 Conflict
    mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-cr104",
                    "date", tomorrow.toString(),
                    "start_time", "11:00",
                    "end_time", "12:00",
                    "purpose", "Remedial algorithms session",
                    "attendance", 40))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("The room is closed during this time window")));
  }

  @Test
  void testTemporaryRoomClosureSafelyCancelsExistingBookings() throws Exception {
    var admin = login("admin@test.local", "password-2026");
    var faculty = login("vikrant@test.local", "password-2026");
    var coord = login("coord@test.local", "password-2026");

    LocalDate day = LocalDate.now().plusDays(2);

    // 1. Faculty submits booking for CR 201
    MvcResult bookingResult = mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-cr201",
                    "date", day.toString(),
                    "start_time", "14:00",
                    "end_time", "16:00",
                    "purpose", "Hands-on Workshop",
                    "attendance", 45))))
        .andExpect(status().isCreated())
        .andReturn();
    String requestId = json.readTree(bookingResult.getResponse().getContentAsString()).get("id").asText();

    // 2. Coordinator approves booking
    mvc.perform(
            post("/api/requests/" + requestId + "/approve")
                .cookie(coord.cookie())
                .header("X-CSRF-Token", coord.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Approved by CSE coordinator"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    // 3. Coordinator/Admin creates temporary closure overlapping the approved booking
    OffsetDateTime closureStart = day.atTime(13, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
    OffsetDateTime closureEnd = day.atTime(17, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime();

    mvc.perform(
            post("/api/rooms/room-cr201/closures")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "closure_type", "TEMPORARY",
                    "reason", "Emergency repairs of projector mount",
                    "starts_at", closureStart.toString(),
                    "ends_at", closureEnd.toString()))))
        .andExpect(status().isOk());

    // 4. Verify request was safely CANCELLED
    mvc.perform(
            get("/api/requests/" + requestId)
                .cookie(faculty.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    // 5. Verify notification was recorded for faculty
    Integer notifCount = jdbc.queryForObject(
        "SELECT count(*) FROM notifications WHERE user_id='faculty-1' AND request_id=? AND message LIKE '%Emergency repairs%'",
        Integer.class, requestId);
    assertThat(notifCount).isGreaterThan(0);
  }

  @Test
  void testLiftTemporaryClosureRestoresRoom() throws Exception {
    var admin = login("admin@test.local", "password-2026");
    var faculty = login("vikrant@test.local", "password-2026");

    LocalDate tomorrow = LocalDate.now().plusDays(1);
    OffsetDateTime closureStart = tomorrow.atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
    OffsetDateTime closureEnd = tomorrow.atTime(12, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime();

    MvcResult closureRes = mvc.perform(
            post("/api/rooms/room-cr104/closures")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "closure_type", "TEMPORARY",
                    "reason", "Floor polishing",
                    "starts_at", closureStart.toString(),
                    "ends_at", closureEnd.toString()))))
        .andExpect(status().isOk())
        .andReturn();
    String closureId = json.readTree(closureRes.getResponse().getContentAsString()).get("id").asText();

    // Lift closure
    mvc.perform(
            delete("/api/rooms/room-cr104/closures/" + closureId)
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Closure lifted successfully."));

    // Verify room is available again
    mvc.perform(
            get("/api/availability")
                .cookie(faculty.cookie())
                .param("date", tomorrow.toString())
                .param("start_time", "09:00")
                .param("end_time", "10:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[?(@.code=='CR 104')].state").value("AVAILABLE"));
  }

  @Test
  void testPermanentRoomClosureDeactivatesRoom() throws Exception {
    var admin = login("admin@test.local", "password-2026");
    var faculty = login("vikrant@test.local", "password-2026");

    // Close room permanently
    mvc.perform(
            post("/api/rooms/room-cr404/closures")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "closure_type", "PERMANENT",
                    "reason", "Converted into Department Faculty Lounge"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.closure_type").value("PERMANENT"));

    // Check room active status
    Boolean isActive = jdbc.queryForObject("SELECT active FROM rooms WHERE id='room-cr404'", Boolean.class);
    assertThat(isActive).isFalse();

    // Verify room no longer appears in public active rooms
    MvcResult roomsRes = mvc.perform(get("/api/rooms").cookie(faculty.cookie()))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode activeRooms = json.readTree(roomsRes.getResponse().getContentAsString());
    assertThat(activeRooms.toString()).doesNotContain("CR 404");

    // Submitting a booking request returns 404
    mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-cr404",
                    "date", LocalDate.now().plusDays(1).toString(),
                    "start_time", "10:00",
                    "end_time", "11:00",
                    "purpose", "Tutorial class",
                    "attendance", 30))))
        .andExpect(status().isNotFound());
  }

  @Test
  void testFacultyAccountMappingManagement() throws Exception {
    var admin = login("admin@test.local", "password-2026");

    // List faculty mappings
    MvcResult mappingsRes = mvc.perform(
            get("/api/admin/pilot/faculty-mappings")
                .cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode list = json.readTree(mappingsRes.getResponse().getContentAsString());
    assertThat(list.size()).isEqualTo(4);

    // Filter by AMBIGUOUS
    mvc.perform(
            get("/api/admin/pilot/faculty-mappings?status=AMBIGUOUS")
                .cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));

    // Update UNMAPPED fac-sachin-sharma to MAPPED with faculty-2
    mvc.perform(
            post("/api/admin/pilot/faculty-mappings/fac-sachin-sharma")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "user_id", "faculty-2",
                    "status", "MAPPED",
                    "notes", "Manually verified CSE faculty account"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("MAPPED"))
        .andExpect(jsonPath("$.user_id").value("faculty-2"));

    // Attempting to map to student-1 must fail with 400 Bad Request
    mvc.perform(
            post("/api/admin/pilot/faculty-mappings/fac-akash-chauhan")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "user_id", "student-1",
                    "status", "MAPPED",
                    "notes", "Invalid mapping attempt"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("User must hold a faculty, coordinator, or HOD role."));
  }

  @Test
  void testPilotVerificationReport() throws Exception {
    var admin = login("admin@test.local", "password-2026");
    var student = login("student@test.local", "password-2026");

    // Student should be forbidden from pilot verification metrics
    mvc.perform(
            get("/api/admin/pilot/verification")
                .cookie(student.cookie()))
        .andExpect(status().isForbidden());

    // Admin receives comprehensive pilot report
    MvcResult reportRes = mvc.perform(
            get("/api/admin/pilot/verification")
                .cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode report = json.readTree(reportRes.getResponse().getContentAsString());

    assertThat(report.has("room_summary")).isTrue();
    assertThat(report.get("room_summary").get("total_rooms").asInt()).isEqualTo(5);
    assertThat(report.get("room_summary").get("verified_rooms").asInt()).isEqualTo(4);
    assertThat(report.get("room_summary").get("unverified_rooms").asInt()).isEqualTo(1);

    assertThat(report.has("faculty_summary")).isTrue();
    assertThat(report.get("faculty_summary").get("total_timetable_faculty").asInt()).isEqualTo(4);
    assertThat(report.get("faculty_summary").get("mapped_count").asInt()).isEqualTo(1);
    assertThat(report.get("faculty_summary").get("ambiguous_count").asInt()).isEqualTo(2);

    assertThat(report.has("timetable_summary")).isTrue();
    assertThat(report.has("issues")).isTrue();
    assertThat(report.get("ready_for_pilot").asBoolean()).isTrue();
  }

  @TestConfiguration
  static class FixtureConfiguration {
    @Bean
    @Primary
    ScheduleFixture timetableFixture() {
      return new ScheduleFixture();
    }
  }

  static class ScheduleFixture implements TimetableLookup {
    volatile boolean complete = true;

    public List<BusyInterval> busy(String roomId, LocalDate date) {
      return List.of();
    }

    public boolean covered(String roomId, LocalDate date) {
      return complete;
    }
  }
}
