export type Role =
  | "STUDENT"
  | "FACULTY"
  | "CLUB_HEAD"
  | "COORDINATOR"
  | "ALLOCATION_FACULTY"
  | "HOD"
  | "ADMIN";

export interface User {
  id: string;
  full_name: string;
  email: string;
  role: Role;
  campus_id?: string;
  department?: string;
}

export interface Session {
  user: User;
  csrf_token: string;
}

export interface Room {
  id: string;
  code: string;
  kind: string;
  building: string;
  capacity: number | null;
  source_label?: string;
  floor?: string | null;
  has_projector?: boolean;
  has_ac?: boolean;
  has_audio?: boolean;
}

export interface BusyInterval {
  start_time: string;
  end_time: string;
  title?: string;
}

export interface AvailableRoom extends Room {
  state: "AVAILABLE" | "OCCUPIED" | "RESTRICTED" | "UNKNOWN";
  reason: string;
  busy_intervals: BusyInterval[];
}

export interface Availability {
  date: string;
  rooms: AvailableRoom[];
  coverage_notice: string;
}

export type RequestStatus =
  | "PENDING"
  | "FORWARDED"
  | "APPROVED"
  | "REJECTED"
  | "WITHDRAWN"
  | "CANCELLED";

export interface HistoryEvent {
  action?: string;
  status?: string;
  reason?: string;
  actor_name?: string;
  created_at?: string;
  at?: string;
}

export interface BookingRequest {
  id: string;
  requester_id: string;
  room_id: string;
  room_code: string;
  date: string;
  start_time: string;
  end_time: string;
  purpose: string;
  attendance: number;
  status: RequestStatus;
  requester_name: string;
  history: HistoryEvent[];
  participant_group?: string;
  required_equipment?: string;
}

export interface RequestInput {
  room_id: string;
  date: string;
  start_time: string;
  end_time: string;
  purpose: string;
  attendance: number;
  participant_group?: string;
  required_equipment?: string;
}

export interface ScheduleSession {
  day_of_week: number;
  start_time: string;
  end_time: string;
  title: string;
  source_label?: string;
}

export interface RoomSchedule {
  room: Room;
  sessions: ScheduleSession[];
  coverage_notice: string;
}

export interface ImportIssue {
  code: string;
  message: string;
  session_id?: string;
  source?: Record<string, unknown>;
}

export interface ImportSession {
  id?: string;
  weekday: number;
  start_time: string;
  end_time: string;
  room_id: string | null;
  subject: string;
  faculty_ids: string[];
  source?: Record<string, unknown>;
}

export interface ImportVersion {
  id: string;
  scope_key: string;
  filename: string;
  status: "DRAFT" | "REVIEWED" | "PUBLISHED";
  valid_from: string;
  valid_until: string;
  expected_revision: number;
  version?: number;
  sessions: ImportSession[];
  issues: ImportIssue[];
  coverage_room_ids: string[];
  acknowledged: boolean;
}

// --- V2 Feature Additions ---

export interface ClashCheckResult {
  room_id: string;
  room_code: string;
  date: string;
  start_time: string;
  end_time: string;
  state: "AVAILABLE" | "OCCUPIED" | "UNKNOWN";
  has_clash: boolean;
  reason: string;
  busy_intervals: BusyInterval[];
}

export interface OverviewSummary {
  total_venues: number;
  pending_requests: number;
  confirmed_bookings: number;
  active_timetable: string;
  academic_session: string;
  institution: string;
  department: string;
}

export interface AuditLogEntry {
  id: string;
  action: string;
  entity: string;
  actor: string;
  role?: string;
  created_at: string;
}

export interface AdminStats {
  total_venues: number;
  total_users: number;
  pending_requests: number;
  confirmed_bookings: number;
  recent_audits: AuditLogEntry[];
}

export interface TeacherScheduleResult {
  teacher: { id: string; name: string };
  date: string;
  day_name?: string;
  day_of_week?: number;
  notice: string;
  sessions: {
    start_time: string;
    end_time: string;
    subject: string;
    room_id: string | null;
    room_code: string | null;
    building: string | null;
  }[];
  scheduled_classes?: {
    start_time: string;
    end_time: string;
    subject: string;
    room_id: string | null;
    room_code: string | null;
    building: string | null;
  }[];
  free_periods?: {
    start_time: string;
    end_time: string;
    duration_minutes: number;
  }[];
  next_scheduled_class?: {
    start_time: string;
    end_time: string;
    subject: string;
    room_id: string | null;
    room_code: string | null;
    building: string | null;
  } | null;
}

// OS & DBMS Practical Lab Types
export interface FcfsJob {
  job_id?: string;
  jobId?: string;
  arrival_time?: number;
  arrivalTime?: number;
  burst_time?: number;
  burstTime?: number;
}

export interface FcfsMetric {
  job_id: string;
  arrival_time: number;
  burst_time: number;
  start_time: number;
  completion_time: number;
  turnaround_time: number;
  waiting_time: number;
}

export interface GanttBlock {
  job_id: string;
  start: number;
  end: number;
  duration: number;
  is_idle: boolean;
}

export interface FcfsResult {
  jobs: FcfsMetric[];
  average_turnaround_time: number;
  average_waiting_time: number;
  throughput: number;
  gantt_chart: GanttBlock[];
}

export interface DeadlockResult {
  mode: string;
  deadlock_occurred: boolean;
  coffman_conditions: Record<string, string>;
  execution_timeline: string[];
  explanation: string;
}

export interface SyncResult {
  use_mutex: boolean;
  num_threads: number;
  increments_per_thread: number;
  expected_counter: number;
  actual_counter: number;
  lost_updates: number;
  race_detected: boolean;
  critical_section_explanation: string;
}

export interface ConcurrencyRaceResult {
  total_concurrent_requests: number;
  confirmed_bookings: number;
  rejected_conflicts: number;
  exclusion_constraint_violations: number;
  execution_time_seconds: number;
  is_safe: boolean;
  verification_message: string;
}
