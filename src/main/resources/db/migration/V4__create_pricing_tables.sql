CREATE TABLE benchmark_price_tables (
    version VARCHAR(120) PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_by VARCHAR(80),
    created_at VARCHAR(40) NOT NULL,
    activated_at VARCHAR(40),
    CONSTRAINT fk_price_table_creator FOREIGN KEY (created_by) REFERENCES benchmark_users(id)
);

CREATE TABLE benchmark_model_prices (
    price_version VARCHAR(120) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    input_per_million_tokens DECIMAL(20,8) NOT NULL,
    output_per_million_tokens DECIMAL(20,8) NOT NULL,
    currency VARCHAR(12) NOT NULL DEFAULT 'USD',
    PRIMARY KEY (price_version, model_id),
    CONSTRAINT fk_model_price_table FOREIGN KEY (price_version)
        REFERENCES benchmark_price_tables(version) ON DELETE CASCADE
);
CREATE INDEX idx_price_table_status ON benchmark_price_tables(status);
