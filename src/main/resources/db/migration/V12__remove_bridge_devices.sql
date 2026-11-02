-- Keep V10 unchanged so existing installations can validate their migration history.
-- Only the retired device-pairing and remote-job storage is removed.
DROP TABLE benchmark_bridge_jobs;
DROP TABLE benchmark_bridge_devices;
DROP TABLE benchmark_bridge_pairings;
