-- V10: User Roles, Department Scopes, HOD Assignments, and Invitations

-- 1. Extend users table for employee ID, lifecycle status and timestamps
ALTER TABLE users ADD COLUMN IF NOT EXISTS employee_id varchar(50) UNIQUE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS status varchar(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INVITED','DEACTIVATED'));
ALTER TABLE users ADD COLUMN IF NOT EXISTS invited_at timestamptz;
ALTER TABLE users ADD COLUMN IF NOT EXISTS activated_at timestamptz;
ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

-- 2. Multi-role assignment table with department scope
CREATE TABLE IF NOT EXISTS user_roles (
    id varchar(36) PRIMARY KEY,
    user_id varchar(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role varchar(24) NOT NULL CHECK (role IN ('STUDENT','FACULTY','CLUB_HEAD','COORDINATOR','HOD','ADMIN')),
    department varchar(80),
    scope_type varchar(32) NOT NULL DEFAULT 'DEPARTMENT',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(user_id, role, department)
);
CREATE INDEX IF NOT EXISTS idx_user_roles_user_id ON user_roles(user_id);

-- 3. Department HOD assignment history and tracking
CREATE TABLE IF NOT EXISTS department_hods (
    id varchar(36) PRIMARY KEY,
    department varchar(80) NOT NULL,
    campus_id varchar(80) NOT NULL DEFAULT 'gehu-hill-dehradun',
    user_id varchar(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    active boolean NOT NULL DEFAULT true,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    assigned_by varchar(36) REFERENCES users(id) ON DELETE SET NULL,
    revoked_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_dept_hods_dept ON department_hods(campus_id, department);

-- 4. Secure invitation tokens for account activation / password setup
CREATE TABLE IF NOT EXISTS invitations (
    id varchar(36) PRIMARY KEY,
    user_id varchar(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_invitations_token_hash ON invitations(token_hash);

-- 5. Extend allocations for assignment audit
ALTER TABLE allocations ADD COLUMN IF NOT EXISTS assigned_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE allocations ADD COLUMN IF NOT EXISTS assigned_by varchar(36) REFERENCES users(id) ON DELETE SET NULL;

-- 6. Backfill existing roles into user_roles
INSERT INTO user_roles (id, user_id, role, department, scope_type)
SELECT md5(random()::text || clock_timestamp()::text || id)::varchar(36), id, role, department,
       CASE WHEN role = 'ADMIN' THEN 'INSTITUTION' ELSE 'DEPARTMENT' END
FROM users
ON CONFLICT (user_id, role, department) DO NOTHING;

-- 7. Backfill existing HODs into department_hods
INSERT INTO department_hods (id, department, campus_id, user_id, active, assigned_at)
SELECT md5(random()::text || clock_timestamp()::text || id)::varchar(36), department, campus_id, id, true, now()
FROM users
WHERE role = 'HOD' AND active = true;
