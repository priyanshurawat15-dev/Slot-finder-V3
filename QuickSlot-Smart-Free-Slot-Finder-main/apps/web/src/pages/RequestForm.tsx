import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import {
  AlertTriangle,
  ArrowLeft,
  ArrowUpRight,
  CheckCircle2,
} from "lucide-react";
import { api } from "../lib/api";
import type { ClashCheckResult, Room } from "../lib/types";
import { campusToday } from "../lib/format";
import { ErrorText, Notice, PageHeading } from "../components/ui";
import { HourSlotSelect } from "../components/HourSlotSelect";
import { SelectField } from "../components/SelectField";
export function RequestForm() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [rooms, setRooms] = useState<Room[]>([]);
  const [roomId, setRoomId] = useState(
    params.get("roomId") || params.get("room_id") || "",
  );
  const [date, setDate] = useState(params.get("date") || campusToday());
  const [start, setStart] = useState(
    params.get("start") || params.get("start_time") || "10:00",
  );
  const [end, setEnd] = useState(
    params.get("end") || params.get("end_time") || "11:00",
  );
  const [participantGroup, setParticipantGroup] = useState("");
  const [requiredEquipment, setRequiredEquipment] = useState("");
  const [purpose, setPurpose] = useState("");
  const [attendance, setAttendance] = useState("");
  const [clashStatus, setClashStatus] = useState<ClashCheckResult | null>(null);
  const [checkingClash, setCheckingClash] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const key = useRef(crypto.randomUUID());
  const lastPayload = useRef("");

  useEffect(() => {
    api.rooms().then(setRooms).catch(setError);
  }, []);

  useEffect(() => {
    if (!roomId || !date || !start || !end || end <= start) {
      setClashStatus(null);
      return;
    }
    let active = true;
    setCheckingClash(true);
    api
      .checkClash(roomId, date, start, end)
      .then((res) => {
        if (active) setClashStatus(res);
      })
      .catch(() => {
        if (active) setClashStatus(null);
      })
      .finally(() => {
        if (active) setCheckingClash(false);
      });
    return () => {
      active = false;
    };
  }, [roomId, date, start, end]);

  const selected = rooms.find((room) => room.id === roomId);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    if (end <= start) {
      setError(new Error("The end time must be after the start time."));
      return;
    }
    const payload = {
      room_id: roomId,
      date,
      start_time: start,
      end_time: end,
      purpose: purpose.trim(),
      attendance: Number(attendance),
      participant_group: participantGroup.trim() || undefined,
      required_equipment: requiredEquipment.trim() || undefined,
    };
    const serialized = JSON.stringify(payload);
    if (lastPayload.current && lastPayload.current !== serialized)
      key.current = crypto.randomUUID();
    lastPayload.current = serialized;
    setBusy(true);
    try {
      await api.createRequest(payload, key.current);
      navigate("/requests?created=1");
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="container page narrow-page">
      <Link to="/" className="back-link">
        <ArrowLeft size={16} /> Back to rooms
      </Link>
      <PageHeading
        title="Request a room."
        text="Send a request to the allocation faculty. A request becomes a reservation only after approval."
      />
      <form className="request-sheet stack-form" onSubmit={submit}>
        <div className="form-section-title">
          <span>Room & time</span>
          <span>Campus time · IST</span>
        </div>
        <label>
          Room
          <SelectField
            aria-label="Room"
            required
            value={roomId}
            onValueChange={setRoomId}
            options={[
              { value: "", label: "Choose a classroom or lecture theatre" },
              ...rooms.map((room) => ({
                value: room.id,
                label: `${room.code} (${room.kind === "LT" ? "LT" : "CR"})${room.building ? ` · ${room.building}` : ""}${room.capacity !== null ? ` · ${room.capacity} seats` : ""}`,
              })),
            ]}
          />
        </label>
        <div className="form-columns">
          <label>
            Date
            <input
              type="date"
              required
              min={campusToday()}
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </label>
          <HourSlotSelect
            start={start}
            end={end}
            onChange={(from, until) => {
              setStart(from);
              setEnd(until);
            }}
          />
        </div>

        {/* Live Clash Check Feedback */}
        {roomId && (
          <div style={{ marginTop: "4px" }}>
            {checkingClash ? (
              <div
                style={{
                  fontSize: "12px",
                  color: "var(--muted)",
                  padding: "6px 10px",
                }}
              >
                Checking room clash & timetable…
              </div>
            ) : clashStatus ? (
              clashStatus.has_clash ? (
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: "8px",
                    padding: "8px 12px",
                    borderRadius: "6px",
                    background: "#fef2f2",
                    border: "1px solid #fecaca",
                    color: "#991b1b",
                    fontSize: "13px",
                  }}
                >
                  <AlertTriangle size={16} />
                  <span>
                    <strong>Conflict Warning:</strong> {clashStatus.reason}
                  </span>
                </div>
              ) : (
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: "8px",
                    padding: "8px 12px",
                    borderRadius: "6px",
                    background: "#f0fdf4",
                    border: "1px solid #bbf7d0",
                    color: "#166534",
                    fontSize: "13px",
                  }}
                >
                  <CheckCircle2 size={16} />
                  <span>
                    <strong>Slot Clear:</strong> {clashStatus.reason}
                  </span>
                </div>
              )
            ) : null}
          </div>
        )}

        <div className="form-section-title">
          <span>Your plans</span>
        </div>
        <label>
          Club, society or student group
          <input
            type="text"
            placeholder="e.g. IEEE Student Branch, Cultural Club, CSE 3rd Year"
            value={participantGroup}
            onChange={(e) => setParticipantGroup(e.target.value)}
          />
        </label>
        <label>
          Required facilities / equipment
          <input
            type="text"
            placeholder="e.g. Projector & Collar Mic, Lab Workstations, Sound System"
            value={requiredEquipment}
            onChange={(e) => setRequiredEquipment(e.target.value)}
          />
        </label>
        <label>
          What is the room for?
          <textarea
            required
            minLength={5}
            maxLength={500}
            rows={4}
            placeholder="Tell the coordinator what you’re organising."
            value={purpose}
            onChange={(e) => setPurpose(e.target.value)}
          />
        </label>
        <label>
          Expected attendance
          <input
            type="number"
            required
            min={1}
            max={selected?.capacity || 5000}
            placeholder="Number of people"
            value={attendance}
            onChange={(e) => setAttendance(e.target.value)}
          />
        </label>
        <Notice>
          Booking hours are 08:00–18:00 IST. The coordinator can approve,
          reject, or forward your request to the HOD. The room is not held while
          a request is pending.
        </Notice>
        {!!error && <ErrorText error={error} />}
        <div className="form-actions">
          <Link to="/requests" className="text-link">
            Cancel
          </Link>
          <button
            className="button button-primary"
            disabled={busy || !rooms.length}
          >
            {busy ? "Sending request…" : "Send request"}
            <ArrowUpRight size={18} />
          </button>
        </div>
      </form>
    </div>
  );
}
