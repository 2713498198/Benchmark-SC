CREATE TABLE benchmark_run_quota_lock (
    singleton_id INT PRIMARY KEY,
    revision BIGINT NOT NULL
);

INSERT INTO benchmark_run_quota_lock (singleton_id, revision) VALUES (1, 0);
