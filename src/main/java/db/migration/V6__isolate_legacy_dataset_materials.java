package db.migration;

import com.cryptobench.benchmark.dataset.service.CustomDatasetRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** H2 and MySQL share JDBC here; no database-specific JSON expressions or files are used. */
public class V6__isolate_legacy_dataset_materials extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        record Legacy(String id, String version, String payload) {}
        List<Legacy> rows = new ArrayList<>();
        try (var statement = context.getConnection().prepareStatement("SELECT id,dataset_version,payload FROM benchmark_datasets"); var result = statement.executeQuery()) {
            while (result.next()) rows.add(new Legacy(result.getString(1), result.getString(2), result.getString(3)));
        }
        for (Legacy row : rows) {
            ObjectNode original = (ObjectNode) mapper.readTree(row.payload());
            original.put("id", row.id()); original.put("version", row.version());
            String hash = CustomDatasetRules.publicHash(original, false);
            ObjectNode publicSummary = CustomDatasetRules.summary(original, false, hash, null);
            try (PreparedStatement insert = context.getConnection().prepareStatement("INSERT INTO benchmark_dataset_packages(dataset_id,dataset_version,private_payload,package_sha256) VALUES(?,?,?,?)")) {
                insert.setString(1, row.id()); insert.setString(2, row.version()); insert.setString(3, original.toString()); insert.setString(4, CustomDatasetRules.hash(original)); insert.executeUpdate();
            }
            try (PreparedStatement update = context.getConnection().prepareStatement("UPDATE benchmark_datasets SET payload=?,schema_version='legacy',execution_status='legacy_unbound',catalog_sha256=? WHERE id=? AND dataset_version=?")) {
                update.setString(1, publicSummary.toString()); update.setString(2, hash); update.setString(3, row.id()); update.setString(4, row.version()); update.executeUpdate();
            }
        }
    }
}
