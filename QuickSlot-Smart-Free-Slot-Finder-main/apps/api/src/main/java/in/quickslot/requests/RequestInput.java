package in.quickslot.requests;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;

public record RequestInput(
    @NotBlank @JsonProperty("room_id") @JsonAlias("roomId") String roomId,
    @NotNull @JsonProperty("date") LocalDate date,
    @NotNull @JsonProperty("start_time") @JsonAlias("startTime") LocalTime startTime,
    @NotNull @JsonProperty("end_time") @JsonAlias("endTime") LocalTime endTime,
    @NotBlank @Size(min = 3, max = 500) @JsonProperty("purpose") String purpose,
    @Min(1) @Max(5000) @JsonProperty("attendance") int attendance,
    @JsonProperty("participant_group") @JsonAlias("participantGroup") String participantGroup,
    @JsonProperty("required_equipment") @JsonAlias("requiredEquipment") String requiredEquipment,
    @JsonProperty("club_id") @JsonAlias("clubId") String clubId) {
  public RequestInput(
      String roomId,
      LocalDate date,
      LocalTime startTime,
      LocalTime endTime,
      String purpose,
      int attendance,
      String participantGroup,
      String requiredEquipment) {
    this(roomId, date, startTime, endTime, purpose, attendance, participantGroup, requiredEquipment, null);
  }
}
