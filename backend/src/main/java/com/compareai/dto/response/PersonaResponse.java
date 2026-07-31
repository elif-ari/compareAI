package com.compareai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonaResponse {
    private Long id;
    private String name;
    private String title;
    private String description;
    private String systemPrompt;
    private String icon;
    private boolean isDefault;
}
