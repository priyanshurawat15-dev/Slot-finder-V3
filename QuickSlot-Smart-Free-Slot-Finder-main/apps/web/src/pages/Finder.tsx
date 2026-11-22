import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { ArrowUpRight, Clock3, Search } from "lucide-react";
import { api } from "../lib/api";
import type { Availability, Room } from "../lib/types";
import { campusToday, displayDate, time } from "../lib/format";
import { Empty, ErrorText, Loading, Notice } from "../components/ui";
import { RoomDirectory } from "../features/availability/RoomDirectory";
import { HourSlotSelect } from "../components/HourSlotSelect";
import { SelectField } from "../components/SelectField";
import { canRequest, useAuth } from "../lib/auth";

const ALLOWED_STATES = ["AVAILABLE", "OCCUPIED", "CLOSED", "UNKNOWN"] as const;

function sanitizeState(raw: string | null): string {
  if (!raw) return "";
  const upper = raw.toUpperCase().trim();
  return ALLOWED_STATES.includes(upper as (typeof ALLOWED_STATES)[number])
    ? upper
    : "";
}

function sanitizeCapacity(raw: string | null): string {
  if (!raw) return "";
  const num = Number(raw);
  return Number.isFinite(num) && num > 0 ? String(Math.floor(num)) : "";
}

function sanitizeBoolean(raw: string | null): boolean {
  if (!raw) return false;
  const lower = raw.toLowerCase().trim();
  return lower === "true" || lower === "1";
}

export function Finder() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();

  // Helper to extract sanitized values from search params
  const urlDate = params.get("date") || campusToday();
  const urlStart = params.get("start_time") || "10:00";
  const urlEnd = params.get("end_time") || "11:00";
  const urlKind = params.get("kind") || "";
  const urlBuilding = params.get("building") || "";
  const urlFloor = params.get("floor") || "";
  const urlState = sanitizeState(params.get("state"));
  const urlCapacity = sanitizeCapacity(
    params.get("min_capacity") || params.get("capacity"),
  );
  const urlProjector = sanitizeBoolean(
    params.get("has_projector") || params.get("projector"),
  );
  const urlAc = sanitizeBoolean(params.get("has_ac") || params.get("ac"));

  // Form input states initialized from URL
  const [date, setDate] = useState(urlDate);
  const [start, setStart] = useState(urlStart);
  const [end, setEnd] = useState(urlEnd);
  const [kind, setKind] = useState(urlKind);
  const [capacity, setCapacity] = useState(urlCapacity);
  const [building, setBuilding] = useState(urlBuilding);
  const [floor, setFloor] = useState(urlFloor);
  const [state, setState] = useState(urlState);
  const [requireProjector, setRequireProjector] = useState(urlProjector);
  const [requireAc, setRequireAc] = useState(urlAc);

  const [allRooms, setAllRooms] = useState<Room[]>([]);
  const [data, setData] = useState<Availability | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  // Sync state from URL when params change (handles browser back/forward navigation)
  useEffect(() => {
    setDate(urlDate);
    setStart(urlStart);
    setEnd(urlEnd);
    setKind(urlKind);
    setBuilding(urlBuilding);
    setFloor(urlFloor);
    setState(urlState);
    setCapacity(urlCapacity);
    setRequireProjector(urlProjector);
    setRequireAc(urlAc);
  }, [
    urlDate,
    urlStart,
    urlEnd,
    urlKind,
    urlBuilding,
    urlFloor,
    urlState,
    urlCapacity,
    urlProjector,
    urlAc,
  ]);

  // Fetch full room catalog for building and floor filter dropdown options
  useEffect(() => {
    api
      .rooms()
      .then(setAllRooms)
      .catch(() => {});
  }, []);

  // Update URL helper: preserves unrelated params, removes empty/defaults, supports replace
  const updateParams = (
    updates: Record<string, string | boolean | undefined>,
    replace = false,
  ) => {
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        for (const [key, val] of Object.entries(updates)) {
          if (val === undefined || val === "" || val === false) {
            next.delete(key);
          } else {
            next.set(key, String(val));
          }
        }
        // Clean up alternate aliases
        if (updates.min_capacity !== undefined) next.delete("capacity");
        if (updates.has_projector !== undefined) next.delete("projector");
        if (updates.has_ac !== undefined) next.delete("ac");
        return next;
      },
      { replace },
    );
  };

  // Fetch availability whenever search criteria change (backend filter pass-through)
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);

    // Build backend query with supported query parameters
    const apiParams = new URLSearchParams();
    apiParams.set("date", urlDate);
    apiParams.set("start_time", urlStart);
    apiParams.set("end_time", urlEnd);
    if (urlKind) apiParams.set("kind", urlKind);
    if (urlBuilding) apiParams.set("building", urlBuilding);
    if (urlFloor) apiParams.set("floor", urlFloor);
    if (urlCapacity) apiParams.set("min_capacity", urlCapacity);
    if (urlProjector) apiParams.set("has_projector", "true");
    if (urlAc) apiParams.set("has_ac", "true");

    api
      .availability(apiParams, controller.signal)
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
  }, [
    urlDate,
    urlStart,
    urlEnd,
    urlKind,
    urlBuilding,
    urlFloor,
    urlCapacity,
    urlProjector,
    urlAc,
  ]);

  // Catalog of buildings and floors for filter dropdowns (falls back to current results if allRooms empty)
  const buildings = useMemo(() => {
    const source = allRooms.length > 0 ? allRooms : data?.rooms || [];
    return [...new Set(source.map((r) => r.building).filter(Boolean))].sort();
  }, [allRooms, data]);

  const floors = useMemo(() => {
    const source = allRooms.length > 0 ? allRooms : data?.rooms || [];
    return [
      ...new Set(
        source.map((r) => r.floor).filter((f): f is string => Boolean(f)),
      ),
    ].sort();
  }, [allRooms, data]);

  // Rooms filtered by all criteria EXCEPT availability state (powers accurate summary cards)
  const matchingRoomsBeforeState = useMemo(
    () =>
      (data?.rooms || []).filter(
        (room) =>
          (!kind || room.kind.toUpperCase() === kind.toUpperCase()) &&
          (!building ||
            room.building.toUpperCase() === building.toUpperCase()) &&
          (!floor ||
            (room.floor && room.floor.toUpperCase() === floor.toUpperCase())) &&
          (!requireProjector || room.has_projector) &&
          (!requireAc || room.has_ac) &&
          (!capacity ||
            (room.capacity !== null && room.capacity >= Number(capacity))),
      ),
    [data, kind, building, floor, capacity, requireProjector, requireAc],
  );

  // Final rooms list filtered by state
  const rooms = useMemo(
    () =>
      state
        ? matchingRoomsBeforeState.filter((room) => room.state === state)
        : matchingRoomsBeforeState,
    [matchingRoomsBeforeState, state],
  );

  const appliedStart = urlStart;
  const appliedEnd = urlEnd;

  const toggleStateFilter = (targetState: string) => {
    const nextState = state === targetState ? "" : targetState;
    setState(nextState);
    updateParams({ state: nextState || undefined }, true);
  };

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    if (end <= start) {
      setError(new Error("Choose an end time after the start time."));
      return;
    }
    if (start < "08:00" || end > "20:00") {
      setError(new Error("Campus operating hours are 08:00 to 20:00."));
      return;
    }
    updateParams(
      {
        date,
        start_time: start,
        end_time: end,
        kind: kind || undefined,
        building: building || undefined,
        floor: floor || undefined,
        state: state || undefined,
        min_capacity: capacity || undefined,
        has_projector: requireProjector || undefined,
        has_ac: requireAc || undefined,
      },
      false,
    );
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
                      updateParams(
                        { start_time: p.from, end_time: p.to },
                        false,
                      );
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
                onValueChange={(val) => {
                  setKind(val);
                  updateParams({ kind: val || undefined }, true);
                }}
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
                  onChange={(e) => {
                    const checked = e.target.checked;
                    setRequireProjector(checked);
                    updateParams({ has_projector: checked || undefined }, true);
                  }}
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
                  onChange={(e) => {
                    const checked = e.target.checked;
                    setRequireAc(checked);
                    updateParams({ has_ac: checked || undefined }, true);
                  }}
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
                onBlur={() => {
                  const sanitized = sanitizeCapacity(capacity);
                  if (sanitized !== capacity) setCapacity(sanitized);
                  updateParams({ min_capacity: sanitized || undefined }, true);
                }}
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
                { state: "CLOSED", label: "Closed" },
                { state: "UNKNOWN", label: "Unverified" },
              ].map((item) => {
                const count = matchingRoomsBeforeState.filter(
                  (room) => room.state === item.state,
                ).length;
                const isActive = state === item.state;
                return (
                  <button
                    type="button"
                    className={`summary-card summary-${item.state.toLowerCase()} ${isActive ? "active" : ""}`}
                    key={item.state}
                    onClick={() => toggleStateFilter(item.state)}
                    aria-pressed={isActive}
                    title={
                      isActive
                        ? `Clear ${item.label} filter`
                        : `Filter by ${item.label} (${count})`
                    }
                  >
                    <span>{item.label}</span>
                    <strong>{count}</strong>
                  </button>
                );
              })}
            </div>
          )}
          <div className="directory-filters">
            <label>
              Building
              <SelectField
                aria-label="Building"
                value={building}
                disabled={!buildings.length}
                onValueChange={(val) => {
                  setBuilding(val);
                  updateParams({ building: val || undefined }, true);
                }}
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
                onValueChange={(val) => {
                  setFloor(val);
                  updateParams({ floor: val || undefined }, true);
                }}
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
                onValueChange={(val) => {
                  setState(val);
                  updateParams({ state: val || undefined }, true);
                }}
                options={[
                  { value: "", label: "All rooms" },
                  { value: "AVAILABLE", label: "Free" },
                  { value: "OCCUPIED", label: "In use / booked" },
                  { value: "CLOSED", label: "Closed" },
                  { value: "UNKNOWN", label: "Unverified" },
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
