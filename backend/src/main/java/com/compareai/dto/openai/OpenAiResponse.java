package com.compareai.dto.openai;

import lombok.Data;

import java.awt.*;
import java.util.List;

@Data
public class OpenAiResponse {

    private List<Choice> choices;
}