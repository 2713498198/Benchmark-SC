package com.cryptobench.benchmark.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import com.cryptobench.benchmark.config.PricingProperties;
import com.cryptobench.benchmark.pricing.repository.PricingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 由服务端按固定价格表计算费用。Agent 返回的 costUsd 只作为原始证据保存，
 * 不参与可信报告的费用字段，避免客户端直接伪造费用。
 */
@Component
public class ServerCostCalculator {
    private record Price(double inputPerMillion, double outputPerMillion) {}

    /* 价格版本随代码发布；未知模型保留空费用，而不是把缺失值当成 0。 */
    private volatile Map<String, Price> prices;
    private volatile String version;

    public ServerCostCalculator() {
        this.version = "cryptobench-price-table-1.0.0";
        this.prices = defaults();
    }

    public ServerCostCalculator(PricingProperties properties) {
        this.version = properties.getVersion();
        this.prices = configured(properties);
    }

    /** Loads the active database price table when present, while retaining config fallback for tests/first boot. */
    @Autowired
    public ServerCostCalculator(PricingProperties properties, PricingRepository repository) {
        this.version = properties.getVersion();
        this.prices = configured(properties);
        repository.active().ifPresent(table -> {
            Map<String, Price> loaded = new LinkedHashMap<>();
            repository.prices(table.version()).forEach(row -> loaded.put(normalize(row.model()),
                    new Price(row.inputPerMillionTokens(), row.outputPerMillionTokens())));
            if (!loaded.isEmpty()) { this.version = table.version(); this.prices = loaded; }
        });
        this.repository = repository;
    }

    private PricingRepository repository;

    /** Refreshes the in-memory price snapshot after an administrator activates a new version. */
    public synchronized void reload() {
        if (repository == null) return;
        repository.active().ifPresent(table -> {
            Map<String, Price> loaded = new LinkedHashMap<>();
            repository.prices(table.version()).forEach(row -> loaded.put(normalize(row.model()),
                    new Price(row.inputPerMillionTokens(), row.outputPerMillionTokens())));
            if (!loaded.isEmpty()) { version = table.version(); prices = loaded; }
        });
    }

    public Double calculate(String model, JsonNode response) {
        Price price = prices.get(normalize(model));
        JsonNode usage = response == null ? null : response.get("usage");
        if (price == null || usage == null || !usage.isObject()) return null;
        long input = usageCount(usage, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens");
        long output = usageCount(usage, "outputTokens", "output_tokens", "completionTokens", "completion_tokens");
        if (input < 0 || output < 0) return null;
        double value = input / 1_000_000d * price.inputPerMillion
                + output / 1_000_000d * price.outputPerMillion;
        return Double.isFinite(value) && value >= 0 ? Math.round(value * 1_000_000d) / 1_000_000d : null;
    }

    public String pricingVersion() {
        return version;
    }

    private static String normalize(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ');
    }

    private static long nonNegativeLong(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0) return -1;
        return node.asLong();
    }

    /** Accept the canonical contract plus common provider/OpenAI naming aliases. */
    private static long usageCount(JsonNode usage, String... names) {
        for (String name : names) {
            if (usage.has(name)) return nonNegativeLong(usage.get(name));
        }
        return -1;
    }

    private static boolean valid(Double value) { return value != null && Double.isFinite(value) && value >= 0; }

    private static Map<String, Price> configured(PricingProperties properties) {
        Map<String, Price> configured = new LinkedHashMap<>();
        properties.getModels().forEach((model, price) -> {
            if (price != null && valid(price.getInputPerMillionTokens()) && valid(price.getOutputPerMillionTokens())) {
                configured.put(normalize(model), new Price(price.getInputPerMillionTokens(), price.getOutputPerMillionTokens()));
            }
        });
        return configured.isEmpty() ? defaults() : configured;
    }

    private static Map<String, Price> defaults() {
        return Map.ofEntries(
                Map.entry("gpt 4.1", new Price(2.0, 8.0)),
                Map.entry("claude sonnet 4", new Price(3.0, 15.0)),
                Map.entry("deepseek v3", new Price(0.27, 1.1)),
                Map.entry("qwen3", new Price(0.8, 2.0)),
                Map.entry("gemini 2.5 pro", new Price(1.25, 10.0)));
    }
}
