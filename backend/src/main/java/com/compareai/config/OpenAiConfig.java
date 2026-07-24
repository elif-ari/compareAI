package com.compareai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OpenAiConfig {

    @Bean
    public RestClient openAiRestClient(OpenAiProperties properties) {

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}