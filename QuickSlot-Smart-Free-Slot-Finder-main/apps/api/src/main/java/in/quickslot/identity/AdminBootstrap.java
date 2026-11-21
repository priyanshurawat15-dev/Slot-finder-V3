package in.quickslot.identity;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements CommandLineRunner {
  private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
  private final JdbcTemplate jdbc;
  private final AuthService auth;
  private final Environment environment;

  public AdminBootstrap(JdbcTemplate jdbc, AuthService auth, Environment environment) {
    this.jdbc = jdbc;
    this.auth = auth;
    this.environment = environment;
  }

  @Override
  @Transactional
  public void run(String... args) {
    String adminEmail = environment.getProperty("QUICKSLOT_ADMIN_EMAIL");
    String adminPassword = environment.getProperty("QUICKSLOT_ADMIN_PASSWORD");

    if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
      return;
    }

    String email = adminEmail.trim().toLowerCase(java.util.Locale.ROOT);
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users WHERE LOWER(email)=?)",
            Boolean.class,
            email);

    if (Boolean.TRUE.equals(exists)) {
      log.info("Admin bootstrap: user {} already exists. Skipping bootstrap.", email);
      return;
    }

    String userId = UUID.randomUUID().toString();
    String passwordHash = auth.passwordHash(adminPassword);
    String campusId = "gehu-hill-dehradun";
    String department = "CS";

    jdbc.update(
        "INSERT INTO users(id, full_name, email, password_hash, role, campus_id, department, active, status, activated_at) "
            + "VALUES (?, ?, ?, ?, 'ADMIN', ?, ?, true, 'ACTIVE', now())",
        userId,
        "System Administrator",
        email,
        passwordHash,
        campusId,
        department);

    jdbc.update(
        "INSERT INTO user_roles(id, user_id, role, department, scope_type) VALUES (?, ?, 'ADMIN', ?, 'INSTITUTION') "
            + "ON CONFLICT (user_id, role, department) DO NOTHING",
        UUID.randomUUID().toString(),
        userId,
        department);

    log.info("Admin bootstrap: successfully initialized administrator account for {}", email);
  }
}
