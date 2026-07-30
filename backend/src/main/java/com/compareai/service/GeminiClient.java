package com.compareai.service;

import com.compareai.client.AiClient;
import com.compareai.config.GeminiProperties;
import com.compareai.dto.ai.AiClientResponse;
import com.compareai.dto.ai.AiMessage;
import com.compareai.dto.ai.AiRequest;
import com.compareai.entity.AiProvider;
import com.compareai.enums.Role;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Google Gemini (generateContent) API'sine gercek istek atan client.
 * MockGeminiClient'in yerini alir (bkz. MockGeminiClient - artik @Component degil).
 *
 * Gemini, OpenAI/Anthropic'ten iki noktada farkli:
 * 1) Mesaj rolleri "user" / "model" (Anthropic/OpenAI'daki "assistant" degil) - bu yuzden
 *    ASSISTANT rolunu burada elle "model"e ceviriyoruz.
 * 2) Sistem talimati "systemInstruction" adinda ayri bir ust-seviye alanda gonderiliyor
 *    (Anthropic'teki "system" alanina benzer, Gemini'ye ozgu sekli).
 */
@Component
public class GeminiClient implements AiClient {

    private final RestClient geminiRestClient;
    private final GeminiProperties properties;

    public GeminiClient(RestClient geminiRestClient, GeminiProperties properties) {
        this.geminiRestClient = geminiRestClient;
        this.properties = properties;
    }

    @Override
    public AiClientResponse sendPrompt(AiRequest request) {
        String systemPrompt = request.getMessages().stream()
                .filter(m -> m.getRole() == Role.SYSTEM)
                .map(AiMessage::getContent)
                .collect(Collectors.joining("\n"));

        List<GeminiContent> contents = sanitizeContents(request.getMessages());

        GeminiRequestBody body = new GeminiRequestBody(
                contents,
                systemPrompt.isBlank() ? null : new GeminiSystemInstruction(List.of(new GeminiPart(systemPrompt)))
        );

        try {
            GeminiResponseBody response = geminiRestClient.post()
                    .uri("/models/{model}:generateContent", properties.getModel())
                    .body(body)
                    .retrieve()
                    .body(GeminiResponseBody.class);

            if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
                return AiClientResponse.builder()
                        .content("[Gemini] Bos cevap dondu.")
                        .build();
            }

            String text = response.candidates().get(0).content().parts().stream()
                    .map(GeminiPart::text)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining("\n"));

            return AiClientResponse.builder().content(text).build();

        } catch (RestClientResponseException e) {
            // Gemini 4xx/5xx dondugunde (orn. gecersiz key/kota asimi) buraya duser
            return AiClientResponse.builder()
                    .content("[Gemini HATA] " + e.getStatusCode() + " - " + e.getResponseBodyAsString())
                    .build();
        } catch (Exception e) {
            return AiClientResponse.builder()
                    .content("[Gemini HATA] Istek basarisiz: " + e.getMessage())
                    .build();
        }
    }

    private List<GeminiContent> sanitizeContents(List<AiMessage> rawMessages) {
        List<GeminiContent> result = new ArrayList<>();
        List<AiMessage> nonSystem = rawMessages.stream()
                .filter(m -> m.getRole() != Role.SYSTEM && m.getContent() != null && !m.getContent().isBlank())
                .collect(Collectors.toList());

        if (nonSystem.isEmpty()) {
            return List.of(new GeminiContent("user", List.of(new GeminiPart("Merhaba"))));
        }

        for (AiMessage msg : nonSystem) {
            String role = msg.getRole() == Role.ASSISTANT ? "model" : "user";
            String text = msg.getContent().trim();

            if (result.isEmpty()) {
                if (!"user".equals(role)) {
                    result.add(new GeminiContent("user", List.of(new GeminiPart("Devam et."))));
                }
                result.add(new GeminiContent(role, List.of(new GeminiPart(text))));
            } else {
                GeminiContent last = result.get(result.size() - 1);
                if (last.role().equals(role)) {
                    String combined = last.parts().get(0).text() + "\n\n" + text;
                    result.set(result.size() - 1, new GeminiContent(role, List.of(new GeminiPart(combined))));
                } else {
                    result.add(new GeminiContent(role, List.of(new GeminiPart(text))));
                }
            }
        }
        return result;
    }

    @Override
    public AiProvider getProvider() {
        return AiProvider.GEMINI;
    }

    private GeminiContent toGeminiContent(AiMessage message) {
        // Gemini'de ASSISTANT rolu yok; onun karsiligi "model"dir. USER oldugu gibi kalir.
        String role = message.getRole() == Role.ASSISTANT ? "model" : "user";
        return new GeminiContent(role, List.of(new GeminiPart(message.getContent())));
    }

    private record GeminiPart(String text) {
    }

    private record GeminiContent(String role, List<GeminiPart> parts) {
    }

    private record GeminiSystemInstruction(List<GeminiPart> parts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GeminiRequestBody(List<GeminiContent> contents, GeminiSystemInstruction systemInstruction) {
    }

    private record GeminiCandidate(GeminiContent content) {
    }

    private record GeminiResponseBody(List<GeminiCandidate> candidates) {
    }
}
