package com.compareai.config; // Paket adını kendi yapına göre kontrol et

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // Tüm endpoint'lere (API'lara) izin ver
                .allowedOrigins("http://localhost:5173") // Sadece React'in çalıştığı adrese izin ver
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // OPTIONS (ön kontrol) isteğine izin ver
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}