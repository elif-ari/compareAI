package com.compareai.dto.openai;

import com.compareai.dto.ai.AiMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenAiRequest {

    private String model;
    private List<AiMessage> messages;
}