import { useEffect, useState } from "react";
import { Clock, MapPin, Search } from "lucide-react";
import { api } from "../lib/api";
import { campusToday, time } from "../lib/format";
import { useAuth } from "../lib/auth";
import { Link } from "react-router-dom";
import { SelectField } from "../components/SelectField";
import {
  Empty,
  ErrorText,
  Loading,
  Notice,
  PageHeading,
} from "../components/ui";
type Schedule = Awaited<ReturnType<typeof api.teacherSchedule>>;
export function Teachers() {
  const { user } = useAuth();
  const [teachers, setTeachers] = useState<{ id: string; name: string }[]>([]);
  const [teacher, setTeacher] = useState("");
  const [date, setDate] = useState(campusToday());
  const [query, setQuery] = useState("");
  const [schedule, setSchedule] = useState<Schedule | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    if (user) api.teachers().then(setTeachers).catch(setError);
  }, [user]);
  useEffect(() => {
    if (!teacher) return;
    let active = true;
    setLoading(true);
    setError(null);
    api
      .teacherSchedule(teacher, date)
      .then((result) => {
        if (active) setSchedule(result);
      })
      .catch((e) => {
        if (active) setError(e);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [teacher, date]);
  return (
    <div className="container page">
      <PageHeading
        title="Faculty timetable"
        text="Look up scheduled teaching venues from published timetables."
      />
      <Notice>
        Scheduled teaching venues are not live locations. A gap between classes
        does not mean a teacher is available for a meeting.
      </Notice>
      {!user ? (
        <div className="empty">
          <h2>Sign in to view teaching schedules.</h2>
          <p>Room search remains open to everyone.</p>
          <Link to="/login?returnTo=%2Fteachers" className="button button-dark">
            Sign in
          </Link>
        </div>
      ) : (
        <>
          <div className="teacher-search">
            <label>
              <span>
                <Search size={16} />
                Find a teacher
              </span>
              <input
                value={query}
                placeholder="Search by name"
                onChange={(e) => setQuery(e.target.value)}
              />
            </label>
            <label>
              Teacher
              <SelectField
                aria-label="Teacher"
                value={teacher}
                onValueChange={setTeacher}
                options={[
                  { value: "", label: "Choose a teacher" },
                  ...teachers
                    .filter((person) =>
                      person.name.toLowerCase().includes(query.toLowerCase()),
                    )
                    .map((person) => ({
                      value: person.id,
                      label: person.name,
                    })),
                ]}
              />
            </label>
            <label>
              Date
              <input
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
            </label>
          </div>

          {/* Quick Pick Faculty Pills */}
          {teachers.length > 0 && (
            <div
              style={{
                margin: "12px 0 18px",
                display: "flex",
                gap: "6px",
                flexWrap: "wrap",
                alignItems: "center",
              }}
            >
              <span
                style={{
                  fontSize: "12px",
                  color: "var(--muted)",
                  fontWeight: 600,
                }}
              >
                Frequent:
              </span>
              {teachers.slice(0, 6).map((t) => (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => setTeacher(t.id)}
                  style={{
                    padding: "3px 9px",
                    borderRadius: "20px",
                    border:
                      teacher === t.id
                        ? "1px solid var(--accent)"
                        : "1px solid var(--line)",
                    background:
                      teacher === t.id ? "var(--accent-soft)" : "var(--paper)",
                    color: teacher === t.id ? "var(--accent)" : "var(--ink)",
                    fontSize: "12px",
                    fontWeight: 500,
                    cursor: "pointer",
                  }}
                >
                  {t.name}
                </button>
              ))}
            </div>
          )}

          {!!error && <ErrorText error={error} />}
          {loading ? (
            <Loading />
          ) : schedule ? (
            <>
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  flexWrap: "wrap",
                  gap: "10px",
                  margin: "16px 0 12px",
                }}
              >
                <h2 style={{ margin: 0 }}>{schedule.teacher.name}</h2>
                {schedule.day_name && (
                  <span
                    style={{
                      padding: "4px 10px",
                      borderRadius: "6px",
                      background: "#f1f5f9",
                      fontWeight: 600,
                      fontSize: "13px",
                      color: "var(--muted)",
                    }}
                  >
                    {schedule.day_name} Schedule
                  </span>
                )}
              </div>

              {/* Next Scheduled Class Card */}
              {schedule.next_scheduled_class && (
                <div
                  style={{
                    padding: "14px 18px",
                    background: "var(--accent-soft)",
                    border: "1px solid var(--accent)",
                    borderRadius: "8px",
                    marginBottom: "16px",
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                    flexWrap: "wrap",
                    gap: "10px",
                  }}
                >
                  <div>
                    <span
                      style={{
                        fontSize: "11px",
                        fontWeight: 700,
                        color: "var(--accent)",
                        textTransform: "uppercase",
                      }}
                    >
                      Upcoming Session Today
                    </span>
                    <h3 style={{ margin: "2px 0 0", fontSize: "16px" }}>
                      {schedule.next_scheduled_class.subject}
                    </h3>
                  </div>
                  <div style={{ textAlign: "right" }}>
                    <div style={{ fontWeight: 700, fontSize: "14px" }}>
                      {time(schedule.next_scheduled_class.start_time)} –{" "}
                      {time(schedule.next_scheduled_class.end_time)}
                    </div>
                    <div style={{ fontSize: "12px", color: "var(--muted)" }}>
                      <MapPin
                        size={13}
                        style={{ display: "inline", verticalAlign: "middle" }}
                      />{" "}
                      Room {schedule.next_scheduled_class.room_code || "TBD"}{" "}
                      {schedule.next_scheduled_class.building
                        ? `(${schedule.next_scheduled_class.building})`
                        : ""}
                    </div>
                  </div>
                </div>
              )}

              {/* Free Consultation Windows Tags */}
              {schedule.free_periods && schedule.free_periods.length > 0 && (
                <div
                  style={{
                    margin: "16px 0",
                    padding: "14px",
                    background: "#f0fdf4",
                    border: "1px solid #bbf7d0",
                    borderRadius: "8px",
                  }}
                >
                  <h4
                    style={{
                      margin: "0 0 8px",
                      fontSize: "13px",
                      fontWeight: 700,
                      color: "#166534",
                    }}
                  >
                    Free Consultation Windows (No classes scheduled)
                  </h4>
                  <div style={{ display: "flex", gap: "8px", flexWrap: "wrap" }}>
                    {schedule.free_periods.map((fp, i) => (
                      <span
                        key={i}
                        style={{
                          display: "inline-flex",
                          alignItems: "center",
                          gap: "4px",
                          padding: "4px 10px",
                          borderRadius: "4px",
                          background: "#fff",
                          border: "1px solid #86efac",
                          fontSize: "12px",
                          fontWeight: 600,
                          color: "#166534",
                        }}
                      >
                        <Clock size={13} /> {time(fp.start_time)}–
                        {time(fp.end_time)} ({fp.duration_minutes} min free)
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {schedule.sessions.length ? (
                <div className="room-list">
                  {schedule.sessions.map((session, index) => (
                    <div className="teacher-session" key={index}>
                      <strong>
                        {time(session.start_time)}–{time(session.end_time)}
                      </strong>
                      <span>{session.subject}</span>
                      <span>
                        <MapPin size={16} />
                        {session.room_code
                          ? `Room ${session.room_code} · ${session.building || ""}`
                          : "Room unresolved"}
                      </span>
                    </div>
                  ))}
                </div>
              ) : (
                <Empty title="No teaching sessions in the published data for this date.">
                  This does not establish meeting availability or physical
                  location.
                </Empty>
              )}
            </>
          ) : (
            <Empty title="Start with a teacher and a date.">
              Only published, mapped teaching sessions will appear.
            </Empty>
          )}
        </>
      )}
    </div>
  );
}
