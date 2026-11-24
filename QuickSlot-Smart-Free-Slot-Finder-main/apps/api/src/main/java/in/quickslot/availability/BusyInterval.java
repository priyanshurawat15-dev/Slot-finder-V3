package in.quickslot.availability;

public record BusyInterval(
    String startTime,
    String endTime,
    String kind,
    String section,
    String group) {

  public BusyInterval(String startTime, String endTime, String kind) {
    this(startTime, endTime, kind, null, null);
  }
}
