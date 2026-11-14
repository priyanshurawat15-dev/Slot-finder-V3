package in.quickslot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.quickslot.availability.BusyInterval;
import in.quickslot.availability.TimetableLookup;
import in.quickslot.identity.AuthService;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.time.LocalDate;
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
class SecurityPhase1Test {
  private static final String SCHEMA =
      "test_sec_" + UUID.randomUUID().toString().replace("-", "");
  private static final String URL = System.getenv("TEST_DATABASE_URL");
  private static final String USER =
      System.getenv().getOrDefault("TEST_DATABASE_USER", "quickslot");
  private static final String PASSWORD =
      System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "quickslot");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired AuthService auth;
  @Autowired Fixture schedule;

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
    jdbc.execute("TRUNCATE users,rooms,timetable_faculty CASCADE");
    jdbc.execute("TRUNCATE login_attempts");
    schedule.complete = true;

    for (String role : List.of("STUDENT", "FACULTY", "CLUB_HEAD", "COORDINATOR", "HOD", "ADMIN")) {
      jdbc.update(
          "INSERT INTO users(id,full_name,email,password_hash,role,campus_id,department) VALUES (?,?,?,?,?,'gehu-hill-dehradun','CS')",
          role,
          role,
          role.toLowerCase(Locale.ROOT) + "@test.local",
          auth.passwordHash("password-2026"),
          role);
    }

    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity,campus_id,department,active) VALUES "
            + "('room-101','101','CLASSROOM','Aryabhata Block',60,'gehu-hill-dehradun','CS',true),"
            + "('room-202','202','CLASSROOM','Aryabhata Block',60,'gehu-hill-dehradun','CS',true)");

    // Only coordinator is allocated to room-101; nobody is allocated to room-202
    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('COORDINATOR','room-101')");

    // Add faculty for timetable lookup
    jdbc.update(
        "INSERT INTO timetable_faculty(id,campus_id,name) VALUES ('fac-1','gehu-hill-dehradun','Dr. Alok Verma') ON CONFLICT DO NOTHING");
  }

  record Login(Cookie cookie, String csrf) {}

  private Login login(String role) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "email",
                                role.toLowerCase(Locale.ROOT) + "@test.local",
                                "password",
                                "password-2026"))))
            .andExpect(status().isOk())
            .andReturn();
    return new Login(
        result.getResponse().getCookie(AuthService.COOKIE),
        json.readTree(result.getResponse().getContentAsString()).get("csrf_token").asText());
  }

  private Map<String, Object> bookingPayload(String roomId) {
    return Map.of(
        "room_id", roomId,
        "date", LocalDate.now().plusDays(2).toString(),
        "start_time", "10:00",
        "end_time", "11:00",
        "purpose", "Technical Seminar on Operating Systems",
        "attendance", 40);
  }

  // TASK 1: Remove Demo Role Switching
  @Test
  void demoRoleSwitchingEndpointIsPermanentlyDisabled() throws Exception {
    var student = login("STUDENT");
    mvc.perform(
            post("/api/auth/switch-demo-role")
                .param("role", "ADMIN")
                .cookie(student.cookie())
                .header("X-CSRF-Token", student.csrf()))
        .andExpect(status().isNotFound());

    // Anonymous call must also fail with 404
    mvc.perform(post("/api/auth/switch-demo-role").param("role", "ADMIN"))
        .andExpect(status().isNotFound());
  }

  // TASK 2: Secure User Listing API
  @Test
  void userListingRequiresAdminAndExcludesSensitiveCredentials() throws Exception {
    // 1. Anonymous must get 401
    mvc.perform(get("/api/auth/users")).andExpect(status().isUnauthorized());

    // 2. Student must get 403
    var student = login("STUDENT");
    mvc.perform(get("/api/auth/users").cookie(student.cookie())).andExpect(status().isForbidden());

    // 3. Faculty must get 403
    var faculty = login("FACULTY");
    mvc.perform(get("/api/auth/users").cookie(faculty.cookie())).andExpect(status().isForbidden());

    // 4. Coordinator must get 403
    var coordinator = login("COORDINATOR");
    mvc.perform(get("/api/auth/users").cookie(coordinator.cookie())).andExpect(status().isForbidden());

    // 5. Admin succeeds and response contains no sensitive fields
    var admin = login("ADMIN");
    MvcResult result =
        mvc.perform(get("/api/auth/users").cookie(admin.cookie()))
            .andExpect(status().isOk())
            .andReturn();

    String content = result.getResponse().getContentAsString();
    assertThat(content).doesNotContain("password_hash");
    assertThat(content).doesNotContain("token_hash");
    assertThat(content).doesNotContain("csrf_token");
    assertThat(content).contains("full_name");
    assertThat(content).contains("email");
    assertThat(content).contains("role");
  }

  // TASK 3: Secure Admin Overview API
  @Test
  void adminOverviewRequiresAdminAndReturnsLiveStats() throws Exception {
    // 1. Anonymous must get 401
    mvc.perform(get("/api/admin/overview-summary")).andExpect(status().isUnauthorized());

    // 2. Student, Faculty, Coordinator, HOD must get 403
    for (String role : List.of("STUDENT", "FACULTY", "COORDINATOR", "HOD")) {
      var user = login(role);
      mvc.perform(get("/api/admin/overview-summary").cookie(user.cookie()))
          .andExpect(status().isForbidden());
    }

    // 3. Admin gets 200 with live DB counts
    var admin = login("ADMIN");
    mvc.perform(get("/api/admin/overview-summary").cookie(admin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_venues").value(2))
        .andExpect(jsonPath("$.pending_requests").value(0))
        .andExpect(jsonPath("$.confirmed_bookings").value(0))
        .andExpect(jsonPath("$.institution").value("Graphic Era Hill University, Dehradun"));
  }

  // TASK 4: Enforce Student Booking Restrictions
  @Test
  void studentsCannotSubmitBookingRequests() throws Exception {
    var student = login("STUDENT");
    mvc.perform(
            post("/api/requests")
                .cookie(student.cookie())
                .header("X-CSRF-Token", student.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(bookingPayload("room-101"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Students are not permitted to submit room booking requests."));
  }

  @Test
  void authorizedRolesCanSubmitBookingRequests() throws Exception {
    for (String role : List.of("FACULTY", "CLUB_HEAD", "COORDINATOR", "HOD")) {
      var user = login(role);
      mvc.perform(
              post("/api/requests")
                  .cookie(user.cookie())
                  .header("X-CSRF-Token", user.csrf())
                  .contentType("application/json")
                  .content(json.writeValueAsString(bookingPayload("room-101"))))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("PENDING"));
    }
  }

  // TASK 5: Correct Admin Approval Permissions & Delegation
  @Test
  void requesterCannotApproveOwnRequest() throws Exception {
    var coordinator = login("COORDINATOR");
    MvcResult submitRes =
        mvc.perform(
                post("/api/requests")
                    .cookie(coordinator.cookie())
                    .header("X-CSRF-Token", coordinator.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(bookingPayload("room-101"))))
            .andExpect(status().isCreated())
            .andReturn();
    String requestId = json.readTree(submitRes.getResponse().getContentAsString()).get("id").asText();

    // Coordinator tries to approve their own request -> 403 Forbidden
    mvc.perform(
            post("/api/requests/" + requestId + "/approve")
                .cookie(coordinator.cookie())
                .header("X-CSRF-Token", coordinator.csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("An independent authorized reviewer must decide this request."));
  }

  @Test
  void adminCannotApproveRoomUnlessAllocated() throws Exception {
    var faculty = login("FACULTY");
    MvcResult submitRes =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(bookingPayload("room-101"))))
            .andExpect(status().isCreated())
            .andReturn();
    String requestId = json.readTree(submitRes.getResponse().getContentAsString()).get("id").asText();

    // Admin is NOT allocated to room-101 -> 403 Forbidden
    var admin = login("ADMIN");
    mvc.perform(
            post("/api/requests/" + requestId + "/approve")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf()))
        .andExpect(status().isForbidden());

    // Allocated coordinator CAN approve
    var coordinator = login("COORDINATOR");
    mvc.perform(
            post("/api/requests/" + requestId + "/approve")
                .cookie(coordinator.cookie())
                .header("X-CSRF-Token", coordinator.csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    // Verify audit log has coordinator as actor
    Integer audits =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE action='REQUEST_APPROVED' AND actor_id='COORDINATOR'",
            Integer.class);
    assertThat(audits).isEqualTo(1);
  }

  @Test
  void coordinatorCanForwardToHodAndHodCanDecide() throws Exception {
    var faculty = login("FACULTY");
    MvcResult submitRes =
        mvc.perform(
                post("/api/requests")
                    .cookie(faculty.cookie())
                    .header("X-CSRF-Token", faculty.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(bookingPayload("room-101"))))
            .andExpect(status().isCreated())
            .andReturn();
    String requestId = json.readTree(submitRes.getResponse().getContentAsString()).get("id").asText();

    var coordinator = login("COORDINATOR");
    // Forwarding requires a reason
    mvc.perform(
            post("/api/requests/" + requestId + "/forward")
                .cookie(coordinator.cookie())
                .header("X-CSRF-Token", coordinator.csrf())
                .contentType("application/json")
                .content("{\"reason\":\"\"}"))
        .andExpect(status().isUnprocessableEntity());

    // Forwarding succeeds with reason
    mvc.perform(
            post("/api/requests/" + requestId + "/forward")
                .cookie(coordinator.cookie())
                .header("X-CSRF-Token", coordinator.csrf())
                .contentType("application/json")
                .content("{\"reason\":\"Special event needs HOD approval\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FORWARDED"));

    // Now that it's forwarded, coordinator cannot approve it (requires HOD)
    mvc.perform(
            post("/api/requests/" + requestId + "/approve")
                .cookie(coordinator.cookie())
                .header("X-CSRF-Token", coordinator.csrf()))
        .andExpect(status().isForbidden());

    // HOD approves
    var hod = login("HOD");
    mvc.perform(
            post("/api/requests/" + requestId + "/approve")
                .cookie(hod.cookie())
                .header("X-CSRF-Token", hod.csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    // Verify HOD audit event
    Integer audits =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE action='REQUEST_APPROVED' AND actor_id='HOD'",
            Integer.class);
    assertThat(audits).isEqualTo(1);
  }

  // TASK 6: Secure Faculty Timetable Lookup
  @Test
  void facultyTimetableLookupRequiresAuthenticationAndAllowsStudents() throws Exception {
    // 1. Anonymous is rejected with 401
    mvc.perform(get("/api/timetables/teachers")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/timetables/teachers/fac-1/schedule").param("date", LocalDate.now().toString()))
        .andExpect(status().isUnauthorized());

    // 2. Authenticated student is allowed to view teachers
    var student = login("STUDENT");
    mvc.perform(get("/api/timetables/teachers").cookie(student.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Dr. Alok Verma"));

    // 3. Authenticated student is allowed to view teacher schedule
    mvc.perform(
            get("/api/timetables/teachers/fac-1/schedule")
                .cookie(student.cookie())
                .param("date", LocalDate.now().plusDays(1).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teacher.name").value("Dr. Alok Verma"))
        .andExpect(jsonPath("$.free_periods").isArray())
        .andExpect(jsonPath("$.notice").exists());
  }

  @TestConfiguration
  static class FixtureConfig {
    @Bean
    @Primary
    Fixture fixture() {
      return new Fixture();
    }
  }

  static class Fixture implements TimetableLookup {
    volatile boolean complete = true;

    public List<BusyInterval> busy(String roomId, LocalDate date) {
      return List.of();
    }

    public boolean covered(String roomId, LocalDate date) {
      return complete;
    }
  }
}
