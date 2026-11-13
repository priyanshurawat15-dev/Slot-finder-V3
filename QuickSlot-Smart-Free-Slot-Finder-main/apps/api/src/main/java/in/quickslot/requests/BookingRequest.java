package in.quickslot.requests;

import java.time.LocalDate;
import java.time.LocalTime;

public record BookingRequest(
    String id,
    String requesterId,
    String roomId,
    LocalDate date,
    LocalTime startTime,
    LocalTime endTime,
    String purpose,
    int attendance,
    String status,
    String participantGroup,
    String requiredEquipment) {
  public BookingRequest(
      String id,
      String requesterId,
      String roomId,
      LocalDate date,
      LocalTime startTime,
      LocalTime endTime,
      String purpose,
      int attendance,
      String status) {
    this(id, requesterId, roomId, date, startTime, endTime, purpose, attendance, status, "", "");
  }
}
