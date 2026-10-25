package com.cryptobench.benchmark.auth.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminInvitationRepository {
    public record Invitation(String id, String email, String displayName, String tokenHash,
                             String invitedBy, String createdAt, long expiresAt, Long usedAt) {}
    private final JdbcTemplate jdbc;

    public AdminInvitationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void deleteOutstanding(String email, long now) {
        jdbc.update("DELETE FROM benchmark_admin_invitations WHERE email=? AND used_at IS NULL AND expires_at<=?", email, now);
    }

    public boolean hasOutstanding(String email, long now) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_admin_invitations "
                + "WHERE email=? AND used_at IS NULL AND expires_at>?", Integer.class, email, now);
        return count != null && count > 0;
    }

    public void insert(String id, String email, String displayName, String tokenHash, String invitedBy,
                       String createdAt, long expiresAt) {
        jdbc.update("INSERT INTO benchmark_admin_invitations "
                        + "(id,email,display_name,token_hash,invited_by,created_at,expires_at) VALUES (?,?,?,?,?,?,?)",
                id, email, displayName, tokenHash, invitedBy, createdAt, expiresAt);
    }

    public Optional<Invitation> find(String tokenHash) {
        return jdbc.query("SELECT id,email,display_name,token_hash,invited_by,created_at,expires_at,used_at "
                        + "FROM benchmark_admin_invitations WHERE token_hash=?",
                (row, i) -> new Invitation(row.getString("id"), row.getString("email"),
                        row.getString("display_name"), row.getString("token_hash"), row.getString("invited_by"),
                        row.getString("created_at"), row.getLong("expires_at"),
                        row.getObject("used_at") == null ? null : ((Number) row.getObject("used_at")).longValue()), tokenHash).stream().findFirst();
    }

    public int consume(String tokenHash, long now) {
        return jdbc.update("UPDATE benchmark_admin_invitations SET used_at=? "
                + "WHERE token_hash=? AND used_at IS NULL AND expires_at>?", now, tokenHash, now);
    }
}
