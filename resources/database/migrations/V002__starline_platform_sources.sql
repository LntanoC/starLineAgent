CREATE TABLE IF NOT EXISTS t_starline_platform_source (
    id                          VARCHAR(64) PRIMARY KEY,
    platform_name               VARCHAR(160) NOT NULL,
    official_domain              VARCHAR(255) NOT NULL UNIQUE,
    public_accessible            BOOLEAN NOT NULL,
    activity_fields_available    BOOLEAN NOT NULL,
    ticket_time_available        BOOLEAN NOT NULL,
    ticket_status_available      BOOLEAN NOT NULL,
    field_stability              VARCHAR(24) NOT NULL CHECK (field_stability IN ('STABLE', 'PARTIAL', 'UNSTABLE')),
    official_identity_evidence   TEXT NOT NULL,
    update_frequency             VARCHAR(160) NOT NULL,
    access_boundary              TEXT NOT NULL,
    source_status                VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (source_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_time                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_starline_platform_source_status
    ON t_starline_platform_source (source_status, official_domain);

CREATE TABLE IF NOT EXISTS t_starline_source_review (
    id              VARCHAR(64) PRIMARY KEY,
    source_id       VARCHAR(64) NOT NULL REFERENCES t_starline_platform_source(id) ON DELETE CASCADE,
    reviewer_type   VARCHAR(24) NOT NULL CHECK (reviewer_type IN ('PRODUCT', 'ENGINEERING')),
    reviewer_name   VARCHAR(120) NOT NULL,
    passed          BOOLEAN NOT NULL,
    review_note     TEXT,
    reviewed_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_starline_source_reviewer UNIQUE (source_id, reviewer_type)
);

CREATE INDEX IF NOT EXISTS idx_starline_source_review_source
    ON t_starline_source_review (source_id);
