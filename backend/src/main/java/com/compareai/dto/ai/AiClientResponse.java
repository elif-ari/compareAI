package com.compareai.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiClientResponse {

    private String content;

    // Performans & Maliyet Metrikleri
    private Long latencyMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private Double estimatedCost;
}