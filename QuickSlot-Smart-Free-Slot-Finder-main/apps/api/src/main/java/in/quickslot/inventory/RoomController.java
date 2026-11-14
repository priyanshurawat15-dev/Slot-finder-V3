package in.quickslot.inventory;

import in.quickslot.availability.AvailabilityService;
import in.quickslot.identity.AuthService;
import in.quickslot.shared.ApiException;
import in.quickslot.shared.AuditService;
import in.quickslot.shared.AuthenticatedUser;
import in.quickslot.shared.WriteLock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {
  private final RoomRepository rooms;
  private final JdbcTemplate jdbc;
  private final AuthService auth;
  private final AuditService audit;
  private final WriteLock lock;

  public RoomController(
      RoomRepository rooms,
      JdbcTemplate jdbc,
      AuthService auth,
      AuditService audit,
      WriteLock lock) {
    this.rooms = rooms;
    this.jdbc = jdbc;
    this.auth = auth;
    this.audit = audit;
    this.lock = lock;
  }

  public record RoomInput(
      @NotBlank @Size(max = 80) String code,
      @Pattern(regexp = "CLASSROOM|LT|LAB|SEMINAR_HALL|AUDITORIUM") @NotNull String kind,
      @NotBlank @Size(max = 100) String building,
      @Min(1) @Max(5000) int capacity,
      @Size(max = 60) String floor) {}

  public record RoomDetailsInput(
      @NotNull @Size(max = 100) String building,
      @Min(1) @Max(5000) Integer capacity,
      @Size(max = 60) String floor) {}

  public record ClosureInput(
      @Pattern(regexp = "TEMPORARY|PERMANENT") @NotNull String closureType,
      @NotBlank @Size(max = 500) String reason,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt) {}

  public record VerificationInput(
      Boolean verified,
      @Min(1) @Max(5000) Integer capacity,
      @Size(max = 100) String building,
      @Size(max = 60) String floor,
      Boolean hasProjector,
      Boolean hasAc,
      Boolean hasAudio) {}

  public static Map<String, ?> publicView(Room room) {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", room.id());
    result.put("code", room.code());
    result.put("kind", room.kind());
    result.put("building", room.building());
    result.put("capacity", room.capacity());
    result.put("source_label", room.sourceLabel());
    result.put("floor", room.floor());
    result.put("has_projector", room.hasProjector());
    result.put("has_ac", room.hasAc());
    result.put("has_audio", room.hasAudio());
    result.put("verified", room.verified());
    result.put("active", room.active());
    return result;
  }

  private AuthenticatedUser canManageRoom(HttpServletRequest request, Room room) {
    var user = auth.require(request);
    if (!user.campusId().equals(room.campusId())) {
      throw new ApiException(403, "Room belongs to another campus.");
    }
    if (user.hasRole("ADMIN")) return user;
    if (user.hasRole("HOD") && room.department().equalsIgnoreCase(user.department())) return user;
    Boolean isAllocated = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM allocations WHERE user_id=? AND room_id=?)",
        Boolean.class,
        user.id(),
        room.id());
    if (Boolean.TRUE.equals(isAllocated)) return user;
    throw new ApiException(403, "You do not have management authority for this room.");
  }

  @GetMapping
  public List<?> list(@RequestParam(value = "all", defaultValue = "false") boolean all) {
    var list = all ? rooms.allRooms() : rooms.publicRooms();
    return list.stream().map(RoomController::publicView).toList();
  }

  @GetMapping("/{id}")
  public Map<String, ?> get(@PathVariable String id) {
    return publicView(rooms.get(id));
  }

  @PostMapping
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @Transactional
  public Map<String, ?> create(@Valid @RequestBody RoomInput input, HttpServletRequest request) {
    var user = auth.admin(request);
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO rooms(id,code,kind,building,capacity,campus_id,department,floor,verified) VALUES"
            + " (?,?,?,?,?,?,?,?,true)",
        id,
        input.code().strip(),
        input.kind(),
        input.building().strip(),
        input.capacity(),
        user.campusId(),
        user.department(),
        input.floor() == null ? null : input.floor().strip());
    audit.record(user, "ROOM_CREATED", id, Map.of());
    return publicView(rooms.get(id));
  }

  @PutMapping("/{id}/details")
  @Transactional
  public Map<String, ?> updateDetails(
      @PathVariable String id,
      @Valid @RequestBody RoomDetailsInput input,
      HttpServletRequest request) {
    var user = auth.admin(request);
    lock.acquire();
    var room = rooms.get(id);
    if (!room.active() || !room.campusId().equals(user.campusId()))
      throw new ApiException(404, "Room not found.");
    jdbc.update(
        "UPDATE rooms SET building=?,capacity=?,floor=?,verified=true WHERE id=?",
        input.building().strip(),
        input.capacity(),
        input.floor() == null || input.floor().isBlank() ? null : input.floor().strip(),
        id);
    audit.record(user, "ROOM_DETAILS_VERIFIED", id, Map.of());
    return publicView(rooms.get(id));
  }

  @PatchMapping("/{id}/verification")
  @Transactional
  public Map<String, ?> updateVerification(
      @PathVariable String id,
      @RequestBody VerificationInput input,
      HttpServletRequest request) {
    var room = rooms.get(id);
    var user = canManageRoom(request, room);
    lock.acquire();

    boolean verified = input.verified() != null ? input.verified() : room.verified();
    int capacity = input.capacity() != null ? input.capacity() : (room.capacity() != null ? room.capacity() : 60);
    String building = input.building() != null && !input.building().isBlank() ? input.building().strip() : room.building();
    String floor = input.floor() != null ? input.floor().strip() : room.floor();
    boolean hasProjector = input.hasProjector() != null ? input.hasProjector() : room.hasProjector();
    boolean hasAc = input.hasAc() != null ? input.hasAc() : room.hasAc();
    boolean hasAudio = input.hasAudio() != null ? input.hasAudio() : room.hasAudio();

    jdbc.update(
        "UPDATE rooms SET verified=?, capacity=?, building=?, floor=?, has_projector=?, has_ac=?, has_audio=? WHERE id=?",
        verified, capacity, building, floor, hasProjector, hasAc, hasAudio, id);

    audit.record(user, "ROOM_VERIFICATION_UPDATED", id, Map.of("verified", verified, "capacity", capacity));
    return publicView(rooms.get(id));
  }

  @GetMapping("/{id}/closures")
  public List<Map<String, Object>> listClosures(@PathVariable String id) {
    return jdbc.query(
        "SELECT rc.*, u.full_name AS created_by_name FROM room_closures rc "
            + "LEFT JOIN users u ON u.id = rc.created_by "
            + "WHERE rc.room_id=? ORDER BY rc.created_at DESC",
        (rs, rowNum) -> {
          var map = new LinkedHashMap<String, Object>();
          map.put("id", rs.getString("id"));
          map.put("room_id", rs.getString("room_id"));
          map.put("closure_type", rs.getString("closure_type"));
          map.put("reason", rs.getString("reason"));
          map.put("starts_at", rs.getObject("starts_at", OffsetDateTime.class));
          map.put("ends_at", rs.getObject("ends_at", OffsetDateTime.class));
          map.put("created_by", rs.getString("created_by"));
          map.put("created_by_name", rs.getString("created_by_name"));
          map.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
          map.put("active", rs.getBoolean("active"));
          return map;
        },
        id);
  }

  @PostMapping("/{id}/closures")
  @Transactional
  public Map<String, Object> createClosure(
      @PathVariable String id,
      @Valid @RequestBody ClosureInput input,
      HttpServletRequest request) {
    var room = rooms.get(id);
    var user = canManageRoom(request, room);
    lock.acquire();

    boolean isPermanent = "PERMANENT".equalsIgnoreCase(input.closureType());
    OffsetDateTime startsAt = input.startsAt() != null ? input.startsAt() : OffsetDateTime.now(AvailabilityService.INDIA);
    OffsetDateTime endsAt = input.endsAt();
    if (!isPermanent) {
      if (endsAt == null) throw new ApiException(422, "Temporary closure requires an end time.");
      if (!startsAt.isBefore(endsAt)) throw new ApiException(422, "Closure start time must be before end time.");
    }

    String closureId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO room_closures(id, room_id, closure_type, reason, starts_at, ends_at, created_by, active) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, true)",
        closureId,
        id,
        input.closureType().toUpperCase(),
        input.reason().strip(),
        startsAt,
        isPermanent ? null : endsAt,
        user.id());

    // Safe handling of existing bookings
    if (isPermanent) {
      jdbc.update("UPDATE rooms SET active=false WHERE id=?", id);

      var pending = jdbc.queryForList(
          "SELECT id, requester_id FROM booking_requests WHERE room_id=? AND status IN ('PENDING', 'FORWARDED')", id);
      for (var req : pending) {
        String reqId = (String) req.get("id");
        String requesterId = (String) req.get("requester_id");
        jdbc.update("UPDATE booking_requests SET status='REJECTED' WHERE id=?", reqId);
        jdbc.update("INSERT INTO decisions(id, request_id, actor_id, action, reason) VALUES (?,?,?,?,?)",
            UUID.randomUUID().toString(), reqId, user.id(), "reject", "Room permanently closed: " + input.reason().strip());
        jdbc.update("INSERT INTO notifications(id, user_id, request_id, message) VALUES (?,?,?,?)",
            UUID.randomUUID().toString(), requesterId, reqId, "Your booking request was rejected: Room permanently closed (" + input.reason().strip() + ")");
      }

      var approved = jdbc.queryForList(
          "SELECT id, requester_id FROM booking_requests WHERE room_id=? AND status='APPROVED'", id);
      for (var req : approved) {
        String reqId = (String) req.get("id");
        String requesterId = (String) req.get("requester_id");
        jdbc.update("UPDATE booking_requests SET status='CANCELLED' WHERE id=?", reqId);
        jdbc.update("UPDATE occupancies SET active=false WHERE source_id=?", reqId);
        jdbc.update("INSERT INTO decisions(id, request_id, actor_id, action, reason) VALUES (?,?,?,?,?)",
            UUID.randomUUID().toString(), reqId, user.id(), "cancel", "Room permanently closed: " + input.reason().strip());
        jdbc.update("INSERT INTO notifications(id, user_id, request_id, message) VALUES (?,?,?,?)",
            UUID.randomUUID().toString(), requesterId, reqId, "Your booking was cancelled: Room permanently closed (" + input.reason().strip() + ")");
      }
    } else {
      var pending = jdbc.queryForList(
          "SELECT id, requester_id FROM booking_requests "
              + "WHERE room_id=? AND status IN ('PENDING', 'FORWARDED') "
              + "AND (date + start_time) AT TIME ZONE 'Asia/Kolkata' < ? "
              + "AND (date + end_time) AT TIME ZONE 'Asia/Kolkata' > ?",
          id, endsAt, startsAt);
      for (var req : pending) {
        String reqId = (String) req.get("id");
        String requesterId = (String) req.get("requester_id");
        jdbc.update("UPDATE booking_requests SET status='REJECTED' WHERE id=?", reqId);
        jdbc.update("INSERT INTO decisions(id, request_id, actor_id, action, reason) VALUES (?,?,?,?,?)",
            UUID.randomUUID().toString(), reqId, user.id(), "reject", "Room temporarily closed: " + input.reason().strip());
        jdbc.update("INSERT INTO notifications(id, user_id, request_id, message) VALUES (?,?,?,?)",
            UUID.randomUUID().toString(), requesterId, reqId, "Your booking request was rejected: Room temporarily closed (" + input.reason().strip() + ")");
      }

      var approved = jdbc.queryForList(
          "SELECT id, requester_id FROM booking_requests "
              + "WHERE room_id=? AND status='APPROVED' "
              + "AND (date + start_time) AT TIME ZONE 'Asia/Kolkata' < ? "
              + "AND (date + end_time) AT TIME ZONE 'Asia/Kolkata' > ?",
          id, endsAt, startsAt);
      for (var req : approved) {
        String reqId = (String) req.get("id");
        String requesterId = (String) req.get("requester_id");
        jdbc.update("UPDATE booking_requests SET status='CANCELLED' WHERE id=?", reqId);
        jdbc.update("UPDATE occupancies SET active=false WHERE source_id=?", reqId);
        jdbc.update("INSERT INTO decisions(id, request_id, actor_id, action, reason) VALUES (?,?,?,?,?)",
            UUID.randomUUID().toString(), reqId, user.id(), "cancel", "Room temporarily closed: " + input.reason().strip());
        jdbc.update("INSERT INTO notifications(id, user_id, request_id, message) VALUES (?,?,?,?)",
            UUID.randomUUID().toString(), requesterId, reqId, "Your booking was cancelled: Room temporarily closed (" + input.reason().strip() + ")");
      }

      jdbc.update(
          "INSERT INTO occupancies(id, room_id, starts_at, ends_at, kind, source_id, active) "
              + "VALUES (?, ?, ?, ?, 'CLOSURE', ?, true)",
          UUID.randomUUID().toString(),
          id,
          startsAt,
          endsAt,
          closureId);
    }

    audit.record(user, "ROOM_CLOSED", id, Map.of("closure_type", input.closureType(), "reason", input.reason().strip()));
    return viewClosure(closureId);
  }

  @DeleteMapping("/{id}/closures/{closureId}")
  @Transactional
  public Map<String, ?> liftClosure(
      @PathVariable String id,
      @PathVariable String closureId,
      HttpServletRequest request) {
    var room = rooms.get(id);
    var user = canManageRoom(request, room);
    lock.acquire();

    var rows = jdbc.queryForList("SELECT * FROM room_closures WHERE id=? AND room_id=?", closureId, id);
    if (rows.isEmpty()) throw new ApiException(404, "Closure not found.");
    var row = rows.get(0);
    boolean wasActive = Boolean.TRUE.equals(row.get("active"));
    String closureType = (String) row.get("closure_type");

    if (wasActive) {
      jdbc.update("UPDATE room_closures SET active=false WHERE id=?", closureId);
      if ("TEMPORARY".equalsIgnoreCase(closureType)) {
        jdbc.update("UPDATE occupancies SET active=false WHERE source_id=?", closureId);
      } else if ("PERMANENT".equalsIgnoreCase(closureType)) {
        Boolean otherPermanent = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM room_closures WHERE room_id=? AND active=true AND closure_type='PERMANENT')",
            Boolean.class, id);
        if (!Boolean.TRUE.equals(otherPermanent)) {
          jdbc.update("UPDATE rooms SET active=true WHERE id=?", id);
        }
      }
      audit.record(user, "ROOM_CLOSURE_LIFTED", id, Map.of("closure_id", closureId));
    }
    return Map.of("message", "Closure lifted successfully.");
  }

  private Map<String, Object> viewClosure(String closureId) {
    var rows = jdbc.query(
        "SELECT rc.*, u.full_name AS created_by_name FROM room_closures rc "
            + "LEFT JOIN users u ON u.id = rc.created_by WHERE rc.id=?",
        (rs, rowNum) -> {
          var map = new LinkedHashMap<String, Object>();
          map.put("id", rs.getString("id"));
          map.put("room_id", rs.getString("room_id"));
          map.put("closure_type", rs.getString("closure_type"));
          map.put("reason", rs.getString("reason"));
          map.put("starts_at", rs.getObject("starts_at", OffsetDateTime.class));
          map.put("ends_at", rs.getObject("ends_at", OffsetDateTime.class));
          map.put("created_by", rs.getString("created_by"));
          map.put("created_by_name", rs.getString("created_by_name"));
          map.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
          map.put("active", rs.getBoolean("active"));
          return map;
        },
        closureId);
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }
}
