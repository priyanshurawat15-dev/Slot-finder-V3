import { useEffect, useState, type FormEvent } from "react";
import {
  UserPlus,
  Search,
  KeyRound,
  CheckCircle2,
  XCircle,
  Copy,
  Check,
  Edit2,
  UserCheck,
  AlertTriangle,
} from "lucide-react";
import { api } from "../lib/api";
import type {
  AdminAccount,
  CreateAccountInput,
  DepartmentHod,
  Room,
} from "../lib/types";
import { ErrorText, Loading, Notice, PageHeading } from "../components/ui";

const ALL_ROLES = [
  { id: "FACULTY", label: "Faculty" },
  { id: "COORDINATOR", label: "Coordinator" },
  { id: "HOD", label: "Head of Department (HOD)" },
  { id: "ADMIN", label: "Administrator" },
  { id: "STUDENT", label: "Student" },
];

export function AccountManagement() {
  const [accounts, setAccounts] = useState<AdminAccount[]>([]);
  const [hods, setHods] = useState<DepartmentHod[]>([]);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [departments, setDepartments] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [success, setSuccess] = useState("");

  // Filters
  const [searchQuery, setSearchQuery] = useState("");
  const [roleFilter, setRoleFilter] = useState("");
  const [deptFilter, setDeptFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [activeTab, setActiveTab] = useState<"accounts" | "hods">("accounts");

  // Modals
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [showAllocModal, setShowAllocModal] = useState(false);
  const [showAssignHodModal, setShowAssignHodModal] = useState(false);

  // Selection states
  const [selectedAccount, setSelectedAccount] = useState<AdminAccount | null>(null);
  const [actionBusy, setActionBusy] = useState(false);
  const [createdInvite, setCreatedInvite] = useState<{
    name: string;
    email: string;
    token: string;
    url: string;
  } | null>(null);
  const [copied, setCopied] = useState(false);

  // Form states for Create
  const [newFullName, setNewFullName] = useState("");
  const [newEmail, setNewEmail] = useState("");
  const [newEmployeeId, setNewEmployeeId] = useState("");
  const [newDepartment, setNewDepartment] = useState("CS");
  const [newPrimaryRole, setNewPrimaryRole] = useState("FACULTY");
  const [newSelectedRoles, setNewSelectedRoles] = useState<string[]>(["FACULTY"]);
  const [newSelectedRooms, setNewSelectedRooms] = useState<string[]>([]);
  const [newSetupMode, setNewSetupMode] = useState<"invite" | "password">("invite");
  const [newPassword, setNewPassword] = useState("");

  // Form states for Edit
  const [editFullName, setEditFullName] = useState("");
  const [editEmployeeId, setEditEmployeeId] = useState("");
  const [editDepartment, setEditDepartment] = useState("CS");
  const [editPrimaryRole, setEditPrimaryRole] = useState("FACULTY");
  const [editSelectedRoles, setEditSelectedRoles] = useState<string[]>([]);

  // Allocations state
  const [allocRooms, setAllocRooms] = useState<string[]>([]);

  // HOD assignment state
  const [assignDept, setAssignDept] = useState("CS");
  const [assignUserId, setAssignUserId] = useState("");

  const loadData = async () => {
    setLoading(true);
    setError(null);
    try {
      const [accs, deptList, hodList, roomList] = await Promise.all([
        api.adminAccounts({
          query: searchQuery,
          role: roleFilter,
          department: deptFilter,
          status: statusFilter,
        }),
        api.listDepartments().catch(() => ["CS", "IT", "ECE", "ME", "CIVIL"]),
        api.listDepartmentHods().catch(() => []),
        api.rooms().catch(() => []),
      ]);
      setAccounts(accs);
      setDepartments(deptList.length ? deptList : ["CS", "IT", "ECE"]);
      setHods(hodList);
      setRooms(roomList);
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [searchQuery, roleFilter, deptFilter, statusFilter]);

  const handleRoleToggle = (
    role: string,
    currentRoles: string[],
    setRoles: (roles: string[]) => void,
  ) => {
    if (currentRoles.includes(role)) {
      if (currentRoles.length === 1) return; // Keep at least one
      setRoles(currentRoles.filter((r) => r !== role));
    } else {
      setRoles([...currentRoles, role]);
    }
  };

  const handleCreateSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setActionBusy(true);
    setError(null);
    try {
      const input: CreateAccountInput = {
        full_name: newFullName.trim(),
        email: newEmail.trim().toLowerCase(),
        employee_id: newEmployeeId.trim() || undefined,
        department: newDepartment,
        role: newPrimaryRole,
        roles: Array.from(new Set([newPrimaryRole, ...newSelectedRoles])),
        room_ids: newSelectedRoles.includes("COORDINATOR") ? newSelectedRooms : [],
        send_invitation: newSetupMode === "invite",
        temporary_password: newSetupMode === "password" ? newPassword : undefined,
      };

      const res = await api.createAccount(input);
      setShowCreateModal(false);
      setSuccess(`Account created for ${newFullName}.`);

      if (res.invitation_token) {
        setCreatedInvite({
          name: newFullName,
          email: newEmail,
          token: res.invitation_token,
          url: `${window.location.origin}/activate?token=${res.invitation_token}`,
        });
      }

      // Reset form
      setNewFullName("");
      setNewEmail("");
      setNewEmployeeId("");
      setNewDepartment("CS");
      setNewPrimaryRole("FACULTY");
      setNewSelectedRoles(["FACULTY"]);
      setNewSelectedRooms([]);
      setNewPassword("");
      await loadData();
    } catch (err) {
      setError(err);
    } finally {
      setActionBusy(false);
    }
  };

  const openEditModal = (acc: AdminAccount) => {
    setSelectedAccount(acc);
    setEditFullName(acc.full_name);
    setEditEmployeeId(acc.employee_id || "");
    setEditDepartment(acc.department || "CS");
    setEditPrimaryRole(acc.role);
    setEditSelectedRoles(acc.roles || [acc.role]);
    setShowEditModal(true);
  };

  const handleEditSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!selectedAccount) return;
    setActionBusy(true);
    setError(null);
    try {
      await api.updateAccount(selectedAccount.id, {
        full_name: editFullName.trim(),
        employee_id: editEmployeeId.trim() || undefined,
        department: editDepartment,
        role: editPrimaryRole,
        roles: Array.from(new Set([editPrimaryRole, ...editSelectedRoles])),
      });
      setShowEditModal(false);
      setSuccess(`Account updated for ${editFullName}.`);
      await loadData();
    } catch (err) {
      setError(err);
    } finally {
      setActionBusy(false);
    }
  };

  const handleStatusToggle = async (acc: AdminAccount) => {
    const nextStatus = acc.status === "DEACTIVATED" ? "ACTIVE" : "DEACTIVATED";
    const confirmMsg =
      nextStatus === "DEACTIVATED"
        ? `Are you sure you want to deactivate ${acc.full_name}? All their active sessions will be terminated immediately.`
        : `Reactivate account for ${acc.full_name}?`;

    if (!window.confirm(confirmMsg)) return;

    setActionBusy(true);
    setError(null);
    try {
      await api.setAccountStatus(acc.id, nextStatus);
      setSuccess(
        `Account ${acc.full_name} is now ${nextStatus.toLowerCase()}.`,
      );
      await loadData();
    } catch (err) {
      setError(err);
    } finally {
      setActionBusy(false);
    }
  };

  const openAllocModal = (acc: AdminAccount) => {
    setSelectedAccount(acc);
    setAllocRooms(acc.allocated_rooms?.map((r) => r.id) || []);
    setShowAllocModal(true);
  };

  const handleAllocSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!selectedAccount) return;
    setActionBusy(true);
    setError(null);
    try {
      await api.updateAllocations(selectedAccount.id, allocRooms);
      setShowAllocModal(false);
      setSuccess(`Venue allocations updated for ${selectedAccount.full_name}.`);
      await loadData();
    } catch (err) {
      setError(err);
    } finally {
      setActionBusy(false);
    }
  };

  const handleAssignHodSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!assignUserId) return;
    setActionBusy(true);
    setError(null);
    try {
      await api.assignHod({
        department: assignDept,
        user_id: assignUserId,
        campus_id: "gehu-hill-dehradun",
      });
      setShowAssignHodModal(false);
      setSuccess(`HOD assigned for ${assignDept} department.`);
      await loadData();
    } catch (err) {
      setError(err);
    } finally {
      setActionBusy(false);
    }
  };

  const copyInviteUrl = () => {
    if (createdInvite) {
      navigator.clipboard.writeText(createdInvite.url);
      setCopied(true);
      setTimeout(() => setCopied(false), 2500);
    }
  };

  // Stats computation
  const totalCount = accounts.length;
  const activeCount = accounts.filter((a) => a.status === "ACTIVE").length;
  const invitedCount = accounts.filter((a) => a.status === "INVITED").length;
  const deactivatedCount = accounts.filter((a) => a.status === "DEACTIVATED").length;

  return (
    <div className="container page">
      <PageHeading
        title="Account & Permission Management"
        text="Manage individual CSE faculty accounts, role scopes, venue coordinator authority, and departmental HOD assignments."
        action={
          <button
            type="button"
            className="button button-primary"
            onClick={() => setShowCreateModal(true)}
            style={{ display: "inline-flex", alignItems: "center", gap: "8px" }}
          >
            <UserPlus size={16} /> Add Individual Account
          </button>
        }
      />

      {!!error && <ErrorText error={error} />}
      {success && <Notice tone="success">{success}</Notice>}

      {/* Invitation Token Card if created */}
      {createdInvite && (
        <section
          style={{
            marginBottom: "24px",
            padding: "20px",
            background: "#eff6ff",
            border: "1px solid #bfdbfe",
            borderRadius: "8px",
          }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
            <div>
              <h3 style={{ fontSize: "16px", fontWeight: 700, color: "#1e40af", marginBottom: "4px" }}>
                Account Created: Activation Link Ready
              </h3>
              <p style={{ fontSize: "13px", color: "#1e3a8a", margin: 0 }}>
                Share this activation link with <strong>{createdInvite.name}</strong> ({createdInvite.email}). They will use it to securely set their password and activate their account.
              </p>
            </div>
            <button
              type="button"
              className="icon-button"
              onClick={() => setCreatedInvite(null)}
              aria-label="Close notice"
            >
              <XCircle size={18} />
            </button>
          </div>
          <div
            style={{
              marginTop: "12px",
              display: "flex",
              alignItems: "center",
              gap: "8px",
              background: "#fff",
              padding: "8px 12px",
              borderRadius: "6px",
              border: "1px solid #cbd5e1",
            }}
          >
            <input
              type="text"
              readOnly
              value={createdInvite.url}
              style={{
                flex: 1,
                border: "none",
                background: "transparent",
                fontSize: "13px",
                fontFamily: "monospace",
                color: "#0f172a",
              }}
            />
            <button
              type="button"
              className="button button-small button-outline"
              onClick={copyInviteUrl}
              style={{ display: "inline-flex", alignItems: "center", gap: "6px", minHeight: "36px" }}
            >
              {copied ? <Check size={14} color="#16a34a" /> : <Copy size={14} />}
              {copied ? "Copied" : "Copy Link"}
            </button>
          </div>
        </section>
      )}

      {/* Metrics Banner */}
      <section
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
          gap: "16px",
          marginBottom: "24px",
        }}
      >
        <div style={{ padding: "16px", background: "var(--paper)", border: "1px solid var(--line)", borderRadius: "8px" }}>
          <span style={{ fontSize: "12px", color: "var(--muted)", textTransform: "uppercase", fontWeight: 600 }}>Total Accounts</span>
          <div style={{ fontSize: "24px", fontWeight: 700, color: "var(--ink)", marginTop: "4px" }}>{totalCount}</div>
          <div style={{ fontSize: "12px", color: "var(--muted)", marginTop: "2px" }}>Across all departments</div>
        </div>
        <div style={{ padding: "16px", background: "var(--paper)", border: "1px solid var(--line)", borderRadius: "8px" }}>
          <span style={{ fontSize: "12px", color: "var(--muted)", textTransform: "uppercase", fontWeight: 600 }}>Active Members</span>
          <div style={{ fontSize: "24px", fontWeight: 700, color: "#16a34a", marginTop: "4px" }}>{activeCount}</div>
          <div style={{ fontSize: "12px", color: "var(--muted)", marginTop: "2px" }}>Currently authenticated</div>
        </div>
        <div style={{ padding: "16px", background: "var(--paper)", border: "1px solid var(--line)", borderRadius: "8px" }}>
          <span style={{ fontSize: "12px", color: "var(--muted)", textTransform: "uppercase", fontWeight: 600 }}>Pending Activation</span>
          <div style={{ fontSize: "24px", fontWeight: 700, color: "#d97706", marginTop: "4px" }}>{invitedCount}</div>
          <div style={{ fontSize: "12px", color: "var(--muted)", marginTop: "2px" }}>Invited via link</div>
        </div>
        <div style={{ padding: "16px", background: "var(--paper)", border: "1px solid var(--line)", borderRadius: "8px" }}>
          <span style={{ fontSize: "12px", color: "var(--muted)", textTransform: "uppercase", fontWeight: 600 }}>Deactivated</span>
          <div style={{ fontSize: "24px", fontWeight: 700, color: "#dc2626", marginTop: "4px" }}>{deactivatedCount}</div>
          <div style={{ fontSize: "12px", color: "var(--muted)", marginTop: "2px" }}>Revoked access</div>
        </div>
      </section>

      {/* Tabs */}
      <div style={{ display: "flex", gap: "12px", borderBottom: "1px solid var(--line)", marginBottom: "20px" }}>
        <button
          type="button"
          onClick={() => setActiveTab("accounts")}
          style={{
            padding: "8px 16px",
            border: "none",
            background: "none",
            fontWeight: 600,
            fontSize: "14px",
            color: activeTab === "accounts" ? "var(--accent)" : "var(--muted)",
            borderBottom: activeTab === "accounts" ? "2px solid var(--accent)" : "none",
            cursor: "pointer",
          }}
        >
          User Accounts & Permissions
        </button>
        <button
          type="button"
          onClick={() => setActiveTab("hods")}
          style={{
            padding: "8px 16px",
            border: "none",
            background: "none",
            fontWeight: 600,
            fontSize: "14px",
            color: activeTab === "hods" ? "var(--accent)" : "var(--muted)",
            borderBottom: activeTab === "hods" ? "2px solid var(--accent)" : "none",
            cursor: "pointer",
          }}
        >
          Department HOD Leadership ({hods.length})
        </button>
      </div>

      {activeTab === "accounts" ? (
        <>
          {/* Search & Filters Bar */}
          <div
            style={{
              display: "flex",
              flexWrap: "wrap",
              gap: "12px",
              marginBottom: "16px",
              alignItems: "center",
              background: "var(--paper)",
              padding: "12px",
              borderRadius: "8px",
              border: "1px solid var(--line)",
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: "8px", flex: "1 1 240px" }}>
              <Search size={16} color="var(--muted)" />
              <input
                type="text"
                placeholder="Search by name, email, or employee ID…"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                style={{ width: "100%", padding: "6px 10px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
              />
            </div>

            <div style={{ display: "flex", gap: "8px", flexWrap: "wrap" }}>
              <select
                value={roleFilter}
                onChange={(e) => setRoleFilter(e.target.value)}
                style={{ padding: "6px 10px", fontSize: "13px", border: "1px solid var(--line)", borderRadius: "4px" }}
              >
                <option value="">All Roles</option>
                {ALL_ROLES.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.label}
                  </option>
                ))}
              </select>

              <select
                value={deptFilter}
                onChange={(e) => setDeptFilter(e.target.value)}
                style={{ padding: "6px 10px", fontSize: "13px", border: "1px solid var(--line)", borderRadius: "4px" }}
              >
                <option value="">All Departments</option>
                {departments.map((d) => (
                  <option key={d} value={d}>
                    {d}
                  </option>
                ))}
              </select>

              <select
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                style={{ padding: "6px 10px", fontSize: "13px", border: "1px solid var(--line)", borderRadius: "4px" }}
              >
                <option value="">All Statuses</option>
                <option value="ACTIVE">Active</option>
                <option value="INVITED">Invited</option>
                <option value="DEACTIVATED">Deactivated</option>
              </select>

              <button
                type="button"
                className="button button-small button-outline"
                onClick={() => {
                  setSearchQuery("");
                  setRoleFilter("");
                  setDeptFilter("");
                  setStatusFilter("");
                }}
                style={{ minHeight: "36px" }}
              >
                Reset
              </button>
            </div>
          </div>

          {/* Accounts Table */}
          {loading ? (
            <Loading label="Loading accounts…" />
          ) : (
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>User / Email</th>
                    <th>Employee ID</th>
                    <th>Department</th>
                    <th>Assigned Roles</th>
                    <th>Status</th>
                    <th>Venue Scope</th>
                    <th style={{ textAlign: "right" }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {accounts.length ? (
                    accounts.map((acc) => (
                      <tr key={acc.id}>
                        <td>
                          <div style={{ fontWeight: 600 }}>{acc.full_name}</div>
                          <div style={{ fontSize: "12px", color: "var(--muted)" }}>{acc.email}</div>
                        </td>
                        <td>
                          {acc.employee_id ? (
                            <code style={{ fontSize: "12px", background: "#f1f5f9", padding: "2px 6px", borderRadius: "4px" }}>
                              {acc.employee_id}
                            </code>
                          ) : (
                            <span style={{ color: "var(--muted)" }}>—</span>
                          )}
                        </td>
                        <td>
                          <span style={{ fontWeight: 500 }}>{acc.department || "CS"}</span>
                        </td>
                        <td>
                          <div style={{ display: "flex", gap: "4px", flexWrap: "wrap" }}>
                            {(acc.roles || [acc.role]).map((r) => {
                              const isCoord = r === "COORDINATOR";
                              const isHod = r === "HOD";
                              const isAdmin = r === "ADMIN";
                              const bg = isAdmin ? "#fee2e2" : isHod ? "#fef3c7" : isCoord ? "#f3e8ff" : "#e0f2fe";
                              const col = isAdmin ? "#991b1b" : isHod ? "#92400e" : isCoord ? "#6b21a8" : "#075985";
                              return (
                                <span
                                  key={r}
                                  style={{
                                    fontSize: "11px",
                                    fontWeight: 600,
                                    padding: "2px 6px",
                                    borderRadius: "4px",
                                    background: bg,
                                    color: col,
                                  }}
                                >
                                  {r}
                                </span>
                              );
                            })}
                          </div>
                        </td>
                        <td>
                          {acc.status === "ACTIVE" ? (
                            <span style={{ fontSize: "12px", color: "#16a34a", fontWeight: 600, display: "inline-flex", alignItems: "center", gap: "4px" }}>
                              <CheckCircle2 size={13} /> Active
                            </span>
                          ) : acc.status === "INVITED" ? (
                            <span style={{ fontSize: "12px", color: "#d97706", fontWeight: 600, display: "inline-flex", alignItems: "center", gap: "4px" }}>
                              <KeyRound size={13} /> Invited
                            </span>
                          ) : (
                            <span style={{ fontSize: "12px", color: "#dc2626", fontWeight: 600, display: "inline-flex", alignItems: "center", gap: "4px" }}>
                              <XCircle size={13} /> Deactivated
                            </span>
                          )}
                        </td>
                        <td>
                          {(acc.roles || []).includes("COORDINATOR") ? (
                            acc.allocated_rooms && acc.allocated_rooms.length > 0 ? (
                              <button
                                type="button"
                                onClick={() => openAllocModal(acc)}
                                style={{
                                  border: "none",
                                  background: "#f1f5f9",
                                  padding: "3px 8px",
                                  borderRadius: "4px",
                                  fontSize: "12px",
                                  cursor: "pointer",
                                  color: "#0f172a",
                                }}
                              >
                                {acc.allocated_rooms.length} room(s)
                              </button>
                            ) : (
                              <button
                                type="button"
                                onClick={() => openAllocModal(acc)}
                                style={{
                                  border: "none",
                                  background: "#fef2f2",
                                  padding: "3px 8px",
                                  borderRadius: "4px",
                                  fontSize: "12px",
                                  cursor: "pointer",
                                  color: "#b91c1c",
                                }}
                              >
                                + Assign Rooms
                              </button>
                            )
                          ) : (
                            <span style={{ color: "var(--muted)", fontSize: "12px" }}>—</span>
                          )}
                        </td>
                        <td style={{ textAlign: "right" }}>
                          <div style={{ display: "inline-flex", gap: "6px" }}>
                            <button
                              type="button"
                              className="button button-small button-outline"
                              onClick={() => openEditModal(acc)}
                              title="Edit account & roles"
                              style={{ padding: "4px 8px", minHeight: "32px", fontSize: "12px" }}
                            >
                              <Edit2 size={13} /> Edit
                            </button>
                            <button
                              type="button"
                              className="button button-small button-outline"
                              onClick={() => handleStatusToggle(acc)}
                              disabled={actionBusy}
                              style={{
                                padding: "4px 8px",
                                minHeight: "32px",
                                fontSize: "12px",
                                color: acc.status === "DEACTIVATED" ? "#16a34a" : "#dc2626",
                                borderColor: acc.status === "DEACTIVATED" ? "#bbf7d0" : "#fecaca",
                              }}
                            >
                              {acc.status === "DEACTIVATED" ? "Reactivate" : "Deactivate"}
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan={7} style={{ textAlign: "center", padding: "24px", color: "var(--muted)" }}>
                        No accounts match your current filters.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
        </>
      ) : (
        /* HOD Leadership Tab */
        <section>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
            <div>
              <h3 style={{ fontSize: "16px", fontWeight: 700, margin: 0 }}>Department Head Authority</h3>
              <p style={{ fontSize: "13px", color: "var(--muted)", margin: "2px 0 0" }}>
                Each academic department is guided by an appointed HOD. Requests forwarded by venue coordinators are routed exclusively to that department's active HOD.
              </p>
            </div>
            <button
              type="button"
              className="button button-primary"
              onClick={() => setShowAssignHodModal(true)}
              style={{ display: "inline-flex", alignItems: "center", gap: "6px" }}
            >
              <UserCheck size={16} /> Assign Department HOD
            </button>
          </div>

          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Department</th>
                  <th>Current Head of Department</th>
                  <th>Employee ID</th>
                  <th>Email</th>
                  <th>Assigned Date</th>
                  <th style={{ textAlign: "right" }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {hods.length ? (
                  hods.map((h) => (
                    <tr key={h.id}>
                      <td style={{ fontWeight: 700 }}>{h.department}</td>
                      <td style={{ fontWeight: 600 }}>{h.user_name}</td>
                      <td>
                        <code style={{ fontSize: "12px", background: "#f1f5f9", padding: "2px 6px", borderRadius: "4px" }}>
                          {h.employee_id || "—"}
                        </code>
                      </td>
                      <td style={{ color: "var(--muted)", fontSize: "13px" }}>{h.user_email}</td>
                      <td style={{ fontSize: "12px", color: "var(--muted)" }}>
                        {new Date(h.assigned_at).toLocaleDateString("en-IN")}
                      </td>
                      <td style={{ textAlign: "right" }}>
                        <button
                          type="button"
                          className="button button-small button-outline"
                          onClick={() => {
                            setAssignDept(h.department);
                            setShowAssignHodModal(true);
                          }}
                          style={{ padding: "4px 8px", minHeight: "32px", fontSize: "12px" }}
                        >
                          Reassign
                        </button>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan={6} style={{ textAlign: "center", padding: "24px", color: "var(--muted)" }}>
                      No department HODs configured yet. Click "Assign Department HOD" above.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {/* CREATE ACCOUNT MODAL */}
      {showCreateModal && (
        <div
          style={{
            position: "fixed",
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: "rgba(15, 23, 42, 0.6)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 1000,
            padding: "16px",
          }}
        >
          <div
            style={{
              background: "#fff",
              borderRadius: "8px",
              maxWidth: "560px",
              width: "100%",
              maxHeight: "90vh",
              overflowY: "auto",
              padding: "24px",
              boxShadow: "0 20px 25px -5px rgba(0, 0, 0, 0.1)",
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
              <h3 style={{ fontSize: "18px", fontWeight: 700, margin: 0 }}>Create Individual Account</h3>
              <button
                type="button"
                className="icon-button"
                onClick={() => setShowCreateModal(false)}
                aria-label="Close"
              >
                <XCircle size={20} />
              </button>
            </div>

            <form onSubmit={handleCreateSubmit} style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
              <div>
                <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                  Full Name *
                </label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Dr. Rajesh Sharma"
                  value={newFullName}
                  onChange={(e) => setNewFullName(e.target.value)}
                  style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                />
              </div>

              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "12px" }}>
                <div>
                  <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                    Email Address *
                  </label>
                  <input
                    type="email"
                    required
                    placeholder="e.g. rajesh.sharma@gehu.ac.in"
                    value={newEmail}
                    onChange={(e) => setNewEmail(e.target.value)}
                    style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                  />
                </div>
                <div>
                  <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                    Employee / Roll ID
                  </label>
                  <input
                    type="text"
                    placeholder="e.g. GEHU-CS-101"
                    value={newEmployeeId}
                    onChange={(e) => setNewEmployeeId(e.target.value)}
                    style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                  />
                </div>
              </div>

              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "12px" }}>
                <div>
                  <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                    Academic Department
                  </label>
                  <select
                    value={newDepartment}
                    onChange={(e) => setNewDepartment(e.target.value)}
                    style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                  >
                    {departments.map((d) => (
                      <option key={d} value={d}>
                        {d}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                    Primary Role *
                  </label>
                  <select
                    value={newPrimaryRole}
                    onChange={(e) => {
                      const r = e.target.value;
                      setNewPrimaryRole(r);
                      if (!newSelectedRoles.includes(r)) {
                        setNewSelectedRoles([...newSelectedRoles, r]);
                      }
                    }}
                    style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                  >
                    {ALL_ROLES.map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.label}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              <div>
                <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "6px" }}>
                  Assigned Roles (Multi-Role Support)
                </label>
                <div style={{ display: "flex", flexWrap: "wrap", gap: "8px" }}>
                  {ALL_ROLES.map((r) => {
                    const checked = newSelectedRoles.includes(r.id);
                    return (
                      <label
                        key={r.id}
                        style={{
                          display: "inline-flex",
                          alignItems: "center",
                          gap: "6px",
                          fontSize: "13px",
                          padding: "6px 10px",
                          borderRadius: "6px",
                          border: checked ? "1px solid var(--accent)" : "1px solid var(--line)",
                          background: checked ? "#f3e8ff" : "#fff",
                          cursor: "pointer",
                        }}
                      >
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => handleRoleToggle(r.id, newSelectedRoles, setNewSelectedRoles)}
                        />
                        {r.label}
                      </label>
                    );
                  })}
                </div>
              </div>

              {/* If Coordinator selected, allow selecting rooms */}
              {newSelectedRoles.includes("COORDINATOR") && (
                <div style={{ background: "#f8fafc", padding: "12px", borderRadius: "6px", border: "1px solid var(--line)" }}>
                  <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                    Allocate Managed Rooms (Coordinator Scope)
                  </label>
                  <p style={{ fontSize: "12px", color: "var(--muted)", margin: "0 0 8px" }}>
                    This coordinator will only receive booking requests for selected rooms.
                  </p>
                  <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(130px, 1fr))", gap: "6px", maxHeight: "140px", overflowY: "auto" }}>
                    {rooms.map((room) => {
                      const isSel = newSelectedRooms.includes(room.id);
                      return (
                        <label
                          key={room.id}
                          style={{
                            display: "flex",
                            alignItems: "center",
                            gap: "6px",
                            fontSize: "12px",
                            padding: "4px 8px",
                            borderRadius: "4px",
                            background: isSel ? "#e0e7ff" : "#fff",
                            border: "1px solid var(--line)",
                            cursor: "pointer",
                          }}
                        >
                          <input
                            type="checkbox"
                            checked={isSel}
                            onChange={() => {
                              if (isSel) {
                                setNewSelectedRooms(newSelectedRooms.filter((id) => id !== room.id));
                              } else {
                                setNewSelectedRooms([...newSelectedRooms, room.id]);
                              }
                            }}
                          />
                          <span>{room.code}</span>
                        </label>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Setup Mode: Invitation vs Initial Password */}
              <div>
                <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "6px" }}>
                  Account Onboarding Method
                </label>
                <div style={{ display: "flex", gap: "16px" }}>
                  <label style={{ display: "flex", alignItems: "center", gap: "6px", fontSize: "13px", cursor: "pointer" }}>
                    <input
                      type="radio"
                      name="setupMode"
                      checked={newSetupMode === "invite"}
                      onChange={() => setNewSetupMode("invite")}
                    />
                    Generate Activation Link (Recommended)
                  </label>
                  <label style={{ display: "flex", alignItems: "center", gap: "6px", fontSize: "13px", cursor: "pointer" }}>
                    <input
                      type="radio"
                      name="setupMode"
                      checked={newSetupMode === "password"}
                      onChange={() => setNewSetupMode("password")}
                    />
                    Set Temporary Password
                  </label>
                </div>
                {newSetupMode === "password" && (
                  <div style={{ marginTop: "10px" }}>
                    <input
                      type="password"
                      placeholder="Enter initial password (min 8 chars)"
                      minLength={8}
                      required={newSetupMode === "password"}
                      value={newPassword}
                      onChange={(e) => setNewPassword(e.target.value)}
                      style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                    />
                  </div>
                )}
              </div>

              <div style={{ display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "12px" }}>
                <button
                  type="button"
                  className="button button-outline"
                  onClick={() => setShowCreateModal(false)}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="button button-primary"
                  disabled={actionBusy}
                >
                  {actionBusy ? "Creating Account…" : "Create Account"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* EDIT ACCOUNT MODAL */}
      {showEditModal && selectedAccount && (
        <div
          style={{
            position: "fixed",
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: "rgba(15, 23, 42, 0.6)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 1000,
            padding: "16px",
          }}
        >
          <div
            style={{
              background: "#fff",
              borderRadius: "8px",
              maxWidth: "520px",
              width: "100%",
              padding: "24px",
              boxShadow: "0 20px 25px -5px rgba(0, 0, 0, 0.1)",
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
              <h3 style={{ fontSize: "18px", fontWeight: 700, margin: 0 }}>
                Edit Account: {selectedAccount.full_name}
              </h3>
              <button
                type="button"
                className="icon-button"
                onClick={() => setShowEditModal(false)}
                aria-label="Close"
              >
                <XCircle size={20} />
              </button>
            </div>

            <form onSubmit={handleEditSubmit} style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
              <div>
                <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                  Full Name
                </label>
                <input
                  type="text"
                  required
                  value={editFullName}
                  onChange={(e) => setEditFullName(e.target.value)}
                  style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                />
              </div>

              <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "12px" }}>
                <div>
                  <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                    Employee / Roll ID
                  </label>
                  <input
                    type="text"
                    value={editEmployeeId}
                    onChange={(e) => setEditEmployeeId(e.target.value)}
                    style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                  />
                </div>
                <div>
                  <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                    Department
                  </label>
                  <select
                    value={editDepartment}
                    onChange={(e) => setEditDepartment(e.target.value)}
                    style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                  >
                    {departments.map((d) => (
                      <option key={d} value={d}>
                        {d}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              <div>
                <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                  Primary Role
                </label>
                <select
                  value={editPrimaryRole}
                  onChange={(e) => setEditPrimaryRole(e.target.value)}
                  style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                >
                  {ALL_ROLES.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.label}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "6px" }}>
                  Assigned Roles
                </label>
                <div style={{ display: "flex", flexWrap: "wrap", gap: "8px" }}>
                  {ALL_ROLES.map((r) => {
                    const checked = editSelectedRoles.includes(r.id);
                    return (
                      <label
                        key={r.id}
                        style={{
                          display: "inline-flex",
                          alignItems: "center",
                          gap: "6px",
                          fontSize: "13px",
                          padding: "6px 10px",
                          borderRadius: "6px",
                          border: checked ? "1px solid var(--accent)" : "1px solid var(--line)",
                          background: checked ? "#f3e8ff" : "#fff",
                          cursor: "pointer",
                        }}
                      >
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => handleRoleToggle(r.id, editSelectedRoles, setEditSelectedRoles)}
                        />
                        {r.label}
                      </label>
                    );
                  })}
                </div>
                <p style={{ fontSize: "11px", color: "var(--muted)", marginTop: "6px" }}>
                  * Modifying roles will immediately refresh this user's active login sessions.
                </p>
              </div>

              <div style={{ display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "12px" }}>
                <button
                  type="button"
                  className="button button-outline"
                  onClick={() => setShowEditModal(false)}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="button button-primary"
                  disabled={actionBusy}
                >
                  {actionBusy ? "Saving…" : "Save Changes"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* MANAGE VENUE ALLOCATIONS MODAL */}
      {showAllocModal && selectedAccount && (
        <div
          style={{
            position: "fixed",
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: "rgba(15, 23, 42, 0.6)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 1000,
            padding: "16px",
          }}
        >
          <div
            style={{
              background: "#fff",
              borderRadius: "8px",
              maxWidth: "500px",
              width: "100%",
              padding: "24px",
              boxShadow: "0 20px 25px -5px rgba(0, 0, 0, 0.1)",
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
              <div>
                <h3 style={{ fontSize: "18px", fontWeight: 700, margin: 0 }}>
                  Manage Coordinator Venues
                </h3>
                <p style={{ fontSize: "13px", color: "var(--muted)", margin: "2px 0 0" }}>
                  Coordinator: <strong>{selectedAccount.full_name}</strong>
                </p>
              </div>
              <button
                type="button"
                className="icon-button"
                onClick={() => setShowAllocModal(false)}
                aria-label="Close"
              >
                <XCircle size={20} />
              </button>
            </div>

            <form onSubmit={handleAllocSubmit}>
              <p style={{ fontSize: "13px", color: "#334155", marginBottom: "12px" }}>
                Select the classrooms, computer labs, or lecture theatres that this coordinator is authorized to approve:
              </p>

              <div
                style={{
                  display: "grid",
                  gridTemplateColumns: "repeat(auto-fill, minmax(130px, 1fr))",
                  gap: "8px",
                  maxHeight: "260px",
                  overflowY: "auto",
                  padding: "8px",
                  border: "1px solid var(--line)",
                  borderRadius: "6px",
                  marginBottom: "16px",
                }}
              >
                {rooms.map((room) => {
                  const isSel = allocRooms.includes(room.id);
                  return (
                    <label
                      key={room.id}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: "6px",
                        fontSize: "12px",
                        padding: "6px 8px",
                        borderRadius: "4px",
                        background: isSel ? "#e0e7ff" : "#f8fafc",
                        border: isSel ? "1px solid var(--accent)" : "1px solid var(--line)",
                        cursor: "pointer",
                      }}
                    >
                      <input
                        type="checkbox"
                        checked={isSel}
                        onChange={() => {
                          if (isSel) {
                            setAllocRooms(allocRooms.filter((id) => id !== room.id));
                          } else {
                            setAllocRooms([...allocRooms, room.id]);
                          }
                        }}
                      />
                      <span>
                        <strong>{room.code}</strong>
                        <div style={{ fontSize: "10px", color: "var(--muted)" }}>{room.building}</div>
                      </span>
                    </label>
                  );
                })}
              </div>

              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <span style={{ fontSize: "12px", color: "var(--muted)" }}>
                  {allocRooms.length} venue(s) selected
                </span>
                <div style={{ display: "flex", gap: "8px" }}>
                  <button
                    type="button"
                    className="button button-outline"
                    onClick={() => setShowAllocModal(false)}
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="button button-primary"
                    disabled={actionBusy}
                  >
                    {actionBusy ? "Saving…" : "Save Allocations"}
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* ASSIGN HOD MODAL */}
      {showAssignHodModal && (
        <div
          style={{
            position: "fixed",
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: "rgba(15, 23, 42, 0.6)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 1000,
            padding: "16px",
          }}
        >
          <div
            style={{
              background: "#fff",
              borderRadius: "8px",
              maxWidth: "480px",
              width: "100%",
              padding: "24px",
              boxShadow: "0 20px 25px -5px rgba(0, 0, 0, 0.1)",
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "16px" }}>
              <h3 style={{ fontSize: "18px", fontWeight: 700, margin: 0 }}>
                Designate Department Head (HOD)
              </h3>
              <button
                type="button"
                className="icon-button"
                onClick={() => setShowAssignHodModal(false)}
                aria-label="Close"
              >
                <XCircle size={20} />
              </button>
            </div>

            <form onSubmit={handleAssignHodSubmit} style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
              <div>
                <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                  Target Academic Department
                </label>
                <select
                  value={assignDept}
                  onChange={(e) => setAssignDept(e.target.value)}
                  style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                >
                  {departments.map((d) => (
                    <option key={d} value={d}>
                      {d} Department
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label style={{ display: "block", fontSize: "13px", fontWeight: 600, marginBottom: "4px" }}>
                  Select Faculty Member to Appoint as HOD
                </label>
                <select
                  required
                  value={assignUserId}
                  onChange={(e) => setAssignUserId(e.target.value)}
                  style={{ width: "100%", padding: "8px 12px", fontSize: "14px", border: "1px solid var(--line)", borderRadius: "4px" }}
                >
                  <option value="">Choose faculty member…</option>
                  {accounts
                    .filter((a) => a.status === "ACTIVE")
                    .map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.full_name} ({a.email}) {a.employee_id ? `— ${a.employee_id}` : ""}
                      </option>
                    ))}
                </select>
              </div>

              <div
                style={{
                  background: "#fef3c7",
                  border: "1px solid #fde68a",
                  padding: "10px 14px",
                  borderRadius: "6px",
                  fontSize: "12px",
                  color: "#92400e",
                  display: "flex",
                  gap: "8px",
                  alignItems: "flex-start",
                }}
              >
                <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: "2px" }} />
                <span>
                  Appointing a new HOD will cleanly archive the prior HOD record for {assignDept} and grant full departmental escalation authority to the selected member.
                </span>
              </div>

              <div style={{ display: "flex", justifyContent: "flex-end", gap: "8px", marginTop: "8px" }}>
                <button
                  type="button"
                  className="button button-outline"
                  onClick={() => setShowAssignHodModal(false)}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="button button-primary"
                  disabled={actionBusy || !assignUserId}
                >
                  {actionBusy ? "Assigning HOD…" : "Confirm Appointment"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
