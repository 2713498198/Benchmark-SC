package com.cryptobench.desktop;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.EnumSet;
import java.util.List;

/** All desktop state belongs to the selected application, never the caller's cwd. */
final class DesktopPaths {
    final Path home;
    final Path data;
    final Path run;
    final Path logs;

    DesktopPaths(Path home) {
        this.home = home.toAbsolutePath().normalize();
        this.data = this.home.resolve("var");
        this.run = data.resolve("run");
        this.logs = data.resolve("logs");
    }

    static DesktopPaths installed() {
        String configured = System.getProperty("cryptobench.desktop.home");
        if (configured == null || configured.isBlank()) {
            String launcher = System.getProperty("jpackage.app-path");
            if (launcher == null || launcher.isBlank()) {
                throw new IllegalStateException("Desktop application directory is missing");
            }
            configured = Path.of(launcher).toAbsolutePath().getParent().toString();
        }
        return new DesktopPaths(Path.of(configured));
    }

    void prepare() throws IOException {
        for (Path path : List.of(data, run, logs, data.resolve("data"), data.resolve("config"), data.resolve("secrets"))) {
            rejectLinks(path);
            Files.createDirectories(path);
        }
        privateAccess(run, true);
        privateAccess(data.resolve("secrets"), true);
        Path probe = Files.createTempFile(logs, ".write-check-", ".tmp");
        Files.delete(probe);
    }

    static void rejectLinks(Path path) throws IOException {
        for (Path part = path.toAbsolutePath().normalize(); part != null; part = part.getParent()) {
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Linked data paths are not supported: " + part);
            }
        }
    }

    static void privateAccess(Path path, boolean directory) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) throw new IOException("Cannot restrict desktop control credentials");
        var entry = AclEntry.newBuilder().setPrincipal(acl.getOwner()).setType(AclEntryType.ALLOW)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(entry.build()));
    }
}
