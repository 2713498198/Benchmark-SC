package com.cryptobench.benchmark.auth.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AdminInvitationRepository {
    public record Invitation(String id, String email, String displayName, String tokenHash, String invitedBy,
                             String createdAt, long expiresAt, Long usedAt, Long revokedAt, int generation, String lastMessageId) {}
    private final JdbcTemplate jdbc;
    private final RowMapper<Invitation> mapper = (r, i) -> new Invitation(r.getString("id"), r.getString("email"), r.getString("display_name"),
            r.getString("token_hash"), r.getString("invited_by"), r.getString("created_at"), r.getLong("expires_at"),
            number(r.getObject("used_at")), number(r.getObject("revoked_at")), r.getInt("generation"), r.getString("last_message_id"));
    public AdminInvitationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }
    public boolean hasOutstanding(String email, long now) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_admin_invitations WHERE email=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>?", Integer.class, email, now) > 0;
    }
    public void insert(String id, String email, String name, String tokenHash, String invitedBy, String createdAt, long expiresAt) {
        jdbc.update("INSERT INTO benchmark_admin_invitations(id,email,display_name,token_hash,invited_by,created_at,expires_at) VALUES(?,?,?,?,?,?,?)",
                id, email, name, tokenHash, invitedBy, createdAt, expiresAt);
    }
    public Optional<Invitation> find(String hash) { return jdbc.query("SELECT * FROM benchmark_admin_invitations WHERE token_hash=?", mapper, hash).stream().findFirst(); }
    public Optional<Invitation> findById(String id) { return jdbc.query("SELECT * FROM benchmark_admin_invitations WHERE id=?", mapper, id).stream().findFirst(); }
    public void setMessage(String id, String messageId) { jdbc.update("UPDATE benchmark_admin_invitations SET last_message_id=? WHERE id=?", messageId, id); }
    public void rotate(String id, String hash, long expires) {
        jdbc.update("UPDATE benchmark_admin_invitations SET token_hash=?,expires_at=?,generation=generation+1 WHERE id=? AND used_at IS NULL AND revoked_at IS NULL", hash, expires, id);
    }
    public void revoke(String id, String actor, long now) { jdbc.update("UPDATE benchmark_admin_invitations SET revoked_at=?,revoked_by=? WHERE id=? AND used_at IS NULL AND revoked_at IS NULL", now, actor, id); }
    public int consume(String hash, long now) {
        return jdbc.update("UPDATE benchmark_admin_invitations SET used_at=? WHERE token_hash=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>?", now, hash, now);
    }
    public List<Invitation> page(String status, String delivery, int page, int size, long now) {
        var args = new ArrayList<Object>(); String where = filters(status, delivery, now, args);
        args.add(size); args.add(page * size);
        return jdbc.query("SELECT i.* FROM benchmark_admin_invitations i LEFT JOIN benchmark_mail_messages m ON m.id=i.last_message_id" + where + " ORDER BY i.created_at DESC,i.id DESC LIMIT ? OFFSET ?", mapper, args.toArray());
    }
    public long count(String status, String delivery, long now) {
        var args = new ArrayList<Object>(); String where = filters(status, delivery, now, args);
        return jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_admin_invitations i LEFT JOIN benchmark_mail_messages m ON m.id=i.last_message_id" + where, Long.class, args.toArray());
    }
    private String filters(String status, String delivery, long now, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null && !status.isBlank()) {
            switch (status) {
                case "accepted" -> where.append(" AND i.used_at IS NOT NULL");
                case "revoked" -> where.append(" AND i.revoked_at IS NOT NULL AND i.used_at IS NULL");
                case "pending", "expired" -> { where.append(" AND i.used_at IS NULL AND i.revoked_at IS NULL AND i.expires_at").append("pending".equals(status) ? ">?" : "<=?"); args.add(now); }
                default -> throw new IllegalArgumentException("invalid invitation filter");
            }
        }
        if (delivery != null && !delivery.isBlank()) { where.append(" AND COALESCE(m.state,'legacy_unknown')=?"); args.add(delivery); }
        return where.toString();
    }
}
