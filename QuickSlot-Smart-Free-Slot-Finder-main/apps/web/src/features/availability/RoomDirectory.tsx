import { ArrowUpRight, Monitor, Wind, Volume2, CalendarPlus } from "lucide-react";
import { Link } from "react-router-dom";
import { Status } from "../../components/ui";
import type { Availability } from "../../lib/types";
import { canRequest, useAuth } from "../../lib/auth";

interface RoomDirectoryProps {
  rooms: Availability["rooms"];
  date: string;
  start: string;
  end: string;
}

const roomStateClass: Record<string, string> = {
  AVAILABLE: "room-card-available",
  OCCUPIED: "room-card-occupied",
  RESTRICTED: "room-card-restricted",
  UNKNOWN: "room-card-unknown",
};

function formatKind(kind: string) {
  switch (kind) {
    case "LT":
      return "Lecture theatre";
    case "LAB":
      return "Computer / AI Lab";
    case "SEMINAR_HALL":
      return "Seminar Hall";
    case "AUDITORIUM":
      return "Auditorium";
    default:
      return "Classroom";
  }
}

function formatAbbr(kind: string) {
  switch (kind) {
    case "LT":
      return "LT";
    case "LAB":
      return "LAB";
    case "SEMINAR_HALL":
      return "SH";
    case "AUDITORIUM":
      return "AUD";
    default:
      return "CR";
  }
}

export function RoomDirectory({ rooms, date, start, end }: RoomDirectoryProps) {
  const { user } = useAuth();
  return (
    <div className="room-card-grid">
      {rooms.map((room) => (
        <div
          className={`room-row room-card ${roomStateClass[room.state] || "room-card-unknown"}`}
          key={room.id}
          style={{ display: "flex", flexDirection: "column" }}
        >
          <Link
            to={`/rooms/${room.id}?date=${date}&start_time=${start}&end_time=${end}`}
            style={{ textDecoration: "none", color: "inherit", flex: 1 }}
          >
            <div className="room-card-heading">
              <div>
                <span className="room-kind">{formatKind(room.kind)}</span>
                <h3 className="room-number">
                  {room.code} <span>({formatAbbr(room.kind)})</span>
                </h3>
              </div>
              {room.state !== "UNKNOWN" && <Status value={room.state} />}
            </div>
            <dl className="room-details">
              <div>
                <dt>Floor</dt>
                <dd>{room.floor || "Not confirmed"}</dd>
              </div>
              <div>
                <dt>Capacity</dt>
                <dd>
                  {room.capacity === null
                    ? "Not confirmed"
                    : `${room.capacity} seats`}
                </dd>
              </div>
            </dl>

            {/* Amenities Badges */}
            {(room.has_projector || room.has_ac || room.has_audio) && (
              <div
                style={{
                  display: "flex",
                  gap: "6px",
                  flexWrap: "wrap",
                  margin: "8px 0",
                  fontSize: "11px",
                }}
              >
                {room.has_projector && (
                  <span
                    style={{
                      display: "inline-flex",
                      alignItems: "center",
                      gap: "3px",
                      padding: "2px 6px",
                      borderRadius: "4px",
                      background: "rgba(91, 25, 209, 0.08)",
                      color: "var(--accent)",
                      fontWeight: 600,
                    }}
                  >
                    <Monitor size={12} /> Projector
                  </span>
                )}
                {room.has_ac && (
                  <span
                    style={{
                      display: "inline-flex",
                      alignItems: "center",
                      gap: "3px",
                      padding: "2px 6px",
                      borderRadius: "4px",
                      background: "rgba(16, 185, 129, 0.08)",
                      color: "#059669",
                      fontWeight: 600,
                    }}
                  >
                    <Wind size={12} /> AC
                  </span>
                )}
                {room.has_audio && (
                  <span
                    style={{
                      display: "inline-flex",
                      alignItems: "center",
                      gap: "3px",
                      padding: "2px 6px",
                      borderRadius: "4px",
                      background: "rgba(245, 158, 11, 0.08)",
                      color: "#d97706",
                      fontWeight: 600,
                    }}
                  >
                    <Volume2 size={12} /> Audio/Mic
                  </span>
                )}
              </div>
            )}

            <p className="room-reason">
              {room.state === "UNKNOWN"
                ? "Check the room in person before use."
                : room.reason}
            </p>
          </Link>

          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              marginTop: "12px",
              paddingTop: "10px",
              borderTop: "1px solid var(--line)",
            }}
          >
            {room.state === "AVAILABLE" ? (
              canRequest(user?.role) ? (
                <Link
                  to={`/requests/new?roomId=${encodeURIComponent(room.id)}&date=${encodeURIComponent(date)}&start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`}
                  className="button button-primary button-small"
                  style={{
                    display: "inline-flex",
                    alignItems: "center",
                    gap: "4px",
                    fontSize: "12px",
                    padding: "4px 10px",
                  }}
                >
                  <CalendarPlus size={14} /> Book slot
                </Link>
              ) : (
                <span style={{ fontSize: "12px", color: "var(--success)", fontWeight: 600 }}>
                  Available slot
                </span>
              )
            ) : (
              <span style={{ fontSize: "12px", color: "var(--muted)" }}>
                {room.state === "OCCUPIED" ? "In use / reserved" : "Restricted venue"}
              </span>
            )}
            <Link
              to={`/rooms/${room.id}?date=${date}&start_time=${start}&end_time=${end}`}
              className="room-card-footer"
              style={{ margin: 0, padding: 0 }}
            >
              <span>Schedule</span>
              <ArrowUpRight size={14} />
            </Link>
          </div>
        </div>
      ))}
    </div>
  );
}
