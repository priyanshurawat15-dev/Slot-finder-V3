package in.quickslot.timetables;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ImportData {
  private ImportData() {}

  public record Session(
      String id,
      int weekday,
      String startTime,
      String endTime,
      String roomId,
      String subject,
      List<String> facultyIds,
      String section,
      String group,
      Map<String, Object> source) {

    public Session(
        String id,
        int weekday,
        String startTime,
        String endTime,
        String roomId,
        String subject,
        List<String> facultyIds,
        Map<String, Object> source) {
      this(
          id,
          weekday,
          startTime,
          endTime,
          roomId,
          subject,
          facultyIds,
          source != null && source.containsKey("section")
              ? String.valueOf(source.get("section"))
              : null,
          source != null && source.containsKey("group")
              ? String.valueOf(source.get("group"))
              : null,
          source);
    }

    public Session(
        int weekday,
        String startTime,
        String endTime,
        String roomId,
        String subject,
        List<String> facultyIds,
        String section,
        String group,
        Map<String, Object> source) {
      this(
          UUID.randomUUID().toString(),
          weekday,
          startTime,
          endTime,
          roomId,
          subject,
          facultyIds,
          section,
          group,
          source);
    }

    public Session(
        int weekday,
        String startTime,
        String endTime,
        String roomId,
        String subject,
        List<String> facultyIds,
        Map<String, Object> source) {
      this(
          UUID.randomUUID().toString(),
          weekday,
          startTime,
          endTime,
          roomId,
          subject,
          facultyIds,
          source != null && source.containsKey("section")
              ? String.valueOf(source.get("section"))
              : null,
          source != null && source.containsKey("group")
              ? String.valueOf(source.get("group"))
              : null,
          source);
    }

    public boolean isOnline() {
      if (source != null) {
        if (Boolean.TRUE.equals(source.get("is_online")) || Boolean.TRUE.equals(source.get("online"))) {
          return true;
        }
        Object mode = source.get("delivery_mode");
        if (mode == null) mode = source.get("mode");
        if (mode != null && "ONLINE".equalsIgnoreCase(String.valueOf(mode).trim())) {
          return true;
        }
      }
      if (roomId != null && "ONLINE".equalsIgnoreCase(roomId.trim())) {
        return true;
      }
      return false;
    }

    public boolean hasOnlineSubjectHint() {
      if (subject != null) {
        String upper = subject.toUpperCase(Locale.ROOT);
        return upper.contains("ONLINE") || upper.contains("SWAYAM") || upper.contains("MOOC");
      }
      return false;
    }
  }

  public record Issue(String code, String message, String sessionId, Map<String, Object> source) {}

  public record Extraction(
      List<Session> sessions, List<Issue> issues, Map<String, String> metadata) {}

  public static Issue issue(String code, String message, Map<String, Object> source, String id) {
    return new Issue(code, message, id, source);
  }
}
