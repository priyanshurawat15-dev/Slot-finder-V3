package in.quickslot.admin;

import in.quickslot.identity.AuthService;
import in.quickslot.shared.ApiException;
import in.quickslot.shared.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/pilot")
public class PilotController {
  private final PilotVerificationService pilotService;
  private final AuthService auth;

  public PilotController(PilotVerificationService pilotService, AuthService auth) {
    this.pilotService = pilotService;
    this.auth = auth;
  }

  private AuthenticatedUser requirePilotManager(HttpServletRequest request) {
    var user = auth.require(request);
    if (!user.hasRole("ADMIN") && !user.hasRole("COORDINATOR") && !user.hasRole("HOD")) {
      throw new ApiException(403, "Administrator, coordinator, or HOD role required.");
    }
    return user;
  }

  @GetMapping("/verification")
  public Map<String, Object> verification(HttpServletRequest request) {
    var user = requirePilotManager(request);
    return pilotService.verificationReport(user);
  }

  @GetMapping("/faculty-mappings")
  public List<Map<String, Object>> listFacultyMappings(
      @RequestParam(value = "status", required = false) String status,
      HttpServletRequest request) {
    requirePilotManager(request);
    return pilotService.facultyMappings(status);
  }

  @PostMapping("/faculty-mappings/{id}")
  public Map<String, Object> updateFacultyMapping(
      @PathVariable String id,
      @RequestBody PilotVerificationService.FacultyMappingInput input,
      HttpServletRequest request) {
    var user = requirePilotManager(request);
    return pilotService.updateFacultyMapping(id, input, user);
  }
}
