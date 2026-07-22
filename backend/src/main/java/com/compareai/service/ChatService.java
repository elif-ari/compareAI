package com.compareai.service;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import com.compareai.client.AiClient;
import com.compareai.dto.ai.AiClientResponse;
import com.compareai.dto.ai.AiMessage;
import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.response.AIResponse;
import com.compareai.entity.AiProvider;
import com.compareai.entity.Conversation;
import com.compareai.entity.Message;
import com.compareai.enums.Role;
import com.compareai.repository.ConversationRepository;
import com.compareai.repository.MessageRepository;
import org.springframework.stereotype.Service;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final Map<AiProvider, AiClient> clientMap;
    private final Executor taskExecutor;

    public ChatService(ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       List<AiClient> aiClients,
                       @Qualifier("taskExecutor") Executor taskExecutor) {

        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.taskExecutor = taskExecutor;

        this.clientMap = new EnumMap<>(AiProvider.class);

        aiClients.forEach(client ->
                this.clientMap.put(client.getProvider(), client)
        );
    }

    public List<AIResponse> sendMessage(String prompt) {

        List<AIResponse> responses = new ArrayList<>();
        Conversation conversation = new Conversation();

        conversation.setTitle(prompt);

        Conversation savedConversation =
                conversationRepository.save(conversation);

        Message userMessage = new Message();

        userMessage.setConversation(conversation);
        userMessage.setRole(Role.USER);
        userMessage.setContent(prompt);
        userMessage.setSelected(false);

        messageRepository.save(userMessage);
        AiRequest request = AiRequest.builder()
                .messages(List.of(
                        AiMessage.builder()
                                .role(Role.USER)
                                .content(prompt)
                                .build()
                ))
                .build();

        List<CompletableFuture<AIResponse>> futures =
                clientMap.values().stream()
                        .map(client ->
                                CompletableFuture.supplyAsync(() -> {

                                    AiClientResponse clientResponse = client.sendPrompt(request);

                                    Message aiMessage = new Message();
                                    aiMessage.setConversation(conversation);
                                    aiMessage.setParentMessage(userMessage);
                                    aiMessage.setRole(Role.ASSISTANT);
                                    aiMessage.setAiProvider(client.getProvider());
                                    aiMessage.setContent(clientResponse.getContent());
                                    aiMessage.setSelected(false);

                                    messageRepository.save(aiMessage);

                                    return AIResponse.builder()
                                            .provider(client.getProvider())
                                            .response(clientResponse.getContent())
                                            .success(true)
                                            .error(null)
                                            .build();

                                }, taskExecutor)
                        )
                        .collect(Collectors.toList());
        responses = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
        return responses;
    }
}