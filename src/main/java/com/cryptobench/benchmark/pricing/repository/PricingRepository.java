package com.cryptobench.benchmark.pricing.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PricingRepository {
    private final JdbcTemplate jdbc;

    public PricingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<PriceTable> active() {
        return jdbc.query("SELECT version, status, created_by, created_at, activated_at "
                        + "FROM benchmark_price_tables WHERE status='active' ORDER BY activated_at DESC",
                (row, i) -> new PriceTable(row.getString("version"), row.getString("status"),
                        row.getString("created_by"), row.getString("created_at"), row.getString("activated_at")))
                .stream().findFirst();
    }

    public List<PriceTable> tables() {
        return jdbc.query("SELECT version, status, created_by, created_at, activated_at "
                        + "FROM benchmark_price_tables ORDER BY created_at DESC",
                (row, i) -> new PriceTable(row.getString("version"), row.getString("status"),
                        row.getString("created_by"), row.getString("created_at"), row.getString("activated_at")));
    }

    public List<ModelPrice> prices(String version) {
        return jdbc.query("SELECT model_id, input_per_million_tokens, output_per_million_tokens, currency "
                        + "FROM benchmark_model_prices WHERE price_version=? ORDER BY model_id",
                (row, i) -> new ModelPrice(version, row.getString("model_id"),
                        row.getBigDecimal("input_per_million_tokens").doubleValue(),
                        row.getBigDecimal("output_per_million_tokens").doubleValue(), row.getString("currency")), version);
    }

    public boolean exists(String version) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM benchmark_price_tables WHERE version=?", Integer.class, version);
        return count != null && count > 0;
    }

    public void insertTable(String version, String status, String createdBy, String createdAt) {
        jdbc.update("INSERT INTO benchmark_price_tables(version,status,created_by,created_at) VALUES(?,?,?,?)",
                version, status, createdBy, createdAt);
    }

    public void insertPrice(String version, String model, double input, double output, String currency) {
        jdbc.update("INSERT INTO benchmark_model_prices(price_version,model_id,input_per_million_tokens,output_per_million_tokens,currency) VALUES(?,?,?,?,?)",
                version, model, input, output, currency);
    }

    public int activate(String version, String activatedAt) {
        jdbc.update("UPDATE benchmark_price_tables SET status='draft', activated_at=NULL WHERE status='active'");
        return jdbc.update("UPDATE benchmark_price_tables SET status='active', activated_at=? WHERE version=?",
                activatedAt, version);
    }

    public record PriceTable(String version, String status, String createdBy, String createdAt, String activatedAt) {}
    public record ModelPrice(String version, String model, double inputPerMillionTokens,
                              double outputPerMillionTokens, String currency) {}
}
