package in.quickslot.availability;

import in.quickslot.inventory.Room;
import in.quickslot.inventory.RoomController;
import in.quickslot.shared.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityService {
  public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  public static final String NOTICE =
      "A room is marked free only after all course timetables using it have been checked. Amber"
          + " means some timetable information is still unverified. Check the room in person before"
          + " use.";
  private final JdbcTemplate jdbc;
  private final ObjectProvider<TimetableLookup> timetables;

  public AvailabilityService(JdbcTemplate jdbc, ObjectProvider<TimetableLookup> timetables) {
    this.jdbc = jdbc;
    this.timetables = timetables;
  }

  public List<BusyInterval> busy(String roomId, LocalDate date) {
    var intervals = new ArrayList<BusyInterval>();
    var lookup = timetables.getIfAvailable();
    if (lookup != null) intervals.addAll(lookup.busy(roomId, date));
    OffsetDateTime start = date.atStartOfDay(INDIA).toOffsetDateTime();
    OffsetDateTime end = date.plusDays(1).atStartOfDay(INDIA).toOffsetDateTime();
    intervals.addAll(
        jdbc.query(
            "SELECT starts_at,ends_at,kind FROM occupancies WHERE room_id=? AND active AND"
                + " starts_at<? AND ends_at>?",
            (row, index) -> {
              var a = row.getObject("starts_at", OffsetDateTime.class).atZoneSameInstant(INDIA);
              var b = row.getObject("ends_at", OffsetDateTime.class).atZoneSameInstant(INDIA);
              String from = a.toLocalDate().isBefore(date) ? "00:00" : a.toLocalTime().toString();
              String until =
                  b.toLocalDate().isAfter(date) ? "23:59:59" : b.toLocalTime().toString();
              return new BusyInterval(from, until, row.getString("kind"));
            },
            roomId,
            end,
            start));
    intervals.sort(Comparator.comparing(BusyInterval::startTime));
    return intervals;
  }

  public Optional<String> activeClosureReason(String roomId, LocalDate date, LocalTime start, LocalTime end) {
    OffsetDateTime reqStart = date.atTime(start).atZone(INDIA).toOffsetDateTime();
    OffsetDateTime reqEnd = date.atTime(end).atZone(INDIA).toOffsetDateTime();
    var list = jdbc.query(
        "SELECT reason, closure_type FROM room_closures WHERE room_id=? AND active=true "
            + "AND (closure_type='PERMANENT' OR (starts_at < ? AND ends_at > ?)) "
            + "ORDER BY CASE WHEN closure_type='PERMANENT' THEN 1 ELSE 2 END, created_at DESC LIMIT 1",
        (rs, rowNum) -> {
          String reason = rs.getString("reason");
          if (reason != null && !reason.isBlank()) {
            return reason.strip();
          }
          String type = rs.getString("closure_type");
          return "PERMANENT".equalsIgnoreCase(type) ? "Room is permanently closed." : "Room is temporarily closed.";
        },
        roomId, reqEnd, reqStart);
    if (!list.isEmpty()) {
      return Optional.of(list.get(0));
    }
    var activeList = jdbc.query(
        "SELECT active FROM rooms WHERE id=?", (rs, rowNum) -> rs.getBoolean("active"), roomId);
    if (!activeList.isEmpty() && Boolean.FALSE.equals(activeList.get(0))) {
      return Optional.of("Room is permanently closed or inactive.");
    }
    return Optional.empty();
  }

  public String state(String roomId, LocalDate date, LocalTime start, LocalTime end) {
    if (activeClosureReason(roomId, date, start, end).isPresent()) {
      return "CLOSED";
    }
    if (busy(roomId, date).stream()
        .anyMatch(
            i ->
                !"CLOSURE".equalsIgnoreCase(i.kind())
                    && LocalTime.parse(i.startTime()).isBefore(end)
                    && LocalTime.parse(i.endTime()).isAfter(start))) {
      return "OCCUPIED";
    }
    var lookup = timetables.getIfAvailable();
    return lookup != null && lookup.covered(roomId, date) ? "AVAILABLE" : "UNKNOWN";
  }

  public Map<String, ?> room(Room room, LocalDate date, LocalTime start, LocalTime end) {
    var output = new LinkedHashMap<String, Object>(RoomController.publicView(room));
    var closureReason = activeClosureReason(room.id(), date, start, end);
    String state;
    String reason;
    if (closureReason.isPresent()) {
      state = "CLOSED";
      reason = "Room closed: " + closureReason.get();
    } else {
      boolean occupied = busy(room.id(), date).stream()
          .anyMatch(
              i ->
                  !"CLOSURE".equalsIgnoreCase(i.kind())
                      && LocalTime.parse(i.startTime()).isBefore(end)
                      && LocalTime.parse(i.endTime()).isAfter(start));
      if (occupied) {
        state = "OCCUPIED";
        reason = "A scheduled class or confirmed booking overlaps this time.";
      } else {
        var lookup = timetables.getIfAvailable();
        if (lookup != null && lookup.covered(room.id(), date)) {
          state = "AVAILABLE";
          reason = "No scheduled use found. Confirm the room is empty when you arrive.";
        } else {
          state = "UNKNOWN";
          reason = "All course timetables for this room have not yet been verified.";
        }
      }
    }
    output.put("state", state);
    output.put("reason", reason);
    output.put("busy_intervals", busy(room.id(), date));
    return output;
  }

  public static void validateWindow(LocalTime start, LocalTime end) {
    if (!start.isBefore(end)) throw new ApiException(422, "Start must be before end.");
  }
}
