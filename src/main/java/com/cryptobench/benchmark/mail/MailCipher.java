package com.cryptobench.benchmark.mail;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Authenticated encryption for short-lived delivery payloads, never for password hashes. */
@Component
public class MailCipher {
    private final MailProperties properties;
    private final boolean production;
    private volatile byte[] current;
    private static final SecureRandom RANDOM = new SecureRandom();
    public MailCipher(MailProperties properties, Environment environment) {
        this.properties = properties;
        production = environment.acceptsProfiles(Profiles.of("prod"));
    }
    public void checkReady() { key(); }
    /** Low-entropy codes need a keyed digest, separate from the AES encryption key. */
    public CodeDigest codeDigest(String challengeId, String userId, String code) {
        byte[] secret = key();
        return new CodeDigest(codeMac(secret, challengeId, userId, code), keyId(secret));
    }
    public boolean verifyCode(String challengeId, String userId, String code, String digest, String id) {
        if (digest == null || id == null || code == null || !code.matches("[0-9]{6}")) return false;
        byte[] secret;
        try { secret = resolveKey(id); }
        catch (RuntimeException e) { throw new CodeKeyUnavailableException(); }
        try {
            byte[] expected = HexFormat.of().parseHex(digest);
            byte[] actual = HexFormat.of().parseHex(codeMac(secret, challengeId, userId, code));
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) { return false; }
    }
    private String codeMac(byte[] secret, String challengeId, String userId, String code) {
        byte[] derived = null;
        byte[] extracted = null;
        try {
            // RFC 5869 extract + one-block expand, with a purpose-specific salt and info.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("cryptobench:password-reset-code:hkdf:v1".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            extracted = mac.doFinal(secret);
            mac.init(new SecretKeySpec(extracted, "HmacSHA256"));
            mac.update("otp-hmac-v1".getBytes(StandardCharsets.UTF_8));
            derived = mac.doFinal(new byte[] { 1 });
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            var buffer = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(buffer)) {
                for (String field : new String[] { "password_reset_code", challengeId, userId == null ? "" : userId, code }) {
                    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(bytes.length); output.write(bytes);
                }
            }
            return HexFormat.of().formatHex(mac.doFinal(buffer.toByteArray()));
        } catch (Exception e) { throw new IllegalStateException("reset_code_digest_unavailable"); }
        finally {
            if (derived != null) Arrays.fill(derived, (byte) 0);
            if (extracted != null) Arrays.fill(extracted, (byte) 0);
        }
    }
    private byte[] resolveKey(String id) {
        byte[] secret = key();
        if (keyId(secret).equals(id)) return secret;
        String previous = properties.getPreviousKeys().get(id);
        if (previous == null) throw new IllegalStateException("mail_key_unavailable");
        byte[] decoded = decode(previous);
        if (!keyId(decoded).equals(id)) throw new IllegalStateException("mail_key_unavailable");
        return decoded;
    }
    public Envelope encrypt(String messageId, String purpose, String reference, String payload) {
        try {
            byte[] secret = key(), nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(messageId, purpose, reference));
            return new Envelope(Base64.getEncoder().encodeToString(cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8))),
                    Base64.getEncoder().encodeToString(nonce), keyId(secret));
        } catch (Exception e) { throw new IllegalStateException("mail_encryption_unavailable", e); }
    }
    public String decrypt(String messageId, String purpose, String reference, Envelope envelope) {
        try {
            byte[] secret = resolveKey(envelope.keyId());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(envelope.nonce())));
            cipher.updateAAD(aad(messageId, purpose, reference));
            return new String(cipher.doFinal(Base64.getDecoder().decode(envelope.ciphertext())), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("mail_decryption_failed"); }
    }
    private synchronized byte[] key() {
        if (current != null) return current;
        try {
            if (!properties.getKey().isBlank()) return current = decode(properties.getKey());
            Path file = Path.of(properties.getKeyFile()).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(file)) throw new IllegalStateException();
            if (production) return current = decode(Files.readString(file, StandardCharsets.US_ASCII));
            boolean newDirectory = !Files.exists(file.getParent());
            Files.createDirectories(file.getParent());
            if (newDirectory) restrict(file.getParent(), true);
            Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
            if (Files.isSymbolicLink(lockPath)) throw new IllegalStateException();
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                restrict(lockPath, false);
                try (var ignored = channel.lock()) {
                    if (!Files.exists(file)) {
                        byte[] generated = new byte[32]; RANDOM.nextBytes(generated);
                        Files.createFile(file); restrict(file, false);
                        Files.writeString(file, Base64.getEncoder().encodeToString(generated), StandardCharsets.US_ASCII);
                    }
                    restrict(file, false);
                    return current = decode(Files.readString(file, StandardCharsets.US_ASCII));
                }
            }
        } catch (Exception e) { throw new IllegalStateException("mail_key_unavailable", e); }
    }
    private static byte[] decode(String encoded) {
        byte[] result = Base64.getDecoder().decode(encoded.trim());
        if (result.length != 32) throw new IllegalArgumentException();
        return result;
    }
    public static String keyId(byte[] key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key)).substring(0, 32); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private byte[] aad(String id, String purpose, String reference) { return (id + "\n" + purpose + "\n" + reference).getBytes(StandardCharsets.UTF_8); }
    private void restrict(Path file, boolean directory) throws java.io.IOException {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) { posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); return; }
        var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) throw new java.io.IOException("private file permissions unavailable");
        var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(builder.build()));
    }
    public record Envelope(String ciphertext, String nonce, String keyId) {}
    public record CodeDigest(String value, String keyId) {}
    public static class CodeKeyUnavailableException extends IllegalStateException {
        public CodeKeyUnavailableException() { super("reset_code_key_unavailable"); }
    }
}
