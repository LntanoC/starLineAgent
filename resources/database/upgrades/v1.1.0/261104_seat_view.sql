-- P0 用两个独立实体保存场馆和演出；分区随演出 JSONB 快照原子发布。
-- show.document 的 sections 可选 seatGrid（rows、seats、reverseRows、reverseSeats），仅用于人工核对的均匀排座估算；历史 JSON 无需迁移。
CREATE TABLE IF NOT EXISTS t_seat_view_venue (
    id VARCHAR(100) PRIMARY KEY,
    document JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS t_seat_view_show (
    id VARCHAR(100) PRIMARY KEY,
    venue_id VARCHAR(100) NOT NULL REFERENCES t_seat_view_venue(id),
    document JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_seat_view_show_venue ON t_seat_view_show(venue_id);
