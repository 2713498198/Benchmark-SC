package com.cryptobench.benchmark.gateway;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModelGatewayController {
    private final GatewayProperties properties;
    public ModelGatewayController(GatewayProperties properties) { this.properties = properties; }
    @GetMapping("/api/agent/gateway")
    public RuntimeView runtime() {
        return new RuntimeView(properties.enabled(), properties.providersOrEmpty().stream()
                .map(p -> new ProviderView(p.id(), p.name(), p.modelsOrEmpty(), p.pricingOrEmpty())).toList(), "chat-completions");
    }
    public record RuntimeView(boolean enabled, List<ProviderView> providers, String protocol) {}
    public record ProviderView(String id, String name, List<String> models, java.util.Map<String, String> priceModelIds) {}
}
