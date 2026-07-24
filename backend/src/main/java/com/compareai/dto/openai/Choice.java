package com.compareai.dto.openai;

import com.compareai.dto.ai.AiMessage;
import lombok.Data;

@Data
public class Choice {

    private AiMessage message;
}