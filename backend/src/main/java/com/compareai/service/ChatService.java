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
import java.util.Comparator;
import java.util.EnumMap;
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

        // 2) Hangi sağlayıcılara soru sorulacağını belirle:
        //    - request.askAllProviders açıkça geldiyse (frontend hangi bar'ın kullanıldığını biliyor) ona uy.
        //    - gelmediyse eski (rol bazlı) çıkarım mantığına düş.
        List<AiClient> targetClients = resolveTargetClients(parentMessage, request.getAskAllProviders(), request.getTargetProvider());

        // 3) Seçilen sağlayıcılara AYNI ANDA (paralel) istek at.
        //    ÖNEMLİ: context artık her sağlayıcı için AYRI hesaplanıyor (buildContext'e bkz.) çünkü
        //    Independent modda her sağlayıcı sadece kendi geçmişini görmeli; Compare modda ise
        //    context her sağlayıcı için aynı (ortak/birleşik geçmiş) olur.
        final Message finalUserMessage = userMessage;
        final Conversation finalConversation = conversation;
        List<CompletableFuture<MessageResponse>> futures = targetClients.stream()
                .map(client -> CompletableFuture.supplyAsync(() -> {
                    List<AiMessage> context = buildContext(finalConversation, finalUserMessage, client.getProvider());
                    AiRequest aiRequest = AiRequest.builder().messages(context).build();
                    return callProviderAndSave(client, aiRequest, finalConversation, finalUserMessage);
                }, taskExecutor))
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
     * @param parentMessage    yeni mesajın bağlandığı parent (geriye dönük uyumluluk için provider çıkarımında kullanılır)
     * @param askAllProviders  frontend'den gelen açık sinyal:
     *                         true  -> üst orta bar: dal ne olursa olsun 3'üne de sor
     *                         false -> AI konteynerinin kendi mini bar'ı: SADECE hedef sağlayıcıya sor
     *                         null  -> eski davranışa düş: parent ASSISTANT ise tek sağlayıcı, değilse 3'ü de
     * @param targetProviderStr askAllProviders=false olduğunda hangi sağlayıcının cevap vereceği (bkz. ChatRequest#targetProvider)
     */
    private List<AiClient> resolveTargetClients(Message parentMessage, Boolean askAllProviders, String targetProviderStr) {
        boolean wantsSingleProvider;

        if (askAllProviders != null) {
            wantsSingleProvider = !askAllProviders;
        } else {
            wantsSingleProvider = parentMessage != null && parentMessage.getRole() == Role.ASSISTANT;
        }

        if (wantsSingleProvider) {
            AiProvider provider = resolveSingleProvider(parentMessage, targetProviderStr);
            AiClient singleClient = clientMap.get(provider);
            if (singleClient == null) {
                throw new ResourceNotFoundException("Sağlayıcı için client bulunamadı: " + provider);
            }
            return List.of(singleClient);
        }

        return new ArrayList<>(clientMap.values());
    }

    /**
     * Tek sağlayıcı modunda hangi AiProvider'a soru sorulacağını çözer.
     * Önce request üzerinde AÇIKÇA belirtilen targetProvider'a bakılır (bkz. ChatRequest#targetProvider);
     * bu, Compare modunda "X ile devam et" dendiğinde parentMessageId'nin illa X'in kendi cevabı
     * olmasını GEREKTİRMEMEK için eklendi (Compare'da parent, ortak geçmişin en son mesajı olabilir).
     * targetProvider gönderilmediyse eski davranışa (parent'ın sağlayıcısı) düşülür.
     */
    private AiProvider resolveSingleProvider(Message parentMessage, String targetProviderStr) {
        if (targetProviderStr != null && !targetProviderStr.isBlank()) {
            try {
                return AiProvider.valueOf(targetProviderStr.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new InvalidBranchOperationException("Geçersiz sağlayıcı: " + targetProviderStr);
            }
        }
        if (parentMessage == null || parentMessage.getRole() != Role.ASSISTANT) {
            throw new InvalidBranchOperationException(
                    "askAllProviders=false gönderildi ama hangi sağlayıcıya sorulacağı belirlenemiyor. " +
                            "targetProvider alanını gönderin ya da parentMessageId bir ASSISTANT mesajına işaret etsin.");
        }
        return parentMessage.getAiProvider();
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
        if (request.getProviders() != null && !request.getProviders().isEmpty()) {
            conversation.setProviders(String.join(",", request.getProviders()));
        }
        if (request.getMode() != null) {
            conversation.setMode(request.getMode());
        }
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
     * AI'ya gönderilecek context'i (mesaj geçmişini) oluşturur. Konuşmanın MODU'na göre iki
     * farklı şekilde davranır:
     *
     * - INDEPENDENT: Hedef sağlayıcı SADECE KENDİSİNİN cevapladığı turları görür (soru + kendi
     *   cevabı). Başka bir sağlayıcıya özel sorulmuş bir soru -metni dahi olsa- bu sağlayıcının
     *   context'ine hiç girmez ("Her yapay zeka yalnızca kendi konuşma geçmişini görür").
     *   NOT: Önceki bir versiyonda burada TÜM kullanıcı mesajları (hangi sağlayıcıya sorulmuş
     *   olursa olsun) context'e ekleniyor, sadece AI cevapları filtreleniyordu. Bu, başka bir
     *   sağlayıcıya özel sorulan bir sorunun METNİNİN diğer sağlayıcıya sızmasına yol açıyordu
     *   (Independent'ın Compare gibi davranmasına sebep olan asıl hata buydu). Şimdi bir turun
     *   context'e girmesi için hedef sağlayıcının o turu BİZZAT cevaplamış olması gerekiyor.
     *
     * - COMPARE: Her kullanıcı sorusundan sonra o soruya cevap veren TÜM sağlayıcıların
     *   cevapları tek bir (birleşik, etiketli) ASSISTANT mesajı olarak context'e eklenir.
     *   Böylece örn. Claude'a sorulan bir soru sonrası "ChatGPT ile devam et" dendiğinde
     *   ChatGPT, Claude'un o soruya verdiği son cevabı da context'inde görür
     *   ("Yapay zekalar ortak konuşma bağlamını paylaşır; biri diğerinin cevabını da okuyabilir").
     *
     * NOT: Bilerek parent_message_id zincirini (branch pointer) DEĞİL, konuşmanın tüm mesajlarını
     * kronolojik sırayla (id artan) kullanıyoruz; hangi turun kime ait olduğunu HER turun kendi
     * ASSISTANT cevaplarına (answersByParent) bakarak buluyoruz, pointer zincirine güvenmiyoruz.
     */
    private List<AiMessage> buildContext(Conversation conversation, Message leaf, AiProvider targetProvider) {
        List<Message> allMessages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());

        // leaf (az önce kaydedilen kullanıcı mesajı) listede olsun/olmasın, geçmişi ondan
        // KESİN olarak ayırmak için id'sine göre filtreliyoruz; leaf'i sona ayrıca ekliyoruz.
        List<Message> history = allMessages.stream()
                .filter(m -> m.getId() < leaf.getId())
                .collect(Collectors.toList());

        boolean compareMode = "COMPARE".equalsIgnoreCase(conversation.getMode());

        Map<Long, List<Message>> answersByParent = history.stream()
                .filter(m -> m.getRole() == Role.ASSISTANT && m.getParentMessage() != null)
                .collect(Collectors.groupingBy(m -> m.getParentMessage().getId()));

        List<AiMessage> context = new ArrayList<>();

        for (Message m : history) {
            if (m.getRole() != Role.USER) continue;
            List<Message> answers = answersByParent.get(m.getId());
            if (answers == null || answers.isEmpty()) continue; // henüz cevaplanmamış bir tur -> context'e taşınacak bir şey yok

            if (compareMode) {
                context.add(AiMessage.builder().role(Role.USER).content(m.getContent()).build());
                String merged = answers.stream()
                        .sorted(Comparator.comparing(Message::getId))
                        .map(a -> "[" + providerLabel(a.getAiProvider()) + "]: " + a.getContent())
                        .collect(Collectors.joining("\n\n"));
                context.add(AiMessage.builder().role(Role.ASSISTANT).content(merged).build());
            } else {
                Message ownAnswer = answers.stream()
                        .filter(a -> a.getAiProvider() == targetProvider)
                        .findFirst()
                        .orElse(null);
                if (ownAnswer == null) continue; // bu turu targetProvider cevaplamamış -> soru dahil hiç görmesin
                context.add(AiMessage.builder().role(Role.USER).content(m.getContent()).build());
                context.add(AiMessage.builder().role(Role.ASSISTANT).content(ownAnswer.getContent()).build());
            }
        }

        context.add(AiMessage.builder().role(Role.USER).content(leaf.getContent()).build());
        return context;
    }

    private String providerLabel(AiProvider provider) {
        if (provider == null) return "Bilinmeyen";
        return switch (provider) {
            case CLAUDE -> "Claude";
            case OPENAI -> "ChatGPT";
            case GEMINI -> "Gemini";
        };
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