package com.cryptobench.benchmark.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime controls for server-owned Docker Agent sandboxes. */
@ConfigurationProperties(prefix = "benchmark.docker")
public record DockerProperties(boolean enabled, List<String> allowedImages, String proxyImage,
                               String endpointHost, String proxyBindAddress) {
    public List<String> allowedImagesOrEmpty() {
        return allowedImages == null ? List.of() : allowedImages;
    }

    public boolean allows(String image) {
        return image != null && allowedImagesOrEmpty().contains(image.trim());
    }

    public String proxyImageOrDefault() {
        return proxyImage == null || proxyImage.isBlank() ? "cryptobench-agent-proxy:local" : proxyImage.trim();
    }

    public String endpointHostOrDefault() {
        return endpointHost == null || endpointHost.isBlank() ? "127.0.0.1" : endpointHost.trim();
    }

    public String proxyBindAddressOrDefault() {
        return proxyBindAddress == null || proxyBindAddress.isBlank() ? "127.0.0.1" : proxyBindAddress.trim();
    }
}
