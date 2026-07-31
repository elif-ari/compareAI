package com.compareai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePersonaRequest {

    @NotBlank(message = "Persona ismi boş olamaz")
    private String name;

    @NotBlank(message = "Unvan boş olamaz")
    private String title;

    private String description;

    @NotBlank(message = "Sistem promptu boş olamaz")
    private String systemPrompt;

    private String icon;

    private boolean isDefault = false;
}
