package com.compareai.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {

    private Long id;

    private String role;

    private String provider;

    private String content;

    private LocalDateTime createdAt;

}