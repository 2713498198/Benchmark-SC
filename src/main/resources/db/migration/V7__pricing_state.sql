ALTER TABLE benchmark_price_tables ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
-- Existing versions may already be referenced by reports, including old inactive versions.
ALTER TABLE benchmark_price_tables ADD COLUMN locked BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE benchmark_pricing_state (
    singleton_id INTEGER PRIMARY KEY,
    active_version VARCHAR(120),
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_pricing_singleton CHECK (singleton_id = 1),
    CONSTRAINT fk_pricing_active_version FOREIGN KEY (active_version) REFERENCES benchmark_price_tables(version)
);
INSERT INTO benchmark_pricing_state(singleton_id, active_version, revision)
VALUES (1, NULL, 0);
UPDATE benchmark_pricing_state SET active_version=(
    SELECT version FROM benchmark_price_tables WHERE status='active'
    ORDER BY activated_at DESC, version DESC LIMIT 1
) WHERE singleton_id=1;
UPDATE benchmark_price_tables SET status='archived'
WHERE status='active' AND version<>(SELECT active_version FROM benchmark_pricing_state WHERE singleton_id=1);