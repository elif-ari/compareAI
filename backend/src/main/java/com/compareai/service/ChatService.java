package com.compareai.service;

import com.compareai.client.AiClient;
import com.compareai.dto.ai.AiClientResponse;
import com.compareai.dto.ai.AiMessage;
import com.compareai.dto.ai.AiRequest;
import com.compareai.dto.request.ChatRequest;
import com.compareai.dto.request.RenameConversationRequest;
import com.compareai.dto.request.SelectMessageRequest;
import com.compareai.dto.response.ChatResponse;
import com.compareai.dto.response.ConversationResponse;
import com.compareai.dto.response.ConversationSummaryResponse;
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
        boolean firstMessage =
                conversation.getTitle() == null
                        || conversation.getTitle().isBlank()
                        || "Yeni Sohbet".equals(conversation.getTitle());

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
        //    - COMPARE modunda bu artık SABİT bir kural: dal/tercih ne olursa olsun HER turda
        //      TÜM sağlayıcılara sorulur (kullanıcı sonra aralarından birini "tercih ettim" diye
        //      işaretler, bkz. preferAnswer). Bu yüzden frontend ne gönderirse göndersin burada eziyoruz.
        //    - Değilse (INDEPENDENT) request.askAllProviders'a, o da yoksa eski çıkarım mantığına uy.
        boolean isCompareMode = "COMPARE".equalsIgnoreCase(conversation.getMode());
        boolean hasExplicitCardTarget = request.getTargetProvider() != null && !request.getTargetProvider().isBlank();
        // COMPARE modunda varsayılan kural hâlâ "her turda 3'üne de sor"dur. TEK istisna: kullanıcı
        // bir kartın kendi mini soru barından (bkz. frontend Chat.jsx handleCardSend) AÇIKÇA
        // targetProvider belirterek tek bir sağlayıcıya soru sormuşsa (örn. "Gemini bunu söyledi,
        // sen ne düşünüyorsun" diye sadece Claude'a sorması), o turda SADECE o sağlayıcıya sorulur.
        // Bu, konuşmanın genel COMPARE modunu DEĞİŞTİRMEZ - bir sonraki üst bar mesajı yine 3'üne birden gider.
        Boolean effectiveAskAllProviders = isCompareMode
                ? (hasExplicitCardTarget ? Boolean.FALSE : Boolean.TRUE)
                : request.getAskAllProviders();
        List<AiClient> targetClients = resolveTargetClients(
                conversation, parentMessage, effectiveAskAllProviders, request.getTargetProvider());

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
        if (firstMessage) {
            try {
                String title = generateConversationTitle(request.getPrompt());

                if (!title.isBlank()) {
                    conversation.setTitle(title);
                    conversationRepository.save(conversation);
                }
            } catch (Exception ignored) {
            }
        }

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
    private List<AiClient> resolveTargetClients(Conversation conversation, Message parentMessage,
                                                Boolean askAllProviders, String targetProviderStr) {
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

        return resolveConfiguredClients(conversation);
    }

    private List<AiClient> resolveConfiguredClients(Conversation conversation) {
        if (conversation.getProviders() == null || conversation.getProviders().isBlank()) {
            return new ArrayList<>(clientMap.values());
        }

        List<AiClient> configuredClients = new ArrayList<>();
        for (String providerName : conversation.getProviders().split(",")) {
            try {
                AiProvider provider = AiProvider.valueOf(providerName.trim().toUpperCase());
                AiClient client = clientMap.get(provider);
                if (client != null) {
                    configuredClients.add(client);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore unknown providers saved by an old or invalid conversation.
            }
        }

        if (configuredClients.isEmpty()) {
            throw new InvalidBranchOperationException(
                    "No usable AI provider is configured for this conversation.");
        }
        return configuredClients;
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
    private String generateConversationTitle(String firstPrompt) {

        AiClient openAi = clientMap.get(AiProvider.OPENAI);

        if (openAi == null) {
            return firstPrompt.length() > 60
                    ? firstPrompt.substring(0, 60)
                    : firstPrompt;
        }

        AiRequest request = AiRequest.builder()
                .messages(List.of(
                        AiMessage.builder()
                                .role(Role.SYSTEM)
                                .content("""
                                    You generate titles for chat conversations.

                                    Rules:
                                    - Maximum 5 words.
                                    - Use concise keywords.
                                    - Language must match the user's language.
                                    - Do not use quotation marks.
                                    - Do not end with punctuation.
                                    - Return ONLY the title.
                                    """)
                                .build(),

                        AiMessage.builder()
                                .role(Role.USER)
                                .content(firstPrompt)
                                .build()
                ))
                .build();

        try {
            return openAi.sendPrompt(request)
                    .getContent()
                    .trim();
        } catch (Exception e) {
            return firstPrompt.length() > 60
                    ? firstPrompt.substring(0, 60)
                    : firstPrompt;
        }
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

    /**
     * COMPARE modu için: kullanıcı bir turda üç sağlayıcının cevabından birini "tercih ettim"
     * diye işaretler. selectMessage'dan (checkout) FARKLI olarak bu, konuşmanın HEAD'ini
     * TAŞIMAZ ve bundan sonraki mesajın kime gideceğini de DEĞİŞTİRMEZ - sadece hangi cevabın
     * kullanıcı tarafından beğenildiğini kaydeder. Bu bilgi bir sonraki turda buildContext
     * tarafından TÜM sağlayıcılara ("(Kullanıcının tercih ettiği cevap)" notuyla) gösterilir,
     * böylece hepsi kullanıcının hangi yaklaşımı beğendiğinden haberdar olur.
     */
    @Transactional
    public List<MessageResponse> preferAnswer(Long conversationId, SelectMessageRequest request) {
        Conversation conversation = getConversationOrThrow(conversationId);
        Message message = messageRepository.findById(request.getMessageId())
                .orElseThrow(() -> new ResourceNotFoundException("Mesaj bulunamadı: " + request.getMessageId()));

        if (!message.getConversation().getId().equals(conversationId)) {
            throw new InvalidBranchOperationException(
                    "Bu mesaj (" + request.getMessageId() + ") bu konuşmaya ait değil.");
        }
        if (message.getRole() != Role.ASSISTANT) {
            throw new InvalidBranchOperationException(
                    "Sadece bir AI cevabı (\"ASSISTANT\" rolündeki bir mesaj) tercih edilebilir.");
        }

        // Aynı turdaki (aynı parent'a sahip) diğer cevapları bul: tercih tek bir cevaba ait olmalı,
        // o yüzden kardeşleri varsa false'a çekiyoruz.
        Long parentId = message.getParentMessage() != null ? message.getParentMessage().getId() : null;
        List<Message> siblings = parentId == null
                ? List.of(message)
                : messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                        .filter(m -> m.getRole() == Role.ASSISTANT
                                && m.getParentMessage() != null
                                && m.getParentMessage().getId().equals(parentId))
                        .collect(Collectors.toList());

        List<Message> updated = new ArrayList<>();
        for (Message sibling : siblings) {
            boolean shouldBeSelected = sibling.getId().equals(message.getId());
            if (sibling.isSelected() != shouldBeSelected) {
                sibling.setSelected(shouldBeSelected);
                updated.add(messageRepository.save(sibling));
            } else {
                updated.add(sibling);
            }
        }

        return updated.stream().map(this::toMessageResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ConversationResponse getConversation(Long conversationId) {
        Conversation conversation = getConversationOrThrow(conversationId);
        return toConversationResponse(conversation);
    }

    /**
     * Dashboard'daki "Sohbet Geçmişi" listesi için: bir kullanıcının tüm konuşmalarını
     * en yeniden en eskiye doğru, özet halinde döner (mesaj içerikleri dahil değildir).
     */
    @Transactional(readOnly = true)
    public List<ConversationSummaryResponse> listConversations(Long userId) {
        return conversationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toConversationSummaryResponse)
                .collect(Collectors.toList());
    }

    // Kullanıcı Dashboard'da bir konuşmanın başlığını düzenlediğinde çağrılır.
    @Transactional
    public ConversationSummaryResponse renameConversation(Long conversationId, RenameConversationRequest request) {
        Conversation conversation = getConversationOrThrow(conversationId);
        conversation.setTitle(request.getTitle().trim());
        conversationRepository.save(conversation);
        return toConversationSummaryResponse(conversation);
    }

    // Kullanıcı Dashboard'da bir konuşmayı sildiğinde çağrılır. Conversation.messages
    // cascade=ALL/orphanRemoval=true olduğu için ilişkili tüm mesajlar da otomatik silinir.
    @Transactional
    public void deleteConversation(Long conversationId) {
        Conversation conversation = getConversationOrThrow(conversationId);
        conversationRepository.delete(conversation);
    }

    // ---- yardımcı metodlar ----

    private Conversation resolveConversation(ChatRequest request) {
        if (request.getConversationId() != null) {
            return getConversationOrThrow(request.getConversationId());
        }
        Conversation conversation = new Conversation();
        conversation.setTitle("Yeni Sohbet");
        conversation.setUserId(request.getUserId());
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
     *   cevabı), normal user/assistant sırasıyla. Başka bir sağlayıcıya özel sorulmuş bir soru
     *   -metni dahi olsa- bu sağlayıcının context'ine hiç girmez.
     *
     * - COMPARE: Geçmiş turlar TEK BİR "user" mesajı içine, açıkça "referans bilgisi" olarak
     *   gömülür (bkz. buildCompareContext). BİLEREK "assistant" rolünde DEĞİL: eski bir versiyonda
     *   geçmiş turlar "[ChatGPT]: ... / [Claude]: ... / [Gemini]: ..." şeklinde etiketli, birleşik
     *   bir ASSISTANT mesajı olarak veriliyordu. Bu, modele "ben zaten böyle çok-etiketli bir cevap
     *   vermişim" izlenimi yaratıyordu ve model yeni soruda da AYNI ETİKETLİ FORMATI taklit ederek
     *   kendi tek/temiz cevabı yerine önceki turun birleşik metnini üretiyordu (gözlemlenen bug).
     *   Artık geçmiş, modelin KENDİ SÖZÜ gibi görünmeyen, sade referans metni olarak veriliyor ve
     *   sistem talimatı modele "sadece kendi cevabını yaz, etiket kullanma" diyor.
     */
    private static final String COMPARE_SYSTEM_HINT =
            "Bu bir çoklu-yapay-zeka karşılaştırma sohbeti: kullanıcı her turda ChatGPT, Claude ve Gemini'nin " +
            "cevaplarını yan yana görüyor ve içlerinden birini o turun tercih ettiği cevap olarak " +
            "işaretleyebiliyor. Kullanıcının bir sonraki mesajından önce sana, önceki turların özetini " +
            "(hangi sağlayıcının ne cevap verdiğini ve kullanıcının hangisini tercih ettiğini) referans amaçlı " +
            "bilgi olarak vereceğim - bu özet SENİN daha önce söylediğin bir şey DEĞİLDİR, başka yapay zekaların " +
            "cevaplarıdır, sadece bağlam içindir.\n\n" +
            "TARTIŞMACI TUTUM: Diğer sağlayıcıların cevaplarını pasifçe kabul etme; onları eleştirel bir gözle " +
            "değerlendir. Eğer bir sağlayıcının cevabında eksik, yanlış, yanıltıcı veya zayıf bir gerekçe " +
            "görüyorsan bunu AÇIKÇA belirt ve neden katılmadığını kısaca gerekçelendir. Katıldığın bir noktayı da " +
            "söyleyebilirsin ama sadece onaylamakla yetinme - kendi bağımsız değerlendirmeni yap, gerekirse farklı " +
            "bir sonuca var. Kullanıcı seni açıkça başka bir sağlayıcının cevabıyla karşılaştırıyorsa (örn. " +
            "\"Gemini şunu söyledi, sen ne düşünüyorsun\"), o cevaba doğrudan ve dürüstçe tepki ver: sadece nazikçe " +
            "\"farklı bakış açıları olabilir\" deyip geçme, gerçekten doğru bulmadığın yeri söyle. Amaç kibarca " +
            "hemfikir olmak değil, kullanıcıya en doğru/en iyi cevabı bulmakta yardımcı olacak gerçek bir tartışma " +
            "ortamı sunmak. Yine de saygılı bir üslup kullan, diğer sağlayıcıyı aşağılama, sadece içeriği eleştir.\n\n" +
            "ÇOK ÖNEMLİ: cevabında ASLA \"[ChatGPT]\", \"[Claude]\", \"[Gemini]\" gibi etiketler kullanma, başka bir " +
            "sağlayıcı adına konuşma ya da referans özetini tekrar etme/kopyalama; kendi görüşünü, kendi doğal " +
            "sesinle, tek bir cevap olarak yaz (diğer sağlayıcıdan bahsederken \"Gemini\", \"ChatGPT\" gibi ismini " +
            "kullanabilirsin, bu yasak değil - yasak olan onun adına konuşmak/onun cevabını taklit etmek).";

    private static final String DEVELOPER_SYSTEM_HINT = """
            Act as a senior software engineer and practical technical mentor.
            Reply in the user's language with a concise, clear, easy-to-scan answer. Lead with the direct answer,
            then add only the most important details. For software questions, provide focused code or commands only
            when useful, and keep examples minimal. Mention assumptions, edge cases, validation, security, or error
            handling only when they materially affect the solution. Prefer maintainable, production-aware solutions
            over vague high-level advice. Do not invent APIs, files, or results;
            clearly label uncertainty and ask a concise follow-up only when required information is missing.
            For non-technical questions, remain helpful and concise without forcing code into the response.
            """;

    private static final int MAX_COMPARE_CONTEXT_TURNS = 6;
    private static final int MAX_INDEPENDENT_CONTEXT_TURNS = 12;
    private static final int MAX_CONTEXT_ANSWER_CHARS = 4_000;

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

        return compareMode
                ? buildCompareContext(history, answersByParent, leaf, targetProvider)
                : buildIndependentContext(history, answersByParent, leaf, targetProvider);
    }

    private String providerIdentityHint(AiProvider targetProvider) {
        String providerName = providerLabel(targetProvider);
        return "You are " + providerName + ". In the comparison reference, any answer labeled \""
                + providerName + "\" is your own earlier answer, not another model's answer. Never critique,"
                + " grade, or debate your own earlier answer. Only evaluate answers from other providers. If the"
                + " user asks about your own earlier answer, answer the new request directly without framing it"
                + " as a self-critique.";
    }

    private List<AiMessage> buildCompareContext(List<Message> history, Map<Long, List<Message>> answersByParent,
                                                  Message leaf, AiProvider targetProvider) {
        StringBuilder referenceBlock = new StringBuilder();
        boolean hasAnsweredHistory = false;
        List<AiMessage> context = new ArrayList<>();
        context.add(AiMessage.builder()
                .role(Role.SYSTEM)
                .content(DEVELOPER_SYSTEM_HINT + "\n\n" + COMPARE_SYSTEM_HINT + "\n\n"
                        + providerIdentityHint(targetProvider))
                .build());

        long answeredTurnCount = history.stream()
                .filter(m -> m.getRole() == Role.USER && answersByParent.containsKey(m.getId()))
                .count();
        long skippedTurnCount = Math.max(0, answeredTurnCount - MAX_COMPARE_CONTEXT_TURNS);
        long processedTurnCount = 0;

        for (Message m : history) {
            if (m.getRole() != Role.USER) continue;
            List<Message> answers = answersByParent.get(m.getId());
            if (answers == null || answers.isEmpty()) continue;
            if (processedTurnCount++ < skippedTurnCount) continue;

            hasAnsweredHistory = true;
            referenceBlock.append("Soru: ").append(m.getContent()).append("\n");
            answers.stream()
                    .sorted(Comparator.comparing(Message::getId))
                    .forEach(a -> referenceBlock.append("- ")
                            .append(providerLabel(a.getAiProvider()))
                            .append(a.isSelected() ? " (kullanıcının tercih ettiği cevap): " : ": ")
                            .append(truncateForContext(a.getContent()))
                            .append("\n"));
            referenceBlock.append("\n");
        }

        if (!hasAnsweredHistory) {
            context.add(AiMessage.builder().role(Role.USER).content(leaf.getContent()).build());
            return context;
        }

        String userTurn = "=== Önceki karşılaştırma turları (REFERANS içindir, bunlar senin sözlerin değildir) ===\n\n"
                + referenceBlock
                + "=== Referans sonu ===\n\n"
                + "Kullanıcının yeni mesajı:\n" + leaf.getContent();
        context.add(AiMessage.builder().role(Role.USER).content(userTurn).build());
        return context;
    }

    private List<AiMessage> buildIndependentContext(List<Message> history, Map<Long, List<Message>> answersByParent,
                                                      Message leaf, AiProvider targetProvider) {
        List<AiMessage> context = new ArrayList<>();
        context.add(AiMessage.builder().role(Role.SYSTEM).content(DEVELOPER_SYSTEM_HINT).build());
        long ownAnsweredTurnCount = history.stream()
                .filter(m -> m.getRole() == Role.USER)
                .filter(m -> answersByParent.getOrDefault(m.getId(), List.of()).stream()
                        .anyMatch(a -> a.getAiProvider() == targetProvider))
                .count();
        long skippedTurnCount = Math.max(0, ownAnsweredTurnCount - MAX_INDEPENDENT_CONTEXT_TURNS);
        long processedTurnCount = 0;

        for (Message m : history) {
            if (m.getRole() != Role.USER) continue;
            List<Message> answers = answersByParent.get(m.getId());
            if (answers == null || answers.isEmpty()) continue;

            Message ownAnswer = answers.stream()
                    .filter(a -> a.getAiProvider() == targetProvider)
                    .findFirst()
                    .orElse(null);
            if (ownAnswer != null && processedTurnCount++ < skippedTurnCount) continue;
            if (ownAnswer == null) continue; // bu turu targetProvider cevaplamamış -> soru dahil hiç görmesin

            context.add(AiMessage.builder().role(Role.USER).content(m.getContent()).build());
            context.add(AiMessage.builder().role(Role.ASSISTANT).content(truncateForContext(ownAnswer.getContent())).build());
        }

        context.add(AiMessage.builder().role(Role.USER).content(leaf.getContent()).build());
        return context;
    }

    private String truncateForContext(String content) {
        if (content == null || content.length() <= MAX_CONTEXT_ANSWER_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTEXT_ANSWER_CHARS) + "\n[Earlier answer truncated for context]";
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
                .providers(splitProviders(conversation.getProviders()))
                .mode(conversation.getMode())
                .currentMessageId(conversation.getCurrentMessageId())
                .messages(messageResponses)
                .build();
    }

    private ConversationSummaryResponse toConversationSummaryResponse(Conversation conversation) {
        return ConversationSummaryResponse.builder()
                .id(conversation.getId())
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .providers(splitProviders(conversation.getProviders()))
                .mode(conversation.getMode())
                .build();
    }

    private List<String> splitProviders(String providersCsv) {
        if (providersCsv == null || providersCsv.isBlank()) {
            return List.of();
        }
        return List.of(providersCsv.split(","));
    }

    private MessageResponse toMessageResponse(Message message) {
        return MessageResponse.builder()
                .id(message.getId())
                .parentMessageId(message.getParentMessage() != null ? message.getParentMessage().getId() : null)
                .role(message.getRole().name())
                .provider(message.getAiProvider() != null ? message.getAiProvider().name() : null)
                .content(message.getContent())
                .selected(message.isSelected())
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
