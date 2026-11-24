import { useEffect, useState } from "react";
import { CheckCircle2, AlertTriangle, AlertCircle, ShieldAlert, DoorClosed, Lock, Unlock, Users, Building, CalendarCheck, RefreshCw } from "lucide-react";
import { api } from "../lib/api";
import type { FacultyMapping, PilotVerificationReport, Room, RoomClosure } from "../lib/types";
import { ErrorText, Loading, PageHeading } from "../components/ui";

export function PilotVerification() {
  const [report, setReport] = useState<PilotVerificationReport | null>(null);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [facultyMappings, setFacultyMappings] = useState<FacultyMapping[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [success, setSuccess] = useState("");
  const [activeTab, setActiveTab] = useState<"overview" | "rooms" | "faculty">("overview");

  // Room verification & closure modal states
  const [selectedRoom, setSelectedRoom] = useState<Room | null>(null);
  const [closures, setClosures] = useState<RoomClosure[]>([]);
  const [closureModalOpen, setClosureModalOpen] = useState(false);
  const [closureType, setClosureType] = useState<"TEMPORARY" | "PERMANENT">("TEMPORARY");
  const [closureReason, setClosureReason] = useState("");
  const [closureStart, setClosureStart] = useState("");
  const [closureEnd, setClosureEnd] = useState("");
  const [actionBusy, setActionBusy] = useState(false);

  // Faculty mapping modal states
  const [mappingModalOpen, setMappingModalOpen] = useState(false);
  const [selectedMapping, setSelectedMapping] = useState<FacultyMapping | null>(null);
  const [targetUserId, setTargetUserId] = useState("");
  const [targetStatus, setTargetStatus] = useState<"MAPPED" | "UNMAPPED" | "AMBIGUOUS">("MAPPED");
  const [mappingNotes, setMappingNotes] = useState("");

  const loadData = async () => {
    setLoading(true);
    try {
      const [rep, rList, fList] = await Promise.all([
        api.pilotVerificationReport(),
        api.allRooms(),
        api.listFacultyMappings(),
      ]);
      setReport(rep);
      setRooms(rList);
      setFacultyMappings(fList);
      setError(null);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const openClosureModal = async (room: Room) => {
    setSelectedRoom(room);
    setClosureReason("");
    setClosureType("TEMPORARY");
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    const startIso = `${tomorrow.toISOString().split("T")[0]}T09:00`;
    const endIso = `${tomorrow.toISOString().split("T")[0]}T17:00`;
    setClosureStart(startIso);
    setClosureEnd(endIso);
    try {
      const existing = await api.roomClosures(room.id);
      setClosures(existing);
      setClosureModalOpen(true);
    } catch (e) {
      setError(e);
    }
  };

  const handleCreateClosure = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedRoom) return;
    setActionBusy(true);
    setError(null);
    try {
      await api.createClosure(selectedRoom.id, {
        closure_type: closureType,
        reason: closureReason,
        starts_at: closureType === "TEMPORARY" ? new Date(closureStart).toISOString() : undefined,
        ends_at: closureType === "TEMPORARY" ? new Date(closureEnd).toISOString() : undefined,
      });
      setSuccess(`Closure created for ${selectedRoom.code}.`);
      setClosureModalOpen(false);
      await loadData();
    } catch (err) {
      setError(err);
    } finally {
      setActionBusy(false);
    }
  };

  const handleLiftClosure = async (closureId: string) => {
    if (!selectedRoom) return;
    setActionBusy(true);
    setError(null);
    try {
      await api.liftClosure(selectedRoom.id, closureId);
      setSuccess("Closure lifted successfully.");
      const updated = await api.roomClosures(selectedRoom.id);
      setClosures(updated);
      await loadData();
    } catch (err) {
      setError(err);
    } finally {
      setActionBusy(false);
    }
  };

  const handleToggleVerification = async (room: Room) => {
    setActionBusy(true);
    setError(null);
    try {
      await api.updateRoomVerification(room.id, {
        verified: !room.verified,
      });
      setSuccess(`Verification updated for ${room.code}.`);
      await loadData();
    } catch (err) {
      setError(err);
    } finally {
      setActionBusy(false);
    }
  };

  const openMappingModal = (m: FacultyMapping) => {
    setSelectedMapping(m);
    setTargetUserId(m.user_id || "");
    setTargetStatus(m.status);
    setMappingNotes(m.notes || "");
    setMappingModalOpen(true);
  };

  const handleUpdateMapping = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedMapping) return;
    setActionBusy(true);
    setError(null);
    try {
      await api.updateFacultyMapping(selectedMapping.timetable_faculty_id, {
        user_id: targetStatus === "MAPPED" ? targetUserId : null,
        status: targetStatus,
        notes: mappingNotes,
      });
      setSuccess(`Mapping updated for ${selectedMapping.faculty_name}.`);
      setMappingModalOpen(false);
      await loadData();
    } catch (err) {
      setError(err);
    } finally {
      setActionBusy(false);
    }
  };

  if (loading && !report) return <Loading label="Loading CSE Pilot Verification Data…" />;

  return (
    <div className="container page pilot-verification-page">
      <div className="page-header" style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <div>
          <PageHeading
            title="CSE Pilot Data Verification"
            text="Audit, verify, and validate Department of Computer Science & Engineering timetable coverage, faculty identities, and room inventory readiness."
          />
        </div>
        <button
          className="button outline"
          onClick={loadData}
          disabled={loading}
          style={{ display: "flex", alignItems: "center", gap: "6px" }}
        >
          <RefreshCw size={15} /> Refresh Data
        </button>
      </div>

      {error ? <ErrorText error={error} /> : null}
      {success && (
        <div style={{ padding: "10px 14px", background: "rgba(16, 185, 129, 0.1)", border: "1px solid #10b981", borderRadius: "6px", color: "#10b981", marginBottom: "16px" }}>
          {success}
        </div>
      )}

      {/* Tabs */}
      <div style={{ display: "flex", gap: "8px", borderBottom: "1px solid var(--border-color, #e2e8f0)", marginBottom: "20px" }}>
        <button
          className={`button ${activeTab === "overview" ? "primary" : "outline"}`}
          onClick={() => setActiveTab("overview")}
          style={{ borderBottomLeftRadius: 0, borderBottomRightRadius: 0 }}
        >
          Overview & Readiness
        </button>
        <button
          className={`button ${activeTab === "rooms" ? "primary" : "outline"}`}
          onClick={() => setActiveTab("rooms")}
          style={{ borderBottomLeftRadius: 0, borderBottomRightRadius: 0 }}
        >
          Rooms & Closures ({rooms.length})
        </button>
        <button
          className={`button ${activeTab === "faculty" ? "primary" : "outline"}`}
          onClick={() => setActiveTab("faculty")}
          style={{ borderBottomLeftRadius: 0, borderBottomRightRadius: 0 }}
        >
          Faculty Identity Mapping ({facultyMappings.length})
        </button>
      </div>

      {/* Tab 1: Overview */}
      {activeTab === "overview" && report && (
        <div>
          {/* Readiness Banner */}
          <div style={{
            padding: "16px",
            borderRadius: "8px",
            marginBottom: "20px",
            background: report.ready_for_pilot ? "rgba(16, 185, 129, 0.1)" : "rgba(245, 158, 11, 0.1)",
            border: `1px solid ${report.ready_for_pilot ? "#10b981" : "#f59e0b"}`,
            display: "flex",
            alignItems: "center",
            gap: "12px"
          }}>
            {report.ready_for_pilot ? (
              <CheckCircle2 size={28} color="#10b981" />
            ) : (
              <AlertTriangle size={28} color="#f59e0b" />
            )}
            <div>
              <h3 style={{ margin: "0 0 4px", color: report.ready_for_pilot ? "#10b981" : "#f59e0b" }}>
                {report.ready_for_pilot ? "CSE Pilot Verification Status: READY" : "CSE Pilot Verification Status: ATTENTION REQUIRED"}
              </h3>
              <p style={{ margin: 0, fontSize: "0.9rem", color: "var(--text-muted)" }}>
                {report.ready_for_pilot
                  ? "All minimum CSE pilot requirements are met. Verified rooms are active with conflict-free timetable coverage."
                  : "Resolve high-severity issues below before initiating live pilot testing."}
              </p>
            </div>
          </div>

          {/* Metric Cards */}
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))", gap: "16px", marginBottom: "24px" }}>
            <div style={{ padding: "16px", border: "1px solid var(--border-color, #e2e8f0)", borderRadius: "8px", background: "var(--surface)" }}>
              <div style={{ display: "flex", alignItems: "center", gap: "8px", marginBottom: "8px" }}>
                <Building size={18} color="#3b82f6" />
                <strong>Room Inventory</strong>
              </div>
              <div style={{ fontSize: "1.8rem", fontWeight: 700 }}>{report.room_summary.total_rooms} Venues</div>
              <div style={{ fontSize: "0.85rem", color: "var(--text-muted)", marginTop: "4px" }}>
                <span style={{ color: "#10b981" }}>{report.room_summary.verified_rooms} Verified</span> •{" "}
                <span style={{ color: "#f59e0b" }}>{report.room_summary.unverified_rooms} Unverified</span> •{" "}
                <span style={{ color: "#ef4444" }}>{report.room_summary.closed_rooms} Closed</span>
              </div>
            </div>

            <div style={{ padding: "16px", border: "1px solid var(--border-color, #e2e8f0)", borderRadius: "8px", background: "var(--surface)" }}>
              <div style={{ display: "flex", alignItems: "center", gap: "8px", marginBottom: "8px" }}>
                <Users size={18} color="#8b5cf6" />
                <strong>Faculty Identities</strong>
              </div>
              <div style={{ fontSize: "1.8rem", fontWeight: 700 }}>{report.faculty_summary.total_timetable_faculty} Teachers</div>
              <div style={{ fontSize: "0.85rem", color: "var(--text-muted)", marginTop: "4px" }}>
                <span style={{ color: "#10b981" }}>{report.faculty_summary.mapped_count} Mapped</span> •{" "}
                <span style={{ color: "#f59e0b" }}>{report.faculty_summary.unmapped_count} Unmapped</span> •{" "}
                <span style={{ color: "#ef4444" }}>{report.faculty_summary.ambiguous_count} Ambiguous</span>
              </div>
            </div>

            <div style={{ padding: "16px", border: "1px solid var(--border-color, #e2e8f0)", borderRadius: "8px", background: "var(--surface)" }}>
              <div style={{ display: "flex", alignItems: "center", gap: "8px", marginBottom: "8px" }}>
                <CalendarCheck size={18} color="#10b981" />
                <strong>Timetable Coverage</strong>
              </div>
              <div style={{ fontSize: "1.8rem", fontWeight: 700 }}>{report.timetable_summary.total_sessions} Sessions</div>
              <div style={{ fontSize: "0.85rem", color: "var(--text-muted)", marginTop: "4px" }}>
                <span>{report.timetable_summary.sessions_in_verified_rooms} In Verified Rooms</span>
                {Boolean(report.timetable_summary.online_sessions) && (
                  <> • <span>{report.timetable_summary.online_sessions} Online</span></>
                )} •{" "}
                <span style={{ color: report.timetable_summary.conflicts_count > 0 ? "#ef4444" : "#10b981" }}>
                  {report.timetable_summary.conflicts_count} Conflicts
                </span>
              </div>
            </div>
          </div>

          {/* Issues Section */}
          <div style={{ marginTop: "24px" }}>
            <h3>Data Quality Issues & Recommendations ({report.issues.length})</h3>
            {report.issues.length === 0 ? (
              <p style={{ color: "#10b981" }}>No outstanding data quality issues detected.</p>
            ) : (
              <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
                {report.issues.map((iss, idx) => (
                  <div
                    key={idx}
                    style={{
                      padding: "12px 16px",
                      borderRadius: "6px",
                      border: "1px solid var(--border-color, #e2e8f0)",
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "space-between",
                      background: "var(--surface)",
                    }}
                  >
                    <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
                      {iss.severity === "ERROR" ? (
                        <ShieldAlert size={18} color="#ef4444" />
                      ) : iss.severity === "WARNING" ? (
                        <AlertTriangle size={18} color="#f59e0b" />
                      ) : (
                        <AlertCircle size={18} color="#3b82f6" />
                      )}
                      <div>
                        <strong>[{iss.type}]</strong> {iss.message}
                      </div>
                    </div>
                    <span
                      style={{
                        fontSize: "0.75rem",
                        padding: "2px 8px",
                        borderRadius: "12px",
                        fontWeight: 600,
                        background:
                          iss.severity === "ERROR"
                            ? "rgba(239, 68, 68, 0.15)"
                            : iss.severity === "WARNING"
                              ? "rgba(245, 158, 11, 0.15)"
                              : "rgba(59, 130, 246, 0.15)",
                        color:
                          iss.severity === "ERROR"
                            ? "#ef4444"
                            : iss.severity === "WARNING"
                              ? "#f59e0b"
                              : "#3b82f6",
                      }}
                    >
                      {iss.severity}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Tab 2: Rooms & Closures */}
      {activeTab === "rooms" && (
        <div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
            <p style={{ margin: 0, color: "var(--text-muted)" }}>
              Manage CSE classrooms, lecture theatres, and specialized computer labs. Ensure physical capacities and closures are verified.
            </p>
          </div>
          <table className="table" style={{ width: "100%" }}>
            <thead>
              <tr>
                <th>Code</th>
                <th>Kind</th>
                <th>Building / Floor</th>
                <th>Capacity</th>
                <th>Verification</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rooms.map((room) => (
                <tr key={room.id}>
                  <td><strong>{room.code}</strong></td>
                  <td>{room.kind}</td>
                  <td>{room.building} {room.floor ? `(${room.floor})` : ""}</td>
                  <td>{room.capacity ? `${room.capacity} seats` : "Unspecified"}</td>
                  <td>
                    <span
                      style={{
                        padding: "3px 8px",
                        borderRadius: "10px",
                        fontSize: "0.8rem",
                        background: room.verified ? "rgba(16, 185, 129, 0.1)" : "rgba(245, 158, 11, 0.1)",
                        color: room.verified ? "#10b981" : "#f59e0b",
                        fontWeight: 600,
                      }}
                    >
                      {room.verified ? "Verified" : "Unverified"}
                    </span>
                  </td>
                  <td>
                    <span
                      style={{
                        padding: "3px 8px",
                        borderRadius: "10px",
                        fontSize: "0.8rem",
                        background: room.active ? "rgba(59, 130, 246, 0.1)" : "rgba(239, 68, 68, 0.1)",
                        color: room.active ? "#3b82f6" : "#ef4444",
                        fontWeight: 600,
                      }}
                    >
                      {room.active ? "Active" : "Closed / Inactive"}
                    </span>
                  </td>
                  <td>
                    <div style={{ display: "flex", gap: "6px" }}>
                      <button
                        className="button outline"
                        style={{ padding: "4px 8px", fontSize: "0.8rem" }}
                        onClick={() => handleToggleVerification(room)}
                        disabled={actionBusy}
                      >
                        {room.verified ? "Mark Unverified" : "Mark Verified"}
                      </button>
                      <button
                        className="button outline"
                        style={{ padding: "4px 8px", fontSize: "0.8rem", display: "flex", alignItems: "center", gap: "4px" }}
                        onClick={() => openClosureModal(room)}
                      >
                        <DoorClosed size={14} /> Closures
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Tab 3: Faculty Identity Mapping */}
      {activeTab === "faculty" && (
        <div>
          <p style={{ margin: "0 0 16px", color: "var(--text-muted)" }}>
            Map CSE timetable teacher records to verified user accounts. Uncertain or conflicting identities are marked as AMBIGUOUS and require administrator verification.
          </p>
          <table className="table" style={{ width: "100%" }}>
            <thead>
              <tr>
                <th>Timetable Faculty</th>
                <th>ID</th>
                <th>Mapped Account</th>
                <th>Status</th>
                <th>Notes</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {facultyMappings.map((m) => (
                <tr key={m.timetable_faculty_id}>
                  <td><strong>{m.faculty_name}</strong></td>
                  <td><code>{m.timetable_faculty_id}</code></td>
                  <td>
                    {m.user_name ? (
                      <div>
                        <div>{m.user_name}</div>
                        <small style={{ color: "var(--text-muted)" }}>{m.user_email}</small>
                      </div>
                    ) : (
                      <span style={{ color: "var(--text-muted)", fontStyle: "italic" }}>None</span>
                    )}
                  </td>
                  <td>
                    <span
                      style={{
                        padding: "3px 8px",
                        borderRadius: "10px",
                        fontSize: "0.8rem",
                        fontWeight: 600,
                        background:
                          m.status === "MAPPED"
                            ? "rgba(16, 185, 129, 0.15)"
                            : m.status === "AMBIGUOUS"
                              ? "rgba(239, 68, 68, 0.15)"
                              : "rgba(245, 158, 11, 0.15)",
                        color:
                          m.status === "MAPPED"
                            ? "#10b981"
                            : m.status === "AMBIGUOUS"
                              ? "#ef4444"
                              : "#f59e0b",
                      }}
                    >
                      {m.status}
                    </span>
                  </td>
                  <td style={{ fontSize: "0.85rem", color: "var(--text-muted)" }}>{m.notes || "—"}</td>
                  <td>
                    <button
                      className="button outline"
                      style={{ padding: "4px 8px", fontSize: "0.8rem" }}
                      onClick={() => openMappingModal(m)}
                    >
                      Update
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Room Closure Modal */}
      {closureModalOpen && selectedRoom && (
        <div style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.5)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100 }}>
          <div style={{ background: "var(--surface, #ffffff)", padding: "24px", borderRadius: "8px", width: "100%", maxWidth: "550px", maxHeight: "90vh", overflowY: "auto" }}>
            <h3 style={{ margin: "0 0 12px" }}>Manage Closures: {selectedRoom.code}</h3>
            
            {/* Existing Active Closures */}
            {closures.length > 0 && (
              <div style={{ marginBottom: "16px" }}>
                <strong>Active / Recorded Closures:</strong>
                <ul style={{ listStyle: "none", padding: 0, marginTop: "8px" }}>
                  {closures.map((c) => (
                    <li key={c.id} style={{ padding: "8px", border: "1px solid var(--border-color, #e2e8f0)", borderRadius: "4px", marginBottom: "6px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                      <div>
                        <div><strong>[{c.closure_type}]</strong> {c.reason}</div>
                        <small style={{ color: "var(--text-muted)" }}>
                          {c.starts_at ? new Date(c.starts_at).toLocaleString("en-IN") : ""}
                          {c.ends_at ? ` to ${new Date(c.ends_at).toLocaleString("en-IN")}` : " (Permanent)"}
                        </small>
                      </div>
                      {c.active && (
                        <button
                          className="button outline"
                          style={{ padding: "3px 6px", fontSize: "0.75rem", display: "flex", alignItems: "center", gap: "3px" }}
                          onClick={() => handleLiftClosure(c.id)}
                          disabled={actionBusy}
                        >
                          <Unlock size={12} /> Lift
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <form onSubmit={handleCreateClosure}>
              <div style={{ marginBottom: "12px" }}>
                <label style={{ display: "block", marginBottom: "4px", fontWeight: 600 }}>Closure Type</label>
                <select
                  className="select"
                  value={closureType}
                  onChange={(e) => setClosureType(e.target.value as "TEMPORARY" | "PERMANENT")}
                  style={{ width: "100%" }}
                >
                  <option value="TEMPORARY">Temporary Closure (Scheduled Maintenance)</option>
                  <option value="PERMANENT">Permanent Closure (Decommission / Reassign)</option>
                </select>
              </div>

              <div style={{ marginBottom: "12px" }}>
                <label style={{ display: "block", marginBottom: "4px", fontWeight: 600 }}>Reason</label>
                <input
                  type="text"
                  required
                  className="input"
                  placeholder="e.g. Projector replacement, AC repair, Exam center reserve"
                  value={closureReason}
                  onChange={(e) => setClosureReason(e.target.value)}
                  style={{ width: "100%" }}
                />
              </div>

              {closureType === "TEMPORARY" && (
                <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "10px", marginBottom: "16px" }}>
                  <div>
                    <label style={{ display: "block", marginBottom: "4px", fontWeight: 600 }}>Starts At</label>
                    <input
                      type="datetime-local"
                      required
                      className="input"
                      value={closureStart}
                      onChange={(e) => setClosureStart(e.target.value)}
                      style={{ width: "100%" }}
                    />
                  </div>
                  <div>
                    <label style={{ display: "block", marginBottom: "4px", fontWeight: 600 }}>Ends At</label>
                    <input
                      type="datetime-local"
                      required
                      className="input"
                      value={closureEnd}
                      onChange={(e) => setClosureEnd(e.target.value)}
                      style={{ width: "100%" }}
                    />
                  </div>
                </div>
              )}

              <div style={{ display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "16px" }}>
                <button type="button" className="button outline" onClick={() => setClosureModalOpen(false)}>
                  Cancel
                </button>
                <button type="submit" className="button primary" disabled={actionBusy}>
                  <Lock size={14} style={{ marginRight: "4px" }} /> Create Closure
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Faculty Mapping Modal */}
      {mappingModalOpen && selectedMapping && (
        <div style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.5)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 100 }}>
          <div style={{ background: "var(--surface, #ffffff)", padding: "24px", borderRadius: "8px", width: "100%", maxWidth: "480px" }}>
            <h3 style={{ margin: "0 0 12px" }}>Update Faculty Mapping</h3>
            <p style={{ margin: "0 0 12px", color: "var(--text-muted)" }}>
              Timetable Faculty: <strong>{selectedMapping.faculty_name}</strong> (<code>{selectedMapping.timetable_faculty_id}</code>)
            </p>

            <form onSubmit={handleUpdateMapping}>
              <div style={{ marginBottom: "12px" }}>
                <label style={{ display: "block", marginBottom: "4px", fontWeight: 600 }}>Status</label>
                <select
                  className="select"
                  value={targetStatus}
                  onChange={(e) => setTargetStatus(e.target.value as "MAPPED" | "UNMAPPED" | "AMBIGUOUS")}
                  style={{ width: "100%" }}
                >
                  <option value="MAPPED">MAPPED — Verified individual account</option>
                  <option value="UNMAPPED">UNMAPPED — Pending registration</option>
                  <option value="AMBIGUOUS">AMBIGUOUS — Multiple / uncertain matches</option>
                </select>
              </div>

              {targetStatus === "MAPPED" && (
                <div style={{ marginBottom: "12px" }}>
                  <label style={{ display: "block", marginBottom: "4px", fontWeight: 600 }}>User ID / Account</label>
                  <input
                    type="text"
                    required
                    className="input"
                    placeholder="Enter user UUID of faculty account"
                    value={targetUserId}
                    onChange={(e) => setTargetUserId(e.target.value)}
                    style={{ width: "100%" }}
                  />
                </div>
              )}

              <div style={{ marginBottom: "16px" }}>
                <label style={{ display: "block", marginBottom: "4px", fontWeight: 600 }}>Audit Notes / Reason</label>
                <input
                  type="text"
                  className="input"
                  placeholder="Verification note or ambiguity explanation"
                  value={mappingNotes}
                  onChange={(e) => setMappingNotes(e.target.value)}
                  style={{ width: "100%" }}
                />
              </div>

              <div style={{ display: "flex", justifyContent: "flex-end", gap: "8px" }}>
                <button type="button" className="button outline" onClick={() => setMappingModalOpen(false)}>
                  Cancel
                </button>
                <button type="submit" className="button primary" disabled={actionBusy}>
                  Save Mapping
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
