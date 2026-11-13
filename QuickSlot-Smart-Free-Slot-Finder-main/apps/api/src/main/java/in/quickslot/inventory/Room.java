package in.quickslot.inventory;

public record Room(
    String id,
    String code,
    String kind,
    String building,
    Integer capacity,
    String campusId,
    String department,
    boolean active,
    String sourceLabel,
    String floor,
    boolean hasProjector,
    boolean hasAc,
    boolean hasAudio) {
  public Room(
      String id,
      String code,
      String kind,
      String building,
      Integer capacity,
      String campusId,
      String department,
      boolean active,
      String sourceLabel,
      String floor) {
    this(id, code, kind, building, capacity, campusId, department, active, sourceLabel, floor, true, true, false);
  }
}
