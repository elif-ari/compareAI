package com.compareai.controller;

import com.compareai.dto.request.ChatRequest;
import com.compareai.dto.request.RenameConversationRequest;
import com.compareai.dto.request.SelectMessageRequest;
import com.compareai.dto.response.ChatResponse;
import com.compareai.dto.response.ConversationResponse;
import com.compareai.dto.response.ConversationSummaryResponse;
import com.compareai.dto.response.MessageResponse;
import com.compareai.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    // OTOMATİK TARTIŞMA MODU: kullanıcı tek bir soru gönderir, seçili sağlayıcılar (varsayılan 5,
    // request.debateRounds ile 2-6 arası ayarlanabilir tur boyunca) kullanıcı müdahalesi olmadan
    // kendi aralarında tartışır, son turda bir moderatör nihai sentezi üretir. Dönen ConversationResponse
    // konuşmanın TAM mesaj listesini içerir (bkz. ChatService#runAutoDebate).
    @PostMapping("/debate")
    public ConversationResponse runAutoDebate(@Valid @RequestBody ChatRequest request) {
        return chatService.runAutoDebate(request);
    }

    // Dashboard'daki "Sohbet Geçmişi" listesi: bir kullanıcıya ait tüm konuşmaları
    // (en yeniden en eskiye) özet halinde getirir.
    @GetMapping("/conversations")
    public List<ConversationSummaryResponse> listConversations(@RequestParam Long userId) {
        return chatService.listConversations(userId);
    }

    // Bir konuşmanın TÜM dallarıyla birlikte tam halini getirir (aynı konuşmaya devam ederken kullanılır).
    @GetMapping("/conversations/{conversationId}")
    public ConversationResponse getConversation(@PathVariable Long conversationId) {
        return chatService.getConversation(conversationId);
    }

    // Dashboard'da bir konuşmanın başlığını düzenler.
    @PatchMapping("/conversations/{conversationId}")
    public ConversationSummaryResponse renameConversation(@PathVariable Long conversationId,
                                                            @Valid @RequestBody RenameConversationRequest request) {
        return chatService.renameConversation(conversationId, request);
    }

    // Dashboard'da bir konuşmayı (tüm mesajlarıyla birlikte) siler.
    @DeleteMapping("/conversations/{conversationId}")
    public void deleteConversation(@PathVariable Long conversationId) {
        chatService.deleteConversation(conversationId);
    }

    // Kullanıcı "bu cevaptan devam etmek istiyorum" dediğinde HEAD'i o mesaja taşır (git checkout gibi).
    // NOT: Bu, INDEPENDENT moddaki "X ile devam et" (branch) akışı içindir.
    @PostMapping("/conversations/{conversationId}/select")
    public ConversationResponse selectMessage(@PathVariable Long conversationId,
                                              @Valid @RequestBody SelectMessageRequest request) {
        return chatService.selectMessage(conversationId, request);
    }

    // COMPARE modunda kullanıcı bir turdaki cevaplardan birini "tercih ettim" diye işaretler.
    // selectMessage'dan farklı olarak HEAD'i TAŞIMAZ; bir sonraki mesaj yine TÜM sağlayıcılara
    // gider, ama bu tercih paylaşılan context'e (buildContext) işlenir - böylece 3 sağlayıcı da
    // kullanıcının hangi cevabı beğendiğinden haberdar olur.
    @PostMapping("/conversations/{conversationId}/prefer")
    public List<MessageResponse> preferAnswer(@PathVariable Long conversationId,
                                               @Valid @RequestBody SelectMessageRequest request) {
        return chatService.preferAnswer(conversationId, request);
    }
}