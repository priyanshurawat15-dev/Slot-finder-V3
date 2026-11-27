package in.quickslot.timetables;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.quickslot.identity.AuthService;
import in.quickslot.shared.ApiException;
import in.quickslot.shared.AuthenticatedUser;
import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
class TimetableIntegrationTest {
  private static final String SCHEMA =
      "test_timetables_" + UUID.randomUUID().toString().replace("-", "");
  private static final String URL = System.getenv("TEST_DATABASE_URL");
  private static final String USER =
      System.getenv().getOrDefault("TEST_DATABASE_USER", "quickslot");
  private static final String PASSWORD = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
  private final AuthenticatedUser admin =
      new AuthenticatedUser(
          "admin", "Administrator", "admin@test.invalid", "ADMIN", "gehu-hill-dehradun", "CS");
  @Autowired TimetableService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;

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
    registry.add("quickslot.demo-seed", () -> false);
  }

  @AfterAll
  static void cleanup() throws Exception {
    try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
        var command = connection.createStatement()) {
      command.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
  }

  @BeforeEach
  void prepare() {
    jdbc.execute("TRUNCATE users,rooms,timetable_scopes,timetable_faculty CASCADE");
    jdbc.update(
        "INSERT INTO users(id,full_name,email,password_hash,role) VALUES"
            + " ('admin','Administrator','admin@test.invalid','unused','ADMIN')");
    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity) VALUES"
            + " ('room','017','CLASSROOM','Main',40)");
    service.addFaculty("F001", "Faculty", admin);
  }

  private ImportData.Session session(String start, String end) {
    return new ImportData.Session(
        "session",
        0,
        start,
        end,
        "room",
        "CS301",
        List.of("F001"),
        Map.of("page", 1, "text", "source cell"));
  }

  private String draft(String scope, LocalDate from, LocalDate until) throws Exception {
    return draftFor(admin, scope, from, until);
  }

  private String draftFor(AuthenticatedUser user, String scope, LocalDate from, LocalDate until)
      throws Exception {
    try (var document = TimetableAdaptersTest.template()) {
      return service
          .upload(
              user,
              "template.docx",
              TimetableAdaptersTest.bytes(document),
              scope,
              from,
              until,
              List.of())
          .get("id")
          .toString();
    }
  }

  private String reviewed(String scope, LocalDate from, LocalDate until, String start, String end)
      throws Exception {
    String id = draft(scope, from, until);
    service.review(
        id,
        new TimetableService.Review(0, List.of(session(start, end)), List.of("room"), true),
        admin);
    return id;
  }

  private Cookie sessionFor(String role, String campus) {
    String userId = UUID.randomUUID().toString();
    String token = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO users(id,full_name,email,password_hash,role,campus_id) VALUES (?,?,?,?,?,?)",
        userId,
        role,
        userId + "@test.invalid",
        "unused",
        role,
        campus);
    jdbc.update(
        "INSERT INTO login_sessions(token_hash,user_id,csrf_token,expires_at) VALUES (?,?,?,?)",
        AuthService.hash(token),
        userId,
        UUID.randomUUID().toString(),
        OffsetDateTime.now().plusHours(1));
    return new Cookie(AuthService.COOKIE, token);
  }

  @Test
  void teacherLookupIsRestrictedToStaffAndCampusWhileRoomSchedulesStayPublic() throws Exception {
    String revision =
        reviewed("CSE-V", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31), "09:00", "10:00");
    service.publish(revision, 1, admin);
    String teachers = "/api/timetables/teachers";
    String schedule = teachers + "/F001/schedule";
    mvc.perform(get(teachers)).andExpect(status().isUnauthorized());
    mvc.perform(get(schedule).param("date", "2026-10-05")).andExpect(status().isUnauthorized());
    for (String role : List.of("STUDENT", "CLUB_HEAD", "FACULTY", "COORDINATOR", "HOD", "ADMIN")) {
      var cookie = sessionFor(role, "gehu-hill-dehradun");
      mvc.perform(get(teachers).cookie(cookie))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value("F001"));
      mvc.perform(get(schedule).param("date", "2026-10-05").cookie(cookie))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.sessions[0].subject").value("CS301"));
    }
    var otherCampus = sessionFor("FACULTY", "other-campus");
    mvc.perform(get(teachers).cookie(otherCampus))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    mvc.perform(get(schedule).param("date", "2026-10-05").cookie(otherCampus))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/rooms/room/schedule").param("date", "2026-10-05"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions[0].title").value("CLASS"))
        .andExpect(jsonPath("$.sessions[0].subject").doesNotExist())
        .andExpect(jsonPath("$.sessions[0].faculty_ids").doesNotExist())
        .andExpect(jsonPath("$.sessions[0].requester_name").doesNotExist());
  }

  @Test
  void draftIssuesAndStaleEditsCannotPublishAndSourceIsRetained() throws Exception {
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);
    String id = draft("CSE-V", from, until);
    assertThatThrownBy(() -> service.publish(id, 0, admin)).isInstanceOf(ApiException.class);
    var payload =
        new TimetableService.Review(0, List.of(session("09:00", "10:00")), List.of("room"), true);
    service.review(id, payload, admin);
    assertThatThrownBy(() -> service.review(id, payload, admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("changed");
    assertThatThrownBy(() -> service.publish(id, 0, admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("changed");
    service.publish(id, 1, admin);
    assertThat(service.source(id, admin).content()).isNotEmpty();
    assertThat(service.teacherSchedule("F001", LocalDate.of(2026, 10, 5), admin).get("sessions"))
        .asList()
        .hasSize(1);
  }

  @Test
  void replacementPreservesUnrelatedScopeHistoricalDatesAndFutureTail() throws Exception {
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);
    String original = reviewed("CSE-V", from, until, "09:00", "10:00");
    service.publish(original, 1, admin);
    String unrelated = reviewed("CSE-III", from, until, "11:00", "12:00");
    service.publish(unrelated, 1, admin);
    String next =
        reviewed("CSE-V", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), "10:00", "11:00");
    service.publish(next, 1, admin);
    assertThat(service.busy("room", LocalDate.of(2026, 10, 5)))
        .extracting(value -> value.startTime())
        .containsExactlyInAnyOrder("10:00", "11:00");
    assertThat(service.busy("room", LocalDate.of(2026, 11, 2)))
        .extracting(value -> value.startTime())
        .containsExactlyInAnyOrder("09:00", "11:00");
    assertThat(service.busy("room", LocalDate.of(2026, 9, 28)))
        .extracting(value -> value.startTime())
        .containsExactlyInAnyOrder("09:00", "11:00");
    assertThat(service.covered("room", LocalDate.of(2026, 10, 5))).isTrue();
    assertThat(service.covered("missing", LocalDate.of(2026, 10, 5))).isFalse();
  }

  @Test
  void publicationCannotOverrideApprovedBookingAndRejectedPublishIsAtomic() throws Exception {
    String id =
        reviewed("CSE-V", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31), "09:00", "10:00");
    jdbc.update(
        "INSERT INTO occupancies(id,room_id,starts_at,ends_at,kind,source_id) VALUES"
            + " ('booking','room',?,?,'BOOKING','booking')",
        OffsetDateTime.parse("2026-10-05T09:30:00+05:30"),
        OffsetDateTime.parse("2026-10-05T10:30:00+05:30"));
    assertThatThrownBy(() -> service.publish(id, 1, admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("approved booking");
    assertThat(service.detail(id, admin).get("status")).isEqualTo("REVIEWED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM timetable_publications", Integer.class))
        .isZero();
  }
  @Test
  void concurrentPublicationOfConflictingScopesHasOneWinner() throws Exception {
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);
    String a = reviewed("CSE-V", from, until, "09:00", "10:00"),
        b = reviewed("CSE-III", from, until, "09:30", "10:30");
    var executor = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Boolean>> jobs = List.of(() -> attemptPublish(a), () -> attemptPublish(b));
      var results = executor.invokeAll(jobs);
      assertThat(
              results.stream()
                  .filter(
                      result -> {
                        try {
                          return result.get();
                        } catch (Exception error) {
                          throw new RuntimeException(error);
                        }
                      })
                  .count())
          .isEqualTo(1);
    } finally {
      executor.shutdown();
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM timetable_publications", Integer.class))
        .isEqualTo(1);
  }

  private boolean attemptPublish(String id) {
    try {
      service.publish(id, 1, admin);
      return true;
    } catch (ApiException error) {
      return false;
    }
  }

  @Test
  void onlineSessionPreservedWithoutRoomIdAndDoesNotOccupyPhysicalRooms() throws Exception {
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);
    String id = draft("CSE-ONLINE", from, until);

    var onlineSession =
        new ImportData.Session(
            "online-1",
            0,
            "10:00",
            "11:00",
            null,
            "ONLINE - Cloud Computing",
            List.of("F001"),
            "A",
            "G1",
            Map.of("delivery_mode", "ONLINE", "section", "A", "group", "G1"));

    service.review(
        id,
        new TimetableService.Review(0, List.of(onlineSession), List.of(), true),
        admin);

    var reviewedDetail = service.detail(id, admin);
    @SuppressWarnings("unchecked")
    var sessions = (List<ImportData.Session>) reviewedDetail.get("sessions");
    assertThat(sessions).hasSize(1);
    assertThat(sessions.get(0).roomId()).isNull();
    assertThat(sessions.get(0).section()).isEqualTo("A");
    assertThat(sessions.get(0).group()).isEqualTo("G1");
    assertThat(sessions.get(0).isOnline()).isTrue();

    service.publish(id, 1, admin);

    LocalDate testDate = LocalDate.of(2026, 10, 5); // Monday (weekday 0)
    // Physical room "room" must NOT be occupied by this online session!
    assertThat(service.busy("room", testDate)).isEmpty();

    // Teacher Finder must return ONLINE location without error and include section/group
    var cookie = sessionFor("FACULTY", "gehu-hill-dehradun");
    mvc.perform(get("/api/timetables/teachers/F001/schedule").param("date", "2026-10-05").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions[0].subject").value("ONLINE - Cloud Computing"))
        .andExpect(jsonPath("$.sessions[0].room_id").doesNotExist())
        .andExpect(jsonPath("$.sessions[0].room_code").value("ONLINE"))
        .andExpect(jsonPath("$.sessions[0].location").value("ONLINE"))
        .andExpect(jsonPath("$.sessions[0].building").value("ONLINE"))
        .andExpect(jsonPath("$.sessions[0].section").value("A"))
        .andExpect(jsonPath("$.sessions[0].group").value("G1"));
  }

  @Test
  void physicalAndOnlineCoexistAtSameTimeWithoutRoomConflictAndExposeSectionGroup() throws Exception {
    service.addFaculty("F002", "Second Faculty", admin);
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);
    String id = draft("CSE-MIXED", from, until);

    var physicalSession =
        new ImportData.Session(
            "phys-1",
            0,
            "09:00",
            "10:00",
            "room",
            "CS301-Physical",
            List.of("F001"),
            "B",
            "G2",
            Map.of("section", "B", "group", "G2"));

    var onlineSession =
        new ImportData.Session(
            "online-2",
            0,
            "09:00",
            "10:00",
            null,
            "SWAYAM - AI Ethics",
            List.of("F002"),
            "B",
            "G1",
            Map.of("delivery_mode", "ONLINE", "section", "B", "group", "G1"));

    service.review(
        id,
        new TimetableService.Review(0, List.of(physicalSession, onlineSession), List.of("room"), true),
        admin);

    service.publish(id, 1, admin);

    LocalDate testDate = LocalDate.of(2026, 10, 5);
    // Physical room busy interval must only include the physical session
    var busy = service.busy("room", testDate);
    assertThat(busy).hasSize(1);
    assertThat(busy.get(0).startTime()).isEqualTo("09:00");
    assertThat(busy.get(0).endTime()).isEqualTo("10:00");
    assertThat(busy.get(0).section()).isEqualTo("B");
    assertThat(busy.get(0).group()).isEqualTo("G2");

    // Room schedule endpoint exposes section and group for the physical session
    mvc.perform(get("/api/rooms/room/schedule").param("date", "2026-10-05"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions[0].title").value("CLASS"))
        .andExpect(jsonPath("$.sessions[0].section").value("B"))
        .andExpect(jsonPath("$.sessions[0].group").value("G2"));
  }

  @Test
  void validationEnforcesOperatingHoursAndRejectsMissingRoomForPhysicalSessions() throws Exception {
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);
    String id = draft("CSE-VALIDATE", from, until);

    // 1. Physical session without roomId is rejected with 422
    var missingRoomSession =
        new ImportData.Session(
            "bad-1",
            0,
            "09:00",
            "10:00",
            null,
            "PHYSICAL - No Room Assigned",
            List.of("F001"),
            "A",
            "G1",
            Map.of());

    assertThatThrownBy(
            () ->
                service.review(
                    id,
                    new TimetableService.Review(0, List.of(missingRoomSession), List.of("room"), true),
                    admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("approved campus room ID");

    // 2. Online session before operating hours (07:00) is rejected with 422
    var earlyOnlineSession =
        new ImportData.Session(
            "bad-2",
            0,
            "07:00",
            "08:00",
            null,
            "ONLINE - Too Early",
            List.of("F001"),
            "A",
            "G1",
            Map.of("delivery_mode", "ONLINE"));

    assertThatThrownBy(
            () ->
                service.review(
                    id,
                    new TimetableService.Review(0, List.of(earlyOnlineSession), List.of(), true),
                    admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("campus operating hours");

    // 3. Faculty collision between physical and online session is rejected with 422
    var physSession =
        new ImportData.Session(
            "good-1",
            0,
            "11:00",
            "12:00",
            "room",
            "Physics Lab",
            List.of("F001"),
            "A",
            "G1",
            Map.of());

    var clashingOnline =
        new ImportData.Session(
            "bad-3",
            0,
            "11:30",
            "12:30",
            null,
            "ONLINE - Clashing Faculty",
            List.of("F001"),
            "A",
            "G2",
            Map.of("delivery_mode", "ONLINE"));

    assertThatThrownBy(
            () ->
                service.review(
                    id,
                    new TimetableService.Review(
                        0, List.of(physSession, clashingOnline), List.of("room"), true),
                    admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("scheduled for overlapping classes");
  }

  @Test
  void subjectNameAloneDoesNotClassifyAsOnline() throws Exception {
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);
    String id = draft("CSE-V", from, until);

    // "Online Algorithms" with null roomId and no online metadata must fail validation (422)
    var fakeOnlineSession =
        new ImportData.Session(
            "s1",
            0,
            "10:00",
            "11:00",
            null,
            "Online Algorithms",
            List.of("F001"),
            "A",
            "G1",
            Map.of());

    assertThatThrownBy(
            () ->
                service.review(
                    id,
                    new TimetableService.Review(0, List.of(fakeOnlineSession), List.of("room"), true),
                    admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("approved campus room ID");

    // But with an approved room ID, "Online Algorithms" succeeds
    var validSession =
        new ImportData.Session(
            "s2",
            0,
            "10:00",
            "11:00",
            "room",
            "Online Algorithms",
            List.of("F001"),
            "A",
            "G1",
            Map.of());

    var reviewed =
        service.review(
            id,
            new TimetableService.Review(0, List.of(validSession), List.of("room"), true),
            admin);
    assertThat(reviewed).isNotNull();
  }

  @Test
  void crossScopeFacultyConflictsAreRejectedAcrossAllSessionTypes() throws Exception {
    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room2','018','CLASSROOM','Main',40)"
            + " ON CONFLICT DO NOTHING");

    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);

    // 1. Physical - Physical cross-scope faculty conflict (different rooms, same faculty, overlapping time)
    String scope1 = draft("CSE-III", from, until);
    var s1 =
        new ImportData.Session(
            "s1",
            0,
            "09:00",
            "10:00",
            "room",
            "Data Structures",
            List.of("F001"),
            "A",
            null,
            Map.of());
    service.review(
        scope1,
        new TimetableService.Review(0, List.of(s1), List.of("room"), true),
        admin);
    service.publish(scope1, 1, admin);

    String scope2 = draft("CSE-V", from, until);
    var s2 =
        new ImportData.Session(
            "s2",
            0,
            "09:30",
            "10:30",
            "room2",
            "Operating Systems",
            List.of("F001"),
            "B",
            null,
            Map.of());
    service.review(
        scope2,
        new TimetableService.Review(0, List.of(s2), List.of("room2"), true),
        admin);

    assertThatThrownBy(() -> service.publish(scope2, 1, admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Timetable conflicts with another published scope ('CSE-III') for faculty member 'Faculty'");

    // 2. Physical - Online cross-scope faculty conflict (Scope 1 physical, Scope 3 online)
    String scope3 = draft("CSE-VII", from, until);
    var s3 =
        new ImportData.Session(
            "s3",
            0,
            "09:30",
            "10:30",
            null,
            "Cloud Computing",
            List.of("F001"),
            "C",
            null,
            Map.of("delivery_mode", "ONLINE"));
    service.review(
        scope3,
        new TimetableService.Review(0, List.of(s3), List.of(), true),
        admin);

    assertThatThrownBy(() -> service.publish(scope3, 1, admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Timetable conflicts with another published scope ('CSE-III') for faculty member 'Faculty'");

    // 3. Online - Online cross-scope faculty conflict
    // First, clear and publish an online scope
    jdbc.execute("TRUNCATE timetable_publications, timetable_revisions CASCADE");
    String onlineScope1 = draft("ONLINE-SCOPE-1", from, until);
    var os1 =
        new ImportData.Session(
            "os1",
            1, // Tuesday
            "14:00",
            "15:00",
            null,
            "Online AI",
            List.of("F001"),
            "A",
            null,
            Map.of("delivery_mode", "ONLINE"));
    service.review(
        onlineScope1,
        new TimetableService.Review(0, List.of(os1), List.of(), true),
        admin);
    service.publish(onlineScope1, 1, admin);

    String onlineScope2 = draft("ONLINE-SCOPE-2", from, until);
    var os2 =
        new ImportData.Session(
            "os2",
            1, // Tuesday
            "14:30",
            "15:30",
            null,
            "Online ML",
            List.of("F001"),
            "B",
            null,
            Map.of("delivery_mode", "ONLINE"));
    service.review(
        onlineScope2,
        new TimetableService.Review(0, List.of(os2), List.of(), true),
        admin);

    assertThatThrownBy(() -> service.publish(onlineScope2, 1, admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Timetable conflicts with another published scope ('ONLINE-SCOPE-1') for faculty member 'Faculty'");

    // 4. Non-overlapping sessions across scopes publish successfully
    String nonOverlapScope = draft("CSE-NON-OVERLAP", from, until);
    var nos =
        new ImportData.Session(
            "nos",
            1, // Tuesday
            "16:00",
            "17:00",
            "room",
            "Compiler Design",
            List.of("F001"),
            "A",
            null,
            Map.of());
    service.review(
        nonOverlapScope,
        new TimetableService.Review(0, List.of(nos), List.of("room"), true),
        admin);
    var published = service.publish(nonOverlapScope, 1, admin);
    assertThat(published).isNotNull();
    assertThat(published.get("status")).isEqualTo("PUBLISHED");
  }

  /**
   * Under the current schema (V2__timetables.sql), timetable_faculty.id is a global primary key
   * bound to a single campus_id, preventing the same faculty ID from being registered across campuses.
   * This test verifies cross-campus timetable isolation: distinct campuses (e.g. Dehradun and Bhimtal)
   * can publish simultaneous sessions for their approved faculty at the identical weekday and time window
   * without cross-campus scope interference or false conflicts.
   */
  @Test
  void crossCampusTimetableIsolationAllowsSimultaneousSchedules() throws Exception {
    var adminOther =
        new AuthenticatedUser(
            "admin2", "Admin Other", "admin2@test.invalid", "ADMIN", "gehu-bhimtal", "CS");
    jdbc.update(
        "INSERT INTO users(id,full_name,email,password_hash,role,campus_id) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        "admin2",
        "Admin Other",
        "admin2@test.invalid",
        "unused",
        "ADMIN",
        "gehu-bhimtal");
    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity,campus_id) VALUES ('room-bhimtal','017','CLASSROOM','Main',40,'gehu-bhimtal')"
            + " ON CONFLICT DO NOTHING");
    service.addFaculty("F-BHIMTAL", "Faculty Bhimtal", adminOther);

    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);

    // Publish Scope 1 on gehu-hill-dehradun for F001, Monday 09:00-10:00
    String scopeDehradun = draft("CSE-DEHRADUN", from, until);
    var s1 =
        new ImportData.Session(
            "s1",
            0,
            "09:00",
            "10:00",
            "room",
            "Algorithms",
            List.of("F001"),
            "A",
            null,
            Map.of());
    service.review(
        scopeDehradun,
        new TimetableService.Review(0, List.of(s1), List.of("room"), true),
        admin);
    var pub1 = service.publish(scopeDehradun, 1, admin);
    assertThat(pub1.get("status")).isEqualTo("PUBLISHED");

    // Publish Scope 2 on gehu-bhimtal for F-BHIMTAL, Monday 09:00-10:00 (exact same weekday & time)
    String scopeBhimtal = draftFor(adminOther, "CSE-BHIMTAL", from, until);
    var s2 =
        new ImportData.Session(
            "s2",
            0,
            "09:00",
            "10:00",
            "room-bhimtal",
            "Algorithms Bhimtal",
            List.of("F-BHIMTAL"),
            "B",
            null,
            Map.of());
    service.review(
        scopeBhimtal,
        new TimetableService.Review(0, List.of(s2), List.of("room-bhimtal"), true),
        adminOther);

    var pub2 = service.publish(scopeBhimtal, 1, adminOther);
    assertThat(pub2.get("status")).isEqualTo("PUBLISHED");
  }

  @Test
  void multiFacultySessionRejectsConflictWhenOneCoTeacherOverlaps() throws Exception {
    service.addFaculty("F002", "Second Faculty", admin);
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);

    // Scope 1: Faculty F001 teaching solo on Monday 10:00-11:00 in "room"
    String scope1 = draft("CSE-SOLO", from, until);
    var s1 =
        new ImportData.Session(
            "s1",
            0,
            "10:00",
            "11:00",
            "room",
            "Computer Networks",
            List.of("F001"),
            "A",
            null,
            Map.of());
    service.review(
        scope1,
        new TimetableService.Review(0, List.of(s1), List.of("room"), true),
        admin);
    service.publish(scope1, 1, admin);

    // Scope 2: Co-taught session with F002 and F001 overlapping time on Monday 10:30-11:30 in "room2"
    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room2','018','CLASSROOM','Main',40)"
            + " ON CONFLICT DO NOTHING");
    String scope2 = draft("CSE-COTAUGHT", from, until);
    var s2 =
        new ImportData.Session(
            "s2",
            0,
            "10:30",
            "11:30",
            "room2",
            "Networks Lab",
            List.of("F002", "F001"),
            "B",
            null,
            Map.of());
    service.review(
        scope2,
        new TimetableService.Review(0, List.of(s2), List.of("room2"), true),
        admin);

    assertThatThrownBy(() -> service.publish(scope2, 1, admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Timetable conflicts with another published scope ('CSE-SOLO') for faculty member 'Faculty'");
  }

  @Test
  void sameFacultyOverlappingTimesWithinSameCampusAndOverlappingDateWindowsRejected() throws Exception {
    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room2','018','CLASSROOM','Main',40)"
            + " ON CONFLICT DO NOTHING");

    var from1 = LocalDate.of(2026, 7, 1);
    var until1 = LocalDate.of(2026, 12, 31);
    var from2 = LocalDate.of(2026, 9, 1);
    var until2 = LocalDate.of(2026, 11, 30); // fully inside [from1, until1]

    String scope1 = draft("SCOPE-A", from1, until1);
    var s1 =
        new ImportData.Session(
            "s1",
            0,
            "09:00",
            "10:00",
            "room",
            "Operating Systems",
            List.of("F001"),
            "A",
            null,
            Map.of());
    service.review(
        scope1,
        new TimetableService.Review(0, List.of(s1), List.of("room"), true),
        admin);
    service.publish(scope1, 1, admin);

    String scope2 = draft("SCOPE-B", from2, until2);
    var s2 =
        new ImportData.Session(
            "s2",
            0,
            "09:30",
            "10:30",
            "room2",
            "Database Systems",
            List.of("F001"),
            "B",
            null,
            Map.of());
    service.review(
        scope2,
        new TimetableService.Review(0, List.of(s2), List.of("room2"), true),
        admin);

    assertThatThrownBy(() -> service.publish(scope2, 1, admin))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Timetable conflicts with another published scope ('SCOPE-A') for faculty member 'Faculty'");
  }

  @Test
  void sameFacultyOverlappingTimesWithinSameCampusNonOverlappingDateWindowsAllowed() throws Exception {
    var springFrom = LocalDate.of(2026, 1, 1);
    var springUntil = LocalDate.of(2026, 6, 30);
    var autumnFrom = LocalDate.of(2026, 7, 1);
    var autumnUntil = LocalDate.of(2026, 12, 31);

    String springScope = draft("TERM-SPRING", springFrom, springUntil);
    var springSession =
        new ImportData.Session(
            "s-spring",
            0,
            "09:00",
            "10:00",
            "room",
            "Spring Course",
            List.of("F001"),
            "A",
            null,
            Map.of());
    service.review(
        springScope,
        new TimetableService.Review(0, List.of(springSession), List.of("room"), true),
        admin);
    var springPub = service.publish(springScope, 1, admin);
    assertThat(springPub.get("status")).isEqualTo("PUBLISHED");

    // Same faculty, same room, same weekday and time slot, but non-overlapping date window
    String autumnScope = draft("TERM-AUTUMN", autumnFrom, autumnUntil);
    var autumnSession =
        new ImportData.Session(
            "s-autumn",
            0,
            "09:00",
            "10:00",
            "room",
            "Autumn Course",
            List.of("F001"),
            "A",
            null,
            Map.of());
    service.review(
        autumnScope,
        new TimetableService.Review(0, List.of(autumnSession), List.of("room"), true),
        admin);
    var autumnPub = service.publish(autumnScope, 1, admin);
    assertThat(autumnPub.get("status")).isEqualTo("PUBLISHED");
  }

  @Test
  void expiredPublicationDoesNotCauseFalseConflictWhenPublishingNewRevision() throws Exception {
    var expiredFrom = LocalDate.of(2025, 7, 1);
    var expiredUntil = LocalDate.of(2025, 12, 31);
    var currentFrom = LocalDate.of(2026, 7, 1);
    var currentUntil = LocalDate.of(2026, 12, 31);

    String expiredScope = draft("PAST-YEAR-2025", expiredFrom, expiredUntil);
    var expiredSession =
        new ImportData.Session(
            "s-past",
            0,
            "09:00",
            "10:00",
            "room",
            "Past Year Course",
            List.of("F001"),
            "A",
            null,
            Map.of());
    service.review(
        expiredScope,
        new TimetableService.Review(0, List.of(expiredSession), List.of("room"), true),
        admin);
    var expiredPub = service.publish(expiredScope, 1, admin);
    assertThat(expiredPub.get("status")).isEqualTo("PUBLISHED");

    // New publication in 2026 with same faculty, room, day and time must not conflict with expired 2025 publication
    String currentScope = draft("CURRENT-YEAR-2026", currentFrom, currentUntil);
    var currentSession =
        new ImportData.Session(
            "s-current",
            0,
            "09:00",
            "10:00",
            "room",
            "Current Year Course",
            List.of("F001"),
            "A",
            null,
            Map.of());
    service.review(
        currentScope,
        new TimetableService.Review(0, List.of(currentSession), List.of("room"), true),
        admin);
    var currentPub = service.publish(currentScope, 1, admin);
    assertThat(currentPub.get("status")).isEqualTo("PUBLISHED");
  }

  @Test
  void onlineSessionWithNullOrEmptyFacultyDoesNotCrashTeacherSchedule() throws Exception {
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);
    String id = draft("CSE-FACULTY-NULL", from, until);

    var onlineEmptyFaculty =
        new ImportData.Session(
            "online-empty",
            0,
            "10:00",
            "11:00",
            null,
            "MOOC Autonomous Study",
            List.of(),
            "A",
            null,
            Map.of("delivery_mode", "ONLINE"));

    var onlineNullFaculty =
        new ImportData.Session(
            "online-null",
            0,
            "11:00",
            "12:00",
            null,
            "Swayam Asynchronous",
            null,
            "A",
            null,
            Map.of("delivery_mode", "ONLINE"));

    var facultySession =
        new ImportData.Session(
            "phys-f001",
            0,
            "14:00",
            "15:00",
            "room",
            "Operating Systems",
            List.of("F001"),
            "A",
            null,
            Map.of());

    String jsonSessions = json.writeValueAsString(List.of(onlineEmptyFaculty, onlineNullFaculty, facultySession));
    String scopeId = jdbc.queryForObject("SELECT scope_id FROM timetable_revisions WHERE id=?", String.class, id);
    jdbc.update(
        "UPDATE timetable_revisions SET sessions=?::jsonb, status='PUBLISHED', published_at=now(), valid_from=?, valid_until=? WHERE id=?",
        jsonSessions, from, until, id);
    jdbc.update(
        "INSERT INTO timetable_publications(id, scope_id, revision_id, valid_from, valid_until) VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID().toString(), scopeId, id, from, until);

    var cookie = sessionFor("FACULTY", "gehu-hill-dehradun");
    LocalDate testDate = LocalDate.of(2026, 10, 5); // Monday

    // Querying F001 should not crash despite online sessions with empty or null facultyIds
    mvc.perform(get("/api/timetables/teachers/F001/schedule").param("date", testDate.toString()).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions").isArray())
        .andExpect(jsonPath("$.sessions[0].subject").value("Operating Systems"));

    // Querying an unrelated faculty should also not crash and return empty sessions
    service.addFaculty("F003", "Third Faculty", admin);
    mvc.perform(get("/api/timetables/teachers/F003/schedule").param("date", testDate.toString()).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions").isArray())
        .andExpect(jsonPath("$.sessions").isEmpty());
  }

  @Test
  void missingRoomLookupDoesNotCrashTeacherScheduleAndReturnsFallback() throws Exception {
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);
    String id = draft("CSE-MISSING-ROOM", from, until);

    var missingRoomSession =
        new ImportData.Session(
            "missing-room-s",
            0,
            "10:00",
            "11:00",
            "room-deleted-999",
            "Ghost Room Lecture",
            List.of("F001"),
            "A",
            null,
            Map.of());

    String jsonSessions = json.writeValueAsString(List.of(missingRoomSession));
    String scopeId = jdbc.queryForObject("SELECT scope_id FROM timetable_revisions WHERE id=?", String.class, id);
    jdbc.update(
        "UPDATE timetable_revisions SET sessions=?::jsonb, status='PUBLISHED', published_at=now(), valid_from=?, valid_until=? WHERE id=?",
        jsonSessions, from, until, id);
    jdbc.update(
        "INSERT INTO timetable_publications(id, scope_id, revision_id, valid_from, valid_until) VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID().toString(), scopeId, id, from, until);

    var cookie = sessionFor("FACULTY", "gehu-hill-dehradun");
    LocalDate testDate = LocalDate.of(2026, 10, 5); // Monday

    mvc.perform(get("/api/timetables/teachers/F001/schedule").param("date", testDate.toString()).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions[0].subject").value("Ghost Room Lecture"))
        .andExpect(jsonPath("$.sessions[0].room_id").value("room-deleted-999"))
        .andExpect(jsonPath("$.sessions[0].room_code").value("room-deleted-999"))
        .andExpect(jsonPath("$.sessions[0].building").value("Unassigned"))
        .andExpect(jsonPath("$.sessions[0].location").value("Room room-deleted-999 (Unassigned)"));
  }

  private ImportData.Session sectionSession(
      String id,
      String start,
      String end,
      String roomId,
      String subject,
      String facultyId,
      String section,
      String group) {
    Map<String, Object> source = new java.util.HashMap<>();
    if (section != null) source.put("section", section);
    if (group != null) source.put("group", group);
    if (roomId == null) source.put("delivery_mode", "ONLINE");
    return new ImportData.Session(
        id,
        0,
        start,
        end,
        roomId,
        subject,
        facultyId != null ? List.of(facultyId) : List.of(),
        section,
        group,
        source);
  }

  @Test
  void sameSectionOverlappingTimeDifferentRoomsAndFacultyFailsValidation() throws Exception {
    jdbc.update("INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room-2','018','CLASSROOM','Main',40)");
    service.addFaculty("F002", "Second Faculty", admin);
    var s1 = sectionSession("s1", "09:00", "10:00", "room", "Data Structures", "F001", "A", null);
    var s2 = sectionSession("s2", "09:30", "10:30", "room-2", "Operating Systems", "F002", "A", null);

    String id = draft("CSE-SEC-CONFLICT", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
    assertThatThrownBy(
            () ->
                service.review(
                    id,
                    new TimetableService.Review(0, List.of(s1, s2), List.of("room", "room-2"), true),
                    admin))
        .isInstanceOf(ApiException.class)
        .matches(ex -> ((ApiException) ex).status() == 422)
        .hasMessage(
            "Section 'A' is scheduled for overlapping classes on weekday 0 (09:00-10:00 'Data Structures' and 09:30-10:30 'Operating Systems')");
  }

  @Test
  void differentSectionsOverlappingTimeAllowed() throws Exception {
    jdbc.update("INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room-2','018','CLASSROOM','Main',40)");
    service.addFaculty("F002", "Second Faculty", admin);
    var s1 = sectionSession("s1", "09:00", "10:00", "room", "Data Structures", "F001", "A", null);
    var s2 = sectionSession("s2", "09:00", "10:00", "room-2", "Operating Systems", "F002", "B", null);

    String id = draft("CSE-SEC-DIFF", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
    var review =
        service.review(
            id,
            new TimetableService.Review(0, List.of(s1, s2), List.of("room", "room-2"), true),
            admin);
    assertThat(review.get("status")).isEqualTo("REVIEWED");
  }

  @Test
  void nullOrBlankSectionValuesDoNotTriggerSectionConflict() throws Exception {
    jdbc.update("INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room-2','018','CLASSROOM','Main',40)");
    service.addFaculty("F002", "Second Faculty", admin);
    service.addFaculty("F003", "Third Faculty", admin);
    var s1 = sectionSession("s1", "09:00", "10:00", "room", "Data Structures", "F001", null, null);
    var s2 = sectionSession("s2", "09:00", "10:00", null, "Online Cloud", "F002", "   ", null);
    var s3 = sectionSession("s3", "09:00", "10:00", "room-2", "Algorithms", "F003", "A", null);

    String id = draft("CSE-SEC-NULL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
    var review =
        service.review(
            id,
            new TimetableService.Review(0, List.of(s1, s2, s3), List.of("room", "room-2"), true),
            admin);
    assertThat(review.get("status")).isEqualTo("REVIEWED");
  }

  @Test
  void differentExplicitSubgroupsG1AndG2AllowedConcurrently() throws Exception {
    jdbc.update("INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room-2','018','CLASSROOM','Main',40)");
    service.addFaculty("F002", "Second Faculty", admin);
    var s1 = sectionSession("s1", "09:00", "10:00", "room", "Data Structures Lab", "F001", "A", "G1");
    var s2 = sectionSession("s2", "09:00", "10:00", "room-2", "Networks Lab", "F002", "A", "G2");

    String id = draft("CSE-SUBGROUP-DIFF", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
    var review =
        service.review(
            id,
            new TimetableService.Review(0, List.of(s1, s2), List.of("room", "room-2"), true),
            admin);
    assertThat(review.get("status")).isEqualTo("REVIEWED");
  }

  @Test
  void sameSubgroupG1VsG1TriggersConflict() throws Exception {
    jdbc.update("INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room-2','018','CLASSROOM','Main',40)");
    service.addFaculty("F002", "Second Faculty", admin);
    var s1 = sectionSession("s1", "09:00", "10:00", "room", "Data Structures Lab", "F001", "A", "G1");
    var s2 = sectionSession("s2", "09:30", "10:30", "room-2", "Networks Lab", "F002", "A", "G1");

    String id = draft("CSE-SUBGROUP-SAME", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
    assertThatThrownBy(
            () ->
                service.review(
                    id,
                    new TimetableService.Review(0, List.of(s1, s2), List.of("room", "room-2"), true),
                    admin))
        .isInstanceOf(ApiException.class)
        .matches(ex -> ((ApiException) ex).status() == 422)
        .hasMessageContaining("Section 'A' is scheduled for overlapping classes");
  }

  @Test
  void wholeSectionNullGroupVsG1TriggersConflict() throws Exception {
    jdbc.update("INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room-2','018','CLASSROOM','Main',40)");
    service.addFaculty("F002", "Second Faculty", admin);
    var s1 = sectionSession("s1", "09:00", "10:00", "room", "Data Structures Lecture", "F001", "A", null);
    var s2 = sectionSession("s2", "09:30", "10:30", "room-2", "Networks Lab", "F002", "A", "G1");

    String id = draft("CSE-WHOLE-VS-G1", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
    assertThatThrownBy(
            () ->
                service.review(
                    id,
                    new TimetableService.Review(0, List.of(s1, s2), List.of("room", "room-2"), true),
                    admin))
        .isInstanceOf(ApiException.class)
        .matches(ex -> ((ApiException) ex).status() == 422)
        .hasMessageContaining("Section 'A' is scheduled for overlapping classes");
  }

  @Test
  void nullGroupVsAllGroupTriggersConflict() throws Exception {
    jdbc.update("INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room-2','018','CLASSROOM','Main',40)");
    service.addFaculty("F002", "Second Faculty", admin);
    var s1 = sectionSession("s1", "09:00", "10:00", "room", "Data Structures Lecture", "F001", "A", null);
    var s2 = sectionSession("s2", "09:30", "10:30", "room-2", "Mathematics Lecture", "F002", "A", "ALL");

    String id = draft("CSE-NULL-VS-ALL", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
    assertThatThrownBy(
            () ->
                service.review(
                    id,
                    new TimetableService.Review(0, List.of(s1, s2), List.of("room", "room-2"), true),
                    admin))
        .isInstanceOf(ApiException.class)
        .matches(ex -> ((ApiException) ex).status() == 422)
        .hasMessageContaining("Section 'A' is scheduled for overlapping classes");
  }

  @Test
  void group1VariationsNormalizeAndTriggerConflict() throws Exception {
    jdbc.update("INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room-2','018','CLASSROOM','Main',40)");
    service.addFaculty("F002", "Second Faculty", admin);
    var s1 = sectionSession("s1", "09:00", "10:00", "room", "Data Structures Lab", "F001", "A", "Group 1");
    var s2 = sectionSession("s2", "09:30", "10:30", "room-2", "Networks Lab", "F002", "a", "g1");

    String id = draft("CSE-GRP-NORM", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));
    assertThatThrownBy(
            () ->
                service.review(
                    id,
                    new TimetableService.Review(0, List.of(s1, s2), List.of("room", "room-2"), true),
                    admin))
        .isInstanceOf(ApiException.class)
        .matches(ex -> ((ApiException) ex).status() == 422)
        .hasMessageContaining("Section 'A' is scheduled for overlapping classes");
  }

  @Test
  void differentSemestersWithSameSectionLetterDoNotConflictAcrossScopes() throws Exception {
    var from = LocalDate.of(2026, 7, 1);
    var until = LocalDate.of(2026, 12, 31);
    jdbc.update("INSERT INTO rooms(id,code,kind,building,capacity) VALUES ('room-2','018','CLASSROOM','Main',40)");
    service.addFaculty("F002", "Second Faculty", admin);

    var s1 = sectionSession("sem3-s1", "09:00", "10:00", "room", "Data Structures", "F001", "A", null);
    String id1 = draft("CSE-SEM3", from, until);
    service.review(id1, new TimetableService.Review(0, List.of(s1), List.of("room"), true), admin);
    service.publish(id1, 1, admin);

    var s2 = sectionSession("sem5-s1", "09:00", "10:00", "room-2", "Compiler Design", "F002", "A", null);
    String id2 = draft("CSE-SEM5", from, until);
    service.review(id2, new TimetableService.Review(0, List.of(s2), List.of("room-2"), true), admin);
    var pubResult = service.publish(id2, 1, admin);
    assertThat(pubResult.get("status")).isEqualTo("PUBLISHED");
  }
}
