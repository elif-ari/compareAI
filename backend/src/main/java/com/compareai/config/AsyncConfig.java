package com.compareai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    @Bean
    public Executor taskExecutor() {
        // SSE streaming ile istekler daha uzun süre acik kalabiliyor (kullanicilar ayni anda
        // birden fazla sohbet/tartisma baslatabilir), o yuzden havuzu biraz genislettik.
        return Executors.newFixedThreadPool(6);
    }

}