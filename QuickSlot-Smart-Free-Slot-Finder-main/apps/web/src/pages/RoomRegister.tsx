import { useEffect, useState, type FormEvent } from "react";
import { Plus, History } from "lucide-react";
import { api } from "../lib/api";
import type { AuditLogEntry, OverviewSummary, Room } from "../lib/types";
import { ErrorText, Loading, Notice, PageHeading } from "../components/ui";
import { SelectField } from "../components/SelectField";
export function RoomRegister() {
  const [rooms, setRooms] = useState<Room[]>([]);
  const [overview, setOverview] = useState<OverviewSummary | null>(null);
  const [auditLogs, setAuditLogs] = useState<AuditLogEntry[]>([]);
  const [showAudits, setShowAudits] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [show, setShow] = useState(false);
  const [editing, setEditing] = useState<Room | null>(null);
  const [busy, setBusy] = useState(false);
  const [success, setSuccess] = useState("");

  useEffect(() => {
    Promise.all([
      api.rooms().then(setRooms),
      api.overviewSummary().then(setOverview).catch(() => null),
    ])
      .catch(setError)
      .finally(() => setLoading(false));
  }, []);

  const loadAudits = async () => {
    if (!auditLogs.length) {
      try {
        const logs = await api.auditLogs();
        setAuditLogs(logs);
      } catch (e) {
        setError(e);
      }
    }
    setShowAudits(!showAudits);
  };
  const create = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      const details = {
        building: String(data.get("building")),
        capacity: data.get("capacity") ? Number(data.get("capacity")) : null,
        floor: String(data.get("floor") || "") || null,
      };
      const room = editing
        ? await api.updateRoomDetails(editing.id, details)
        : await api.createRoom({
            code: String(data.get("code")),
            kind: String(data.get("kind")),
            building: String(data.get("building")),
            capacity: Number(data.get("capacity")),
            floor: details.floor,
          });
      setRooms((current) =>
        editing
          ? current.map((item) => (item.id === room.id ? room : item))
          : [...current, room],
      );
      setShow(false);
      setSuccess(
        `Room ${room.code} ${editing ? "details updated" : "added to the register"}.`,
      );
      setEditing(null);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="container page">
      <PageHeading
        title="Room register."
        text="Maintain the room identities used to match timetables and requests. Room codes keep their leading zeroes."
        action={
          <div style={{ display: "flex", gap: "8px", flexWrap: "wrap" }}>
            <button
              className="button button-outline"
              type="button"
              onClick={loadAudits}
            >
              <History size={16} />
              {showAudits ? "Hide audit trail" : "Audit trail"}
            </button>
            <button
              className="button button-primary"
              type="button"
              onClick={() => {
                setEditing(null);
                setShow(!show);
              }}
            >
              <Plus size={17} />
              Add a room
            </button>
          </div>
        }
      />
      {overview && (
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(210px, 1fr))",
            gap: "12px",
            margin: "16px 0 24px",
          }}
        >
          <div
            style={{
              padding: "16px",
              background: "var(--paper)",
              border: "1px solid var(--line)",
              borderRadius: "8px",
            }}
          >
            <span
              style={{
                fontSize: "12px",
                color: "var(--muted)",
                textTransform: "uppercase",
                fontWeight: 600,
              }}
            >
              Total Registered Venues
            </span>
            <div
              style={{
                fontSize: "24px",
                fontWeight: 700,
                color: "var(--accent)",
                marginTop: "4px",
              }}
            >
              {overview.total_venues}
            </div>
            <div
              style={{
                fontSize: "12px",
                color: "var(--muted)",
                marginTop: "2px",
              }}
            >
              CRs, LTs, Labs & Auditoriums
            </div>
          </div>
          <div
            style={{
              padding: "16px",
              background: "var(--paper)",
              border: "1px solid var(--line)",
              borderRadius: "8px",
            }}
          >
            <span
              style={{
                fontSize: "12px",
                color: "var(--muted)",
                textTransform: "uppercase",
                fontWeight: 600,
              }}
            >
              Active Timetable Scope
            </span>
            <div
              style={{
                fontSize: "14px",
                fontWeight: 700,
                marginTop: "6px",
                lineHeight: 1.3,
              }}
            >
              {overview.active_timetable}
            </div>
            <div
              style={{
                fontSize: "12px",
                color: "var(--muted)",
                marginTop: "2px",
              }}
            >
              Session: {overview.academic_session}
            </div>
          </div>
          <div
            style={{
              padding: "16px",
              background: "var(--paper)",
              border: "1px solid var(--line)",
              borderRadius: "8px",
            }}
          >
            <span
              style={{
                fontSize: "12px",
                color: "var(--muted)",
                textTransform: "uppercase",
                fontWeight: 600,
              }}
            >
              Pending Requests
            </span>
            <div
              style={{
                fontSize: "24px",
                fontWeight: 700,
                color: "#d97706",
                marginTop: "4px",
              }}
            >
              {overview.pending_requests}
            </div>
            <div
              style={{
                fontSize: "12px",
                color: "var(--muted)",
                marginTop: "2px",
              }}
            >
              Awaiting decision
            </div>
          </div>
          <div
            style={{
              padding: "16px",
              background: "var(--paper)",
              border: "1px solid var(--line)",
              borderRadius: "8px",
            }}
          >
            <span
              style={{
                fontSize: "12px",
                color: "var(--muted)",
                textTransform: "uppercase",
                fontWeight: 600,
              }}
            >
              Confirmed Bookings
            </span>
            <div
              style={{
                fontSize: "24px",
                fontWeight: 700,
                color: "#16a34a",
                marginTop: "4px",
              }}
            >
              {overview.confirmed_bookings}
            </div>
            <div
              style={{
                fontSize: "12px",
                color: "var(--muted)",
                marginTop: "2px",
              }}
            >
              ACID verified
            </div>
          </div>
        </div>
      )}
      {showAudits && (
        <section
          style={{
            marginBottom: "24px",
            padding: "18px",
            background: "var(--paper)",
            border: "1px solid var(--line)",
            borderRadius: "8px",
          }}
        >
          <h3 style={{ fontSize: "16px", fontWeight: 700, marginBottom: "12px" }}>
            Administrative Audit Trail
          </h3>
          {auditLogs.length ? (
            <div style={{ overflowX: "auto" }}>
              <table
                style={{
                  width: "100%",
                  fontSize: "13px",
                  borderCollapse: "collapse",
                  textAlign: "left",
                }}
              >
                <thead>
                  <tr
                    style={{
                      background: "#f8fafc",
                      borderBottom: "2px solid var(--line)",
                    }}
                  >
                    <th style={{ padding: "8px 12px" }}>Timestamp</th>
                    <th style={{ padding: "8px 12px" }}>Action</th>
                    <th style={{ padding: "8px 12px" }}>Entity</th>
                    <th style={{ padding: "8px 12px" }}>Actor</th>
                  </tr>
                </thead>
                <tbody>
                  {auditLogs.map((log) => (
                    <tr
                      key={log.id}
                      style={{ borderBottom: "1px solid var(--line)" }}
                    >
                      <td
                        style={{ padding: "8px 12px", color: "var(--muted)" }}
                      >
                        {new Date(log.created_at).toLocaleString("en-IN", {
                          timeZone: "Asia/Kolkata",
                        })}
                      </td>
                      <td style={{ padding: "8px 12px", fontWeight: 600 }}>
                        {log.action}
                      </td>
                      <td style={{ padding: "8px 12px" }}>{log.entity}</td>
                      <td style={{ padding: "8px 12px" }}>
                        {log.actor} {log.role ? `(${log.role})` : ""}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p style={{ fontSize: "13px", color: "var(--muted)" }}>
              No administrative actions recorded yet.
            </p>
          )}
        </section>
      )}
      {!!error && <ErrorText error={error} />}{" "}
      {success && <Notice tone="success">{success}</Notice>}
      {show && (
        <form
          key={editing?.id || "new"}
          className="request-sheet stack-form inventory-form"
          onSubmit={create}
        >
          <h2>
            {editing
              ? `Verify room ${editing.code} details`
              : "Add a classroom or lecture theatre"}
          </h2>
          {editing && (
            <p>
              Enter only details confirmed by the university. Leave unknown
              fields blank.
            </p>
          )}
          <div className="form-columns">
            <label>
              Room code
              <input
                name="code"
                required
                maxLength={40}
                placeholder="e.g. 017"
                defaultValue={editing?.code}
                disabled={!!editing}
              />
            </label>
            <label>
              Building
              <input
                name="building"
                required={!editing}
                maxLength={100}
                defaultValue={editing?.building}
              />
            </label>
            <label>
              Capacity
              <input
                name="capacity"
                type="number"
                required={!editing}
                min={1}
                max={5000}
                defaultValue={editing?.capacity || ""}
              />
            </label>
            <label>
              Floor
              <input
                name="floor"
                maxLength={60}
                defaultValue={editing?.floor || ""}
                placeholder="Confirmed floor"
              />
            </label>
            <label>
              Type
              <SelectField
                aria-label="Type"
                name="kind"
                defaultValue={editing?.kind || "CLASSROOM"}
                disabled={!!editing}
                options={[
                  { value: "CLASSROOM", label: "Classroom" },
                  { value: "LT", label: "Lecture theatre" },
                  { value: "LAB", label: "Computer / AI Lab" },
                  { value: "SEMINAR_HALL", label: "Seminar Hall" },
                  { value: "AUDITORIUM", label: "Auditorium" },
                ]}
              />
            </label>
          </div>
          <div className="form-actions">
            <button
              type="button"
              className="text-link"
              onClick={() => setShow(false)}
            >
              Cancel
            </button>
            <button className="button button-dark" disabled={busy}>
              {busy
                ? "Saving…"
                : editing
                  ? "Save verified details"
                  : "Add room"}
            </button>
          </div>
        </form>
      )}
      {loading ? (
        <Loading />
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Room</th>
                <th>Building</th>
                <th>Floor</th>
                <th>Type</th>
                <th>Capacity</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              {rooms.map((room) => (
                <tr key={room.id}>
                  <td>
                    <strong>{room.code}</strong>
                  </td>
                  <td>{room.building || "—"}</td>
                  <td>{room.floor || "Not confirmed"}</td>
                  <td>
                    {room.kind === "LT" ? "Lecture theatre" : "Classroom"}
                  </td>
                  <td>
                    {room.capacity === null ? "—" : `${room.capacity} seats`}
                  </td>
                  <td>
                    <button
                      type="button"
                      className="text-link"
                      onClick={() => {
                        setEditing(room);
                        setShow(true);
                      }}
                    >
                      Verify details
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
