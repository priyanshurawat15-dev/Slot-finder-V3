package in.quickslot.validation;

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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
class SystemValidationPhase5Test {
  private static final String SCHEMA =
      "test_p5_" + UUID.randomUUID().toString().replace("-", "");
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

    // 1. Setup users across all 6 roles
    insertUser("admin-1", "Campus Administrator", "admin@test.local", "ADMIN", "CS", "EMP-ADM1");
    insertUser("hod-cs", "Dr. HOD CSE", "hodcs@test.local", "HOD", "CS", "EMP-HOD1");
    insertUser("hod-mech", "Dr. HOD MECH", "hodmech@test.local", "HOD", "MECH", "EMP-HODM");
    insertUser("coord-cs", "CSE Venue Coordinator", "coordcs@test.local", "COORDINATOR", "CS", "EMP-C1");
    insertUser("coord-mech", "MECH Venue Coordinator", "coordmech@test.local", "COORDINATOR", "MECH", "EMP-CM");
    insertUser("faculty-1", "Dr. Vikrant Sharma", "vikrant@test.local", "FACULTY", "CS", "EMP-FAC1");
    insertUser("faculty-2", "Dr. Sachin Sharma", "sachin@test.local", "FACULTY", "CS", "EMP-FAC2");
    insertUser("clubhead-1", "Codev Club Head", "clubhead@test.local", "CLUB_HEAD", "CS", "EMP-CH1");
    insertUser("student-1", "Aarav Gupta", "student@test.local", "STUDENT", "CS", "EMP-STU1");

    // 2. Setup department HODs
    jdbc.update(
        "INSERT INTO department_hods(id,department,campus_id,user_id,active) VALUES "
            + "('dh-cs','CS','gehu-hill-dehradun','hod-cs',true),"
            + "('dh-mech','MECH','gehu-hill-dehradun','hod-mech',true)");

    // 3. Setup rooms (CSE and MECH)
    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity,campus_id,department,active,verified,floor) VALUES "
            + "('room-cr104','CR 104','CLASSROOM','CS Block',60,'gehu-hill-dehradun','CS',true,true,'First floor'),"
            + "('room-cr201','CR 201','CLASSROOM','CS Block',60,'gehu-hill-dehradun','CS',true,true,'Second floor'),"
            + "('room-audi','AUD-01','AUDITORIUM','Main Administrative Block',500,'gehu-hill-dehradun','CS',true,true,'Ground floor'),"
            + "('room-mech','MECH-101','CLASSROOM','Mechanical Block',60,'gehu-hill-dehradun','MECH',true,true,'Ground floor'),"
            + "('room-unverified','CR 999','CLASSROOM','CS Block',60,'gehu-hill-dehradun','CS',true,false,'Fifth floor')");

    // 4. Setup allocations
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-cs','room-cr104')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-cs','room-cr201')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-cs','room-audi')");
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('coord-mech','room-mech')");

    // 5. Setup clubs (verified & unverified)
    jdbc.update(
        "INSERT INTO clubs(id,slug,name,summary,description,email,instagram,linkedin,campus_id,source_label,verified) VALUES "
            + "('club-codev','codev','Co-Dev Club','Technical Club','Coding and Open Source','codev@gehu.ac.in','insta','linkedin','gehu-hill-dehradun','CSE',true),"
            + "('club-unverified','unv','Unverified Hobby Club','Hobby','Music and Gaming','unv@gehu.ac.in','insta','linkedin','gehu-hill-dehradun','CSE',false)");

    jdbc.update("INSERT INTO club_managers(club_id,user_id) VALUES ('club-codev','clubhead-1')");
    jdbc.update("INSERT INTO club_managers(club_id,user_id) VALUES ('club-unverified','clubhead-1')");

    // 6. Setup timetable faculty & mappings
    jdbc.update(
        "INSERT INTO timetable_faculty(id,campus_id,name) VALUES "
            + "('fac-vikrant-sharma','gehu-hill-dehradun','Dr. Vikrant Sharma'),"
            + "('fac-sachin-sharma','gehu-hill-dehradun','Dr. Sachin Sharma'),"
            + "('fac-akash-chauhan','gehu-hill-dehradun','Mr. Akash Chauhan')");

    jdbc.update(
        "INSERT INTO faculty_account_mappings(timetable_faculty_id,user_id,status,notes) VALUES "
            + "('fac-vikrant-sharma','faculty-1','MAPPED','Verified CSE faculty account'),"
            + "('fac-sachin-sharma','faculty-2','MAPPED','Verified CSE faculty account'),"
            + "('fac-akash-chauhan',null,'AMBIGUOUS','Multiple faculty matches with surname Chauhan')");
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

  // =========================================================================
  // 1. Role-Based Login and Permission Verification
  // =========================================================================
  @Test
  void test1_RoleBasedLoginAndPermissions() throws Exception {
    // 1. Faculty
    var faculty = login("vikrant@test.local", "password-2026");
    mvc.perform(get("/api/auth/me").cookie(faculty.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.role").value("FACULTY"));

    // 2. Club Head
    var clubHead = login("clubhead@test.local", "password-2026");
    mvc.perform(get("/api/auth/me").cookie(clubHead.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.role").value("CLUB_HEAD"));

    // 3. Coordinator
    var coord = login("coordcs@test.local", "password-2026");
    mvc.perform(get("/api/auth/me").cookie(coord.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.role").value("COORDINATOR"));

    // 4. HOD
    var hod = login("hodcs@test.local", "password-2026");
    mvc.perform(get("/api/auth/me").cookie(hod.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.role").value("HOD"));

    // 5. Admin
    var admin = login("admin@test.local", "password-2026");
    mvc.perform(get("/api/auth/me").cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.role").value("ADMIN"));

    // 6. Student
    var student = login("student@test.local", "password-2026");
    mvc.perform(get("/api/auth/me").cookie(student.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.role").value("STUDENT"));
  }

  // =========================================================================
  // 2. Unauthorized Access & Self-Approval Prevention
  // =========================================================================
  @Test
  void test2_UnauthorizedAccessAndSelfApprovalPrevention() throws Exception {
    var student = login("student@test.local", "password-2026");
    var faculty = login("vikrant@test.local", "password-2026");
    var coordCs = login("coordcs@test.local", "password-2026");
    var coordMech = login("coordmech@test.local", "password-2026");
    var admin = login("admin@test.local", "password-2026");

    LocalDate tomorrow = LocalDate.now().plusDays(1);

    // 1. Direct unauthenticated request to admin API returns 401
    mvc.perform(get("/api/admin/accounts"))
        .andExpect(status().isUnauthorized());

    // 2. Student cannot submit room booking request (returns 403)
    mvc.perform(
            post("/api/requests")
                .cookie(student.cookie())
                .header("X-CSRF-Token", student.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-cr104",
                    "date", tomorrow.toString(),
                    "start_time", "10:00",
                    "end_time", "11:00",
                    "purpose", "Student Study Session",
                    "attendance", 10))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Students are not permitted to submit room booking requests."));

    // 3. Faculty cannot access admin account management or pilot metrics (returns 403)
    mvc.perform(get("/api/admin/accounts").cookie(faculty.cookie()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/pilot/verification").cookie(faculty.cookie()))
        .andExpect(status().isForbidden());

    // 4. Scope enforcement: MECH coordinator cannot approve CSE room request
    // Faculty submits request for room-cr104 (CSE room)
    MvcResult bookingRes = mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-cr104",
                    "date", tomorrow.toString(),
                    "start_time", "09:00",
                    "end_time", "10:00",
                    "purpose", "CSE Algorithms Review",
                    "attendance", 35))))
        .andExpect(status().isCreated())
        .andReturn();
    String cseRequestId = json.readTree(bookingRes.getResponse().getContentAsString()).get("id").asText();

    // MECH coordinator attempts to approve CSE request -> returns 403 Forbidden
    mvc.perform(
            post("/api/requests/" + cseRequestId + "/approve")
                .cookie(coordMech.cookie())
                .header("X-CSRF-Token", coordMech.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Unauthorized cross-department approval attempt"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("An independent authorized reviewer must decide this request."));

    // 5. Self-Approval Prevention:
    // Coordinator submits request for their own allocated room (room-cr104)
    MvcResult selfBookingRes = mvc.perform(
            post("/api/requests")
                .cookie(coordCs.cookie())
                .header("X-CSRF-Token", coordCs.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-cr104",
                    "date", tomorrow.toString(),
                    "start_time", "14:00",
                    "end_time", "15:00",
                    "purpose", "Coordinator Faculty Session",
                    "attendance", 25))))
        .andExpect(status().isCreated())
        .andReturn();
    String selfRequestId = json.readTree(selfBookingRes.getResponse().getContentAsString()).get("id").asText();

    // Coordinator attempts to self-approve their own request -> returns 403 Forbidden
    mvc.perform(
            post("/api/requests/" + selfRequestId + "/approve")
                .cookie(coordCs.cookie())
                .header("X-CSRF-Token", coordCs.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Self-approving my own request"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("An independent authorized reviewer must decide this request."));

    // 6. Account Deactivation & Session Invalidation:
    // Faculty-2 logs in and obtains active session
    var fac2Session = login("sachin@test.local", "password-2026");

    // Admin deactivates faculty-2
    mvc.perform(
            post("/api/admin/accounts/faculty-2/status")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("status", "DEACTIVATED"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));

    // Faculty-2's existing session must now be invalidated and rejected with 401
    mvc.perform(get("/api/auth/me").cookie(fac2Session.cookie()))
        .andExpect(status().isUnauthorized());

    // Faculty-2 attempting to login again must be rejected with 401
    mvc.perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", "sachin@test.local", "password", "password-2026"))))
        .andExpect(status().isUnauthorized());
  }

  // =========================================================================
  // 3. Multi-Tier Booking Workflow: Club Head -> Coordinator -> HOD -> Approved
  // =========================================================================
  @Test
  void test3_MultiTierBookingWorkflowAndAuditNotifications() throws Exception {
    var clubHead = login("clubhead@test.local", "password-2026");
    var coord = login("coordcs@test.local", "password-2026");
    var hod = login("hodcs@test.local", "password-2026");

    LocalDate eventDate = LocalDate.now().plusDays(3);

    // 1. Club Head requests AUD-01 for Codev Hackathon
    MvcResult requestRes = mvc.perform(
            post("/api/requests")
                .cookie(clubHead.cookie())
                .header("X-CSRF-Token", clubHead.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-audi",
                    "date", eventDate.toString(),
                    "start_time", "10:00",
                    "end_time", "16:00",
                    "purpose", "Annual Open-Source Codev Hackathon",
                    "attendance", 250,
                    "participant_group", "CSE and IT 2nd/3rd Year Students",
                    "required_equipment", "Projector, PA System, Extension boards",
                    "club_id", "club-codev"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.club_name").value("Co-Dev Club"))
        .andReturn();
    String reqId = json.readTree(requestRes.getResponse().getContentAsString()).get("id").asText();

    // 2. Coordinator reviews request, recognizes large-scale multi-year gathering, forwards to HOD
    mvc.perform(
            post("/api/requests/" + reqId + "/forward")
                .cookie(coord.cookie())
                .header("X-CSRF-Token", coord.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Forwarded to HOD CSE for department clearance of 250+ attendance"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FORWARDED"));

    // 3. Verify notification delivered to HOD
    Integer hodNotifs = jdbc.queryForObject(
        "SELECT count(*) FROM notifications WHERE user_id='hod-cs' AND request_id=? AND message LIKE '%forwarded%'",
        Integer.class, reqId);
    assertThat(hodNotifs).isGreaterThanOrEqualTo(1);

    // 4. HOD approves the forwarded request
    mvc.perform(
            post("/api/requests/" + reqId + "/approve")
                .cookie(hod.cookie())
                .header("X-CSRF-Token", hod.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Approved by Head of Department"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    // 5. Verify occupancy created in database with kind 'BOOKING'
    Integer occupancies = jdbc.queryForObject(
        "SELECT count(*) FROM occupancies WHERE source_id=? AND active=true AND kind='BOOKING'",
        Integer.class, reqId);
    assertThat(occupancies).isEqualTo(1);

    // 6. Verify audit history contains SUBMITTED, FORWARDED, APPROVED
    var decisions = jdbc.queryForList(
        "SELECT action, reason FROM decisions WHERE request_id=? ORDER BY created_at", reqId);
    assertThat(decisions).hasSize(3);
    assertThat(decisions.get(0).get("action")).isEqualTo("SUBMITTED");
    assertThat(decisions.get(1).get("action")).isEqualTo("FORWARDED");
    assertThat(decisions.get(2).get("action")).isEqualTo("APPROVED");

    // 7. Verify Club Head receives approval notification
    Integer clubNotifs = jdbc.queryForObject(
        "SELECT count(*) FROM notifications WHERE user_id='clubhead-1' AND request_id=? AND message LIKE '%approved%'",
        Integer.class, reqId);
    assertThat(clubNotifs).isGreaterThanOrEqualTo(1);

    // 8. Cancellation frees occupancy immediately
    mvc.perform(
            post("/api/requests/" + reqId + "/cancel")
                .cookie(clubHead.cookie())
                .header("X-CSRF-Token", clubHead.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Rescheduled due to university exams"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    Integer activeOccupancies = jdbc.queryForObject(
        "SELECT count(*) FROM occupancies WHERE source_id=? AND active=true",
        Integer.class, reqId);
    assertThat(activeOccupancies).isEqualTo(0);
  }

  // =========================================================================
  // 4. Duplicate & Simultaneous Booking Concurrency
  // =========================================================================
  @Test
  void test4_DuplicateAndConcurrentSimultaneousBookings() throws Exception {
    var faculty = login("vikrant@test.local", "password-2026");
    var coord = login("coordcs@test.local", "password-2026");

    LocalDate tomorrow = LocalDate.now().plusDays(1);

    // 1. Idempotency Key Duplicate Prevention
    String idempotencyKey = "unique-key-" + UUID.randomUUID();
    var payload = Map.of(
        "room_id", "room-cr104",
        "date", tomorrow.toString(),
        "start_time", "08:00",
        "end_time", "09:00",
        "purpose", "Data Structures Lab Briefing",
        "attendance", 40);

    MvcResult firstCall = mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isCreated())
        .andReturn();
    String firstId = json.readTree(firstCall.getResponse().getContentAsString()).get("id").asText();

    // Replay identical call with same Idempotency-Key
    MvcResult secondCall = mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isCreated())
        .andReturn();
    String secondId = json.readTree(secondCall.getResponse().getContentAsString()).get("id").asText();

    assertThat(secondId).isEqualTo(firstId);
    Integer countRequests = jdbc.queryForObject(
        "SELECT count(*) FROM booking_requests WHERE room_id='room-cr104' AND date=? AND start_time='08:00:00'",
        Integer.class, tomorrow);
    assertThat(countRequests).isEqualTo(1);

    // 2. Simultaneous Overlapping Booking Approval Race
    // Create two separate requests for overlapping time slots on room-cr201
    // Request A: 11:00 - 13:00
    // Request B: 12:00 - 14:00 (overlaps by 1 hour)
    MvcResult reqARes = mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-cr201",
                    "date", tomorrow.toString(),
                    "start_time", "11:00",
                    "end_time", "13:00",
                    "purpose", "Algorithms Class",
                    "attendance", 45))))
        .andExpect(status().isCreated())
        .andReturn();
    String reqA = json.readTree(reqARes.getResponse().getContentAsString()).get("id").asText();

    MvcResult reqBRes = mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-cr201",
                    "date", tomorrow.toString(),
                    "start_time", "12:00",
                    "end_time", "14:00",
                    "purpose", "Operating Systems Class",
                    "attendance", 45))))
        .andExpect(status().isCreated())
        .andReturn();
    String reqB = json.readTree(reqBRes.getResponse().getContentAsString()).get("id").asText();

    // Execute concurrent approval attempts using multithreaded executor
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch startGate = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger conflictCount = new AtomicInteger(0);

    Callable<Void> approveA = () -> {
      startGate.await();
      try {
        var res = mvc.perform(
            post("/api/requests/" + reqA + "/approve")
                .cookie(coord.cookie())
                .header("X-CSRF-Token", coord.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Concurrent approval A"))));
        if (res.andReturn().getResponse().getStatus() == 200) successCount.incrementAndGet();
        else if (res.andReturn().getResponse().getStatus() == 409) conflictCount.incrementAndGet();
      } catch (Exception ignored) {}
      return null;
    };

    Callable<Void> approveB = () -> {
      startGate.await();
      try {
        var res = mvc.perform(
            post("/api/requests/" + reqB + "/approve")
                .cookie(coord.cookie())
                .header("X-CSRF-Token", coord.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Concurrent approval B"))));
        if (res.andReturn().getResponse().getStatus() == 200) successCount.incrementAndGet();
        else if (res.andReturn().getResponse().getStatus() == 409) conflictCount.incrementAndGet();
      } catch (Exception ignored) {}
      return null;
    };

    var futureA = pool.submit(approveA);
    var futureB = pool.submit(approveB);
    startGate.countDown();
    futureA.get(10, TimeUnit.SECONDS);
    futureB.get(10, TimeUnit.SECONDS);
    pool.shutdown();

    // Exactly one must succeed, and the other must be rejected with 409 Conflict
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(conflictCount.get()).isEqualTo(1);

    // Verify database integrity: only 1 active occupancy exists for room-cr201
    Integer activeOcc = jdbc.queryForObject(
        "SELECT count(*) FROM occupancies WHERE room_id='room-cr201' AND active=true",
        Integer.class);
    assertThat(activeOcc).isEqualTo(1);
  }

  // =========================================================================
  // 5. CSE Pilot Data Validation & Room Closures
  // =========================================================================
  @Test
  void test5_CsePilotDataAndClosuresValidation() throws Exception {
    var admin = login("admin@test.local", "password-2026");
    var faculty = login("vikrant@test.local", "password-2026");

    LocalDate testDate = LocalDate.now().plusDays(2);

    // 1. Incomplete/unverified room CR 999 must NOT report confirmed availability
    fixture.complete = false; // Mock incomplete timetable coverage for unverified rooms
    mvc.perform(
            get("/api/availability")
                .cookie(faculty.cookie())
                .param("date", testDate.toString())
                .param("start_time", "10:00")
                .param("end_time", "11:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[?(@.code=='CR 999')].state").value("UNKNOWN"))
        .andExpect(jsonPath("$.rooms[?(@.code=='CR 999')].reason").value("All course timetables for this room have not yet been verified."));
    fixture.complete = true;

    // 2. Temporary closure blocks availability and creates OCCUPIED state with notice
    OffsetDateTime startClosure = testDate.atTime(12, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime();
    OffsetDateTime endClosure = testDate.atTime(15, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toOffsetDateTime();

    mvc.perform(
            post("/api/rooms/room-cr104/closures")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "closure_type", "TEMPORARY",
                    "reason", "Network Switch Upgrade",
                    "starts_at", startClosure.toString(),
                    "ends_at", endClosure.toString()))))
        .andExpect(status().isOk());

    mvc.perform(
            get("/api/availability")
                .cookie(faculty.cookie())
                .param("date", testDate.toString())
                .param("start_time", "13:00")
                .param("end_time", "14:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[?(@.code=='CR 104')].state").value("OCCUPIED"));

    // 3. Submitting booking during closure window returns 409
    mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-cr104",
                    "date", testDate.toString(),
                    "start_time", "13:00",
                    "end_time", "14:00",
                    "purpose", "Extra Lecture",
                    "attendance", 30))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("The room is closed during this time window")));

    // 4. Pilot Verification Metrics API verification
    mvc.perform(get("/api/admin/pilot/verification").cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.room_summary.total_rooms").value(5))
        .andExpect(jsonPath("$.room_summary.verified_rooms").value(4))
        .andExpect(jsonPath("$.room_summary.unverified_rooms").value(1))
        .andExpect(jsonPath("$.faculty_summary.ambiguous_count").value(1))
        .andExpect(jsonPath("$.ready_for_pilot").value(true));
  }

  // =========================================================================
  // 6. Complete End-to-End Real-World Lifecycle
  // =========================================================================
  @Test
  void test6_EndToEndLifeCycle() throws Exception {
    var faculty = login("vikrant@test.local", "password-2026");
    var coord = login("coordcs@test.local", "password-2026");

    LocalDate tomorrow = LocalDate.now().plusDays(1);

    // Step 1: Faculty searches availability for tomorrow 15:00 - 17:00
    mvc.perform(
            get("/api/availability")
                .cookie(faculty.cookie())
                .param("date", tomorrow.toString())
                .param("start_time", "15:00")
                .param("end_time", "17:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[?(@.code=='CR 104')].state").value("AVAILABLE"));

    // Step 2: Faculty submits booking request for CR 104
    MvcResult bookingRes = mvc.perform(
            post("/api/requests")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of(
                    "room_id", "room-cr104",
                    "date", tomorrow.toString(),
                    "start_time", "15:00",
                    "end_time", "17:00",
                    "purpose", "Advanced DBMS Seminar",
                    "attendance", 50,
                    "participant_group", "B.Tech CSE 5th Sem",
                    "required_equipment", "Projector & Microphone"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andReturn();
    String requestId = json.readTree(bookingRes.getResponse().getContentAsString()).get("id").asText();

    // Step 3: Venue coordinator reviews pending requests and approves
    mvc.perform(
            post("/api/requests/" + requestId + "/approve")
                .cookie(coord.cookie())
                .header("X-CSRF-Token", coord.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Approved by CSE Venue Coordinator"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    // Step 4: Availability check now reports OCCUPIED for CR 104
    mvc.perform(
            get("/api/availability")
                .cookie(faculty.cookie())
                .param("date", tomorrow.toString())
                .param("start_time", "15:00")
                .param("end_time", "17:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[?(@.code=='CR 104')].state").value("OCCUPIED"));

    // Step 5: Faculty checks in-app notifications
    mvc.perform(get("/api/notifications").cookie(faculty.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.request_id=='" + requestId + "')].message")
            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("approved"))));

    // Step 6: Faculty inspects single request endpoint
    mvc.perform(get("/api/requests/" + requestId).cookie(faculty.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(requestId))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.history.length()").value(2));

    // Step 7: Faculty cancels booking -> Room availability restored
    mvc.perform(
            post("/api/requests/" + requestId + "/cancel")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("reason", "Seminar moved online"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    // Step 8: Room availability returns to AVAILABLE
    mvc.perform(
            get("/api/availability")
                .cookie(faculty.cookie())
                .param("date", tomorrow.toString())
                .param("start_time", "15:00")
                .param("end_time", "17:00"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rooms[?(@.code=='CR 104')].state").value("AVAILABLE"));
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
