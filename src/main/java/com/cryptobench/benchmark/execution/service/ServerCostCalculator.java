package com.cryptobench.benchmark.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 由服务端按固定价格表计算费用。Agent 返回的 costUsd 只作为原始证据保存，
 * 不参与可信报告的费用字段，避免客户端直接伪造费用。
 */
@Component
public class ServerCostCalculator {
    private record Price(double inputPerThousand, double outputPerThousand) {}

    /* 价格版本随代码发布；未知模型保留空费用，而不是把缺失值当成 0。 */
    private static final Map<String, Price> PRICES = Map.ofEntries(
            Map.entry("gpt-4.1", new Price(0.002, 0.008)),
            Map.entry("claude sonnet 4", new Price(0.003, 0.015)),
            Map.entry("deepseek v3", new Price(0.00027, 0.0011)),
            Map.entry("qwen3", new Price(0.0008, 0.002)),
            Map.entry("gemini 2.5 pro", new Price(0.00125, 0.010)));

    public Double calculate(String model, JsonNode response) {
        Price price = PRICES.get(normalize(model));
        JsonNode usage = response == null ? null : response.get("usage");
        if (price == null || usage == null || !usage.isObject()) return null;
        long input = nonNegativeLong(usage.get("inputTokens"));
        long output = nonNegativeLong(usage.get("outputTokens"));
        if (input < 0 || output < 0) return null;
        double value = input / 1000d * price.inputPerThousand
                + output / 1000d * price.outputPerThousand;
        return Double.isFinite(value) && value >= 0 ? Math.round(value * 1_000_000d) / 1_000_000d : null;
    }

    public String pricingVersion() {
        return "cryptobench-price-table-1.0.0";
    }

    private static String normalize(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }

    private static long nonNegativeLong(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 0) return -1;
        return node.asLong();
    }
}
