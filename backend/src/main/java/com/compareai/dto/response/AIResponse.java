package com.compareai.dto.response;

import com.compareai.entity.AiProvider;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIResponse {

    private AiProvider provider;

    private String response;

    private boolean success;

    private String error;

}