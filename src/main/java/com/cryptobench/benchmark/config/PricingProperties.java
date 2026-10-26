package com.cryptobench.benchmark.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "benchmark.pricing")
public class PricingProperties {
    private String version = "cryptobench-price-table-1.0.0";
    private Map<String, ModelPrice> models = new LinkedHashMap<>();

    public String getVersion() { return version; }
    public void setVersion(String version) { if (version != null && !version.isBlank()) this.version = version; }
    public Map<String, ModelPrice> getModels() { return models; }
    public void setModels(Map<String, ModelPrice> models) { if (models != null) this.models = models; }

    public static class ModelPrice {
        private Double inputPerMillionTokens;
        private Double outputPerMillionTokens;
        public Double getInputPerMillionTokens() { return inputPerMillionTokens; }
        public void setInputPerMillionTokens(Double value) { inputPerMillionTokens = value; }
        public Double getOutputPerMillionTokens() { return outputPerMillionTokens; }
        public void setOutputPerMillionTokens(Double value) { outputPerMillionTokens = value; }
    }
}
