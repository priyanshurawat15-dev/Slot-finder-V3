-- V8: Enhancements from V2 (amenities, venue kinds, capacities, request metadata)

-- 1. Expand room kinds to support LAB, SEMINAR_HALL, AUDITORIUM
ALTER TABLE rooms DROP CONSTRAINT IF EXISTS rooms_kind_check;
ALTER TABLE rooms ADD CONSTRAINT rooms_kind_check CHECK (kind IN ('CLASSROOM', 'LT', 'LAB', 'SEMINAR_HALL', 'AUDITORIUM'));

-- 2. Add venue amenities
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS has_projector boolean NOT NULL DEFAULT true;
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS has_ac boolean NOT NULL DEFAULT true;
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS has_audio boolean NOT NULL DEFAULT false;

-- 3. Add booking request metadata
ALTER TABLE booking_requests ADD COLUMN IF NOT EXISTS participant_group varchar(150) NOT NULL DEFAULT '';
ALTER TABLE booking_requests ADD COLUMN IF NOT EXISTS required_equipment varchar(250) NOT NULL DEFAULT '';

-- 4. Set reasonable default capacities for existing rooms where capacity is null
UPDATE rooms SET capacity = 60 WHERE capacity IS NULL AND kind = 'CLASSROOM';
UPDATE rooms SET capacity = 120 WHERE capacity IS NULL AND kind = 'LT';
UPDATE rooms SET building = 'CS Block' WHERE building IS NULL OR building = '';

-- 5. Insert additional specialized demo venues from V2 if they do not exist
INSERT INTO rooms (id, code, kind, building, capacity, campus_id, department, active, source_label, floor, has_projector, has_ac, has_audio)
VALUES
  ('aud-kp-nautiyal', 'AUD-01', 'AUDITORIUM', 'Main Administrative Block', 500, 'gehu-hill-dehradun', 'CS', true, 'Campus Master Directory', 'Ground floor', true, true, true),
  ('lab-robotics-ai', 'LAB-101', 'LAB', 'CS Block', 45, 'gehu-hill-dehradun', 'CS', true, 'Campus Master Directory', 'First floor', true, true, false),
  ('seminar-hall-01', 'SH-201', 'SEMINAR_HALL', 'CS Block', 150, 'gehu-hill-dehradun', 'CS', true, 'Campus Master Directory', 'Second floor', true, true, true)
ON CONFLICT (campus_id, building, code, kind) DO UPDATE SET
  capacity = EXCLUDED.capacity,
  floor = EXCLUDED.floor,
  has_projector = EXCLUDED.has_projector,
  has_ac = EXCLUDED.has_ac,
  has_audio = EXCLUDED.has_audio;

-- 6. Ensure coordinators have allocation authority over the demo rooms
INSERT INTO allocations (user_id, room_id)
SELECT u.id, r.id
FROM users u
CROSS JOIN rooms r
WHERE u.role IN ('COORDINATOR', 'ADMIN')
ON CONFLICT (user_id, room_id) DO NOTHING;


