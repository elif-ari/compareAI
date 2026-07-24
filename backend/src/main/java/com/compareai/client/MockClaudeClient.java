package com.compareai.client;

import com.compareai.entity.AiProvider;
import org.springframework.stereotype.Component;
import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.ai.AiClientResponse;


// DIKKAT: @Component KASITLI OLARAK KALDIRILDI - gercek entegrasyon service/ClaudeClient.java'da.
// Tekrar mock'a donmek istersen: bu satirin ustune @Component ekle,
// ClaudeClient.java'daki @Component'i kaldir.
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