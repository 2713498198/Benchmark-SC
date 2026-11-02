ALTER TABLE benchmark_datasets ADD COLUMN schema_version VARCHAR(80) NOT NULL DEFAULT 'legacy';
ALTER TABLE benchmark_datasets ADD COLUMN execution_status VARCHAR(30) NOT NULL DEFAULT 'legacy_unbound';
ALTER TABLE benchmark_datasets ADD COLUMN evaluator_version VARCHAR(120);
ALTER TABLE benchmark_datasets ADD COLUMN catalog_sha256 VARCHAR(64);
ALTER TABLE benchmark_datasets ADD COLUMN published_at VARCHAR(40);

CREATE TABLE benchmark_dataset_packages (
    dataset_id VARCHAR(128) NOT NULL,
    dataset_version VARCHAR(40) NOT NULL,
    private_payload LONGTEXT NOT NULL,
    package_sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY (dataset_id, dataset_version),
    CONSTRAINT fk_dataset_package FOREIGN KEY (dataset_id, dataset_version)
        REFERENCES benchmark_datasets(id, dataset_version)
);
