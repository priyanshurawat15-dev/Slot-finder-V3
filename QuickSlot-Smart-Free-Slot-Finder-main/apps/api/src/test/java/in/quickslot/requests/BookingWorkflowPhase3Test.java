package in.quickslot.requests;

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
class BookingWorkflowPhase3Test {
  private static final String SCHEMA =
      "test_p3_" + UUID.randomUUID().toString().replace("-", "");
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
    fixture.complete = false;
    jdbc.execute(
        "TRUNCATE users,rooms,allocations,user_roles,department_hods,invitations,audit_events,"
            + "booking_requests,decisions,occupancies,notifications,idempotency_records,clubs,club_managers,club_memberships CASCADE");
    jdbc.execute("TRUNCATE login_attempts");

    // Setup users
    insertUser("faculty-1", "Prof. CSE Faculty", "faculty@test.local", "FACULTY", "CS", "EMP-FAC1");
    insertUser("faculty-mech", "Prof. Mech Faculty", "facmech@test.local", "FACULTY", "MECH", "EMP-FACM");
    insertUser("coord-101", "Coordinator 101", "coord101@test.local", "COORDINATOR", "CS", "EMP-C101");
    insertUser("coord-202", "Coordinator 202", "coord202@test.local", "COORDINATOR", "CS", "EMP-C202");
    insertUser("hod-cs", "Dr. HOD CSE", "hodcs@test.local", "HOD", "CS", "EMP-HODCS");
    insertUser("hod-mech", "Dr. HOD MECH", "hodmech@test.local", "HOD", "MECH", "EMP-HODMECH");
    insertUser("club-head-1", "Club Head Codev", "clubhead@test.local", "CLUB_HEAD", "CS", "EMP-CH1");
    insertUser("student-1", "Student One", "student@test.local", "STUDENT", "CS", "EMP-STU1");
    insertUser("admin-1", "Campus Admin", "admin@test.local", "ADMIN", "CS", "EMP-ADM1");

    // Setup department HODs
    jdbc.update(
        "INSERT INTO department_hods(id,department,campus_id,user_id,active) VALUES "
            + "('dh-cs','CS','gehu-hill-dehradun','hod-cs',true),"
            + "('dh-mech','MECH','gehu-hill-dehradun','hod-mech',true)");

    // Setup rooms
    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity,campus_id,department,active) VALUES "
            + "('room-101','101','CLASSROOM','Aryabhata Block',60,'gehu-hill-dehradun','CS',true),"
            + "('room-202','202','CLASSROOM','Aryabhata Block',60,'gehu-hill-dehradun','CS',true),"
            + "('room-mech','M-101','CLASSROOM','Mechanical Block',60,'gehu-hill-dehradun','MECH',true)");

    // Setup allocations
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-101','room-101')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-202','room-202')");

    // Setup clubs
    jdbc.update(
        "INSERT INTO clubs(id,slug,name,summary,description,email,instagram,linkedin,campus_id,source_label,verified) VALUES "
            + "('club-1','codev','Co-Dev Club','Technical Club','Coding and open source','codev@gehu.ac.in','insta','linkedin','gehu-hill-dehradun','Label',true),"
            + "('club-2','unverified-club','Unverified Club','Pending club','Not verified yet','unv@gehu.ac.in','insta','linkedin','gehu-hill-dehradun','Label',false),"
            + "('club-3','robotics','Robotics Club','Hardware Club','Robotics and IoT','robotics@gehu.ac.in','insta','linkedin','gehu-hill-dehradun','Label',true)");

    // Club managers: club-1 and club-2 managed by club-head-1; club-3 managed by faculty-1
    jdbc.update("INSERT INTO club_managers(club_id,user_id) VALUES ('club-1','club-head-1')");
    jdbc.update("INSERT INTO club_managers(club_id,user_id) VALUES ('club-2','club-head-1')");
    jdbc.update("INSERT INTO club_managers(club_id,user_id) VALUES ('club-3','faculty-1')");
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

  record Login(Cookie cookie, String csrf) {}

  private Login login(String email, String password) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    return new Login(
        result.getResponse().getCookie(AuthService.COOKIE),
        json.readTree(result.getResponse().getContentAsString()).get("csrf_token").asText());
  }

  private LocalDate tomorrow() {
    return LocalDate.now().plusDays(1);
  }

  @Test
  void facultyCanSubmitBookingRequest() throws Exception {
    Login faculty = login("faculty@test.local", "password-2026");

    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "10:00:00",
        "end_time", "11:00:00",
        "purpose", "CS301 Remedial Class",
        "attendance", 40,
        "participant_group", "3rd Year CSE",
        "required_equipment", "Projector");

    MvcResult res =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.room_code").value("101"))
            .andExpect(jsonPath("$.requester_name").value("Prof. CSE Faculty"))
            .andReturn();

    JsonNode node = json.readTree(res.getResponse().getContentAsString());
    String requestId = node.get("id").asText();

    // Check coordinator received notification
    Integer notifCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id='coord-101' AND request_id=?",
            Integer.class,
            requestId);
    assertThat(notifCount).isEqualTo(1);
  }

  @Test
  void verifiedClubHeadCanSubmitBookingForManagedClub() throws Exception {
    Login clubHead = login("clubhead@test.local", "password-2026");

    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "14:00:00",
        "end_time", "16:00:00",
        "purpose", "Co-Dev Hackathon Briefing",
        "attendance", 50,
        "club_id", "club-1");

    mvc.perform(
            post("/api/requests")
                .cookie(clubHead.cookie())
                .header("X-CSRF-Token", clubHead.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.club_id").value("club-1"))
        .andExpect(jsonPath("$.club_name").value("Co-Dev Club"));
  }

  @Test
  void unverifiedClubBookingIsRejected() throws Exception {
    Login clubHead = login("clubhead@test.local", "password-2026");

    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "14:00:00",
        "end_time", "16:00:00",
        "purpose", "Unverified Club Meeting",
        "attendance", 20,
        "club_id", "club-2");

    mvc.perform(
            post("/api/requests")
                .cookie(clubHead.cookie())
                .header("X-CSRF-Token", clubHead.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("This club is not verified for room bookings."));
  }

  @Test
  void clubHeadCannotBookForUnassignedClub() throws Exception {
    Login clubHead = login("clubhead@test.local", "password-2026");

    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "14:00:00",
        "end_time", "16:00:00",
        "purpose", "Robotics Club Workshop",
        "attendance", 30,
        "club_id", "club-3");

    mvc.perform(
            post("/api/requests")
                .cookie(clubHead.cookie())
                .header("X-CSRF-Token", clubHead.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("You are not an authorized manager for this club."));
  }

  @Test
  void studentCannotSubmitBooking() throws Exception {
    Login student = login("student@test.local", "password-2026");

    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "10:00:00",
        "end_time", "11:00:00",
        "purpose", "Study Group",
        "attendance", 5);

    mvc.perform(
            post("/api/requests")
                .cookie(student.cookie())
                .header("X-CSRF-Token", student.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isForbidden());
  }

  @Test
  void coordinatorCanApproveWithinScope() throws Exception {
    Login faculty = login("faculty@test.local", "password-2026");
    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "10:00:00",
        "end_time", "11:00:00",
        "purpose", "CS301 Remedial Class",
        "attendance", 40);

    MvcResult res =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

    fixture.complete = true;
    Login coord101 = login("coord101@test.local", "password-2026");

    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(coord101.cookie())
                .header("X-CSRF-Token", coord101.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Approved by coordinator"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    // Check occupancy was created
    Integer occupancies =
        jdbc.queryForObject(
            "SELECT count(*) FROM occupancies WHERE source_id=? AND active=true AND kind='BOOKING'",
            Integer.class,
            reqId);
    assertThat(occupancies).isEqualTo(1);

    // Requester received approval notification
    Integer requesterNotif =
        jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id='faculty-1' AND request_id=?",
            Integer.class,
            reqId);
    assertThat(requesterNotif).isGreaterThanOrEqualTo(1);
  }

  @Test
  void coordinatorCannotApproveOutOfScope() throws Exception {
    Login faculty = login("faculty@test.local", "password-2026");
    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "10:00:00",
        "end_time", "11:00:00",
        "purpose", "CS301 Remedial Class",
        "attendance", 40);

    MvcResult res =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

    fixture.complete = true;
    Login coord202 = login("coord202@test.local", "password-2026");

    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(coord202.cookie())
                .header("X-CSRF-Token", coord202.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Trying to approve 101"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void coordinatorCanForwardToDepartmentHodAndHodCanApprove() throws Exception {
    Login faculty = login("faculty@test.local", "password-2026");
    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "10:00:00",
        "end_time", "11:00:00",
        "purpose", "CS301 Special Lab Exam",
        "attendance", 45);

    MvcResult res =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

    // Coordinator forwards to HOD
    Login coord101 = login("coord101@test.local", "password-2026");
    mvc.perform(
            post("/api/requests/" + reqId + "/forward")
                .cookie(coord101.cookie())
                .header("X-CSRF-Token", coord101.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Needs HOD approval for lab exam"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FORWARDED"));

    // Verify HOD CS received notification
    Integer hodNotif =
        jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id='hod-cs' AND request_id=?",
            Integer.class,
            reqId);
    assertThat(hodNotif).isEqualTo(1);

    // HOD MECH cannot approve CSE forwarded request
    Login hodMech = login("hodmech@test.local", "password-2026");
    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(hodMech.cookie())
                .header("X-CSRF-Token", hodMech.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Cross dept attempt"))))
        .andExpect(status().isForbidden());

    // HOD CS approves
    fixture.complete = true;
    Login hodCs = login("hodcs@test.local", "password-2026");
    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(hodCs.cookie())
                .header("X-CSRF-Token", hodCs.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Approved by CSE HOD"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  void selfApprovalIsBlocked() throws Exception {
    Login coord101 = login("coord101@test.local", "password-2026");

    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "10:00:00",
        "end_time", "11:00:00",
        "purpose", "Coordinator Personal Session",
        "attendance", 10);

    MvcResult res =
        mvc.perform(
                post("/api/requests")
                    .cookie(coord101.cookie())
                    .header("X-CSRF-Token", coord101.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

    fixture.complete = true;
    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(coord101.cookie())
                .header("X-CSRF-Token", coord101.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Self approve attempt"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("An independent authorized reviewer must decide this request."));
  }

  @Test
  void simultaneousBookingOverlapConflictAndCancellation() throws Exception {
    Login faculty = login("faculty@test.local", "password-2026");

    // Request 1: 10:00 to 12:00
    var body1 = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "10:00:00",
        "end_time", "12:00:00",
        "purpose", "Session A",
        "attendance", 30);
    MvcResult res1 =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body1)))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId1 = json.readTree(res1.getResponse().getContentAsString()).get("id").asText();

    // Request 2: 11:00 to 13:00 (overlaps with 10:00-12:00)
    var body2 = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "11:00:00",
        "end_time", "13:00:00",
        "purpose", "Session B",
        "attendance", 30);
    MvcResult res2 =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body2)))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId2 = json.readTree(res2.getResponse().getContentAsString()).get("id").asText();

    fixture.complete = true;
    Login coord101 = login("coord101@test.local", "password-2026");

    // Approve Request 1
    mvc.perform(
            post("/api/requests/" + reqId1 + "/approve")
                .cookie(coord101.cookie())
                .header("X-CSRF-Token", coord101.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Approved 1"))))
        .andExpect(status().isOk());

    // Approving overlapping Request 2 must fail with 409
    mvc.perform(
            post("/api/requests/" + reqId2 + "/approve")
                .cookie(coord101.cookie())
                .header("X-CSRF-Token", coord101.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Approved 2"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("The room is occupied or booked during this time window."));

    // Cancel Request 1
    mvc.perform(
            post("/api/requests/" + reqId1 + "/cancel")
                .cookie(coord101.cookie())
                .header("X-CSRF-Token", coord101.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Cancelled session A"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    // Now Request 2 can be approved
    mvc.perform(
            post("/api/requests/" + reqId2 + "/approve")
                .cookie(coord101.cookie())
                .header("X-CSRF-Token", coord101.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Approved 2 after cancel"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  void adminCanMonitorAllCampusRequestsButCannotDirectlyDecideWithoutAllocation() throws Exception {
    Login faculty = login("faculty@test.local", "password-2026");
    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "10:00:00",
        "end_time", "11:00:00",
        "purpose", "CS301 Remedial Class",
        "attendance", 40);

    MvcResult res =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    String reqId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

    Login admin = login("admin@test.local", "password-2026");

    // Admin can monitor all campus requests
    mvc.perform(
            get("/api/requests")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + reqId + "')]").exists());

    // Admin cannot approve directly if not allocated coordinator
    fixture.complete = true;
    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Admin bypass attempt"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void notificationsMarkAsReadAndReadAll() throws Exception {
    Login faculty = login("faculty@test.local", "password-2026");
    var body = Map.of(
        "room_id", "room-101",
        "date", tomorrow().toString(),
        "start_time", "10:00:00",
        "end_time", "11:00:00",
        "purpose", "CS301 Remedial Class",
        "attendance", 40);

    mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isCreated());

    Login coord101 = login("coord101@test.local", "password-2026");

    MvcResult listRes =
        mvc.perform(
                get("/api/notifications")
                    .cookie(coord101.cookie())
                    .header("X-CSRF-Token", coord101.csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].is_read").value(false))
            .andReturn();

    String notifId = json.readTree(listRes.getResponse().getContentAsString()).get(0).get("id").asText();

    // Mark single notification read
    mvc.perform(
            post("/api/notifications/" + notifId + "/read")
                .cookie(coord101.cookie())
                .header("X-CSRF-Token", coord101.csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));

    // Check list reflects is_read = true
    mvc.perform(
            get("/api/notifications")
                .cookie(coord101.cookie())
                .header("X-CSRF-Token", coord101.csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].is_read").value(true));

    // Test mark all read
    mvc.perform(
            post("/api/notifications/read-all")
                .cookie(coord101.cookie())
                .header("X-CSRF-Token", coord101.csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
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
    volatile boolean complete;

    public List<BusyInterval> busy(String roomId, LocalDate date) {
      return List.of();
    }

    public boolean covered(String roomId, LocalDate date) {
      return complete;
    }
  }
}
