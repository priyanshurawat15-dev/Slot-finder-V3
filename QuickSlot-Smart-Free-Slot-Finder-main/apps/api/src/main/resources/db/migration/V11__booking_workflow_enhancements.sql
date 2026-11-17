-- V11: Phase 3 Booking Workflow Enhancements
-- Adds verified status to clubs, club association to booking requests, and read status to notifications

ALTER TABLE clubs ADD COLUMN IF NOT EXISTS verified boolean NOT NULL DEFAULT true;

ALTER TABLE booking_requests ADD COLUMN IF NOT EXISTS club_id varchar(36) REFERENCES clubs(id) ON DELETE SET NULL;

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS is_read boolean NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_booking_requests_club ON booking_requests(club_id);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notifications(user_id, is_read);
