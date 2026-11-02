package com.cryptobench.benchmark;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Retiring remote execution must not delete accounts, finished results or recovery credentials. */
class LocalExecutionMigrationTest {
    @TempDir Path temporary;

    @Test void v12RemovesOnlyDeviceTablesAndPreservesExistingRecordsAcrossRestart() throws Exception {
        String url = url("existing");
        Flyway.configure().dataSource(url, "sa", "").target("11").load().migrate();
        String report = "{\"id\":\"preserved-report\",\"executorType\":\"bridge\",\"cost\":0.123456}";
        String config = "{\"executorType\":\"bridge\",\"bridgeId\":\"retired-device\",\"seed\":987}";
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO benchmark_users(id,email,display_name,user_role,password_hash,created_at) VALUES('preserved-owner','retire@example.test','Existing user','admin','preserved-hash','2026-01-01T00:00:00Z')");
            statement.execute("INSERT INTO benchmark_sessions(token_hash,user_id,expires_at) VALUES('" + "a".repeat(64) + "','preserved-owner',4102444800000)");
            statement.execute("INSERT INTO benchmark_password_reset_tokens(token_hash,user_id,expires_at,created_at) VALUES('" + "b".repeat(64) + "','preserved-owner',4102444800000,'2026-01-01T00:00:00Z')");
            statement.execute("INSERT INTO benchmark_reports(id,owner_id,published,payload) VALUES('preserved-report','preserved-owner',TRUE,'" + report + "')");
            statement.execute("INSERT INTO benchmark_runs(run_id,owner_id,kind,execution_source,dataset_id,dataset_version,budget,task_scope,model_id,seed,status,total_tasks,completed_tasks,passed_tasks,config_payload,report_id,created_at) VALUES('preserved-run','preserved-owner','evaluation','local','mini-spn','1.0.0','standard','sample','fixture',987,'completed',1,1,1,'" + config + "','preserved-report','2026-01-01T00:00:00Z')");
            statement.execute("INSERT INTO benchmark_run_tasks(run_id,task_id,ordinal,status,passed,cost_usd,agent_response) VALUES('preserved-run','main:task-1',1,'passed',TRUE,0.123456,'{\"artifact\":{\"answer\":4}}')");
            statement.execute("INSERT INTO benchmark_bridge_pairings(code_hash,owner_id,device_name,expires_at) VALUES('" + "c".repeat(64) + "','preserved-owner','Retired computer',4102444800000)");
            statement.execute("INSERT INTO benchmark_bridge_devices(id,owner_id,device_name,token_hash,created_at,last_seen,agents_payload) VALUES('retired-device','preserved-owner','Retired computer','" + "d".repeat(64) + "',1,2,'[]')");
            statement.execute("INSERT INTO benchmark_bridge_jobs(id,bridge_id,owner_id,run_id,generation,task_id,attempt,status,request_payload,created_at) VALUES('retired-job','retired-device','preserved-owner','preserved-run',1,'main:task-1',1,'completed','{}',1)");
        }

        var latest = Flyway.configure().dataSource(url, "sa", "").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.info().pending()).isEmpty();
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            assertDeviceTablesAbsent(connection);
            assertThat(value(connection, "SELECT password_hash FROM benchmark_users WHERE id='preserved-owner'")).isEqualTo("preserved-hash");
            assertThat(value(connection, "SELECT COUNT(*) FROM benchmark_sessions WHERE user_id='preserved-owner'")).isEqualTo("1");
            assertThat(value(connection, "SELECT COUNT(*) FROM benchmark_password_reset_tokens WHERE user_id='preserved-owner' AND used_at IS NULL")).isEqualTo("1");
            assertThat(value(connection, "SELECT payload FROM benchmark_reports WHERE id='preserved-report'")).isEqualTo(report);
            assertThat(value(connection, "SELECT config_payload FROM benchmark_runs WHERE run_id='preserved-run'")).isEqualTo(config);
            assertThat(value(connection, "SELECT status FROM benchmark_runs WHERE run_id='preserved-run'")).isEqualTo("completed");
            assertThat(value(connection, "SELECT agent_response FROM benchmark_run_tasks WHERE run_id='preserved-run'")).isEqualTo("{\"artifact\":{\"answer\":4}}");
            assertThat(value(connection, "SELECT cost_usd FROM benchmark_run_tasks WHERE run_id='preserved-run'")).isEqualTo("0.123456");
        }
        // With all connections closed, the next validation reads the upgraded file database again.
        assertThat(Flyway.configure().dataSource(url, "sa", "").load().migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            assertDeviceTablesAbsent(connection);
            assertThat(value(connection, "SELECT payload FROM benchmark_reports WHERE id='preserved-report'")).isEqualTo(report);
        }
    }

    @Test void freshInstallHasLocalExecutionAndPasswordRecoveryWithoutDeviceTables() throws Exception {
        String url = url("fresh");
        var migration = Flyway.configure().dataSource(url, "sa", "").load();
        migration.migrate();
        migration.validate();
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            assertDeviceTablesAbsent(connection);
            assertThat(value(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('benchmark_users','benchmark_runs','benchmark_reports','benchmark_dataset_packages','benchmark_password_reset_challenges')")).isEqualTo("5");
        }
    }

    private String url(String name) {
        return "jdbc:h2:file:" + temporary.resolve(name).toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
    }

    private void assertDeviceTablesAbsent(Connection connection) throws Exception {
        assertThat(value(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('benchmark_bridge_pairings','benchmark_bridge_devices','benchmark_bridge_jobs')")).isEqualTo("0");
    }

    private String value(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
