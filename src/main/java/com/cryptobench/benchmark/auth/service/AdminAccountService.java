package com.cryptobench.benchmark.auth.service;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.AdminAuditRepository;
import com.cryptobench.benchmark.auth.repository.AdminInvitationRepository;
import com.cryptobench.benchmark.auth.repository.SessionRepository;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-side lifecycle rules for real administrator accounts. */
@Service
public class AdminAccountService {
    private static final Duration INVITATION_LIFETIME = Duration.ofHours(48);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final SessionRepository sessions;
    private final AdminInvitationRepository invitations;
    private final AdminAuditRepository audits;
    private final PasswordResetNotifier notifier;
    private final PasswordService passwords;
    private final ObjectMapper mapper;

    public AdminAccountService(UserRepository users, SessionRepository sessions,
                               AdminInvitationRepository invitations, AdminAuditRepository audits,
                               PasswordResetNotifier notifier, PasswordService passwords, ObjectMapper mapper) {
        this.users = users;
        this.sessions = sessions;
        this.invitations = invitations;
        this.audits = audits;
        this.notifier = notifier;
        this.passwords = passwords;
        this.mapper = mapper;
    }

    public Page<AccountView> list(String query, String role, String status, int page, int size) {
        validateFilter(role, "admin", "researcher");
        validateFilter(status, "active", "disabled");
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<AccountView> items = users.findPage(query, role, status, safePage, safeSize).stream()
                .map(AccountView::from).toList();
        return new Page<>(items, users.countPage(query, role, status), safePage, safeSize);
    }

    @Transactional
    public InvitationResult invite(BenchmarkUser actor, String emailInput, String nameInput) {
        String email = normalizeEmail(emailInput);
        String name = clean(nameInput);
        if (name.isBlank() || name.length() > 30) throw bad("姓名长度无效");
        if (users.existsByEmail(email)) throw conflict("该邮箱已经注册");
        long now = System.currentTimeMillis();
        invitations.deleteOutstanding(email, now);
        if (invitations.hasOutstanding(email, now)) throw conflict("该邮箱已有待处理邀请");
        String token = randomToken();
        long expires = now + INVITATION_LIFETIME.toMillis();
        invitations.insert("invite_" + UUID.randomUUID(), email, name, hash(token), actor.id(),
                Instant.now().toString(), expires);
        boolean delivered = notifier.sendAdminInvite(email, name, token, Instant.ofEpochMilli(expires));
        audit(actor.id(), null, "invite_admin", json("email", email, "name", name), "success");
        return new InvitationResult(delivered ? "queued" : "unconfigured", Instant.ofEpochMilli(expires).toString(), notifier.deliveryMode());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public BenchmarkUser acceptInvitation(String rawToken, String password) {
        if (rawToken == null || !rawToken.matches("[a-f0-9]{64}") || password == null || password.length() < 12) {
            audit(null, null, "accept_admin_invite", json("reason", "invalid_format"), "failed");
            throw bad("邀请令牌或密码格式无效");
        }
        var invitation = invitations.find(hash(rawToken)).orElse(null);
        if (invitation == null) {
            audit(null, null, "accept_admin_invite", json("reason", "unknown_or_expired"), "failed");
            throw bad("邀请令牌无效或已过期");
        }
        long now = System.currentTimeMillis();
        if (invitation.usedAt() != null || invitation.expiresAt() <= now) {
            audit(invitation.invitedBy(), null, "accept_admin_invite", json("email", invitation.email()), "failed");
            throw bad("邀请令牌无效或已过期");
        }
        if (users.existsByEmail(invitation.email())) {
            audit(invitation.invitedBy(), null, "accept_admin_invite", json("email", invitation.email(), "reason", "email_exists"), "failed");
            throw conflict("该邮箱已经注册");
        }
        if (invitations.consume(invitation.tokenHash(), now) != 1) {
            audit(invitation.invitedBy(), null, "accept_admin_invite", json("email", invitation.email(), "reason", "already_used"), "failed");
            throw bad("邀请令牌无效或已过期");
        }
        BenchmarkUser user = new BenchmarkUser("admin_" + UUID.randomUUID(), invitation.email(),
                invitation.displayName(), "admin", passwords.encode(password), null, Instant.now().toString());
        users.insert(user);
        audit(invitation.invitedBy(), user.id(), "accept_admin_invite", json("email", user.email()), "success");
        return user;
    }

    @Transactional
    public AccountView changeRole(BenchmarkUser actor, String id, String role) {
        if (!"admin".equals(role) && !"researcher".equals(role)) throw bad("角色无效");
        BenchmarkUser target = find(id);
        if (actor.id().equals(target.id())) throw bad("不能修改自己的角色");
        if (target.role().equals(role)) return AccountView.from(target);
        if ("admin".equals(target.role()) && "researcher".equals(role)
                && "active".equals(target.accountStatus()) && users.countActiveAdmins() <= 1) {
            throw conflict("不能降级最后一个有效管理员");
        }
        users.updateRole(target.id(), role);
        audit(actor.id(), target.id(), "change_role", json("email", target.email(), "from", target.role(), "to", role), "success");
        return AccountView.from(find(target.id()));
    }

    @Transactional
    public AccountView changeStatus(BenchmarkUser actor, String id, String status) {
        if (!"active".equals(status) && !"disabled".equals(status)) throw bad("账号状态无效");
        BenchmarkUser target = find(id);
        if (actor.id().equals(target.id())) throw bad("不能修改自己的账号状态");
        if (target.accountStatus().equals(status)) return AccountView.from(target);
        if ("disabled".equals(status) && "admin".equals(target.role()) && users.countActiveAdmins() <= 1) {
            throw conflict("不能停用最后一个有效管理员");
        }
        String at = "disabled".equals(status) ? Instant.now().toString() : null;
        users.updateStatus(target.id(), status, at, "disabled".equals(status) ? actor.id() : null);
        int revoked = "disabled".equals(status) ? sessions.deleteByUserAndCount(target.id()) : 0;
        audit(actor.id(), target.id(), "disabled".equals(status) ? "disable_user" : "enable_user",
                json("email", target.email(), "revokedSessions", revoked), "success");
        return AccountView.from(find(target.id()));
    }

    @Transactional
    public int revokeSessions(BenchmarkUser actor, String id) {
        BenchmarkUser target = find(id);
        int count = sessions.deleteByUserAndCount(target.id());
        audit(actor.id(), target.id(), "revoke_sessions", json("email", target.email(), "count", count), "success");
        return count;
    }

    public Page<AuditView> audit(String action, String target, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<AuditView> items = audits.findPage(action, target, safePage, safeSize).stream()
                .map(AuditView::from).toList();
        return new Page<>(items, audits.count(action, target), safePage, safeSize);
    }

    private BenchmarkUser find(String id) {
        return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "账号不存在"));
    }

    private void audit(String actor, String target, String action, String metadata, String result) {
        audits.insert("audit_" + UUID.randomUUID(), actor, target, action, metadata, result, Instant.now().toString());
    }

    private String json(Object... values) {
        try {
            var node = mapper.createObjectNode();
            for (int i = 0; i + 1 < values.length; i += 2) node.put(String.valueOf(values[i]), String.valueOf(values[i + 1]));
            return mapper.writeValueAsString(node);
        } catch (Exception exception) { return "{}"; }
    }

    private static String normalizeEmail(String input) {
        String email = clean(input).toLowerCase(Locale.ROOT);
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw bad("请输入有效邮箱");
        return email;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static void validateFilter(String value, String... allowed) {
        if (value != null && !value.isBlank() && java.util.Arrays.stream(allowed).noneMatch(value::equals)) throw bad("筛选条件无效");
    }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    private static String randomToken() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record InvitationResult(String status, String expiresAt, String delivery) {}
    public record AccountView(String id, String name, String email, String role, String status, String createdAt) {
        static AccountView from(BenchmarkUser user) { return new AccountView(user.id(), user.name(), user.email(), user.role(), user.accountStatus(), user.createdAt()); }
    }
    public record AuditView(String id, String actorUserId, String targetUserId, String action,
                            String metadata, String result, String createdAt) {
        static AuditView from(AdminAuditRepository.Audit audit) { return new AuditView(audit.id(), audit.actorUserId(), audit.targetUserId(), audit.action(), audit.metadata(), audit.result(), audit.createdAt()); }
    }
}
