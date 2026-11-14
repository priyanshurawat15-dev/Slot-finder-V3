package in.quickslot.admin;

import in.quickslot.identity.AuthService;
import in.quickslot.shared.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AccountController {
  private final AccountManagementService service;
  private final AuthService auth;

  public AccountController(AccountManagementService service, AuthService auth) {
    this.service = service;
    this.auth = auth;
  }

  @GetMapping("/accounts")
  public List<Map<String, Object>> listAccounts(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) String role,
      @RequestParam(required = false) String department,
      @RequestParam(required = false) String status,
      HttpServletRequest request) {
    AuthenticatedUser admin = auth.admin(request);
    return service.listAccounts(admin, query, role, department, status);
  }

  @GetMapping("/accounts/{id}")
  public Map<String, Object> getAccount(@PathVariable String id, HttpServletRequest request) {
    AuthenticatedUser admin = auth.admin(request);
    return service.getAccount(admin, id);
  }

  @PostMapping("/accounts")
  public ResponseEntity<Map<String, Object>> createAccount(
      @RequestBody AccountManagementService.CreateAccountInput input, HttpServletRequest request) {
    AuthenticatedUser admin = auth.admin(request);
    var created = service.createAccount(admin, input);
    return ResponseEntity.status(201).body(created);
  }

  @PutMapping("/accounts/{id}")
  public Map<String, Object> updateAccount(
      @PathVariable String id,
      @RequestBody AccountManagementService.UpdateAccountInput input,
      HttpServletRequest request) {
    AuthenticatedUser admin = auth.admin(request);
    return service.updateAccount(admin, id, input);
  }

  @PostMapping("/accounts/{id}/status")
  public Map<String, Object> setStatus(
      @PathVariable String id,
      @RequestBody AccountManagementService.StatusInput input,
      HttpServletRequest request) {
    AuthenticatedUser admin = auth.admin(request);
    return service.setStatus(admin, id, input.status());
  }

  @GetMapping("/accounts/{id}/allocations")
  public List<?> getAllocations(@PathVariable String id, HttpServletRequest request) {
    AuthenticatedUser admin = auth.admin(request);
    var acc = service.getAccount(admin, id);
    return (List<?>) acc.get("allocated_rooms");
  }

  @PutMapping("/accounts/{id}/allocations")
  public Map<String, Object> updateAllocations(
      @PathVariable String id,
      @RequestBody AccountManagementService.AllocationUpdateInput input,
      HttpServletRequest request) {
    AuthenticatedUser admin = auth.admin(request);
    return service.updateAllocations(admin, id, input.room_ids());
  }

  @GetMapping("/departments")
  public List<String> listDepartments() {
    return service.listDepartments();
  }

  @GetMapping("/departments/hods")
  public List<Map<String, Object>> listDepartmentHods(HttpServletRequest request) {
    AuthenticatedUser admin = auth.admin(request);
    return service.listDepartmentHods();
  }

  @PostMapping("/departments/hods")
  public Map<String, Object> assignHod(
      @RequestBody AccountManagementService.HodAssignInput input, HttpServletRequest request) {
    AuthenticatedUser admin = auth.admin(request);
    return service.assignHod(admin, input.department(), input.user_id(), input.campus_id());
  }
}
