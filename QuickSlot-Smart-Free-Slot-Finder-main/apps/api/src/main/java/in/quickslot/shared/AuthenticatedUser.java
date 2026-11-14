package in.quickslot.shared;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record AuthenticatedUser(
    String id,
    String fullName,
    String email,
    String role,
    Set<String> roles,
    String campusId,
    String department) {

  public AuthenticatedUser(
      String id, String fullName, String email, String role, String campusId, String department) {
    this(
        id,
        fullName,
        email,
        role,
        role != null ? Set.of(role) : Collections.emptySet(),
        campusId,
        department);
  }

  public boolean hasRole(String targetRole) {
    if (targetRole == null) return false;
    if (role != null && role.equalsIgnoreCase(targetRole)) return true;
    if (roles != null) {
      for (String r : roles) {
        if (r != null && r.equalsIgnoreCase(targetRole)) return true;
      }
    }
    return false;
  }
}
