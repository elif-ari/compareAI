package com.compareai.client;

import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.ai.AiClientResponse;
import com.compareai.entity.AiProvider;
import org.springframework.stereotype.Component;

// DIKKAT: @Component KASITLI OLARAK KALDIRILDI.
// Gercek OpenAI entegrasyonu com.compareai.service.OpenAiClient sinifinda yapildi ve o,
// AiProvider.OPENAI icin kullanilan client oldu. Bu sinif hem @Component hem de o sinif
// ayni anda kayitli olursa ChatService'teki clientMap'te (EnumMap<AiProvider, AiClient>)
// hangisinin kazanacagi Spring'in bean olusturma sirasina baglar - ongorulemez olur.
// Tekrar mock'a donmek istersen: bu satirin ustune @Component ekle,
// OpenAiClient.java'daki @Component'i kaldir.
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
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}