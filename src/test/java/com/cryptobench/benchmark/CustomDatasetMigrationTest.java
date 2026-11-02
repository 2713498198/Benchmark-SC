package com.cryptobench.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CustomDatasetMigrationTest {
    @Test
    void migratesExistingV4DatasetWithoutPublishingPrivateFieldsOrEnablingExecution() throws Exception {
        String url = "jdbc:h2:mem:custom-migration-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("4").load().migrate();
        String old = """
                {"id":"legacy-suite","version":"1.9.0","name":"Legacy","tasks":1,"published":true,
                 "secret":"PRIVATE_MARKER","taskPackage":[{"id":"task-1","objective":"Public question","input":{"rounds":4},"expected":{"answer":"HIDDEN_ANSWER"}}]}
                """;
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO benchmark_users(id,email,display_name,user_role,password_hash,created_at) VALUES('migration-owner','migration@example.test','Migration','admin','not-a-real-password','2026-01-01T00:00:00Z')");
            }
            try (var insert = connection.prepareStatement("INSERT INTO benchmark_datasets(id,dataset_version,owner_id,payload) VALUES('legacy-suite','1.9.0','migration-owner',?)")) { insert.setString(1, old); insert.executeUpdate(); }
        }
        var upgraded = Flyway.configure().dataSource(url, "sa", "").target("6").load();
        upgraded.migrate();
        upgraded.migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT payload,execution_status FROM benchmark_datasets")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).doesNotContain("PRIVATE_MARKER", "HIDDEN_ANSWER", "taskPackage", "expected");
                assertThat(new ObjectMapper().readTree(rows.getString(1)).path("executionReady").asBoolean()).isFalse();
                assertThat(rows.getString(2)).isEqualTo("legacy_unbound");
            }
            try (var rows = statement.executeQuery("SELECT private_payload FROM benchmark_dataset_packages")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).contains("HIDDEN_ANSWER", "Public question");
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
