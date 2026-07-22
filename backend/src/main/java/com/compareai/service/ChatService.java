package com.compareai.service;

import com.compareai.client.AiClient;
import com.compareai.dto.ai.AiClientResponse;
import com.compareai.dto.ai.AiMessage;
import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.response.AIResponse;
import com.compareai.entity.AiProvider;
import com.compareai.enums.Role;
import com.compareai.repository.ConversationRepository;
import com.compareai.repository.MessageRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final Map<AiProvider, AiClient> clientMap;

    public ChatService(ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       List<AiClient> aiClients) {

        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;

        this.clientMap = new EnumMap<>(AiProvider.class);

        aiClients.forEach(client ->
                this.clientMap.put(client.getProvider(), client)
        );
    }

    public List<AIResponse> sendMessage(String prompt) {

        List<AIResponse> responses = new ArrayList<>();

        AiRequest request = AiRequest.builder()
                .messages(List.of(
                        AiMessage.builder()
                                .role(Role.USER)
                                .content(prompt)
                                .build()
                ))
                .build();

        for (AiClient client : clientMap.values()) {

            AiClientResponse clientResponse = client.sendPrompt(request);

            responses.add(
                    AIResponse.builder()
                            .provider(client.getProvider())
                            .response(clientResponse.getContent())
                            .success(true)
                            .error(null)
                            .build()
            );
        }

        return responses;
    }
}