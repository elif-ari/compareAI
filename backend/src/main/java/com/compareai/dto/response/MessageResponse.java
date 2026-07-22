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

    private LocalDateTime createdAt;

}