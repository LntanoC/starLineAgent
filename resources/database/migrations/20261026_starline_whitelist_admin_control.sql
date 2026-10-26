BEGIN;

-- Replace the earlier review-state constraint while preserving existing sources.
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 't_starline_platform_source'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%source_status%'
    LOOP
        EXECUTE format('ALTER TABLE t_starline_platform_source DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

UPDATE t_starline_platform_source
SET source_status = CASE
    WHEN source_status = 'APPROVED' AND public_accessible THEN 'ACTIVE'
    ELSE 'DISABLED'
END
WHERE source_status IN ('PENDING', 'APPROVED', 'REJECTED');

ALTER TABLE t_starline_platform_source
    ALTER COLUMN source_status SET DEFAULT 'ACTIVE';

ALTER TABLE t_starline_platform_source
    ADD CONSTRAINT chk_starline_platform_source_status
    CHECK (source_status IN ('ACTIVE', 'DISABLED'));

COMMIT;
