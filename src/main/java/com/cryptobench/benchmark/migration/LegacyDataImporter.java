package com.cryptobench.benchmark.migration;

import com.cryptobench.benchmark.auth.model.BenchmarkUser;
import com.cryptobench.benchmark.auth.repository.UserRepository;
import com.cryptobench.benchmark.config.BenchmarkProperties;
import com.cryptobench.benchmark.connection.model.AgentConnection;
import com.cryptobench.benchmark.connection.repository.ConnectionRepository;
import com.cryptobench.benchmark.dataset.repository.DatasetRepository;
import com.cryptobench.benchmark.report.model.BenchmarkReport;
import com.cryptobench.benchmark.report.repository.ReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 由显式配置开关启用的启动导入器，将旧 Node 服务的 JSON 数据导入关系数据库。
 * 源文件只读；不迁移旧会话，用户必须重新登录。
 */
@Component
public class LegacyDataImporter implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyDataImporter.class);
    private final BenchmarkProperties properties;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final ConnectionRepository connections;
    private final ReportRepository reports;
    private final DatasetRepository datasets;

    public LegacyDataImporter(BenchmarkProperties properties, ObjectMapper mapper, UserRepository users,
                              ConnectionRepository connections, ReportRepository reports, DatasetRepository datasets) {
        this.properties = properties;
        this.mapper = mapper;
        this.users = users;
        this.connections = connections;
        this.reports = reports;
        this.datasets = datasets;
    }

    /**
     * 启动时检查开关、源文件及目标用户表；数据写入处于同一事务，运行时异常会触发回滚。
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.legacyImportEnabled()) {
            return;
        }
        Path source = Path.of(properties.legacyDbFile());
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("Legacy database file does not exist: " + source);
        }
        // 要求目标用户表为空，避免重复导入或与已有账号混合。
        if (users.count() != 0) {
            throw new IllegalStateException("Legacy import requires an empty target database; disable LEGACY_IMPORT_ENABLED");
        }
        JsonNode database = mapper.readTree(source.toFile());
        if (!database.path("users").isArray()) {
            throw new IllegalStateException("Invalid legacy database: users array is missing");
        }
        // 先导入用户再导入关联资源，满足外键依赖；保留旧密码哈希和盐供兼容验证。
        for (JsonNode user : database.path("users")) {
            users.insert(new BenchmarkUser(user.path("id").asText(), user.path("email").asText(),
                    user.path("name").asText(), user.path("role").asText(), user.path("passwordHash").asText(),
                    user.path("passwordSalt").asText(), user.path("createdAt").asText()));
        }
        for (JsonNode input : database.path("connections")) {
            connections.insert(new AgentConnection(input.path("id").asText(), input.path("ownerId").asText(),
                    input.path("name").asText(), input.path("url").asText(), input.path("model").asText(),
                    input.path("createdAt").asText()));
        }
        // 归属存入独立数据库列，响应文档中移除 ownerId。
        for (JsonNode input : database.path("reports")) {
            ObjectNode payload = ((ObjectNode) input).deepCopy();
            payload.remove("ownerId");
            reports.insert(new BenchmarkReport(input.path("id").asText(), input.path("ownerId").asText(),
                    input.path("published").asBoolean(), payload));
        }
        // 只迁移已发布的自定义测评集，跳过旧版未发布草稿。
        for (JsonNode input : database.path("customDatasets")) {
            if (input.path("published").asBoolean()) {
                ObjectNode payload = ((ObjectNode) input).deepCopy();
                payload.remove("ownerId");
                datasets.insert(input.path("ownerId").asText(), payload);
            }
        }
        LOG.info("Legacy import complete: {} users; source left unchanged; sign in again to create new sessions",
                database.path("users").size());
    }
}
