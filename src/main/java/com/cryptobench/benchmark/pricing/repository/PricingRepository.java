package com.cryptobench.benchmark.pricing.repository;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.cryptobench.benchmark.persistence.MybatisDatabase;
import com.cryptobench.benchmark.persistence.MybatisRowMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class PricingRepository {
    private final MybatisDatabase database;
    private static final MybatisRowMapper<PriceTable> TABLE = (row, i) -> new PriceTable(row.getString("version"),
            row.getString("status"), row.getString("created_by"), row.getString("created_at"),
            row.getString("activated_at"), row.getLong("revision"), row.getBoolean("locked"));

    public PricingRepository(MybatisDatabase database) { this.database = database; }

    public State state(boolean lock) {
        return database.queryForObject("SELECT active_version, revision FROM benchmark_pricing_state WHERE singleton_id=1"
                + (lock ? " FOR UPDATE" : ""), (row, i) -> new State(row.getString("active_version"), row.getLong("revision")));
    }

    public Optional<PriceTable> active() {
        String version = state(false).activeVersion();
        return version == null ? Optional.empty() : table(version, false);
    }

    public Optional<PriceTable> table(String version, boolean lock) {
        return database.query("SELECT * FROM benchmark_price_tables WHERE version=?" + (lock ? " FOR UPDATE" : ""),
                TABLE, version).stream().findFirst();
    }

    public List<PriceTable> tables() {
        return database.query("SELECT * FROM benchmark_price_tables ORDER BY created_at DESC, version", TABLE);
    }

    public List<ModelPrice> prices(String version) {
        return database.query("SELECT model_id, input_per_million_tokens, output_per_million_tokens, currency "
                        + "FROM benchmark_model_prices WHERE price_version=? ORDER BY model_id",
                (row, i) -> new ModelPrice(version, row.getString("model_id"),
                        row.getBigDecimal("input_per_million_tokens"), row.getBigDecimal("output_per_million_tokens"),
                        row.getString("currency")), version);
    }

    public boolean exists(String version) { return table(version, false).isPresent(); }

    public void insertTable(String version, String status, String createdBy, String createdAt) {
        database.update("INSERT INTO benchmark_price_tables(version,status,created_by,created_at,revision,locked) VALUES(?,?,?,?,0,TRUE)",
                version, status, createdBy, createdAt);
    }

    public void insertPrice(String version, String model, BigDecimal input, BigDecimal output, String currency) {
        database.update("INSERT INTO benchmark_model_prices(price_version,model_id,input_per_million_tokens,output_per_million_tokens,currency) VALUES(?,?,?,?,?)",
                version, model, input, output, currency);
    }

    public void insertPrice(String version, String model, double input, double output, String currency) {
        insertPrice(version, model, BigDecimal.valueOf(input), BigDecimal.valueOf(output), currency);
    }

    /** Caller holds the singleton row lock in the same transaction. */
    public int activate(String version, String activatedAt) {
        database.update("UPDATE benchmark_price_tables SET status='archived' WHERE status='active' AND version<>?", version);
        int updated = database.update("UPDATE benchmark_price_tables SET status='active', activated_at=?, locked=TRUE WHERE version=?", activatedAt, version);
        if (updated == 1) database.update("UPDATE benchmark_pricing_state SET active_version=?, revision=revision+1 WHERE singleton_id=1", version);
        return updated;
    }

    public record State(String activeVersion, long revision) {}
    public record PriceTable(String version, String status, String createdBy, String createdAt, String activatedAt,
                             long revision, boolean locked) {}
    public record ModelPrice(String version, String model,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal inputPerMillionTokens,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outputPerMillionTokens, String currency) {
        public ModelPrice(String version, String model, double input, double output, String currency) {
            this(version, model, BigDecimal.valueOf(input), BigDecimal.valueOf(output), currency);
        }
    }
}
