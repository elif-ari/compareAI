package com.compareai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ClaudeConfig {

    @Bean
    public RestClient claudeRestClient(ClaudeProperties properties) {

        // Anthropic OpenAI'dan farkli: Authorization: Bearer degil, x-api-key header'i kullanir
        // ve her istekte anthropic-version header'i zorunludur.
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("x-api-key", properties.getKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
