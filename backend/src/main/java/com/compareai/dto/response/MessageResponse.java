package com.compareai.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {

    private Long id;

    // Bu mesaj hangi mesajın devamı? Ağacın kökündeki mesajda null olur.
    private Long parentMessageId;

    private String role;

    private String provider;

    private String content;

    // Compare modunda: kullanıcı bu cevabı o turda "tercih ettim" diye işaretlediyse true.
    private boolean selected;

    // Performans & Maliyet Metrikleri
    private Long latencyMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private Double estimatedCost;

    private LocalDateTime createdAt;

}