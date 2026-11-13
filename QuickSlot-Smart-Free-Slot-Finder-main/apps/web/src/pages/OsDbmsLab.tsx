import { useState } from "react";
import {
  Cpu,
  Database,
  ShieldAlert,
  Lock,
  Play,
  CheckCircle2,
  XCircle,
  RefreshCw,
  AlertTriangle,
} from "lucide-react";
import { api } from "../lib/api";
import type {
  ConcurrencyRaceResult,
  DeadlockResult,
  FcfsJob,
  FcfsResult,
  SyncResult,
} from "../lib/types";
import { PageHeading, ErrorText } from "../components/ui";

export function OsDbmsLab() {
  const [activeModule, setActiveModule] = useState<
    "concurrency" | "fcfs" | "deadlock" | "sync"
  >("concurrency");

  // Concurrency Race State
  const [numThreads, setNumThreads] = useState(20);
  const [raceLoading, setRaceLoading] = useState(false);
  const [raceResult, setRaceResult] = useState<ConcurrencyRaceResult | null>(
    null,
  );
  const [raceError, setRaceError] = useState<unknown>(null);

  // FCFS State
  const [fcfsJobs, setFcfsJobs] = useState<FcfsJob[]>([
    { jobId: "Req-A (Auditorium)", arrivalTime: 0, burstTime: 4 },
    { jobId: "Req-B (Robotics Lab)", arrivalTime: 1, burstTime: 3 },
    { jobId: "Req-C (Seminar Hall)", arrivalTime: 2, burstTime: 5 },
    { jobId: "Req-D (Room 106)", arrivalTime: 4, burstTime: 2 },
  ]);
  const [fcfsLoading, setFcfsLoading] = useState(false);
  const [fcfsResult, setFcfsResult] = useState<FcfsResult | null>(null);
  const [fcfsError, setFcfsError] = useState<unknown>(null);

  // Deadlock State
  const [deadlockMode, setDeadlockMode] = useState<"deadlock" | "prevent">(
    "deadlock",
  );
  const [deadlockLoading, setDeadlockLoading] = useState(false);
  const [deadlockResult, setDeadlockResult] = useState<DeadlockResult | null>(
    null,
  );
  const [deadlockError, setDeadlockError] = useState<unknown>(null);

  // Sync Mutex State
  const [syncThreads, setSyncThreads] = useState(10);
  const [syncIncrements, setSyncIncrements] = useState(2000);
  const [useMutex, setUseMutex] = useState(false);
  const [syncLoading, setSyncLoading] = useState(false);
  const [syncResult, setSyncResult] = useState<SyncResult | null>(null);
  const [syncError, setSyncError] = useState<unknown>(null);

  // Runners
  const handleRunRace = async () => {
    setRaceLoading(true);
    setRaceError(null);
    try {
      const res = await api.runConcurrencyRace(numThreads);
      setRaceResult(res);
    } catch (err) {
      setRaceError(err);
    } finally {
      setRaceLoading(false);
    }
  };

  const handleRunFcfs = async () => {
    setFcfsLoading(true);
    setFcfsError(null);
    try {
      const res = await api.runFcfs(fcfsJobs);
      setFcfsResult(res);
    } catch (err) {
      setFcfsError(err);
    } finally {
      setFcfsLoading(false);
    }
  };

  const handleRunDeadlock = async () => {
    setDeadlockLoading(true);
    setDeadlockError(null);
    try {
      const res = await api.runDeadlock(deadlockMode);
      setDeadlockResult(res);
    } catch (err) {
      setDeadlockError(err);
    } finally {
      setDeadlockLoading(false);
    }
  };

  const handleRunSync = async () => {
    setSyncLoading(true);
    setSyncError(null);
    try {
      const res = await api.runSync(syncThreads, syncIncrements, useMutex);
      setSyncResult(res);
    } catch (err) {
      setSyncError(err);
    } finally {
      setSyncLoading(false);
    }
  };

  return (
    <div className="container page" style={{ maxWidth: 1100 }}>
      <PageHeading
        title="OS & DBMS Concept Verification Lab"
        text="Live interactive demonstrations connecting operating system principles and PostgreSQL ACID transaction mechanics."
      />

      {/* Module Selector Navigation */}
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(240px, 1fr))",
          gap: "12px",
          marginBottom: "24px",
        }}
      >
        <button
          onClick={() => setActiveModule("concurrency")}
          style={{
            padding: "16px",
            textAlign: "left",
            borderRadius: "8px",
            border:
              activeModule === "concurrency"
                ? "2px solid var(--accent)"
                : "1px solid var(--line)",
            background:
              activeModule === "concurrency" ? "var(--accent-soft)" : "var(--paper)",
            cursor: "pointer",
            transition: "all 0.15s ease",
          }}
        >
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: "8px",
              color: "var(--accent)",
              fontWeight: 700,
              fontSize: "15px",
            }}
          >
            <Database size={18} /> PostgreSQL GiST Exclusion
          </div>
          <p
            style={{
              fontSize: "13px",
              color: "var(--muted)",
              marginTop: "6px",
              lineHeight: 1.4,
            }}
          >
            20–50 concurrent thread race condition against PostgreSQL 18 ACID kernel.
          </p>
        </button>

        <button
          onClick={() => setActiveModule("fcfs")}
          style={{
            padding: "16px",
            textAlign: "left",
            borderRadius: "8px",
            border:
              activeModule === "fcfs"
                ? "2px solid var(--accent)"
                : "1px solid var(--line)",
            background:
              activeModule === "fcfs" ? "var(--accent-soft)" : "var(--paper)",
            cursor: "pointer",
            transition: "all 0.15s ease",
          }}
        >
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: "8px",
              color: "#166534",
              fontWeight: 700,
              fontSize: "15px",
            }}
          >
            <Cpu size={18} /> FCFS Process Scheduling
          </div>
          <p
            style={{
              fontSize: "13px",
              color: "var(--muted)",
              marginTop: "6px",
              lineHeight: 1.4,
            }}
          >
            Interactive Gantt chart, Turnaround Time (TAT) & Waiting Time (WT) analysis.
          </p>
        </button>

        <button
          onClick={() => setActiveModule("deadlock")}
          style={{
            padding: "16px",
            textAlign: "left",
            borderRadius: "8px",
            border:
              activeModule === "deadlock"
                ? "2px solid var(--accent)"
                : "1px solid var(--line)",
            background:
              activeModule === "deadlock" ? "var(--accent-soft)" : "var(--paper)",
            cursor: "pointer",
            transition: "all 0.15s ease",
          }}
        >
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: "8px",
              color: "#991b1b",
              fontWeight: 700,
              fontSize: "15px",
            }}
          >
            <ShieldAlert size={18} /> Deadlock & Resource Hierarchy
          </div>
          <p
            style={{
              fontSize: "13px",
              color: "var(--muted)",
              marginTop: "6px",
              lineHeight: 1.4,
            }}
          >
            Coffman circular wait condition vs strict lock ordering prevention.
          </p>
        </button>

        <button
          onClick={() => setActiveModule("sync")}
          style={{
            padding: "16px",
            textAlign: "left",
            borderRadius: "8px",
            border:
              activeModule === "sync"
                ? "2px solid var(--accent)"
                : "1px solid var(--line)",
            background:
              activeModule === "sync" ? "var(--accent-soft)" : "var(--paper)",
            cursor: "pointer",
            transition: "all 0.15s ease",
          }}
        >
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: "8px",
              color: "#854d0e",
              fontWeight: 700,
              fontSize: "15px",
            }}
          >
            <Lock size={18} /> Critical Section & Mutex
          </div>
          <p
            style={{
              fontSize: "13px",
              color: "var(--muted)",
              marginTop: "6px",
              lineHeight: 1.4,
            }}
          >
            Multi-threaded lost updates demonstration vs atomic Mutex synchronization.
          </p>
        </button>
      </div>

      {/* MODULE 1: CONCURRENCY RACE & POSTGRESQL EXCLUSION */}
      {activeModule === "concurrency" && (
        <div style={{ display: "flex", flexDirection: "column", gap: "20px" }}>
          <div
            style={{
              padding: "24px",
              background: "var(--paper)",
              border: "1px solid var(--line)",
              borderRadius: "8px",
            }}
          >
            <div
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "flex-start",
                flexWrap: "wrap",
                gap: "16px",
              }}
            >
              <div style={{ maxWidth: 680 }}>
                <h3
                  style={{
                    fontSize: "18px",
                    fontWeight: 700,
                    display: "flex",
                    alignItems: "center",
                    gap: "8px",
                  }}
                >
                  <Database color="var(--accent)" size={20} /> Live High-Concurrency ACID Double-Booking Race
                </h3>
                <p
                  style={{
                    margin: "8px 0 0",
                    color: "var(--muted)",
                    fontSize: "14px",
                    lineHeight: 1.5,
                  }}
                >
                  Spawns concurrent worker threads simultaneously, each firing an independent transaction targeting the <strong>exact same venue and time window</strong>.
                  Demonstrates how PostgreSQL 18's <code>EXCLUDE USING gist</code> row-level constraint enforces ACID Isolation and guarantees that <strong>exactly 1 booking succeeds</strong> while all competing requests are rejected.
                </p>
              </div>

              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: "12px",
                  background: "#f8fafc",
                  padding: "12px 16px",
                  borderRadius: "8px",
                  border: "1px solid var(--line)",
                }}
              >
                <div>
                  <label
                    htmlFor="concurrency-threads"
                    style={{ fontSize: "12px", fontWeight: 600, color: "var(--muted)", display: "block" }}
                  >
                    Threads
                  </label>
                  <select
                    id="concurrency-threads"
                    value={numThreads}
                    onChange={(e) => setNumThreads(Number(e.target.value))}
                    disabled={raceLoading}
                    style={{
                      padding: "6px 10px",
                      borderRadius: "6px",
                      border: "1px solid var(--line)",
                      fontWeight: 600,
                    }}
                  >
                    <option value={10}>10 Threads</option>
                    <option value={20}>20 Threads (Standard)</option>
                    <option value={30}>30 Threads</option>
                    <option value={50}>50 Threads (Stress)</option>
                  </select>
                </div>
                <button
                  className="button button-primary"
                  onClick={handleRunRace}
                  disabled={raceLoading}
                  style={{ alignSelf: "flex-end" }}
                >
                  {raceLoading ? (
                    <>
                      <RefreshCw size={15} className="spin" /> Executing Race…
                    </>
                  ) : (
                    <>
                      <Play size={15} /> Run Concurrency Race
                    </>
                  )}
                </button>
              </div>
            </div>

            <div
              style={{
                marginTop: "20px",
                display: "grid",
                gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))",
                gap: "16px",
                fontSize: "13px",
                background: "#fbfbfe",
                padding: "14px",
                borderRadius: "6px",
                border: "1px dashed var(--line)",
              }}
            >
              <div>
                <strong style={{ color: "#b91c1c" }}>1. The Traditional Web Bug (TOCTOU):</strong>
                <p style={{ margin: "4px 0 0", color: "var(--muted)", lineHeight: 1.4 }}>
                  A standard <code>SELECT ... WHERE NOT EXISTS</code> followed by <code>INSERT</code> suffers from Time-of-Check to Time-of-Use. Multiple requests see the slot free at the same microsecond and both insert, causing double bookings.
                </p>
              </div>
              <div>
                <strong style={{ color: "#166534" }}>2. The PostgreSQL 18 Exclusion Constraint:</strong>
                <p style={{ margin: "4px 0 0", color: "var(--muted)", lineHeight: 1.4 }}>
                  PostgreSQL enforces <code>EXCLUDE USING gist (room_id WITH =, during WITH &&)</code> in the storage engine. Even with 50 threads firing in the same millisecond, the database serializes writes and raises <code>exclusion_violation (23P01)</code>.
                </p>
              </div>
            </div>
          </div>

          {!!raceError && <ErrorText error={raceError} />}

          {raceResult && (
            <div
              style={{
                padding: "24px",
                background: "var(--paper)",
                border: "2px solid #16a34a",
                borderRadius: "8px",
              }}
            >
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  flexWrap: "wrap",
                  gap: "16px",
                  borderBottom: "1px solid var(--line)",
                  paddingBottom: "16px",
                  marginBottom: "16px",
                }}
              >
                <div>
                  <h4
                    style={{
                      margin: 0,
                      fontSize: "16px",
                      fontWeight: 700,
                      display: "flex",
                      alignItems: "center",
                      gap: "8px",
                      color: "#166534",
                    }}
                  >
                    <CheckCircle2 size={18} /> Race Completed: ACID Guarantee Verified
                  </h4>
                  <div style={{ fontSize: "13px", color: "var(--muted)", marginTop: "4px" }}>
                    Execution Time: <strong>{raceResult.execution_time_seconds}s</strong> · Constraint Status: <strong>{raceResult.is_safe ? "SAFE (Zero Double-Bookings)" : "UNSAFE"}</strong>
                  </div>
                </div>

                <div style={{ display: "flex", gap: "20px" }}>
                  <div style={{ textAlign: "center" }}>
                    <div style={{ fontSize: "24px", fontWeight: 800, color: "#166534" }}>
                      {raceResult.confirmed_bookings}
                    </div>
                    <div style={{ fontSize: "11px", color: "var(--muted)", textTransform: "uppercase" }}>
                      Booked (Winner)
                    </div>
                  </div>
                  <div style={{ textAlign: "center" }}>
                    <div style={{ fontSize: "24px", fontWeight: 800, color: "#b91c1c" }}>
                      {raceResult.rejected_conflicts}
                    </div>
                    <div style={{ fontSize: "11px", color: "var(--muted)", textTransform: "uppercase" }}>
                      Conflicts Prevented
                    </div>
                  </div>
                  <div style={{ textAlign: "center" }}>
                    <div style={{ fontSize: "24px", fontWeight: 800, color: "var(--accent)" }}>
                      {raceResult.total_concurrent_requests}
                    </div>
                    <div style={{ fontSize: "11px", color: "var(--muted)", textTransform: "uppercase" }}>
                      Total Contenders
                    </div>
                  </div>
                </div>
              </div>

              <div
                style={{
                  background: "#f0fdf4",
                  border: "1px solid #bbf7d0",
                  padding: "12px 16px",
                  borderRadius: "6px",
                  fontSize: "13px",
                  color: "#166534",
                  lineHeight: 1.5,
                }}
              >
                <strong>PostgreSQL Kernel Verification:</strong> {raceResult.verification_message}
              </div>
            </div>
          )}
        </div>
      )}

      {/* MODULE 2: FCFS PROCESS SCHEDULING */}
      {activeModule === "fcfs" && (
        <div style={{ display: "flex", flexDirection: "column", gap: "20px" }}>
          <div
            style={{
              padding: "24px",
              background: "var(--paper)",
              border: "1px solid var(--line)",
              borderRadius: "8px",
            }}
          >
            <div
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "flex-start",
                flexWrap: "wrap",
                gap: "16px",
              }}
            >
              <div style={{ maxWidth: 650 }}>
                <h3
                  style={{
                    fontSize: "18px",
                    fontWeight: 700,
                    display: "flex",
                    alignItems: "center",
                    gap: "8px",
                  }}
                >
                  <Cpu color="#166534" size={20} /> First-Come, First-Served (FCFS) CPU Scheduling
                </h3>
                <p
                  style={{
                    margin: "8px 0 0",
                    color: "var(--muted)",
                    fontSize: "14px",
                    lineHeight: 1.5,
                  }}
                >
                  Simulates non-preemptive CPU batch scheduling for incoming room booking requests. Computes standard Operating System scheduling metrics:
                  <strong> Turnaround Time (TAT = CT − AT)</strong>, <strong>Waiting Time (WT = TAT − BT)</strong>, and generates an interactive <strong>Gantt Chart</strong> timeline.
                </p>
              </div>

              <button
                className="button button-primary"
                onClick={handleRunFcfs}
                disabled={fcfsLoading}
              >
                {fcfsLoading ? (
                  <>
                    <RefreshCw size={15} className="spin" /> Calculating…
                  </>
                ) : (
                  <>
                    <Play size={15} /> Calculate Schedule
                  </>
                )}
              </button>
            </div>

            {/* Editable Job Queue */}
            <div style={{ marginTop: "20px" }}>
              <h4 style={{ fontSize: "14px", fontWeight: 700, marginBottom: "10px" }}>
                Process Request Queue
              </h4>
              <div style={{ overflowX: "auto" }}>
                <table
                  style={{
                    width: "100%",
                    borderCollapse: "collapse",
                    fontSize: "13px",
                    textAlign: "left",
                  }}
                >
                  <thead>
                    <tr style={{ background: "#f8fafc", borderBottom: "2px solid var(--line)" }}>
                      <th style={{ padding: "8px 12px" }}>Process / Request ID</th>
                      <th style={{ padding: "8px 12px" }}>Arrival Time (AT)</th>
                      <th style={{ padding: "8px 12px" }}>Burst Time (BT)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {fcfsJobs.map((job, idx) => (
                      <tr key={idx} style={{ borderBottom: "1px solid var(--line)" }}>
                        <td style={{ padding: "8px 12px", fontWeight: 600 }}>{job.jobId}</td>
                        <td style={{ padding: "8px 12px" }}>
                          <input
                            type="number"
                            min={0}
                            value={job.arrivalTime}
                            onChange={(e) => {
                              const updated = [...fcfsJobs];
                              updated[idx].arrivalTime = Number(e.target.value);
                              setFcfsJobs(updated);
                            }}
                            style={{
                              width: "70px",
                              padding: "4px 8px",
                              borderRadius: "4px",
                              border: "1px solid var(--line)",
                            }}
                          />
                        </td>
                        <td style={{ padding: "8px 12px" }}>
                          <input
                            type="number"
                            min={1}
                            value={job.burstTime}
                            onChange={(e) => {
                              const updated = [...fcfsJobs];
                              updated[idx].burstTime = Number(e.target.value);
                              setFcfsJobs(updated);
                            }}
                            style={{
                              width: "70px",
                              padding: "4px 8px",
                              borderRadius: "4px",
                              border: "1px solid var(--line)",
                            }}
                          />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          {!!fcfsError && <ErrorText error={fcfsError} />}

          {fcfsResult && (
            <div
              style={{
                padding: "24px",
                background: "var(--paper)",
                border: "1px solid var(--line)",
                borderRadius: "8px",
              }}
            >
              <h4 style={{ fontSize: "16px", fontWeight: 700, marginBottom: "16px" }}>
                Gantt Chart Timeline
              </h4>

              {/* Gantt Chart Visualizer */}
              <div
                style={{
                  display: "flex",
                  width: "100%",
                  height: "56px",
                  borderRadius: "6px",
                  overflow: "hidden",
                  border: "1px solid var(--line)",
                  marginBottom: "8px",
                }}
              >
                {fcfsResult.gantt_chart.map((block, idx) => (
                  <div
                    key={idx}
                    style={{
                      flex: block.duration,
                      background: block.is_idle
                        ? "#e2e8f0"
                        : [
                            "#6366f1",
                            "#10b981",
                            "#f59e0b",
                            "#ec4899",
                            "#8b5cf6",
                          ][idx % 5],
                      color: block.is_idle ? "#64748b" : "#fff",
                      display: "flex",
                      flexDirection: "column",
                      alignItems: "center",
                      justifyContent: "center",
                      fontWeight: 700,
                      fontSize: "12px",
                      borderRight: "1px solid rgba(255,255,255,0.3)",
                      padding: "2px",
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                      whiteSpace: "nowrap",
                    }}
                    title={`${block.job_id}: [${block.start} -> ${block.end}]`}
                  >
                    <span>{block.job_id}</span>
                    <span style={{ fontSize: "10px", opacity: 0.9 }}>
                      {block.start}–{block.end}
                    </span>
                  </div>
                ))}
              </div>

              {/* Metrics Summary Cards */}
              <div
                style={{
                  display: "grid",
                  gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
                  gap: "12px",
                  margin: "20px 0",
                }}
              >
                <div
                  style={{
                    padding: "12px",
                    background: "#f8fafc",
                    border: "1px solid var(--line)",
                    borderRadius: "6px",
                    textAlign: "center",
                  }}
                >
                  <div style={{ fontSize: "12px", color: "var(--muted)" }}>Avg Turnaround Time</div>
                  <div style={{ fontSize: "20px", fontWeight: 700, color: "var(--accent)" }}>
                    {fcfsResult.average_turnaround_time.toFixed(2)} ms
                  </div>
                </div>
                <div
                  style={{
                    padding: "12px",
                    background: "#f8fafc",
                    border: "1px solid var(--line)",
                    borderRadius: "6px",
                    textAlign: "center",
                  }}
                >
                  <div style={{ fontSize: "12px", color: "var(--muted)" }}>Avg Waiting Time</div>
                  <div style={{ fontSize: "20px", fontWeight: 700, color: "#166534" }}>
                    {fcfsResult.average_waiting_time.toFixed(2)} ms
                  </div>
                </div>
                <div
                  style={{
                    padding: "12px",
                    background: "#f8fafc",
                    border: "1px solid var(--line)",
                    borderRadius: "6px",
                    textAlign: "center",
                  }}
                >
                  <div style={{ fontSize: "12px", color: "var(--muted)" }}>System Throughput</div>
                  <div style={{ fontSize: "20px", fontWeight: 700, color: "#854d0e" }}>
                    {fcfsResult.throughput.toFixed(3)} req/ms
                  </div>
                </div>
              </div>

              {/* Detailed Metrics Table */}
              <div style={{ overflowX: "auto" }}>
                <table
                  style={{
                    width: "100%",
                    borderCollapse: "collapse",
                    fontSize: "13px",
                    textAlign: "left",
                  }}
                >
                  <thead>
                    <tr style={{ background: "#f8fafc", borderBottom: "2px solid var(--line)" }}>
                      <th style={{ padding: "8px 12px" }}>Process</th>
                      <th style={{ padding: "8px 12px" }}>Arrival (AT)</th>
                      <th style={{ padding: "8px 12px" }}>Burst (BT)</th>
                      <th style={{ padding: "8px 12px" }}>Start</th>
                      <th style={{ padding: "8px 12px" }}>Completion (CT)</th>
                      <th style={{ padding: "8px 12px" }}>TAT (CT−AT)</th>
                      <th style={{ padding: "8px 12px" }}>WT (TAT−BT)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {fcfsResult.jobs.map((m, idx) => (
                      <tr key={idx} style={{ borderBottom: "1px solid var(--line)" }}>
                        <td style={{ padding: "8px 12px", fontWeight: 600 }}>{m.job_id}</td>
                        <td style={{ padding: "8px 12px" }}>{m.arrival_time}</td>
                        <td style={{ padding: "8px 12px" }}>{m.burst_time}</td>
                        <td style={{ padding: "8px 12px" }}>{m.start_time}</td>
                        <td style={{ padding: "8px 12px" }}>{m.completion_time}</td>
                        <td style={{ padding: "8px 12px", fontWeight: 600, color: "var(--accent)" }}>
                          {m.turnaround_time}
                        </td>
                        <td style={{ padding: "8px 12px", fontWeight: 600, color: "#166534" }}>
                          {m.waiting_time}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}

      {/* MODULE 3: DEADLOCK & COFFMAN CONDITIONS */}
      {activeModule === "deadlock" && (
        <div style={{ display: "flex", flexDirection: "column", gap: "20px" }}>
          <div
            style={{
              padding: "24px",
              background: "var(--paper)",
              border: "1px solid var(--line)",
              borderRadius: "8px",
            }}
          >
            <div
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "flex-start",
                flexWrap: "wrap",
                gap: "16px",
              }}
            >
              <div style={{ maxWidth: 650 }}>
                <h3
                  style={{
                    fontSize: "18px",
                    fontWeight: 700,
                    display: "flex",
                    alignItems: "center",
                    gap: "8px",
                  }}
                >
                  <ShieldAlert color="#991b1b" size={20} /> Coffman Conditions Deadlock & Hierarchy Prevention
                </h3>
                <p
                  style={{
                    margin: "8px 0 0",
                    color: "var(--muted)",
                    fontSize: "14px",
                    lineHeight: 1.5,
                  }}
                >
                  Analyzes Edward G. Coffman Jr.'s 4 necessary conditions for system deadlock:
                  <strong> Mutual Exclusion</strong>, <strong>Hold and Wait</strong>, <strong>No Preemption</strong>, and <strong>Circular Wait</strong>.
                  Compares uncoordinated circular resource acquisition against strict <strong>Resource Hierarchy Ordering</strong>.
                </p>
              </div>

              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: "12px",
                  background: "#f8fafc",
                  padding: "12px 16px",
                  borderRadius: "8px",
                  border: "1px solid var(--line)",
                }}
              >
                <div>
                  <label
                    htmlFor="deadlock-strategy"
                    style={{ fontSize: "12px", fontWeight: 600, color: "var(--muted)", display: "block" }}
                  >
                    Strategy Mode
                  </label>
                  <select
                    id="deadlock-strategy"
                    value={deadlockMode}
                    onChange={(e) =>
                      setDeadlockMode(e.target.value as "deadlock" | "prevent")
                    }
                    disabled={deadlockLoading}
                    style={{
                      padding: "6px 10px",
                      borderRadius: "6px",
                      border: "1px solid var(--line)",
                      fontWeight: 600,
                    }}
                  >
                    <option value="deadlock">Simulate Deadlock (Circular Wait)</option>
                    <option value="prevent">Prevent via Resource Ordering</option>
                  </select>
                </div>
                <button
                  className="button button-primary"
                  onClick={handleRunDeadlock}
                  disabled={deadlockLoading}
                  style={{ alignSelf: "flex-end" }}
                >
                  {deadlockLoading ? (
                    <>
                      <RefreshCw size={15} className="spin" /> Simulating…
                    </>
                  ) : (
                    <>
                      <Play size={15} /> Execute Simulation
                    </>
                  )}
                </button>
              </div>
            </div>

            {/* Coffman Conditions Cards */}
            <div
              style={{
                marginTop: "20px",
                display: "grid",
                gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr)))",
                gap: "12px",
              }}
            >
              {[
                {
                  title: "1. Mutual Exclusion",
                  desc: "Venues and hardware locks cannot be shared simultaneously.",
                },
                {
                  title: "2. Hold and Wait",
                  desc: "Thread 1 holds Venue A while requesting Venue B.",
                },
                {
                  title: "3. No Preemption",
                  desc: "A lock cannot be forcibly reclaimed from an active thread.",
                },
                {
                  title: "4. Circular Wait",
                  desc: "Thread 1 awaits B (held by 2); Thread 2 awaits A (held by 1).",
                },
              ].map((c, i) => (
                <div
                  key={i}
                  style={{
                    padding: "12px",
                    background: "#fbfbfe",
                    borderRadius: "6px",
                    border: "1px solid var(--line)",
                    fontSize: "12px",
                  }}
                >
                  <strong style={{ color: "var(--accent)" }}>{c.title}</strong>
                  <p style={{ margin: "4px 0 0", color: "var(--muted)", lineHeight: 1.4 }}>
                    {c.desc}
                  </p>
                </div>
              ))}
            </div>
          </div>

          {!!deadlockError && <ErrorText error={deadlockError} />}

          {deadlockResult && (
            <div
              style={{
                padding: "24px",
                background: "var(--paper)",
                border: deadlockResult.deadlock_occurred
                  ? "2px solid #b91c1c"
                  : "2px solid #16a34a",
                borderRadius: "8px",
              }}
            >
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: "10px",
                  fontSize: "16px",
                  fontWeight: 700,
                  color: deadlockResult.deadlock_occurred ? "#b91c1c" : "#166534",
                  marginBottom: "12px",
                }}
              >
                {deadlockResult.deadlock_occurred ? (
                  <>
                    <AlertTriangle size={20} /> Deadlock Detected: System Circular Wait Reached
                  </>
                ) : (
                  <>
                    <CheckCircle2 size={20} /> Deadlock Prevented: Strict Resource Hierarchy Succeeded
                  </>
                )}
              </div>

              <p style={{ fontSize: "14px", color: "var(--muted)", lineHeight: 1.5, marginBottom: "16px" }}>
                {deadlockResult.explanation}
              </p>

              {/* Execution Timeline */}
              <h5 style={{ fontSize: "13px", fontWeight: 700, marginBottom: "8px" }}>
                Thread Execution Timeline
              </h5>
              <div
                style={{
                  background: "#1e1e24",
                  color: "#e2e8f0",
                  padding: "14px",
                  borderRadius: "6px",
                  fontFamily: "monospace",
                  fontSize: "12px",
                  maxHeight: "220px",
                  overflowY: "auto",
                  display: "flex",
                  flexDirection: "column",
                  gap: "6px",
                }}
              >
                {deadlockResult.execution_timeline.map((item, i) => (
                  <div key={i} style={{ display: "flex", gap: "8px" }}>
                    <span style={{ color: "#94a3b8" }}>[{i + 1}]</span>
                    <span>{item}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* MODULE 4: MUTEX & CRITICAL SECTION */}
      {activeModule === "sync" && (
        <div style={{ display: "flex", flexDirection: "column", gap: "20px" }}>
          <div
            style={{
              padding: "24px",
              background: "var(--paper)",
              border: "1px solid var(--line)",
              borderRadius: "8px",
            }}
          >
            <div
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "flex-start",
                flexWrap: "wrap",
                gap: "16px",
              }}
            >
              <div style={{ maxWidth: 650 }}>
                <h3
                  style={{
                    fontSize: "18px",
                    fontWeight: 700,
                    display: "flex",
                    alignItems: "center",
                    gap: "8px",
                  }}
                >
                  <Lock color="#854d0e" size={20} /> Critical Section & Mutex Synchronization
                </h3>
                <p
                  style={{
                    margin: "8px 0 0",
                    color: "var(--muted)",
                    fontSize: "14px",
                    lineHeight: 1.5,
                  }}
                >
                  Demonstrates the classic <strong>Race Condition</strong> problem where concurrent worker threads attempt unsynchronized read-modify-write operations on a shared memory counter.
                  Shows how uncoordinated execution results in <strong>Lost Updates</strong>, whereas an atomic <strong>Mutex (Mutual Exclusion lock)</strong> guarantees exact correctness.
                </p>
              </div>

              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: "12px",
                  background: "#f8fafc",
                  padding: "12px 16px",
                  borderRadius: "8px",
                  border: "1px solid var(--line)",
                  flexWrap: "wrap",
                }}
              >
                <div>
                  <label
                    htmlFor="sync-threads"
                    style={{ fontSize: "12px", fontWeight: 600, color: "var(--muted)", display: "block" }}
                  >
                    Threads
                  </label>
                  <select
                    id="sync-threads"
                    value={syncThreads}
                    onChange={(e) => setSyncThreads(Number(e.target.value))}
                    disabled={syncLoading}
                    style={{
                      padding: "6px 8px",
                      borderRadius: "6px",
                      border: "1px solid var(--line)",
                      fontWeight: 600,
                    }}
                  >
                    <option value={5}>5 Threads</option>
                    <option value={10}>10 Threads</option>
                    <option value={20}>20 Threads</option>
                  </select>
                </div>

                <div>
                  <label
                    htmlFor="sync-increments"
                    style={{ fontSize: "12px", fontWeight: 600, color: "var(--muted)", display: "block" }}
                  >
                    Increments / Thread
                  </label>
                  <select
                    id="sync-increments"
                    value={syncIncrements}
                    onChange={(e) => setSyncIncrements(Number(e.target.value))}
                    disabled={syncLoading}
                    style={{
                      padding: "6px 8px",
                      borderRadius: "6px",
                      border: "1px solid var(--line)",
                      fontWeight: 600,
                    }}
                  >
                    <option value={1000}>1,000</option>
                    <option value={2000}>2,000</option>
                    <option value={5000}>5,000</option>
                  </select>
                </div>

                <div style={{ display: "flex", alignItems: "center", gap: "6px", marginTop: "16px" }}>
                  <input
                    type="checkbox"
                    id="useMutexCheck"
                    checked={useMutex}
                    onChange={(e) => setUseMutex(e.target.checked)}
                    disabled={syncLoading}
                  />
                  <label
                    htmlFor="useMutexCheck"
                    style={{ fontSize: "13px", fontWeight: 700, cursor: "pointer" }}
                  >
                    Use Mutex Lock
                  </label>
                </div>

                <button
                  className="button button-primary"
                  onClick={handleRunSync}
                  disabled={syncLoading}
                  style={{ alignSelf: "flex-end" }}
                >
                  {syncLoading ? (
                    <>
                      <RefreshCw size={15} className="spin" /> Testing…
                    </>
                  ) : (
                    <>
                      <Play size={15} /> Run Counter Test
                    </>
                  )}
                </button>
              </div>
            </div>
          </div>

          {!!syncError && <ErrorText error={syncError} />}

          {syncResult && (
            <div
              style={{
                padding: "24px",
                background: "var(--paper)",
                border: syncResult.race_detected
                  ? "2px solid #b91c1c"
                  : "2px solid #16a34a",
                borderRadius: "8px",
              }}
            >
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  flexWrap: "wrap",
                  gap: "16px",
                  borderBottom: "1px solid var(--line)",
                  paddingBottom: "16px",
                  marginBottom: "16px",
                }}
              >
                <div>
                  <h4
                    style={{
                      margin: 0,
                      fontSize: "16px",
                      fontWeight: 700,
                      display: "flex",
                      alignItems: "center",
                      gap: "8px",
                      color: syncResult.race_detected ? "#b91c1c" : "#166534",
                    }}
                  >
                    {syncResult.race_detected ? (
                      <>
                        <XCircle size={18} /> Race Condition Detected: Lost Updates Occurred
                      </>
                    ) : (
                      <>
                        <CheckCircle2 size={18} /> Synchronization Verified: Counter Matches Perfectly
                      </>
                    )}
                  </h4>
                  <div style={{ fontSize: "13px", color: "var(--muted)", marginTop: "4px" }}>
                    Mode: <strong>{syncResult.use_mutex ? "Thread-Safe Mutex Lock (ReentrantLock)" : "Unsynchronized Shared Memory"}</strong>
                  </div>
                </div>

                <div style={{ display: "flex", gap: "20px" }}>
                  <div style={{ textAlign: "center" }}>
                    <div style={{ fontSize: "24px", fontWeight: 800 }}>
                      {syncResult.expected_counter.toLocaleString()}
                    </div>
                    <div style={{ fontSize: "11px", color: "var(--muted)", textTransform: "uppercase" }}>
                      Expected Count
                    </div>
                  </div>
                  <div style={{ textAlign: "center" }}>
                    <div
                      style={{
                        fontSize: "24px",
                        fontWeight: 800,
                        color: syncResult.race_detected ? "#b91c1c" : "#166534",
                      }}
                    >
                      {syncResult.actual_counter.toLocaleString()}
                    </div>
                    <div style={{ fontSize: "11px", color: "var(--muted)", textTransform: "uppercase" }}>
                      Actual Count
                    </div>
                  </div>
                  <div style={{ textAlign: "center" }}>
                    <div
                      style={{
                        fontSize: "24px",
                        fontWeight: 800,
                        color: syncResult.lost_updates > 0 ? "#b91c1c" : "#166534",
                      }}
                    >
                      {syncResult.lost_updates.toLocaleString()}
                    </div>
                    <div style={{ fontSize: "11px", color: "var(--muted)", textTransform: "uppercase" }}>
                      Lost Updates
                    </div>
                  </div>
                </div>
              </div>

              <p style={{ fontSize: "14px", color: "var(--muted)", lineHeight: 1.5 }}>
                {syncResult.critical_section_explanation}
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
