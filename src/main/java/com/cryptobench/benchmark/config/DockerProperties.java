package com.cryptobench.benchmark.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime controls for server-owned Docker Agent sandboxes. */
@ConfigurationProperties(prefix = "benchmark.docker")
public record DockerProperties(boolean enabled, List<String> allowedImages) {
    public List<String> allowedImagesOrEmpty() {
        return allowedImages == null ? List.of() : allowedImages;
    }

    public boolean allows(String image) {
        return image != null && allowedImagesOrEmpty().contains(image.trim());
    }
}
