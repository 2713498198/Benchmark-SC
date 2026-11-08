package com.cryptobench.benchmark.mail;

import com.cryptobench.benchmark.persistence.MybatisDatabase;
import com.cryptobench.benchmark.persistence.MybatisRowMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MailRepository {
    private final MybatisDatabase database;
    private static final String OPEN = "('queued','retry_wait','dispatching')";
    private final MybatisRowMapper<Message> mapper = (r, i) -> new Message(r.getString("id"), r.getString("purpose"), r.getString("reference_id"),
            r.getString("owner_id"), r.getInt("generation"), r.getString("transport"), r.getString("state"),
            new MailCipher.Envelope(r.getString("ciphertext"), r.getString("nonce"), r.getString("key_id")), r.getInt("attempts"),
            r.getLong("next_attempt_at"), r.getLong("expires_at"), r.getString("claim_id"), r.getString("error_code"));
    public MailRepository(MybatisDatabase database) { this.database = database; }
    /** 低频身份操作在多个实例之间串行执行，包括同一邮箱的首次邀请。 */
    public void guard() { database.queryForObject("SELECT id FROM benchmark_mail_guard WHERE id=1 FOR UPDATE", Integer.class); }
    public void insert(String id, String purpose, String reference, String owner, int generation, String transport, MailCipher.Envelope encrypted, long expires, long now) {
        database.update("INSERT INTO benchmark_mail_messages(id,purpose,reference_id,owner_id,generation,transport,state,ciphertext,nonce,key_id,next_attempt_at,expires_at,created_at) VALUES(?,?,?,?,?,?,'queued',?,?,?,?,?,?)",
                id, purpose, reference, owner, generation, transport, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyId(), now, expires, now);
    }
    public Optional<Message> find(String id) {
        if (id == null) return Optional.empty();
        return database.query("SELECT * FROM benchmark_mail_messages WHERE id=?", mapper, id).stream().findFirst();
    }
    public List<Message> due(long now) {
        return database.query("SELECT * FROM benchmark_mail_messages WHERE attempts<5 AND expires_at>? AND ((state IN ('queued','retry_wait') AND next_attempt_at<=?) OR (state='dispatching' AND lease_until<=?)) ORDER BY next_attempt_at,id LIMIT 10", mapper, now, now, now);
    }
    public Optional<Message> claim(String id, long now) {
        String claim = UUID.randomUUID().toString();
        int changed = database.update("UPDATE benchmark_mail_messages SET state='dispatching',attempts=attempts+1,claim_id=?,lease_until=? WHERE id=? AND attempts<5 AND expires_at>? AND ((state IN ('queued','retry_wait') AND next_attempt_at<=?) OR (state='dispatching' AND lease_until<=?))", claim, now + 60_000, id, now, now, now);
        return changed == 1 ? find(id) : Optional.empty();
    }
    public boolean validReference(Message message, long now) {
        Integer count = switch (message.purpose()) {
            case "admin_invite" -> database.queryForObject("SELECT COUNT(*) FROM benchmark_admin_invitations WHERE id=? AND generation=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>?",
                    Integer.class, message.reference(), message.generation(), now);
            case "password_reset_code" -> database.queryForObject("SELECT COUNT(*) FROM benchmark_password_reset_challenges WHERE id=? AND user_id IS NOT NULL AND status='active' AND expires_at>?",
                    Integer.class, message.reference(), now);
            default -> 0;
        };
        return count != null && count > 0;
    }
    public void finish(Message message, String state, String error, long now) {
        database.update("UPDATE benchmark_mail_messages SET state=?,error_code=?,completed_at=?,ciphertext=NULL,nonce=NULL,key_id=NULL,lease_until=NULL,claim_id=NULL WHERE id=? AND state='dispatching' AND claim_id=?", state, error, now, message.id(), message.claimId());
    }
    public void retry(Message message, String error, long next) {
        database.update("UPDATE benchmark_mail_messages SET state='retry_wait',error_code=?,next_attempt_at=?,lease_until=NULL,claim_id=NULL WHERE id=? AND state='dispatching' AND claim_id=?", error, next, message.id(), message.claimId());
    }
    public void cancelReference(String purpose, String reference, long now) {
        database.update("UPDATE benchmark_mail_messages SET state='cancelled',completed_at=?,ciphertext=NULL,nonce=NULL,key_id=NULL,lease_until=NULL,claim_id=NULL WHERE purpose=? AND reference_id=? AND state IN " + OPEN, now, purpose, reference);
    }
    public void cancelOwner(String owner, long now) {
        database.update("UPDATE benchmark_mail_messages SET state='cancelled',completed_at=?,ciphertext=NULL,nonce=NULL,key_id=NULL,lease_until=NULL,claim_id=NULL WHERE purpose='password_reset_code' AND owner_id=? AND state IN " + OPEN, now, owner);
    }
    public void expire(long now) {
        database.update("UPDATE benchmark_mail_messages SET state='expired',completed_at=?,ciphertext=NULL,nonce=NULL,key_id=NULL,lease_until=NULL,claim_id=NULL WHERE expires_at<=? AND state IN " + OPEN, now, now);
        database.update("UPDATE benchmark_mail_messages SET state='failed',error_code='attempts_exhausted',completed_at=?,ciphertext=NULL,nonce=NULL,key_id=NULL,lease_until=NULL,claim_id=NULL WHERE state='dispatching' AND attempts>=5 AND lease_until<=?", now, now);
    }
    public record Message(String id, String purpose, String reference, String owner, int generation, String transport, String state,
                          MailCipher.Envelope encrypted, int attempts, long nextAttemptAt, long expiresAt, String claimId, String errorCode) {}
}
