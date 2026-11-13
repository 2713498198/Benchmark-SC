package com.cryptobench.benchmark.auth.repository;

import com.cryptobench.benchmark.persistence.MybatisDatabase;
import com.cryptobench.benchmark.persistence.MybatisRowMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class AdminAuditRepository {
    public record Audit(String id, String actorUserId, String targetUserId, String action,
                        String metadata, String result, String createdAt) {}
    private static final MybatisRowMapper<Audit> MAPPER = (row, i) -> new Audit(
            row.getString("id"), row.getString("actor_user_id"), row.getString("target_user_id"),
            row.getString("action"), row.getString("metadata"), row.getString("result"), row.getString("created_at"));
    private final MybatisDatabase database;

    public AdminAuditRepository(MybatisDatabase database) { this.database = database; }

    public void insert(String id, String actor, String target, String action, String metadata,
                       String result, String createdAt) {
        database.update("INSERT INTO benchmark_admin_audits "
                        + "(id,actor_user_id,target_user_id,action,metadata,result,created_at) VALUES (?,?,?,?,?,?,?)",
                id, actor, target, action, metadata, result, createdAt);
    }

    public List<Audit> findPage(String action, String target, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM benchmark_admin_audits WHERE 1=1");
        ArrayList<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) { sql.append(" AND action=?"); args.add(action); }
        if (target != null && !target.isBlank()) { sql.append(" AND target_user_id=?"); args.add(target); }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?"); args.add(size); args.add((long) page * size);
        return database.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(String action, String target) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM benchmark_admin_audits WHERE 1=1");
        ArrayList<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) { sql.append(" AND action=?"); args.add(action); }
        if (target != null && !target.isBlank()) { sql.append(" AND target_user_id=?"); args.add(target); }
        return database.queryForObject(sql.toString(), Long.class, args.toArray());
    }
}
