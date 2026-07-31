package com.compareai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    private Long conversationId;

    private Long userId;

    private Long parentMessageId;

    private Boolean askAllProviders;

    private String targetProvider;

    private List<String> providers;

    private String mode;

    private Long personaId;

    @NotBlank
    private String prompt;

    private Integer debateRounds;

    private String responseLength;

    private Boolean useRoles;
}
