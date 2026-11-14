import type {
  Availability,
  BookingRequest,
  RequestInput,
  Room,
  RoomSchedule,
  Session,
  ImportSession,
  ImportVersion,
  ClashCheckResult,
  OverviewSummary,
  AdminStats,
  AuditLogEntry,
  TeacherScheduleResult,
  FcfsJob,
  FcfsResult,
  DeadlockResult,
  SyncResult,
  ConcurrencyRaceResult,
  AdminAccount,
  CreateAccountInput,
  UpdateAccountInput,
  DepartmentHod,
} from "./types";
let csrfToken = "";
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function send<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  if (options.method && options.method !== "GET" && csrfToken)
    headers.set("X-CSRF-Token", csrfToken);
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...options,
      headers,
      credentials: "include",
    });
  } catch {
    throw new ApiError(
      "The server is unavailable. Check that the QuickSlot API is running, then try again.",
      0,
    );
  }
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as {
      detail?: unknown;
    } | null;
    const validation = Array.isArray(body?.detail)
      ? body.detail
          .map((issue: unknown) => {
            if (typeof issue === "string") return issue;
            if (issue && typeof issue === "object") {
              const entry = issue as {
                msg?: string;
                message?: string;
                loc?: (string | number)[];
              };
              return [
                entry.loc?.slice(1).join(" / "),
                entry.msg || entry.message,
              ]
                .filter(Boolean)
                .join(": ");
            }
            return "";
          })
          .filter(Boolean)
          .slice(0, 8)
          .join("; ")
      : "";
    const detail =
      validation ||
      (typeof body?.detail === "string"
        ? body.detail
        : response.status === 401
          ? "Please sign in to continue."
          : `This action could not be completed (${response.status}). Please try again.`);
    throw new ApiError(detail, response.status);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
async function session(path: string, options?: RequestInit) {
  const result = await send<Session>(path, options);
  csrfToken = result.csrf_token;
  return result;
}
export const api = {
  me: () => session("/auth/me"),
  login: (email: string, password: string) =>
    session("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),
  logout: async () => {
    await send("/auth/logout", { method: "POST" });
    csrfToken = "";
  },
  rooms: () => send<Room[]>("/rooms"),
  availability: (params: URLSearchParams, signal?: AbortSignal) =>
    send<Availability>(`/availability?${params}`, { signal }),
  schedule: (id: string, date: string) =>
    send<RoomSchedule>(
      `/rooms/${encodeURIComponent(id)}/schedule?date=${date}`,
    ),
  requests: () => send<BookingRequest[]>("/requests"),
  createRequest: (input: RequestInput, key: string) =>
    send<BookingRequest>("/requests", {
      method: "POST",
      headers: { "Idempotency-Key": key },
      body: JSON.stringify(input),
    }),
  decide: (id: string, action: string, reason: string) =>
    send<BookingRequest>(`/requests/${id}/${action}`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    }),
  versions: () => send<ImportVersion[]>("/timetables"),
  version: (id: string) => send<ImportVersion>(`/timetables/${id}`),
  upload: (
    file: File,
    scope_key: string,
    valid_from: string,
    valid_until: string,
  ) => {
    const body = new FormData();
    body.append("file", file);
    body.append("scope_key", scope_key);
    body.append("valid_from", valid_from);
    body.append("valid_until", valid_until);
    body.append("coverage_room_ids", "[]");
    return send<ImportVersion>("/timetables", { method: "POST", body });
  },
  review: (
    id: string,
    sessions: ImportSession[],
    coverage_room_ids: string[],
    acknowledge_source: boolean,
    expected_revision: number,
  ) =>
    send<ImportVersion>(`/timetables/${id}/review`, {
      method: "PUT",
      body: JSON.stringify({
        sessions,
        coverage_room_ids,
        acknowledge_source,
        expected_revision,
      }),
    }),
  publish: (id: string, expected_revision: number) =>
    send<ImportVersion>(`/timetables/${id}/publish`, {
      method: "POST",
      body: JSON.stringify({ expected_revision }),
    }),
  createRoom: (room: Omit<Room, "id">) =>
    send<Room>("/rooms", { method: "POST", body: JSON.stringify(room) }),
  updateRoomDetails: (
    id: string,
    details: {
      building: string;
      capacity: number | null;
      floor: string | null;
    },
  ) =>
    send<Room>(`/rooms/${encodeURIComponent(id)}/details`, {
      method: "PUT",
      body: JSON.stringify(details),
    }),
  faculty: () => send<{ id: string; name: string }[]>("/timetables/faculty"),
  createFaculty: (id: string, name: string) =>
    send<{ id: string; name: string }>("/timetables/faculty", {
      method: "POST",
      body: JSON.stringify({ id, name }),
    }),
  teachers: () => send<{ id: string; name: string }[]>("/timetables/teachers"),
  teacherSchedule: (id: string, date: string) =>
    send<TeacherScheduleResult>(
      `/timetables/teachers/${encodeURIComponent(id)}/schedule?date=${date}`,
    ),

  // Availability & Clash Check
  checkClash: (roomId: string, date: string, startTime: string, endTime: string) =>
    send<ClashCheckResult>(
      `/availability/check-clash?room_id=${encodeURIComponent(roomId)}&date=${encodeURIComponent(date)}&start_time=${encodeURIComponent(startTime)}&end_time=${encodeURIComponent(endTime)}`,
    ),

  // Admin Overview & Auditing
  overviewSummary: () => send<OverviewSummary>("/admin/overview-summary"),
  adminStats: () => send<AdminStats>("/admin/stats"),
  auditLogs: () => send<AuditLogEntry[]>("/admin/audit-logs"),

  // OS & DBMS Practical Verification Lab
  runFcfs: (jobs?: FcfsJob[]) =>
    send<FcfsResult>("/demo/fcfs", {
      method: "POST",
      body: JSON.stringify(jobs || []),
    }),
  runDeadlock: (mode: "deadlock" | "prevent" = "deadlock") =>
    send<DeadlockResult>(`/demo/deadlock?mode=${encodeURIComponent(mode)}`, {
      method: "POST",
    }),
  runSync: (numThreads = 10, increments = 1000, useMutex = true) =>
    send<SyncResult>(
      `/demo/sync?num_threads=${numThreads}&increments_per_thread=${increments}&use_mutex=${useMutex}`,
      { method: "POST" },
    ),
  runConcurrencyRace: (numCompeting = 20) =>
    send<ConcurrencyRaceResult>(
      `/demo/concurrency-race?num_competing=${numCompeting}`,
      { method: "POST" },
    ),

  // Account Management & Permissions (Phase 2)
  adminAccounts: (params?: { query?: string; role?: string; department?: string; status?: string }) => {
    const searchParams = new URLSearchParams();
    if (params?.query) searchParams.set("query", params.query);
    if (params?.role) searchParams.set("role", params.role);
    if (params?.department) searchParams.set("department", params.department);
    if (params?.status) searchParams.set("status", params.status);
    const qs = searchParams.toString();
    return send<AdminAccount[]>(`/admin/accounts${qs ? `?${qs}` : ""}`);
  },
  adminAccount: (id: string) => send<AdminAccount>(`/admin/accounts/${encodeURIComponent(id)}`),
  createAccount: (data: CreateAccountInput) =>
    send<{ success: boolean; id: string; invitation_token?: string; activation_url?: string }>(
      "/admin/accounts",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  updateAccount: (id: string, data: UpdateAccountInput) =>
    send<AdminAccount>(`/admin/accounts/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(data),
    }),
  setAccountStatus: (id: string, status: "ACTIVE" | "DEACTIVATED") =>
    send<{ success: boolean; status: string; active: boolean; message: string }>(
      `/admin/accounts/${encodeURIComponent(id)}/status`,
      {
        method: "POST",
        body: JSON.stringify({ status }),
      },
    ),
  updateAllocations: (id: string, room_ids: string[]) =>
    send<{ success: boolean; user_id: string; allocated_rooms: unknown[] }>(
      `/admin/accounts/${encodeURIComponent(id)}/allocations`,
      {
        method: "PUT",
        body: JSON.stringify({ room_ids }),
      },
    ),
  listDepartments: () => send<string[]>("/admin/departments"),
  listDepartmentHods: () => send<DepartmentHod[]>("/admin/departments/hods"),
  assignHod: (data: { department: string; user_id: string; campus_id?: string }) =>
    send<{ success: boolean; department: string; user_id: string }>(
      "/admin/departments/hods",
      {
        method: "POST",
        body: JSON.stringify(data),
      },
    ),
  activateAccount: (token: string, new_password: string) =>
    send<{ success: boolean; message: string; email: string }>("/auth/activate", {
      method: "POST",
      body: JSON.stringify({ token, new_password }),
    }),
};
