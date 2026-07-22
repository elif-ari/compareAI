package com.compareai.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummaryResponse {

    private Long id;

    private String title;

    private LocalDateTime createdAt;
}