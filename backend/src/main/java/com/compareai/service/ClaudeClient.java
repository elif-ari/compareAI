package com.compareai.service;

import com.compareai.client.AiClient;
import com.compareai.config.ClaudeProperties;
import com.compareai.dto.ai.AiClientResponse;
import com.compareai.dto.ai.AiMessage;
import com.compareai.dto.ai.AiRequest;
import com.compareai.entity.AiProvider;
import com.compareai.enums.Role;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Anthropic Messages API'sine (POST /v1/messages) gercek istek atan client.
 * MockClaudeClient'in yerini alir (bkz. MockClaudeClient - artik @Component degil).
 *
 * Anthropic API, OpenAI'dan farkli olarak "system" rolunu messages listesi icinde kabul
 * ETMEZ; sistem talimati ayri bir ust-seviye "system" alaninda gonderilmeli. Bu yuzden
 * gelen AiMessage listesindeki SYSTEM mesajlarini ayirip system alanina, geri kalanini
 * (user/assistant) messages alanina koyuyoruz.
 */
@Component
public class ClaudeClient implements AiClient {

    private final RestClient claudeRestClient;
    private final ClaudeProperties properties;

    public ClaudeClient(RestClient claudeRestClient, ClaudeProperties properties) {
        this.claudeRestClient = claudeRestClient;
        this.properties = properties;
    }

    @Override
    public AiClientResponse sendPrompt(AiRequest request) {
        String systemPrompt = request.getMessages().stream()
                .filter(m -> m.getRole() == Role.SYSTEM)
                .map(AiMessage::getContent)
                .collect(Collectors.joining("\n"));

        List<ClaudeMessage> messages = request.getMessages().stream()
                .filter(m -> m.getRole() != Role.SYSTEM)
                .map(m -> new ClaudeMessage(m.getRole().getValue(), m.getContent()))
                .collect(Collectors.toList());

        ClaudeRequestBody body = new ClaudeRequestBody(
                properties.getModel(),
                properties.getMaxTokens() > 0 ? properties.getMaxTokens() : 1024,
                systemPrompt.isBlank() ? null : List.of(new ClaudeSystemBlock("text", systemPrompt)),
                messages
        );

        try {
            ClaudeResponseBody response = claudeRestClient.post()
                    .uri("/messages")
                    .body(body)
                    .retrieve()
                    .body(ClaudeResponseBody.class);

            if (response == null || response.content() == null || response.content().isEmpty()) {
                return AiClientResponse.builder()
                        .content("[Claude] Bos cevap dondu.")
                        .build();
            }

            String text = response.content().stream()
                    .filter(block -> "text".equals(block.type()))
                    .map(ClaudeContentBlock::text)
                    .collect(Collectors.joining("\n"));

            return AiClientResponse.builder().content(text).build();

        } catch (RestClientResponseException e) {
            // Anthropic 4xx/5xx dondugunde (orn. gecersiz/yetersiz bakiyeli API key) buraya duser
            return AiClientResponse.builder()
                    .content("[Claude HATA] " + e.getStatusCode() + " - " + e.getResponseBodyAsString())
                    .build();
        } catch (Exception e) {
            return AiClientResponse.builder()
                    .content("[Claude HATA] Istek basarisiz: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public AiProvider getProvider() {
        return AiProvider.CLAUDE;
    }

    private record ClaudeMessage(String role, String content) {
    }

    // system alanini KASITLI OLARAK duz String yerine content-block array olarak modelliyoruz:
    // Anthropic API bu hesap/model kombinasyonunda system icin array bekliyor
    // ("Input should be a valid array" 400 hatasi). @JsonInclude(NON_NULL) sayesinde system
    // gonderilecek bir sey yoksa alan tamamen istekten cikartiliyor (null olarak gonderilmiyor).
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ClaudeRequestBody(String model, int max_tokens, List<ClaudeSystemBlock> system,
                                      List<ClaudeMessage> messages) {
    }

    private record ClaudeSystemBlock(String type, String text) {
    }

    private record ClaudeResponseBody(List<ClaudeContentBlock> content) {
    }

    private record ClaudeContentBlock(String type, String text) {
    }
}
