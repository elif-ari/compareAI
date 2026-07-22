package com.compareai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    // Tarayicidan http://localhost:8080/api/health adresine gidip test edebilirsin
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "OK",
                "message", "Backend ayakta ve MySQL'e baglanmaya hazir"
        );
    }
}
