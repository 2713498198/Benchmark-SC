package com.cryptobench.benchmark.gateway;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Administrator-defined endpoints and model allowlists; never contains user API keys. */
@ConfigurationProperties(prefix = "benchmark.gateway")
public record GatewayProperties(boolean enabled, String image, List<Provider> providers, String providersJson) {
    public GatewayProperties(boolean enabled, String image, List<Provider> providers) { this(enabled, image, providers, null); }
    @ConstructorBinding
    public GatewayProperties {
        if ((providers == null || providers.isEmpty()) && providersJson != null && !providersJson.isBlank()) {
            if (providersJson.length() > 128000) throw new IllegalArgumentException("模型网关供应商配置过大");
            try {
                providers = new com.fasterxml.jackson.databind.ObjectMapper().readValue(providersJson,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Provider>>() {});
            } catch (Exception invalid) { throw new IllegalArgumentException("模型网关供应商 JSON 无效，请检查 providers 配置"); }
        }
        providers = providers == null ? List.of() : List.copyOf(providers);
        providersJson = null;
        var ids = new java.util.HashSet<String>();
        for (Provider provider : providers) {
            if (provider.id() == null || !provider.id().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}") || !ids.add(provider.id())
                    || provider.modelsOrEmpty().isEmpty()) throw new IllegalArgumentException("模型网关供应商 ID/模型列表无效或重复");
        }
        if (enabled && providers.isEmpty()) throw new IllegalArgumentException("启用模型网关前请配置至少一个供应商及模型");
    }
    public String imageOrDefault() { return image == null || image.isBlank() ? "cryptobench-model-gateway:local" : image.trim(); }
    public List<Provider> providersOrEmpty() { return providers == null ? List.of() : providers; }
    public record Provider(String id, String name, String baseUrl, List<String> models, Map<String, String> priceModelIds) {
        @ConstructorBinding
        public Provider { }
        public Provider(String id, String name, String baseUrl, List<String> models) { this(id, name, baseUrl, models, Map.of()); }
        public List<String> modelsOrEmpty() { return models == null ? List.of() : List.copyOf(models); }
        public Map<String, String> pricingOrEmpty() { return priceModelIds == null ? Map.of() : Map.copyOf(priceModelIds); }
    }
}
