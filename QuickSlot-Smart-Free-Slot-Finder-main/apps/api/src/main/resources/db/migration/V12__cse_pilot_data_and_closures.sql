-- V12: CSE Pilot Data Verification, Room Closures, and Faculty Identity Mapping

-- 1. Add physical verification status to rooms
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS verified boolean NOT NULL DEFAULT false;

-- Mark pre-existing CSE pilot rooms as verified
UPDATE rooms SET verified = true
WHERE code IN ('017', '106', '107', '113', '114', '115', '116', '117', '123', '124', '206', '224', 'AUD-01', 'LAB-101', 'SH-201');

-- 2. Insert verified CSE rooms from timetable source data (tt.xlsx & PDF schedules)
INSERT INTO rooms (id, code, kind, building, capacity, campus_id, department, active, source_label, floor, has_projector, has_ac, has_audio, verified)
VALUES
  ('room-cr-104', 'CR 104', 'CLASSROOM', 'CS Block', 60, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'First floor', true, true, false, true),
  ('room-cr-201', 'CR 201', 'CLASSROOM', 'CS Block', 60, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, false, true),
  ('room-cr-202', 'CR 202', 'CLASSROOM', 'CS Block', 60, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, false, true),
  ('room-cr-203', 'CR 203', 'CLASSROOM', 'CS Block', 60, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, false, true),
  ('room-cr-204', 'CR 204', 'CLASSROOM', 'CS Block', 60, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, false, true),
  ('room-cr-205', 'CR 205', 'CLASSROOM', 'CS Block', 60, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, false, true),
  ('room-cr-206', 'CR 206', 'CLASSROOM', 'CS Block', 60, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, false, true),
  ('room-cr-207', 'CR 207', 'CLASSROOM', 'CS Block', 60, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, false, true),
  ('room-cr-404', 'CR 404', 'CLASSROOM', 'CS Block', 60, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Fourth floor', true, false, false, false),
  ('room-lt-201', 'LT 201', 'LT', 'CS Block', 120, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, true, true),
  ('room-lt-202', 'LT 202', 'LT', 'CS Block', 120, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, true, true),
  ('room-lt-301', 'LT 301', 'LT', 'CS Block', 120, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Third floor', true, true, true, true),
  ('room-lt-302', 'LT 302', 'LT', 'CS Block', 120, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Third floor', true, true, true, true),
  ('room-tcl-1', 'TCL 1', 'LAB', 'CS Block', 40, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'First floor', true, true, false, true),
  ('room-tcl-2', 'TCL 2', 'LAB', 'CS Block', 40, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'First floor', true, true, false, true),
  ('room-tcl-3', 'TCL 3', 'LAB', 'CS Block', 40, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, false, true),
  ('room-tcl-4', 'TCL 4', 'LAB', 'CS Block', 40, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Second floor', true, true, false, true),
  ('room-ubuntu-1', 'UBUNTU LAB 1', 'LAB', 'CS Block', 50, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Ground floor', true, true, false, true),
  ('room-ubuntu-2', 'UBUNTU LAB 2', 'LAB', 'CS Block', 50, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Ground floor', true, true, false, true),
  ('room-micro-1', 'MICRO LAB 1', 'LAB', 'CS Block', 40, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'First floor', true, true, false, true),
  ('room-micro-2', 'MICRO LAB 2', 'LAB', 'CS Block', 40, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'First floor', true, true, false, true),
  ('room-logic-lab', 'LOGIC LAB', 'LAB', 'CS Block', 45, 'gehu-hill-dehradun', 'CS', true, 'CSE Timetable Master (tt.xlsx)', 'Basement', true, true, false, true)
ON CONFLICT (campus_id, building, code, kind) DO UPDATE SET
  capacity = EXCLUDED.capacity,
  floor = EXCLUDED.floor,
  has_projector = EXCLUDED.has_projector,
  has_ac = EXCLUDED.has_ac,
  has_audio = EXCLUDED.has_audio,
  verified = EXCLUDED.verified;

-- Allocate new rooms to coordinators and admin
INSERT INTO allocations (user_id, room_id)
SELECT u.id, r.id
FROM users u
CROSS JOIN rooms r
WHERE u.role IN ('COORDINATOR', 'ADMIN')
ON CONFLICT (user_id, room_id) DO NOTHING;

-- 3. Room Closures Table
CREATE TABLE IF NOT EXISTS room_closures (
    id varchar(36) PRIMARY KEY,
    room_id varchar(36) NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    closure_type varchar(24) NOT NULL CHECK (closure_type IN ('TEMPORARY', 'PERMANENT')),
    reason varchar(500) NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    created_by varchar(36) NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    active boolean NOT NULL DEFAULT true,
    CHECK (ends_at IS NULL OR starts_at < ends_at)
);
CREATE INDEX IF NOT EXISTS idx_room_closures_room ON room_closures(room_id, active, starts_at, ends_at);

-- 4. Faculty from CSE Timetable
INSERT INTO timetable_faculty (id, campus_id, name)
VALUES
  ('fac-vikrant-sharma', 'gehu-hill-dehradun', 'Dr. Vikrant Sharma'),
  ('fac-vp-dubey', 'gehu-hill-dehradun', 'Dr. V. P. Dubey'),
  ('fac-akash-chauhan', 'gehu-hill-dehradun', 'Mr. Akash Chauhan'),
  ('fac-siddhant-thapliyal', 'gehu-hill-dehradun', 'Dr. Siddhant Thapliyal'),
  ('fac-amit-gupta', 'gehu-hill-dehradun', 'Dr. Amit Gupta'),
  ('fac-prakash-srivastava', 'gehu-hill-dehradun', 'Dr. Prakash Srivastava'),
  ('fac-amrita-tiwari', 'gehu-hill-dehradun', 'Ms. Amrita Tiwari'),
  ('fac-priyanka-nautiyal', 'gehu-hill-dehradun', 'Dr. Priyanka Nautiyal'),
  ('fac-teekam-singh', 'gehu-hill-dehradun', 'Dr. Teekam Singh'),
  ('fac-rahul-chauhan', 'gehu-hill-dehradun', 'Mr. Rahul Chauhan'),
  ('fac-prateek-srivastava', 'gehu-hill-dehradun', 'Dr. Prateek Srivastava'),
  ('fac-ayushi-jain', 'gehu-hill-dehradun', 'Ms. Ayushi Jain'),
  ('fac-aashish-garg', 'gehu-hill-dehradun', 'Dr. Aashish Garg'),
  ('fac-anupam-singh', 'gehu-hill-dehradun', 'Dr. Anupam Singh'),
  ('fac-sudhani-verma', 'gehu-hill-dehradun', 'Ms. Sudhani Verma'),
  ('fac-himanshu-namdev', 'gehu-hill-dehradun', 'Mr. Himanshu Namdev'),
  ('fac-jyoti-joshi', 'gehu-hill-dehradun', 'Dr. Jyoti Joshi'),
  ('fac-mohit-payal', 'gehu-hill-dehradun', 'Dr. Mohit Payal'),
  ('fac-vivek-chamoli', 'gehu-hill-dehradun', 'Dr. Vivek Chamoli'),
  ('fac-seema-gulati', 'gehu-hill-dehradun', 'Dr. Seema Gulati'),
  ('fac-ajay-shukla', 'gehu-hill-dehradun', 'Dr. Ajay N. Shukla'),
  ('fac-divesh-kumar', 'gehu-hill-dehradun', 'Mr. Divesh Kumar'),
  ('fac-aditi-singhal', 'gehu-hill-dehradun', 'Ms. Aditi Singhal'),
  ('fac-sameeksha-ahuja', 'gehu-hill-dehradun', 'Ms. Sameeksha Ahuja'),
  ('fac-manvi-bohra', 'gehu-hill-dehradun', 'Ms. Manvi Bohra'),
  ('fac-swati-joshi', 'gehu-hill-dehradun', 'Ms. Swati Joshi'),
  ('fac-nitin-thapliyal', 'gehu-hill-dehradun', 'Mr. Nitin Thapliyal')
ON CONFLICT (id) DO NOTHING;

-- 5. Faculty Account Mappings Table
CREATE TABLE IF NOT EXISTS faculty_account_mappings (
    timetable_faculty_id varchar(80) NOT NULL REFERENCES timetable_faculty(id) ON DELETE CASCADE,
    user_id varchar(36) REFERENCES users(id) ON DELETE SET NULL,
    status varchar(24) NOT NULL DEFAULT 'UNMAPPED' CHECK (status IN ('MAPPED', 'UNMAPPED', 'AMBIGUOUS')),
    notes varchar(255) NOT NULL DEFAULT '',
    mapped_at timestamptz,
    mapped_by varchar(36) REFERENCES users(id) ON DELETE SET NULL,
    PRIMARY KEY (timetable_faculty_id)
);
CREATE INDEX IF NOT EXISTS idx_faculty_mappings_user ON faculty_account_mappings(user_id);
CREATE INDEX IF NOT EXISTS idx_faculty_mappings_status ON faculty_account_mappings(status);

-- Seed initial faculty account mappings for all timetable faculty
INSERT INTO faculty_account_mappings (timetable_faculty_id, status, notes)
SELECT id, 'UNMAPPED', 'Pending individual faculty account registration'
FROM timetable_faculty
ON CONFLICT (timetable_faculty_id) DO NOTHING;

-- Map demo faculty user if present
INSERT INTO faculty_account_mappings (timetable_faculty_id, user_id, status, notes, mapped_at)
SELECT 'fac-devesh-pratap', u.id, 'MAPPED', 'Auto-mapped default faculty account', now()
FROM users u
WHERE u.role = 'FACULTY' AND u.active = true
LIMIT 1
ON CONFLICT (timetable_faculty_id) DO UPDATE SET
  user_id = EXCLUDED.user_id,
  status = EXCLUDED.status,
  notes = EXCLUDED.notes,
  mapped_at = EXCLUDED.mapped_at;

-- Flag ambiguous faculty where duplicate or uncertain identities exist
UPDATE faculty_account_mappings
SET status = 'AMBIGUOUS', notes = 'Multiple faculty matches with surname Chauhan in CSE directory'
WHERE timetable_faculty_id IN ('fac-akash-chauhan', 'fac-rahul-chauhan');

































