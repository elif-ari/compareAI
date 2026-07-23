package com.compareai.service;

import com.compareai.client.AiClient;
import com.compareai.dto.ai.AiClientResponse;
import com.compareai.dto.ai.AiMessage;
import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.request.ChatRequest;
import com.compareai.dto.request.SelectMessageRequest;
import com.compareai.dto.response.ChatResponse;
import com.compareai.dto.response.ConversationResponse;
import com.compareai.dto.response.MessageResponse;
import com.compareai.entity.AiProvider;
import com.compareai.entity.Conversation;
import com.compareai.entity.Message;
import com.compareai.enums.Role;
import com.compareai.exception.InvalidBranchOperationException;
import com.compareai.exception.ResourceNotFoundException;
import com.compareai.repository.ConversationRepository;
import com.compareai.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

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
        aiClients.forEach(client -> this.clientMap.put(client.getProvider(), client));
    }

    /**
     * Yeni bir mesaj gönderir.
     * - conversationId yoksa: yeni konuşma açılır (kök mesajdan başlar).
     * - conversationId var, parentMessageId yoksa: konuşmanın HEAD'inden (currentMessageId) devam edilir.
     * - conversationId var, parentMessageId de verilmişse: o mesajdan yeni bir dal açılır
     *   (örn. kullanıcı iki mesaj öncesine dönüp Gemini dalıyla devam etmek istiyor).
     */
    // DİKKAT: Bu metot kasıtlı olarak @Transactional DEĞİL.
    // Sebep: kullanıcı mesajı kaydedildikten sonra paralel thread'lerde (taskExecutor)
    // AI cevapları kaydediliyor ve bunlar parent_message_id ile kullanıcı mesajına FK referansı veriyor.
    // Eğer bu metot tek bir transaction içinde olsaydı, kullanıcı mesajı satırı commit edilmeden
    // paralel thread'ler ona referans vermeye çalışır, MySQL FK kontrolü için commit'i bekler,
    // ana thread ise paralel thread'lerin bitmesini (join) bekler -> deadlock / lock wait timeout.
    // Her repository.save() zaten kendi içinde ayrı ayrı transactional (Spring Data JPA), o yüzden
    // burada ekstra @Transactional'a gerek yok; tam tersine zarar veriyor.
    public ChatResponse sendMessage(ChatRequest request) {

        Conversation conversation = resolveConversation(request);
        Message parentMessage = resolveParentMessage(request, conversation);

        // 1) Kullanıcı mesajını kaydet
        Message userMessage = new Message();
        userMessage.setConversation(conversation);
        userMessage.setParentMessage(parentMessage);
        userMessage.setRole(Role.USER);
        userMessage.setContent(request.getPrompt());
        userMessage = messageRepository.save(userMessage);

        // Kullanıcı mesajı gönderildiği an HEAD bu mesaja taşınır.
        // (Bir AI cevabı seçilince HEAD o cevaba taşınacak - bkz. selectMessage)
        conversation.setCurrentMessageId(userMessage.getId());
        conversationRepository.save(conversation);

        // 2) Kök'ten bu mesaja kadar olan yolu (branch context'i) çıkar
        List<AiMessage> context = buildContext(conversation.getId(), userMessage);
        AiRequest aiRequest = AiRequest.builder().messages(context).build();

        // 3) Hangi sağlayıcılara soru sorulacağını belirle:
        //    - request.askAllProviders açıkça geldiyse (frontend hangi bar'ın kullanıldığını biliyor) ona uy.
        //    - gelmediyse eski (rol bazlı) çıkarım mantığına düş.
        List<AiClient> targetClients = resolveTargetClients(parentMessage, request.getAskAllProviders());

        // 4) Seçilen sağlayıcılara AYNI ANDA (paralel) istek at
        final Message finalUserMessage = userMessage;
        List<CompletableFuture<MessageResponse>> futures = targetClients.stream()
                .map(client -> CompletableFuture.supplyAsync(() ->
                        callProviderAndSave(client, aiRequest, conversation, finalUserMessage), taskExecutor))
                .collect(Collectors.toList());

        List<MessageResponse> aiResponses = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        return ChatResponse.builder()
                .conversationId(conversation.getId())
                .currentMessageId(conversation.getCurrentMessageId())
                .userMessage(toMessageResponse(userMessage))
                .aiResponses(aiResponses)
                .build();
    }

    /**
     * Hangi AI sağlayıcı(lar)ına soru sorulacağını belirler.
     *
     * @param parentMessage    yeni mesajın bağlandığı parent (context/dal bilgisi için)
     * @param askAllProviders  frontend'den gelen açık sinyal:
     *                         true  -> üst orta bar: dal ne olursa olsun 3'üne de sor
     *                         false -> AI konteynerinin kendi mini bar'ı: SADECE parent'ın sağlayıcısına sor
     *                         null  -> eski davranışa düş: parent ASSISTANT ise tek sağlayıcı, değilse 3'ü de
     */
    private List<AiClient> resolveTargetClients(Message parentMessage, Boolean askAllProviders) {
        boolean wantsSingleProvider;

        if (askAllProviders != null) {
            wantsSingleProvider = !askAllProviders;
        } else {
            wantsSingleProvider = parentMessage != null && parentMessage.getRole() == Role.ASSISTANT;
        }

        if (wantsSingleProvider) {
            if (parentMessage == null || parentMessage.getRole() != Role.ASSISTANT) {
                throw new InvalidBranchOperationException(
                        "askAllProviders=false gönderildi ama parent bir AI cevabı değil; " +
                                "hangi sağlayıcıya sorulacağı belirlenemiyor. parentMessageId bir ASSISTANT mesajına işaret etmeli.");
            }
            AiProvider provider = parentMessage.getAiProvider();
            AiClient singleClient = clientMap.get(provider);
            if (singleClient == null) {
                throw new ResourceNotFoundException("Sağlayıcı için client bulunamadı: " + provider);
            }
            return List.of(singleClient);
        }

        return new ArrayList<>(clientMap.values());
    }

    private MessageResponse callProviderAndSave(AiClient client, AiRequest aiRequest,
                                                Conversation conversation, Message parentMessage) {
        AiClientResponse clientResponse = client.sendPrompt(aiRequest);

        Message aiMessage = new Message();
        aiMessage.setConversation(conversation);
        aiMessage.setParentMessage(parentMessage);
        aiMessage.setRole(Role.ASSISTANT);
        aiMessage.setAiProvider(client.getProvider());
        aiMessage.setContent(clientResponse.getContent());

        Message saved = messageRepository.save(aiMessage);
        return toMessageResponse(saved);
    }

    /**
     * Kullanıcı hangi cevaptan (hangi AI'nin dalından) devam etmek istiyorsa
     * konuşmanın HEAD'ini (currentMessageId) o mesaja taşır. Git'teki "checkout" gibi.
     */
    @Transactional
    public ConversationResponse selectMessage(Long conversationId, SelectMessageRequest request) {
        Conversation conversation = getConversationOrThrow(conversationId);
        Message message = messageRepository.findById(request.getMessageId())
                .orElseThrow(() -> new ResourceNotFoundException("Mesaj bulunamadı: " + request.getMessageId()));

        if (!message.getConversation().getId().equals(conversationId)) {
            throw new InvalidBranchOperationException(
                    "Bu mesaj (" + request.getMessageId() + ") bu konuşmaya ait değil.");
        }

        conversation.setCurrentMessageId(message.getId());
        conversationRepository.save(conversation);

        return toConversationResponse(conversation);
    }

    @Transactional(readOnly = true)
    public ConversationResponse getConversation(Long conversationId) {
        Conversation conversation = getConversationOrThrow(conversationId);
        return toConversationResponse(conversation);
    }

    // ---- yardımcı metodlar ----

    private Conversation resolveConversation(ChatRequest request) {
        if (request.getConversationId() != null) {
            return getConversationOrThrow(request.getConversationId());
        }
        Conversation conversation = new Conversation();
        conversation.setTitle(generateTitle(request.getPrompt()));
        return conversationRepository.save(conversation);
    }

    private Conversation getConversationOrThrow(Long conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Konuşma bulunamadı: " + conversationId));
    }

    /**
     * Yeni mesajın parent'ını belirler:
     * 1) İstekte parentMessageId açıkça verilmişse onu kullan (branch checkout / farklı daldan devam).
     * 2) Verilmemişse konuşmanın mevcut HEAD'ini kullan (normal akışta devam).
     * 3) Konuşma yeni açıldıysa (henüz hiç mesaj yoksa) null döner -> kök mesaj.
     */
    private Message resolveParentMessage(ChatRequest request, Conversation conversation) {
        Long parentId = request.getParentMessageId() != null
                ? request.getParentMessageId()
                : conversation.getCurrentMessageId();

        if (parentId == null) {
            return null;
        }

        Message parent = messageRepository.findById(parentId)
                .orElseThrow(() -> new ResourceNotFoundException("Mesaj bulunamadı: " + parentId));

        if (!parent.getConversation().getId().equals(conversation.getId())) {
            throw new InvalidBranchOperationException(
                    "parentMessageId (" + parentId + ") bu konuşmaya ait değil.");
        }

        return parent;
    }

    /**
     * Verilen yaprak (leaf) mesajdan kök'e kadar olan zinciri çıkarıp AI'ya
     * gönderilecek sıralı context'e çevirir. N+1 sorgudan kaçınmak için
     * konuşmadaki tüm mesajları tek seferde çekip bellekte map üzerinden geziyoruz.
     */
    private List<AiMessage> buildContext(Long conversationId, Message leaf) {
        List<Message> allMessages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        Map<Long, Message> byId = new HashMap<>();
        allMessages.forEach(m -> byId.put(m.getId(), m));
        // leaf henüz allMessages listesinde olmayabilir (aynı transaction içinde yeni save edildi ama
        // flush garantisi yoksa), güvenlik için ekleyelim.
        byId.putIfAbsent(leaf.getId(), leaf);

        List<Message> path = new ArrayList<>();
        Long currentId = leaf.getId();
        while (currentId != null) {
            Message current = byId.get(currentId);
            if (current == null) break;
            path.add(current);
            currentId = current.getParentMessage() != null ? current.getParentMessage().getId() : null;
        }
        Collections.reverse(path);

        return path.stream()
                .map(m -> AiMessage.builder().role(m.getRole()).content(m.getContent()).build())
                .collect(Collectors.toList());
    }

    private ConversationResponse toConversationResponse(Conversation conversation) {
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        List<MessageResponse> messageResponses = messages.stream()
                .map(this::toMessageResponse)
                .collect(Collectors.toList());

        return ConversationResponse.builder()
                .id(conversation.getId())
                .title(conversation.getTitle())
                .currentMessageId(conversation.getCurrentMessageId())
                .messages(messageResponses)
                .build();
    }

    private MessageResponse toMessageResponse(Message message) {
        return MessageResponse.builder()
                .id(message.getId())
                .parentMessageId(message.getParentMessage() != null ? message.getParentMessage().getId() : null)
                .role(message.getRole().name())
                .provider(message.getAiProvider() != null ? message.getAiProvider().name() : null)
                .content(message.getContent())
                .createdAt(message.getCreatedAt())
                .build();
    }

    private String generateTitle(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "Yeni Konuşma";
        }
        String trimmed = prompt.trim();
        return trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed;
    }
}