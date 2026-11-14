package in.quickslot.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.quickslot.identity.AuthService;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
class AccountManagementPhase2Test {
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
    jdbc.execute("TRUNCATE users,rooms,allocations,user_roles,department_hods,invitations,audit_events CASCADE");
    jdbc.execute("TRUNCATE login_attempts");

    for (String role : List.of("STUDENT", "FACULTY", "COORDINATOR", "HOD", "ADMIN")) {
      String id = role;
      jdbc.update(
          "INSERT INTO users(id,full_name,email,password_hash,role,campus_id,department,status,active,employee_id) "
              + "VALUES (?,?,?,?,?,'gehu-hill-dehradun','CS','ACTIVE',true,?)",
          id,
          role + " User",
          role.toLowerCase(Locale.ROOT) + "@test.local",
          auth.passwordHash("password-2026"),
          role,
          "EMP-" + role);
      jdbc.update(
          "INSERT INTO user_roles(id,user_id,role,department,scope_type) VALUES (?,?,?,?,?)",
          UUID.randomUUID().toString(),
          id,
          role,
          "CS",
          role.equals("ADMIN") ? "INSTITUTION" : "DEPARTMENT");
    }

    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity,campus_id,department,active) VALUES "
            + "('room-101','101','CLASSROOM','Aryabhata Block',60,'gehu-hill-dehradun','CS',true),"
            + "('room-202','202','CLASSROOM','Aryabhata Block',60,'gehu-hill-dehradun','CS',true)");

    jdbc.update("INSERT INTO allocations(user_id,room_id) VALUES ('COORDINATOR','room-101')");
    jdbc.update(
        "INSERT INTO department_hods(id,department,campus_id,user_id,active) VALUES "
            + "('hod-1','CS','gehu-hill-dehradun','HOD',true)");
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

  private Login loginRole(String role) throws Exception {
    return login(role.toLowerCase(Locale.ROOT) + "@test.local", "password-2026");
  }

  @Test
  void adminCanCreateAccountWithRolesAndReceiveInvitation() throws Exception {
    Login admin = loginRole("ADMIN");

    var body = Map.of(
        "full_name", "Dr. Rajesh Sharma",
        "email", "rajesh.sharma@gehu.ac.in",
        "employee_id", "GEHU-CS-101",
        "department", "CS",
        "role", "FACULTY",
        "roles", List.of("FACULTY", "COORDINATOR"),
        "room_ids", List.of("room-101", "room-202"));

    MvcResult res =
        mvc.perform(
                post("/api/admin/accounts")
                    .cookie(admin.cookie())
                    .header("X-CSRF-Token", admin.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode responseJson = json.readTree(res.getResponse().getContentAsString());
    assertThat(responseJson.get("success").asBoolean()).isTrue();
    String createdId = responseJson.get("id").asText();
    assertThat(responseJson.get("status").asText()).isEqualTo("INVITED");
    assertThat(responseJson.has("invitation_token")).isTrue();

    // Verify user in database
    var userRow = jdbc.queryForMap("SELECT * FROM users WHERE id=?", createdId);
    assertThat(userRow.get("full_name")).isEqualTo("Dr. Rajesh Sharma");
    assertThat(userRow.get("email")).isEqualTo("rajesh.sharma@gehu.ac.in");
    assertThat(userRow.get("employee_id")).isEqualTo("GEHU-CS-101");
    assertThat(userRow.get("status")).isEqualTo("INVITED");

    // Verify user roles
    var roles = jdbc.queryForList("SELECT role FROM user_roles WHERE user_id=?", String.class, createdId);
    assertThat(roles).containsExactlyInAnyOrder("FACULTY", "COORDINATOR");

    // Verify allocations
    var allocations = jdbc.queryForList("SELECT room_id FROM allocations WHERE user_id=?", String.class, createdId);
    assertThat(allocations).containsExactlyInAnyOrder("room-101", "room-202");
  }

  @Test
  void duplicateEmailOrEmployeeIdIsRejectedWithConflict() throws Exception {
    Login admin = loginRole("ADMIN");

    // Duplicate email
    var dupEmail = Map.of(
        "full_name", "Duplicate Email Person",
        "email", "faculty@test.local", // already exists
        "role", "FACULTY");

    mvc.perform(
            post("/api/admin/accounts")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(dupEmail)))
        .andExpect(status().isConflict());

    // Duplicate employee ID
    var dupEmp = Map.of(
        "full_name", "Duplicate Emp Person",
        "email", "newperson@gehu.ac.in",
        "employee_id", "EMP-FACULTY", // already exists
        "role", "FACULTY");

    mvc.perform(
            post("/api/admin/accounts")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(dupEmp)))
        .andExpect(status().isConflict());
  }

  @Test
  void nonAdminCannotManageAccounts() throws Exception {
    var body = Map.of(
        "full_name", "Sneaky Creation",
        "email", "sneaky@test.local",
        "role", "ADMIN");

    // Anonymous
    mvc.perform(
            post("/api/admin/accounts")
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isUnauthorized());

    // Faculty
    Login faculty = loginRole("FACULTY");
    mvc.perform(
            post("/api/admin/accounts")
                .cookie(faculty.cookie())
                .header("X-CSRF-Token", faculty.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isForbidden());

    // Coordinator
    Login coord = loginRole("COORDINATOR");
    mvc.perform(
            post("/api/admin/accounts")
                .cookie(coord.cookie())
                .header("X-CSRF-Token", coord.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andExpect(status().isForbidden());
  }

  @Test
  void invitedUserCanActivateAccountAndLogin() throws Exception {
    Login admin = loginRole("ADMIN");

    var createBody = Map.of(
        "full_name", "New Prof",
        "email", "prof.new@gehu.ac.in",
        "role", "FACULTY");

    MvcResult createRes =
        mvc.perform(
                post("/api/admin/accounts")
                    .cookie(admin.cookie())
                    .header("X-CSRF-Token", admin.csrf())
                    .contentType("application/json")
                    .content(json.writeValueAsString(createBody)))
            .andExpect(status().isCreated())
            .andReturn();

    String token = json.readTree(createRes.getResponse().getContentAsString()).get("invitation_token").asText();

    // Before activation, login fails
    mvc.perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", "prof.new@gehu.ac.in", "password", "any"))))
        .andExpect(status().isUnauthorized());

    // Activate account
    mvc.perform(
            post("/api/auth/activate")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("token", token, "new_password", "prof-Secure2026!"))))
        .andExpect(status().isOk());

    // Token cannot be reused
    mvc.perform(
            post("/api/auth/activate")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("token", token, "new_password", "prof-Secure2026!"))))
        .andExpect(status().isBadRequest());

    // Now login succeeds
    Login userLogin = login("prof.new@gehu.ac.in", "prof-Secure2026!");
    assertThat(userLogin.cookie()).isNotNull();

    // Verify /api/auth/me returns active user
    mvc.perform(
            get("/api/auth/me")
                .cookie(userLogin.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.email").value("prof.new@gehu.ac.in"));
  }

  @Test
  void deactivatedUserCannotLoginAndActiveSessionsAreImmediatelyTerminated() throws Exception {
    Login faculty = loginRole("FACULTY");

    // Before deactivation: session is valid
    mvc.perform(get("/api/auth/me").cookie(faculty.cookie())).andExpect(status().isOk());

    // Admin deactivates faculty
    Login admin = loginRole("ADMIN");
    mvc.perform(
            post("/api/admin/accounts/FACULTY/status")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("status", "DEACTIVATED"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));

    // Immediately: existing faculty session is terminated
    mvc.perform(get("/api/auth/me").cookie(faculty.cookie())).andExpect(status().isUnauthorized());

    // Login attempt fails for deactivated user
    mvc.perform(
            post("/api/auth/login")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", "faculty@test.local", "password", "password-2026"))))
        .andExpect(status().isUnauthorized());

    // Reactivate faculty
    mvc.perform(
            post("/api/admin/accounts/FACULTY/status")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("status", "ACTIVE"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));

    // Now faculty can login again
    Login reactivated = loginRole("FACULTY");
    assertThat(reactivated.cookie()).isNotNull();
    mvc.perform(get("/api/auth/me").cookie(reactivated.cookie())).andExpect(status().isOk());
  }

  @Test
  void adminCanAssignDepartmentHodAndReplacesPreviousHod() throws Exception {
    Login admin = loginRole("ADMIN");

    // Assign FACULTY user as the new HOD of CS department
    mvc.perform(
            post("/api/admin/departments/hods")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "department", "CS",
                            "user_id", "FACULTY",
                            "campus_id", "gehu-hill-dehradun"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    // Verify in department_hods
    var hods = jdbc.queryForList("SELECT user_id, active FROM department_hods WHERE department='CS' ORDER BY assigned_at DESC");
    assertThat(hods).hasSize(2);
    // Newest is FACULTY and active=true
    assertThat(hods.get(0).get("user_id")).isEqualTo("FACULTY");
    assertThat(hods.get(0).get("active")).isEqualTo(true);
    // Old is HOD and active=false
    assertThat(hods.get(1).get("user_id")).isEqualTo("HOD");
    assertThat(hods.get(1).get("active")).isEqualTo(false);
  }

  @Test
  void adminCanUpdateCoordinatorAllocations() throws Exception {
    Login admin = loginRole("ADMIN");

    // Update coordinator allocations to room-202 only
    mvc.perform(
            put("/api/admin/accounts/COORDINATOR/allocations")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("room_ids", List.of("room-202")))))
        .andExpect(status().isOk());

    var allocated = jdbc.queryForList("SELECT room_id FROM allocations WHERE user_id='COORDINATOR'", String.class);
    assertThat(allocated).containsExactly("room-202");
  }

  @Test
  void bootstrapAdminOnlyAllowedWhenNoActiveAdminExists() throws Exception {
    // There is already an ADMIN account in DB
    mvc.perform(
            post("/api/auth/bootstrap")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "full_name", "Second Admin",
                            "email", "secondadmin@gehu.ac.in",
                            "password", "AdminPass2026!"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void auditEventsAreRecordedForAccountOperations() throws Exception {
    Login admin = loginRole("ADMIN");

    // Create an account
    mvc.perform(
            post("/api/admin/accounts")
                .cookie(admin.cookie())
                .header("X-CSRF-Token", admin.csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "full_name", "Audit Test User",
                            "email", "audittest@gehu.ac.in",
                            "role", "FACULTY"))))
        .andExpect(status().isCreated());

    // Check audit_events
    var audits = jdbc.queryForList("SELECT action FROM audit_events WHERE action='ACCOUNT_CREATED'");
    assertThat(audits).isNotEmpty();
  }
}
