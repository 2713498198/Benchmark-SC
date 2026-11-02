package com.cryptobench.benchmark.gateway;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayPropertiesTest {
    @Test
    void environmentJsonCarriesPublicModelAndPricingRoutesAndRejectsSecrets() {
        var properties = new GatewayProperties(true, null, List.of(), """
                [{"id":"example","name":"Example","baseUrl":"https://provider.example/v1",
                  "models":["provider-model"],"priceModelIds":{"provider-model":"priced-model"}}]
                """);
        assertThat(properties.providersOrEmpty()).hasSize(1);
        assertThat(properties.providersOrEmpty().getFirst().pricingOrEmpty()).containsEntry("provider-model", "priced-model");
        assertThat(properties.providersJson()).isNull();
        assertThatThrownBy(() -> new GatewayProperties(true, null, List.of(), "[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayProperties(true, null, List.of(), "[{\"apiKey\":\"must-not-be-config\"}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("must-not-be-config");
    }

    @Test
    void explicitYamlProviderListTakesPrecedenceAndLegacyConstructorRemainsSupported() {
        var provider = new GatewayProperties.Provider("yaml", "YAML", "https://provider.example/v1", List.of("m"), Map.of("m", "priced"));
        assertThat(new GatewayProperties(true, null, List.of(provider), "invalid ignored JSON").providersOrEmpty()).containsExactly(provider);
        assertThat(new GatewayProperties(false, null, null).providersOrEmpty()).isEmpty();
    }
}
