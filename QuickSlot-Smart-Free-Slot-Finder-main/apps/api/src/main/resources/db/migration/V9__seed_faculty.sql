-- V9: Seed department faculty members for Teacher Lookup & consultation windows
INSERT INTO timetable_faculty (id, campus_id, name)
VALUES
  ('fac-devesh-pratap', 'gehu-hill-dehradun', 'Dr. Devesh Pratap Singh'),
  ('fac-sachin-sharma', 'gehu-hill-dehradun', 'Dr. Sachin Sharma'),
  ('fac-ankit-sharma', 'gehu-hill-dehradun', 'Er. Ankit Sharma'),
  ('fac-priya-rawat', 'gehu-hill-dehradun', 'Dr. Priya Rawat'),
  ('fac-rk-sharma', 'gehu-hill-dehradun', 'Prof. R.K. Sharma'),
  ('fac-neha-joshi', 'gehu-hill-dehradun', 'Dr. Neha Joshi')
ON CONFLICT (id) DO NOTHING;
