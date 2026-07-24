package com.compareai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GeminiConfig {

    @Bean
    public RestClient geminiRestClient(GeminiProperties properties) {

        // Google, OpenAI/Anthropic'ten farkli: key'i Authorization/x-api-key header'i yerine
        // x-goog-api-key header'inda bekliyor (URL query param olarak da kabul eder ama header
        // daha guvenli - loglara/proxy'lere URL'de key sizmasini engeller).
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("x-goog-api-key", properties.getKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
