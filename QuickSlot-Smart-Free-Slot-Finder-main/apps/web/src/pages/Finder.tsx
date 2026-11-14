import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { ArrowUpRight, Clock3, Search } from "lucide-react";
import { api } from "../lib/api";
import type { Availability } from "../lib/types";
import { campusToday, displayDate, time } from "../lib/format";
import { Empty, ErrorText, Loading, Notice } from "../components/ui";
import { RoomDirectory } from "../features/availability/RoomDirectory";
import { HourSlotSelect } from "../components/HourSlotSelect";
import { SelectField } from "../components/SelectField";
import { canRequest, useAuth } from "../lib/auth";

export function Finder() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const [date, setDate] = useState(params.get("date") || campusToday());
  const [start, setStart] = useState(params.get("start_time") || "10:00");
  const [end, setEnd] = useState(params.get("end_time") || "11:00");
  const [kind, setKind] = useState(params.get("kind") || "");
  const [capacity, setCapacity] = useState("");
  const [building, setBuilding] = useState("");
  const [floor, setFloor] = useState("");
  const [state, setState] = useState("");
  const [requireProjector, setRequireProjector] = useState(false);
  const [requireAc, setRequireAc] = useState(false);
  const [data, setData] = useState<Availability | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const query = params.toString();

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    const search = new URLSearchParams(query);
    if (!search.has("date")) search.set("date", campusToday());
    if (!search.has("start_time")) search.set("start_time", "10:00");
    if (!search.has("end_time")) search.set("end_time", "11:00");
    api
      .availability(search, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setData(value);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) {
          setError(failure);
          setData(null);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [query]);

  const rooms = useMemo(
    () =>
      (data?.rooms || []).filter(
        (room) =>
          (!kind || room.kind === kind) &&
          (!building || room.building === building) &&
          (!floor || room.floor === floor) &&
          (!state || room.state === state) &&
          (!requireProjector || room.has_projector) &&
          (!requireAc || room.has_ac) &&
          (!capacity ||
            (room.capacity !== null && room.capacity >= Number(capacity))),
      ),
    [data, kind, building, floor, state, capacity, requireProjector, requireAc],
  );
  const buildings = [
    ...new Set(data?.rooms.map((room) => room.building).filter(Boolean) || []),
  ];
  const appliedStart = params.get("start_time") || "10:00";
  const floors = [
    ...new Set(
      data?.rooms
        .map((room) => room.floor)
        .filter((value): value is string => !!value) || [],
    ),
  ];
  const appliedEnd = params.get("end_time") || "11:00";

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (end <= start) {
      setError(new Error("Choose an end time after the start time."));
      return;
    }
    setParams({
      date,
      start_time: start,
      end_time: end,
      ...(kind ? { kind } : {}),
    });
  };

  return (
    <div className="container page availability-page">
      <div className="page-heading">
        <div>
          <h1>Room availability</h1>
          <p>Find classrooms and lecture theatres at Hill Dehradun.</p>
        </div>
        {canRequest(user?.role) && (
          <Link className="button button-outline" to="/requests/new">
            Request a room <ArrowUpRight size={16} />
          </Link>
        )}
      </div>
      <div className="availability-workspace">
        <aside className="search-aside" aria-labelledby="search-title">
          <div className="panel-title">
            <h2 id="search-title">Search rooms</h2>
            <span>
              <Clock3 size={13} /> IST
            </span>
          </div>
          <form className="stack-form" onSubmit={submit}>
            <label>
              Date
              <input
                required
                aria-label="Date"
                type="date"
                value={date}
                onChange={(event) => setDate(event.target.value)}
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
            {/* Standard Period Presets */}
            <div>
              <span
                style={{
                  fontSize: "12px",
                  color: "var(--muted)",
                  fontWeight: 600,
                  display: "block",
                  marginBottom: "4px",
                }}
              >
                Period presets (Timetable)
              </span>
              <div style={{ display: "flex", gap: "4px", flexWrap: "wrap" }}>
                {[
                  { label: "P1", from: "09:00", to: "10:00" },
                  { label: "P2", from: "10:00", to: "11:00" },
                  { label: "P3", from: "11:15", to: "12:15" },
                  { label: "P4", from: "12:15", to: "13:15" },
                  { label: "Lab", from: "14:00", to: "16:00" },
                  { label: "Eve", from: "16:00", to: "18:00" },
                ].map((p) => (
                  <button
                    key={p.label}
                    type="button"
                    onClick={() => {
                      setStart(p.from);
                      setEnd(p.to);
                    }}
                    style={{
                      padding: "3px 7px",
                      fontSize: "11px",
                      borderRadius: "4px",
                      border:
                        start === p.from && end === p.to
                          ? "1px solid var(--accent)"
                          : "1px solid var(--line)",
                      background:
                        start === p.from && end === p.to
                          ? "var(--accent-soft)"
                          : "var(--paper)",
                      color:
                        start === p.from && end === p.to
                          ? "var(--accent)"
                          : "var(--ink)",
                      fontWeight: 600,
                      cursor: "pointer",
                    }}
                    title={`${p.label}: ${p.from} - ${p.to}`}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            </div>
            <label>
              Venue type
              <SelectField
                aria-label="Venue type"
                value={kind}
                onValueChange={setKind}
                options={[
                  { value: "", label: "All venue types" },
                  { value: "CLASSROOM", label: "Classroom" },
                  { value: "LT", label: "Lecture theatre" },
                  { value: "LAB", label: "Computer / AI Lab" },
                  { value: "SEMINAR_HALL", label: "Seminar Hall" },
                  { value: "AUDITORIUM", label: "Auditorium" },
                ]}
              />
            </label>
            <div
              style={{
                display: "flex",
                gap: "12px",
                fontSize: "13px",
                padding: "2px 0",
              }}
            >
              <label
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: "6px",
                  cursor: "pointer",
                }}
              >
                <input
                  type="checkbox"
                  checked={requireProjector}
                  onChange={(e) => setRequireProjector(e.target.checked)}
                />
                Projector
              </label>
              <label
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: "6px",
                  cursor: "pointer",
                }}
              >
                <input
                  type="checkbox"
                  checked={requireAc}
                  onChange={(e) => setRequireAc(e.target.checked)}
                />
                AC
              </label>
            </div>
            <label>
              Minimum seats
              <input
                type="number"
                min="1"
                max="5000"
                placeholder="Any capacity"
                value={capacity}
                onChange={(event) => setCapacity(event.target.value)}
              />
            </label>
            <button className="button button-primary" disabled={loading}>
              <Search size={16} />
              Find a room
            </button>
          </form>
          <p className="physical-note">
            Check the room in person before settling in.
          </p>
        </aside>
        <section className="directory-panel" aria-labelledby="results-title">
          <div className="directory-heading">
            <div>
              <h2 id="results-title">Classrooms & lecture theatres</h2>
              <p>
                {data
                  ? `${displayDate(data.date)} · ${time(appliedStart)}–${time(appliedEnd)}`
                  : "Published room schedules"}
              </p>
            </div>
            {data && <span className="room-count">{rooms.length} rooms</span>}
          </div>
          {data && (
            <div
              className="availability-summary"
              aria-label="Room status totals"
            >
              {[
                { state: "AVAILABLE", label: "Free" },
                { state: "OCCUPIED", label: "In use / booked" },
                { state: "RESTRICTED", label: "Restricted" },
              ].map((item) => (
                <div
                  className={`summary-card summary-${item.state.toLowerCase()}`}
                  key={item.state}
                >
                  <span>{item.label}</span>
                  <strong>
                    {rooms.filter((room) => room.state === item.state).length}
                  </strong>
                </div>
              ))}
            </div>
          )}
          <div className="directory-filters">
            <label>
              Building
              <SelectField
                aria-label="Building"
                value={building}
                disabled={!buildings.length}
                onValueChange={setBuilding}
                options={[
                  {
                    value: "",
                    label: buildings.length ? "All buildings" : "Not confirmed",
                  },
                  ...buildings.map((value) => ({ value, label: value })),
                ]}
              />
            </label>
            <label>
              Floor
              <SelectField
                aria-label="Floor"
                value={floor}
                disabled={!floors.length}
                onValueChange={setFloor}
                options={[
                  {
                    value: "",
                    label: floors.length ? "All floors" : "Not confirmed",
                  },
                  ...floors.map((value) => ({ value, label: value })),
                ]}
              />
            </label>
            <label>
              Availability
              <SelectField
                aria-label="Availability"
                value={state}
                onValueChange={setState}
                options={[
                  { value: "", label: "All rooms" },
                  { value: "AVAILABLE", label: "Free" },
                  { value: "OCCUPIED", label: "In use / booked" },
                  { value: "RESTRICTED", label: "Restricted" },
                ]}
              />
            </label>
          </div>
          {error ? (
            <ErrorText error={error} />
          ) : loading ? (
            <Loading label="Checking schedules…" />
          ) : rooms.length ? (
            <RoomDirectory
              rooms={rooms}
              date={data?.date || date}
              start={appliedStart}
              end={appliedEnd}
            />
          ) : (
            <Empty title="No rooms match these filters.">
              Try another room type, building or time.
            </Empty>
          )}
          {data?.coverage_notice && (
            <Notice>
              <strong>About these results</strong>
              <p>
                Availability follows published timetables and approved bookings.
                Check the room in person before use.
              </p>
            </Notice>
          )}
        </section>
      </div>
    </div>
  );
}
