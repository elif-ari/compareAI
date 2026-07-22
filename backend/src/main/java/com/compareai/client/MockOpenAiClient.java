package com.compareai.client;

import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.ai.AiClientResponse;
import com.compareai.entity.AiProvider;
import org.springframework.stereotype.Component;

@Component
public class MockOpenAiClient implements AiClient {

    @Override
    public AiClientResponse sendPrompt(AiRequest request) {

        simulateNetworkDelay();

        String prompt = request.getMessages()
                .get(request.getMessages().size() - 1)
                .getContent();

        return AiClientResponse.builder()
                .content("[ChatGPT MOCK CEVABI] Sorduğun soru: \"" + prompt + "\"")
                .build();
    }

    @Override
    public AiProvider getProvider() {
        return AiProvider.OPENAI;
    }

    private void simulateNetworkDelay() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}