package com.compareai.dto.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSummaryResponse {

    private Long id;

    private String title;

    private LocalDateTime createdAt;

    // Bu konusmada secilmis olan saglayicilar (orn. ["OPENAI","CLAUDE"]) - dashboard'da
    // kucuk rozet olarak gosterilir.
    private List<String> providers;

    // INDEPENDENT | COMPARE
    private String mode;
}