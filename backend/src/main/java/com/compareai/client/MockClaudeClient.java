package com.compareai.client;

import com.compareai.entity.AiProvider;
import org.springframework.stereotype.Component;
import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.ai.AiClientResponse;


@Component
public class MockClaudeClient implements AiClient {

    @Override
    public AiClientResponse sendPrompt(AiRequest request) {
        simulateNetworkDelay();

        String prompt = request.getMessages()
                .get(request.getMessages().size() - 1)
                .getContent();

        return AiClientResponse.builder()
                .content("[CLAUDE MOCK CEVABI] Sorduğun soru: \"" + prompt + "\"")
                .build();
    }

    @Override
    public AiProvider getProvider() {
        return AiProvider.CLAUDE;
    }

    private void simulateNetworkDelay() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}