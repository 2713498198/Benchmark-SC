package com.cryptobench.benchmark;

import com.cryptobench.benchmark.auth.dto.LoginRequest;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.auth.service.AuthService;
import com.cryptobench.benchmark.config.BenchmarkProperties;
import com.cryptobench.benchmark.connection.repository.ConnectionRepository;
import com.cryptobench.benchmark.dataset.repository.DatasetRepository;
import com.cryptobench.benchmark.migration.LegacyDataImporter;
import com.cryptobench.benchmark.report.repository.ReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 旧 JSON 数据导入的集成测试，使用独立内存数据库；测试事务结束后回滚数据库写入。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:legacy-import-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Transactional
class LegacyDataImportIntegrationTest {
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired ConnectionRepository connections;
    @Autowired ReportRepository reports;
    @Autowired DatasetRepository datasets;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;

    /**
     * 验证旧账号密码可用、关联数据完整、旧会话不导入、源文件不变，以及重复导入被拒绝。
     */
    @Test
    void importsLegacyDataAndPasswordsWithoutChangingSourceOrReusingSessions() throws Exception {
        // 在构建输出目录创建旧版数据样本，不接触开发环境真实数据。
        Path source = Files.createTempDirectory(Path.of("target"), "legacy-import-").resolve("db.json");
        String fixture = """
                {
                  "users": [{"id":"legacy-user","email":"legacy@example.com","name":"Legacy","role":"researcher",
                    "passwordHash":"62fd440a53a6d02a85a9760eafe8eb476210009581221d589e528c85a802ff37cc9030dc9e4a29d3c7b26a0e4f03fd85101367fecda364309b1ed2d7298ba3d4",
                    "passwordSalt":"0123456789abcdef0123456789abcdef","createdAt":"2026-01-01T00:00:00Z"}],
                  "sessions":[{"token":"old-token","userId":"legacy-user","expiresAt":9999999999999}],
                  "connections":[{"id":"legacy-connection","ownerId":"legacy-user","name":"Agent","url":"http://localhost:9000","model":"test","createdAt":"2026-01-01T00:00:00Z"}],
                  "reports":[{"id":"legacy-report","ownerId":"legacy-user","published":true,"synced":true,"scope":"full"}],
                  "customDatasets":[{"id":"legacy-suite","version":"1.0.0","ownerId":"legacy-user","published":true,"tasks":1,"taskPackage":[{"id":"task-1"}]}]
                }
                """;
        Files.writeString(source, fixture);
        // 手动构造启用开关的导入器；此处直接调用，数据库事务由测试类上的 @Transactional 提供。
        var importer = new LegacyDataImporter(new BenchmarkProperties(false, false, true, source.toString()),
                mapper, users, connections, reports, datasets);
        importer.run(new DefaultApplicationArguments());

        assertThat(auth.login(new LoginRequest("legacy@example.com", "legacy-test-password")).id()).isEqualTo("legacy-user");
        assertThat(connections.findByOwner("legacy-user")).hasSize(1);
        assertThat(reports.findPublished()).hasSize(1);
        assertThat(reports.findPublished().getFirst().toResponse().has("ownerId")).isFalse();
        assertThat(datasets.findPublished()).hasSize(1);
        // 旧令牌不延续到新系统，迁移后的用户必须重新登录。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_sessions", Long.class)).isZero();
        assertThat(Files.readString(source)).isEqualTo(fixture);
        assertThatThrownBy(() -> importer.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
    }
}
