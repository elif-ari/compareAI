package com.compareai.service;

import com.compareai.client.AiClient;
import com.compareai.config.OpenAiProperties;
import com.compareai.dto.ai.AiClientResponse;
import com.compareai.dto.ai.AiMessage;
import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.openai.OpenAiMessage;
import com.compareai.entity.AiProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * OpenAI Chat Completions API'sine gercek istek atan client.
 * MockOpenAiClient'in yerini alir (bkz. MockOpenAiClient - artik @Component degil).
 */
@Component
public class OpenAiClient implements AiClient {

    private final RestClient openAiRestClient;
    private final OpenAiProperties properties;

    public OpenAiClient(RestClient openAiRestClient, OpenAiProperties properties) {
        this.openAiRestClient = openAiRestClient;
        this.properties = properties;
    }

    @Override
    public AiClientResponse sendPrompt(AiRequest request) {
        List<OpenAiMessage> messages = request.getMessages().stream()
                .map(this::toOpenAiMessage)
                .collect(Collectors.toList());

        OpenAiRequestBody body = new OpenAiRequestBody(properties.getModel(), messages);

        try {
            OpenAiResponseBody response = openAiRestClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(OpenAiResponseBody.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                return AiClientResponse.builder()
                        .content("[OpenAI] Bos cevap dondu.")
                        .build();
            }

            String content = response.choices().get(0).message().getContent();
            return AiClientResponse.builder().content(content).build();

        } catch (RestClientResponseException e) {
            // OpenAI 4xx/5xx dondugunde (orn. gecersiz/yetersiz kredili API key) buraya duser
            return AiClientResponse.builder()
                    .content("[OpenAI HATA] " + e.getStatusCode() + " - " + e.getResponseBodyAsString())
                    .build();
        } catch (Exception e) {
            return AiClientResponse.builder()
                    .content("[OpenAI HATA] Istek basarisiz: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public AiProvider getProvider() {
        return AiProvider.OPENAI;
    }

    private OpenAiMessage toOpenAiMessage(AiMessage message) {
        String role = message.getRole().name().toLowerCase(Locale.ROOT); // Locale.ROOT sart: sistem Turkce locale'deyse
                                                                            // "ASSISTANT".toLowerCase() -> "assıstant" (noktasiz i) olur ve OpenAI 400 doner.
        return new OpenAiMessage(role, message.getContent());
    }

    // OpenAiRequest/OpenAiResponse DTO'lari AiMessage tipini kullaniyor (role: enum),
    // ancak OpenAI API'sinin bekledigi JSON'da role string olmali ("user"/"assistant"/"system").
    // Bu yuzden burada, sadece bu client'in kullandigi, dogru JSON sekline sahip kucuk local
    // record'lar tanimliyoruz; mevcut dto/openai/* siniflarina dokunmuyoruz.
    private record OpenAiRequestBody(String model, List<OpenAiMessage> messages) {
    }

    private record OpenAiResponseBody(List<OpenAiChoice> choices) {
    }

    private record OpenAiChoice(OpenAiMessage message) {
    }
}
