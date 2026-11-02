package com.cryptobench.benchmark;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Opt-in test for a disposable database; never accepts a normal production database name. */
@EnabledIfEnvironmentVariable(named="CRYPTOBENCH_TEST_MYSQL_URL",matches=".*")
class MysqlMigrationIntegrationTest {
    @Test void upgradesV4FixtureThroughAllNewMigrationsAndValidatesSchema()throws Exception{
        String url=System.getenv("CRYPTOBENCH_TEST_MYSQL_URL"),user=System.getenv("CRYPTOBENCH_TEST_MYSQL_USER"),password=System.getenv("CRYPTOBENCH_TEST_MYSQL_PASSWORD");
        assertThat(url).matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/cryptobench_test_[a-z0-9_]+(?:\\?.*)?");
        try(var connection=DriverManager.getConnection(url,user,password);var query=connection.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")){rows.next();assertThat(rows.getInt(1)).as("use a fresh disposable database").isZero();}
        Flyway.configure().dataSource(url,user,password).target("4").load().migrate();
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            statement.execute("INSERT INTO benchmark_users(id,email,display_name,user_role,password_hash,created_at) VALUES('mysql-fixture-owner','mysql@example.test','Fixture','admin','fixture-hash','2026-01-01T00:00:00Z')");
            statement.execute("INSERT INTO benchmark_password_reset_tokens(token_hash,user_id,expires_at,created_at) VALUES('"+"a".repeat(64)+"','mysql-fixture-owner',4102444800000,'2026-01-01T00:00:00Z')");
            statement.execute("INSERT INTO benchmark_sessions(token_hash,user_id,expires_at) VALUES('"+"b".repeat(64)+"','mysql-fixture-owner',4102444800000)");
            try(var insert=connection.prepareStatement("INSERT INTO benchmark_datasets(id,dataset_version,owner_id,payload) VALUES('mysql-suite','1.0.0','mysql-fixture-owner',?)")){
                insert.setString(1,"{\"id\":\"mysql-suite\",\"version\":\"1.0.0\",\"tasks\":1,\"taskPackage\":[{\"id\":\"t1\",\"objective\":\"Public question\",\"expected\":{\"answer\":\"PRIVATE_MYSQL_MARKER\"}}]}");insert.executeUpdate();
            }
        }
        var latest=Flyway.configure().dataSource(url,user,password).load();latest.migrate();latest.validate();assertThat(latest.info().pending()).isEmpty();
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
            try(var rows=statement.executeQuery("SELECT payload,execution_status FROM benchmark_datasets WHERE id='mysql-suite'")){rows.next();assertThat(rows.getString(1)).doesNotContain("PRIVATE_MYSQL_MARKER", "taskPackage");assertThat(rows.getString(2)).isEqualTo("legacy_unbound");}
            try(var rows=statement.executeQuery("SELECT private_payload FROM benchmark_dataset_packages WHERE dataset_id='mysql-suite'")){rows.next();assertThat(rows.getString(1)).contains("PRIVATE_MYSQL_MARKER");}
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('benchmark_bridge_pairings','benchmark_bridge_devices','benchmark_bridge_jobs')")){rows.next();assertThat(rows.getInt(1)).isZero();}
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('benchmark_users','benchmark_runs','benchmark_reports','benchmark_dataset_packages')")){rows.next();assertThat(rows.getInt(1)).isEqualTo(4);}
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('benchmark_password_reset_challenges','benchmark_password_reset_grants','benchmark_password_reset_rate_events')")){rows.next();assertThat(rows.getInt(1)).isEqualTo(3);}
            try(var rows=statement.executeQuery("SELECT used_at FROM benchmark_password_reset_tokens WHERE token_hash='"+"a".repeat(64)+"'")){assertThat(rows.next()).isTrue();assertThat(rows.getObject(1)).isNull();}
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM benchmark_sessions WHERE user_id='mysql-fixture-owner'")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
            statement.execute("INSERT INTO benchmark_password_reset_challenges(id,user_id,email_key,code_digest,code_key_id,status,created_at,expires_at) VALUES('"+"c".repeat(64)+"','mysql-fixture-owner','"+"d".repeat(64)+"','"+"e".repeat(64)+"','"+"f".repeat(32)+"','active',1,4102444800000)");
            statement.execute("UPDATE benchmark_password_reset_challenges SET failed_attempts=failed_attempts+1 WHERE id='"+"c".repeat(64)+"'");
            try(var rows=statement.executeQuery("SELECT failed_attempts,expires_at FROM benchmark_password_reset_challenges WHERE id='"+"c".repeat(64)+"'")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);assertThat(rows.getLong(2)).isEqualTo(4102444800000L);}
            statement.execute("INSERT INTO benchmark_password_reset_grants(token_hash,user_id,challenge_id,created_at,expires_at) VALUES('"+"9".repeat(64)+"','mysql-fixture-owner','"+"c".repeat(64)+"',1,4102444800000)");
            statement.execute("INSERT INTO benchmark_password_reset_rate_events(id,event_scope,subject_hash,created_at) VALUES('migration-event','failed_email','"+"d".repeat(64)+"',1)");
        }
    }
}
