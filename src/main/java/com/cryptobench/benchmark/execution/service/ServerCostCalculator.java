package com.cryptobench.benchmark.execution.service;

import com.cryptobench.benchmark.config.PricingProperties;
import com.cryptobench.benchmark.pricing.repository.PricingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Run owners persist a server-created snapshot and use it for every task, retry and report. */
@Component
public class ServerCostCalculator {
    private final ObjectNode fallback;
    private final PricingRepository repository;
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    public ServerCostCalculator() { this(new PricingProperties()); }
    public ServerCostCalculator(PricingProperties properties) { this(properties, null); }

    @Autowired
    public ServerCostCalculator(PricingProperties properties, PricingRepository repository) {
        this.repository = repository;
        fallback = base(properties.getVersion(), 0);
        fallback.put("source", "config");
        properties.getModels().forEach((model, p) -> {
            if (p != null && valid(p.getInputPerMillionTokens()) && valid(p.getOutputPerMillionTokens())) {
                add(fallback, model, BigDecimal.valueOf(p.getInputPerMillionTokens()), BigDecimal.valueOf(p.getOutputPerMillionTokens()), "USD");
            }
        });
        if (fallback.path("prices").isEmpty()) {
            add(fallback, "gpt 4.1", new BigDecimal("2"), new BigDecimal("8"), "USD");
            add(fallback, "claude sonnet 4", new BigDecimal("3"), new BigDecimal("15"), "USD");
            add(fallback, "deepseek v3", new BigDecimal("0.27"), new BigDecimal("1.1"), "USD");
            add(fallback, "qwen3", new BigDecimal("0.8"), new BigDecimal("2"), "USD");
            add(fallback, "gemini 2.5 pro", new BigDecimal("1.25"), new BigDecimal("10"), "USD");
        }
    }

    /** No shared mutable price cache: each caller gets a detached, internally consistent value. */
    public ObjectNode snapshot() {
        if (repository == null) return seal(fallback.deepCopy());
        PricingRepository.State state = repository.state(false);
        if (state.activeVersion() == null) {
            ObjectNode copy = fallback.deepCopy(); copy.put("revision", state.revision()); return seal(copy);
        }
        ObjectNode snapshot = base(state.activeVersion(), state.revision());
        snapshot.put("source", "database");
        repository.prices(state.activeVersion()).forEach(p -> add(snapshot, p.model(), p.inputPerMillionTokens(), p.outputPerMillionTokens(), p.currency()));
        return seal(snapshot);
    }

    /** Kept for old callers; database activation is observed at the next snapshot. */
    public void reload() { }
    public String pricingVersion() { return snapshot().path("version").asText(); }
    public Double calculate(String model, JsonNode response) { return calculate(snapshot(), model, response); }

    public Double calculate(ObjectNode snapshot, String model, JsonNode response) {
        if (!"calculated".equals(costStatus(snapshot, model, response))) return null;
        JsonNode row = price(snapshot, model), usage = response.path("usage");
        BigDecimal value = decimal(row.path("inputPerMillionTokens")).multiply(BigDecimal.valueOf(usageCount(usage, true)))
                .add(decimal(row.path("outputPerMillionTokens")).multiply(BigDecimal.valueOf(usageCount(usage, false))))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        double result = value.doubleValue();
        return Double.isFinite(result) ? result : null;
    }

    public String costStatus(ObjectNode snapshot, String model, JsonNode response) {
        if (snapshot == null || !"cryptobench-pricing-snapshot-v1".equals(snapshot.path("schema").asText())) return "missing_snapshot";
        JsonNode row = price(snapshot, model);
        if (row == null) return "missing_price";
        if (!"USD".equals(row.path("currency").asText()) || !"USD".equals(snapshot.path("currency").asText())) return "unsupported_currency";
        if (!decimalPrice(row.get("inputPerMillionTokens")) || !decimalPrice(row.get("outputPerMillionTokens"))) return "missing_price";
        JsonNode usage = response == null ? null : response.get("usage");
        if (usage == null || !usage.isObject() || token(usage, true) == null || token(usage, false) == null) return "missing_usage";
        if (usageCount(usage, true) < 0 || usageCount(usage, false) < 0) return "invalid_usage";
        return "calculated";
    }

    public static String normalize(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ");
    }
    private static ObjectNode base(String version, long revision) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("schema", "cryptobench-pricing-snapshot-v1"); node.put("version", version);
        node.put("revision", revision); node.put("currency", "USD"); node.putArray("prices"); return node;
    }
    private static ObjectNode seal(ObjectNode snapshot) {
        snapshot.put("capturedAt", Instant.now().toString());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical(snapshot).toString().getBytes(StandardCharsets.UTF_8));
            snapshot.put("snapshotHash", HexFormat.of().formatHex(hash));
            return snapshot;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            var names = new java.util.TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            for (String name : names) if (!name.equals("snapshotHash")) sorted.set(name, canonical(value.get(name)));
            return sorted;
        }
        if (value.isArray()) {
            var sorted = JsonNodeFactory.instance.arrayNode(); value.forEach(item -> sorted.add(canonical(item))); return sorted;
        }
        return value.deepCopy();
    }
    private static void add(ObjectNode snapshot, String model, BigDecimal input, BigDecimal output, String currency) {
        ObjectNode price = snapshot.withArray("prices").addObject(); price.put("model", normalize(model));
        price.put("inputPerMillionTokens", input.toPlainString()); price.put("outputPerMillionTokens", output.toPlainString()); price.put("currency", currency);
    }
    private static JsonNode price(ObjectNode snapshot, String model) {
        for (JsonNode row : snapshot.path("prices")) if (normalize(model).equals(normalize(row.path("model").asText()))) return row;
        return null;
    }
    private static boolean valid(Double n) { return n != null && Double.isFinite(n) && n >= 0; }
    private static BigDecimal decimal(JsonNode node) { return new BigDecimal(node.asText()); }
    private static boolean decimalPrice(JsonNode node) {
        try { return node != null && (node.isNumber() || node.isTextual()) && decimal(node).signum() >= 0; }
        catch (NumberFormatException exception) { return false; }
    }
    private static JsonNode token(JsonNode usage, boolean input) {
        String[] names = input ? new String[]{"inputTokens", "input_tokens", "promptTokens", "prompt_tokens"}
                : new String[]{"outputTokens", "output_tokens", "completionTokens", "completion_tokens"};
        for (String name : names) if (usage.has(name)) return usage.get(name);
        return null;
    }
    private static long usageCount(JsonNode usage, boolean input) {
        JsonNode node = token(usage, input);
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.asLong() >= 0 ? node.asLong() : -1;
    }
}
