package in.quickslot.inventory;

import java.time.OffsetDateTime;

public record RoomClosure(
    String id,
    String roomId,
    String closureType,
    String reason,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    String createdBy,
    OffsetDateTime createdAt,
    boolean active) {}
