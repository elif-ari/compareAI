package com.compareai.controller;

import com.compareai.dto.request.ChatRequest;
import com.compareai.dto.request.SelectMessageRequest;
import com.compareai.dto.response.ChatResponse;
import com.compareai.dto.response.ConversationResponse;
import com.compareai.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    // Yeni mesaj gönder. conversationId yoksa yeni konuşma açılır.
    // parentMessageId verilirse o mesajdan yeni bir dal (branch) açılır,
    // verilmezse konuşmanın mevcut HEAD'inden devam edilir.
    @PostMapping
    public ChatResponse sendMessage(@Valid @RequestBody ChatRequest request) {
        return chatService.sendMessage(request);
    }

    // Bir konuşmanın TÜM dallarıyla birlikte tam halini getirir (aynı konuşmaya devam ederken kullanılır).
    @GetMapping("/conversations/{conversationId}")
    public ConversationResponse getConversation(@PathVariable Long conversationId) {
        return chatService.getConversation(conversationId);
    }

    // Kullanıcı "bu cevaptan devam etmek istiyorum" dediğinde HEAD'i o mesaja taşır (git checkout gibi).
    @PostMapping("/conversations/{conversationId}/select")
    public ConversationResponse selectMessage(@PathVariable Long conversationId,
                                              @Valid @RequestBody SelectMessageRequest request) {
        return chatService.selectMessage(conversationId, request);
    }
}