package com.cryptobench.benchmark.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Preserve JSON decimal values through HTTP requests, private catalogs and Agent responses. */
@Configuration
public class JsonPrecisionConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer decimalJsonNumbers() {
        return builder -> builder.featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
}
