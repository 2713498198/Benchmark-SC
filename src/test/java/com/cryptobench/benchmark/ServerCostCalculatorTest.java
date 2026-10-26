package com.cryptobench.benchmark;

import com.cryptobench.benchmark.execution.service.ServerCostCalculator;
import com.cryptobench.benchmark.config.PricingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServerCostCalculatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ServerCostCalculator calculator = new ServerCostCalculator();

    @Test
    void calculatesKnownModelFromTokenUsageAndLeavesUnknownCostMissing() throws Exception {
        var response = mapper.readTree("{\"usage\":{\"inputTokens\":1000,\"outputTokens\":500}}");
        assertThat(calculator.calculate("GPT-4.1", response)).isEqualTo(0.006d);
        assertThat(calculator.calculate("local-model", response)).isNull();
    }

    @Test
    void ignoresAgentSuppliedCost() throws Exception {
        var response = mapper.readTree("{\"usage\":{\"costUsd\":999}}");
        assertThat(calculator.calculate("GPT-4.1", response)).isNull();
    }

    @Test
    void rejectsNonIntegralOrNegativeTokenCounts() throws Exception {
        assertThat(calculator.calculate("GPT-4.1", mapper.readTree(
                "{\"usage\":{\"inputTokens\":10.5,\"outputTokens\":2}}"))).isNull();
        assertThat(calculator.calculate("GPT-4.1", mapper.readTree(
                "{\"usage\":{\"inputTokens\":-1,\"outputTokens\":2}}"))).isNull();
    }

    @Test
    void acceptsCommonProviderTokenFieldAliases() throws Exception {
        var response = mapper.readTree("{\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":500}}");
        assertThat(calculator.calculate("GPT-4.1", response)).isEqualTo(0.006d);
    }

    @Test
    void usesConfiguredVersionedPriceTable() throws Exception {
        var properties = new PricingProperties();
        properties.setVersion("provider-prices-2026-01");
        var custom = new PricingProperties.ModelPrice();
        custom.setInputPerMillionTokens(1d);
        custom.setOutputPerMillionTokens(3d);
        properties.setModels(Map.of("custom-model", custom));

        var configured = new ServerCostCalculator(properties);
        var response = mapper.readTree("{\"usage\":{\"inputTokens\":1000,\"outputTokens\":500}}");

        assertThat(configured.calculate("custom-model", response)).isEqualTo(0.0025d);
        assertThat(configured.pricingVersion()).isEqualTo("provider-prices-2026-01");
        assertThat(configured.calculate("GPT-4.1", response)).isNull();
    }
}
