package in.quickslot.availability;

import in.quickslot.inventory.RoomController;
import in.quickslot.inventory.RoomRepository;
import in.quickslot.shared.ApiException;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AvailabilityController {
  private final AvailabilityService availability;
  private final RoomRepository rooms;

  public AvailabilityController(AvailabilityService availability, RoomRepository rooms) {
    this.availability = availability;
    this.rooms = rooms;
  }

  @GetMapping("/availability")
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, ?> search(
      @RequestParam LocalDate date,
      @RequestParam(value = "start_time", required = false) LocalTime start,
      @RequestParam(value = "end_time", required = false) LocalTime end,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "building", required = false) String building,
      @RequestParam(value = "floor", required = false) String floor,
      @RequestParam(value = "min_capacity", required = false) Integer minCapacity,
      @RequestParam(value = "has_projector", required = false) Boolean hasProjector,
      @RequestParam(value = "has_ac", required = false) Boolean hasAc) {
    LocalTime startTime = start != null ? start : LocalTime.of(9, 0);
    LocalTime endTime = end != null ? end : LocalTime.of(10, 0);
    AvailabilityService.validateWindow(startTime, endTime);

    var filteredRooms = rooms.publicRooms().stream().filter(r -> {
      if (kind != null && !kind.isBlank() && !r.kind().equalsIgnoreCase(kind.trim())) return false;
      if (building != null && !building.isBlank() && !r.building().equalsIgnoreCase(building.trim())) return false;
      if (floor != null && !floor.isBlank() && (r.floor() == null || !r.floor().equalsIgnoreCase(floor.trim()))) return false;
      if (minCapacity != null && (r.capacity() == null || r.capacity() < minCapacity)) return false;
      if (hasProjector != null && hasProjector && !r.hasProjector()) return false;
      if (hasAc != null && hasAc && !r.hasAc()) return false;
      return true;
    }).toList();

    return Map.of(
        "date",
        date,
        "rooms",
        filteredRooms.stream().map(r -> availability.room(r, date, startTime, endTime)).toList(),
        "coverage_notice",
        AvailabilityService.NOTICE);
  }

  @GetMapping("/availability/check-clash")
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, ?> checkClash(
      @RequestParam("room_id") String roomId,
      @RequestParam LocalDate date,
      @RequestParam("start_time") LocalTime start,
      @RequestParam("end_time") LocalTime end) {
    AvailabilityService.validateWindow(start, end);
    var room = rooms.get(roomId);
    String state = availability.state(room.id(), date, start, end);
    boolean hasClash = state.equals("OCCUPIED");
    var busyIntervals = availability.busy(room.id(), date);

    return Map.of(
        "room_id", roomId,
        "room_code", room.code(),
        "date", date,
        "start_time", start,
        "end_time", end,
        "state", state,
        "has_clash", hasClash,
        "reason", switch (state) {
          case "OCCUPIED" -> "Selected time slot clashes with an existing class or confirmed booking.";
          case "AVAILABLE" -> "Room is verified free from timetable classes and bookings.";
          default -> "Room availability is unverified for this period.";
        },
        "busy_intervals", busyIntervals
    );
  }

  @GetMapping("/rooms/{id}/schedule")
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, ?> schedule(@PathVariable String id, @RequestParam LocalDate date) {
    var room = rooms.get(id);
    if (!room.active() || !room.campusId().equals("gehu-hill-dehradun"))
      throw new ApiException(404, "Room not found.");
    LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    var sessions = new ArrayList<Map<String, ?>>();
    for (int day = 0; day < 7; day++) {
      LocalDate current = monday.plusDays(day);
      for (var interval : availability.busy(id, current)) {
        sessions.add(
            Map.of(
                "day_of_week",
                day,
                "start_time",
                interval.startTime(),
                "end_time",
                interval.endTime(),
                "title",
                interval.kind(),
                "source_label",
                "Published schedule"));
      }
    }
    return Map.of(
        "room",
        RoomController.publicView(room),
        "sessions",
        sessions,
        "coverage_notice",
        AvailabilityService.NOTICE);
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok", "service", "quickslot-api");
  }
}
