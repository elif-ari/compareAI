package com.compareai.client;

import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.ai.AiClientResponse;
import com.compareai.entity.AiProvider;

public interface AiClient {

    AiClientResponse sendPrompt(AiRequest request);

    AiProvider getProvider();
}