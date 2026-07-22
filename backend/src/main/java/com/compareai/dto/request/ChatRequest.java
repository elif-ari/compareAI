package com.compareai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    private Long conversationId;

    // Yeni mesaj hangi mesajın devamı?
    // İlk mesaj ise null olabilir.
    private Long parentMessageId;

    @NotBlank
    private String prompt;
}