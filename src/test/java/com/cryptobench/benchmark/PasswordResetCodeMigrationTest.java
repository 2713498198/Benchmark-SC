package com.cryptobench.benchmark;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 升级隔离的文件数据库，重启 JDBC 连接后仍应保留验证码流程状态。 */
class PasswordResetCodeMigrationTest {
    @TempDir Path temporary;

    @Test void v11CreatesCodeResetTablesAndPreservesSessions()throws Exception{
        String url="jdbc:h2:file:"+temporary.resolve("reset-upgrade").toAbsolutePath().toString().replace('\\','/')+";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").target("10").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){
            statement.execute("INSERT INTO benchmark_users(id,email,display_name,user_role,password_hash,created_at) VALUES('old-reset-user','old@example.test','Existing user','researcher','preserved-password-hash','2026-01-01T00:00:00Z')");
            statement.execute("INSERT INTO benchmark_sessions(token_hash,user_id,expires_at) VALUES('"+"b".repeat(64)+"','old-reset-user',4102444800000)");
        }
        var migration=Flyway.configure().dataSource(url,"sa","").load();migration.migrate();migration.validate();assertThat(migration.info().pending()).isEmpty();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){
            try(var rows=statement.executeQuery("SELECT password_hash FROM benchmark_users WHERE id='old-reset-user'")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("preserved-password-hash");}
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM benchmark_sessions WHERE user_id='old-reset-user'")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
            statement.execute("INSERT INTO benchmark_password_reset_challenges(id,user_id,email_key,code_digest,code_key_id,status,created_at,expires_at) VALUES('"+"c".repeat(64)+"','old-reset-user','"+"d".repeat(64)+"','"+"e".repeat(64)+"','"+"f".repeat(32)+"','active',1,4102444800000)");
            statement.execute("UPDATE benchmark_password_reset_challenges SET failed_attempts=3 WHERE id='"+"c".repeat(64)+"'");
            statement.execute("INSERT INTO benchmark_password_reset_grants(token_hash,user_id,challenge_id,created_at,expires_at) VALUES('"+"9".repeat(64)+"','old-reset-user','"+"c".repeat(64)+"',1,4102444800000)");
            statement.execute("INSERT INTO benchmark_password_reset_rate_events(id,event_scope,subject_hash,created_at) VALUES('upgrade-failed-event','failed_email','"+"d".repeat(64)+"',1)");
        }
        // No DB_CLOSE_DELAY: all connections were closed, so this loads persisted H2 state again.
        Flyway.configure().dataSource(url,"sa","").load().validate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){
            try(var rows=statement.executeQuery("SELECT failed_attempts FROM benchmark_password_reset_challenges WHERE id='"+"c".repeat(64)+"'")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(3);}
            try(var rows=statement.executeQuery("SELECT expires_at,used_at,revoked_at FROM benchmark_password_reset_grants WHERE user_id='old-reset-user'")){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(4102444800000L);assertThat(rows.getObject(2)).isNull();assertThat(rows.getObject(3)).isNull();}
            try(var rows=statement.executeQuery("SELECT event_scope,subject_hash FROM benchmark_password_reset_rate_events WHERE id='upgrade-failed-event'")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("failed_email");assertThat(rows.getString(2)).isEqualTo("d".repeat(64));}
        }
    }
}
