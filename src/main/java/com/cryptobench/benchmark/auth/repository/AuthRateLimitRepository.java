package com.cryptobench.benchmark.auth.repository;

import com.cryptobench.benchmark.persistence.MybatisDatabase;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Durable counters for login and registration abuse prevention. */
@Repository
public class AuthRateLimitRepository {
    private final MybatisDatabase database;

    public AuthRateLimitRepository(MybatisDatabase database) {
        this.database = database;
    }

    public void event(String scope, String key, long now) {
        database.update("INSERT INTO benchmark_auth_rate_events(id,event_scope,subject_hash,created_at) VALUES(?,?,?,?)",
                UUID.randomUUID().toString(), scope, key, now);
    }

    public long retryAfter(String scope, String key, long now, long window, int limit) {
        List<Long> times = database.query(
                "SELECT created_at FROM benchmark_auth_rate_events WHERE event_scope=? AND subject_hash=? AND created_at>? ORDER BY created_at",
                (row, index) -> row.getLong("created_at"), scope, key, now - window);
        if (times.size() < limit) return 0;
        return Math.max(1, (times.get(times.size() - limit) + window - now + 999) / 1000);
    }

    public void clear(String scope, String key) {
        database.update("DELETE FROM benchmark_auth_rate_events WHERE event_scope=? AND subject_hash=?", scope, key);
    }

    public void cleanup(long now, long retentionMillis) {
        database.update("DELETE FROM benchmark_auth_rate_events WHERE created_at<=?",
                now - Math.max(3_600_000L, retentionMillis));
    }
}
