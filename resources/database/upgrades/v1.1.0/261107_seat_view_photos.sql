CREATE TABLE IF NOT EXISTS t_seat_view_photo (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    show_id VARCHAR(100) NOT NULL REFERENCES t_seat_view_show(id),
    section_code VARCHAR(100) NOT NULL,
    row_number INTEGER,
    seat_number INTEGER,
    caption VARCHAR(500) NOT NULL DEFAULT '',
    storage_key TEXT NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_seat_view_photo_show_section ON t_seat_view_photo(show_id, section_code, created_at DESC);
