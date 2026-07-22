package com.compareai.client;

import com.compareai.entity.AiProvider;
import org.springframework.stereotype.Component;
import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.ai.AiClientResponse;

/**
 * GECICI mock client. Gercek Google Gemini API'si baglanana kadar kullanilir.
 */
@Component
public class MockGeminiClient implements AiClient {

    @Override
    public AiClientResponse sendPrompt(AiRequest request) {

        simulateNetworkDelay();

        String prompt = request.getMessages()
                .get(request.getMessages().size() - 1)
                .getContent();

        return AiClientResponse.builder()
                .content("[GEMINI MOCK CEVABI] Sorduğun soru: \"" + prompt + "\"")
                .build();
    }

    @Override
    public AiProvider getProvider() {
        return AiProvider.GEMINI;
    }

    private void simulateNetworkDelay() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}