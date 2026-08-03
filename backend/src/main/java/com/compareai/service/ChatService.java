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
import com.compareai.entity.Persona;
import com.compareai.repository.ConversationRepository;
import com.compareai.repository.MessageRepository;
import com.compareai.repository.PersonaRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final PersonaRepository personaRepository;
    private final Map<AiProvider, AiClient> clientMap;
    private final Executor taskExecutor;

    public ChatService(ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       PersonaRepository personaRepository,
                       List<AiClient> aiClients,
                       @Qualifier("taskExecutor") Executor taskExecutor) {

        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.personaRepository = personaRepository;
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
        List<AiClient> targetClients = resolveTargetClients(parentMessage, effectiveAskAllProviders, request.getTargetProvider());

        // 3) Seçilen sağlayıcılara AYNI ANDA (paralel) istek at.
        //    ÖNEMLİ: context artık her sağlayıcı için AYRI hesaplanıyor (buildContext'e bkz.) çünkü
        //    Independent modda her sağlayıcı sadece kendi geçmişini görmeli; Compare modda ise
        //    context her sağlayıcı için aynı (ortak/birleşik geçmiş) olur.
        List<MessageResponse> aiResponses = runBroadcastTurn(conversation, userMessage, targetClients,
                request.getResponseLength(), null);

        // Konuşmanın gerçekten İLK mesajıysa (kök mesaj, parentMessage == null), ham prompt'u
        // (generateTitle ile 60 karakterde kesilmiş hali) yerine bir AI'ya kısa/profesyonel,
        // anahtar-kelime tarzı bir başlık ürettiriyoruz - bkz. maybeGenerateSmartTitle.
        // Sonraki turlarda başlığa DOKUNULMAZ.
        maybeGenerateSmartTitle(conversation, parentMessage, request.getPrompt(), targetClients);

        return ChatResponse.builder()
                .conversationId(conversation.getId())
                .currentMessageId(conversation.getCurrentMessageId())
                .userMessage(toMessageResponse(userMessage))
                .aiResponses(aiResponses)
                .build();
    }

    private static final String TITLE_SYSTEM_HINT =
            "Kullanıcının aşağıdaki mesajına, bir sohbet geçmişi listesinde gösterilecek KISA ve " +
            "PROFESYONEL bir başlık üret. Kurallar: 2-5 kelime; Türkçe; konuyu özetleyen anahtar " +
            "kelimelerle, isim tamlaması gibi (örn. 'Sağlıklı Yaşam Uygulama İsimleri', 'React vs Angular " +
            "Karşılaştırması', 'Mercimek Çorbası Tarifi'); başında/sonunda tırnak işareti KULLANMA; sonuna " +
            "nokta KOYMA; SADECE başlığı yaz, başka hiçbir açıklama, selamlama veya ek cümle ekleme.";

    // Konuşmanın kök mesajı gönderildikten sonra çağrılır: ham prompt yerine kısa/anahtar-kelime tarzı
    // bir başlık üretmeyi DENER. Bu bir "best effort" adımdır - herhangi bir sebeple başarısız olursa
    // (API hatası, boş cevap, vb.) sessizce eski (resolveConversation'da generateTitle ile atanmış ham
    // prompt) başlığı korur; ana sohbet akışını ASLA bozmaz veya kullanıcıya hata döndürmez.
    private void maybeGenerateSmartTitle(Conversation conversation, Message parentMessage, String userPrompt,
                                          List<AiClient> targetClients) {
        if (parentMessage != null || targetClients.isEmpty() || userPrompt == null || userPrompt.isBlank()) {
            return;
        }
        try {
            AiClient titleClient = targetClients.get(0);
            List<AiMessage> titlePromptMessages = List.of(
                    AiMessage.builder().role(Role.SYSTEM).content(TITLE_SYSTEM_HINT).build(),
                    AiMessage.builder().role(Role.USER).content(userPrompt).build()
            );
            AiClientResponse response = titleClient.sendPrompt(
                    AiRequest.builder().messages(titlePromptMessages).build());

            String smartTitle = sanitizeGeneratedTitle(response.getContent());
            if (smartTitle != null && !smartTitle.isBlank()) {
                conversation.setTitle(smartTitle);
                conversationRepository.save(conversation);
            }
        } catch (Exception e) {
            // Kasıtlı olarak yutuluyor: başlık kozmetik bir alandır, üretimi başarısız olsa da
            // kullanıcının asıl mesajına verilen cevaplar zaten döndü/kaydedildi.
        }
    }

    // Modelin ürettiği başlığı temizler: baştaki/sondaki tırnak-benzeri karakterleri, sondaki noktayı
    // kaldırır ve listede taşmaması için makul bir uzunlukta keser.
    private String sanitizeGeneratedTitle(String rawTitle) {
        if (rawTitle == null) return null;
        String cleaned = rawTitle.trim()
                .replaceAll("^[\"'“”«»`]+", "")
                .replaceAll("[\"'“”«»`]+$", "")
                .replaceAll("\\.+$", "")
                .trim();
        if (cleaned.isBlank()) return null;
        return cleaned.length() > 70 ? cleaned.substring(0, 70).trim() + "…" : cleaned;
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

    // Verilen client listesine, verilen leaf (kullanıcı/tetikleyici) mesajına göre AYNI ANDA (paralel)
    // istek atar ve her cevabı kaydeder. Hem normal sendMessage akışında HEM DE Otomatik Tartışma
    // Modu'ndaki her turda (bkz. runAutoDebate) kullanılan ORTAK bir yardımcıdır.
    //
    // @param responseLength  "KISA"/"NORMAL"/"DETAYLI" (null/boş -> ek talimat yok)
    // @param personaHints    sağlayıcı -> tartışma rolü talimatı eşlemesi (null -> kimseye rol atanmaz,
    //                        normal sendMessage akışında her zaman null gönderilir; yalnızca
    //                        Otomatik Tartışma Modu'nda useRoles=true ise doldurulur)
    private List<MessageResponse> runBroadcastTurn(Conversation conversation, Message leafMessage,
                                                    List<AiClient> clients, String responseLength,
                                                    Map<AiProvider, String> personaHints) {
        List<CompletableFuture<MessageResponse>> futures = clients.stream()
                .map(client -> CompletableFuture.supplyAsync(() -> {
                    String personaHint = personaHints != null ? personaHints.get(client.getProvider()) : null;
                    List<AiMessage> context = buildContext(conversation, leafMessage, client.getProvider(),
                            responseLength, personaHint);
                    AiRequest aiRequest = AiRequest.builder().messages(context).build();
                    return callProviderAndSave(client, aiRequest, conversation, leafMessage);
                }, taskExecutor))
                .collect(Collectors.toList());

        return futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
    }

    private void sendSseEvent(SseEmitter emitter, Object lock, String eventName, Object data) {
        try {
            synchronized (lock) {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            }
        } catch (IOException | IllegalStateException e) {
            // yut
        }
    }

    private List<MessageResponse> runBroadcastTurnStreaming(SseEmitter emitter, Object emitLock,
                                                             Conversation conversation, Message leafMessage,
                                                             List<AiClient> clients, String responseLength,
                                                             Map<AiProvider, String> personaHints,
                                                             int round, int totalRounds) {
        List<CompletableFuture<MessageResponse>> futures = clients.stream()
                .map(client -> CompletableFuture.supplyAsync(() -> {
                    String personaHint = personaHints != null ? personaHints.get(client.getProvider()) : null;
                    List<AiMessage> context = buildContext(conversation, leafMessage, client.getProvider(),
                            responseLength, personaHint);
                    AiRequest aiRequest = AiRequest.builder().messages(context).build();
                    MessageResponse response = callProviderAndSave(client, aiRequest, conversation, leafMessage);
                    sendSseEvent(emitter, emitLock, "ai_response",
                            Map.of("round", round, "totalRounds", totalRounds, "message", response));
                    return response;
                }, taskExecutor))
                .collect(Collectors.toList());

        return futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
    }

    public SseEmitter streamMessage(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        Object emitLock = new Object();

        Runnable task = () -> {
            try {
                Conversation conversation = resolveConversation(request);
                Message parentMessage = resolveParentMessage(request, conversation);

                Message userMessage = new Message();
                userMessage.setConversation(conversation);
                userMessage.setParentMessage(parentMessage);
                userMessage.setRole(Role.USER);
                userMessage.setContent(request.getPrompt());
                userMessage = messageRepository.save(userMessage);

                conversation.setCurrentMessageId(userMessage.getId());
                conversationRepository.save(conversation);

                boolean isCompareMode = "COMPARE".equalsIgnoreCase(conversation.getMode());
                boolean hasExplicitCardTarget = request.getTargetProvider() != null && !request.getTargetProvider().isBlank();
                Boolean effectiveAskAllProviders = isCompareMode
                        ? (hasExplicitCardTarget ? Boolean.FALSE : Boolean.TRUE)
                        : request.getAskAllProviders();
                List<AiClient> targetClients = resolveTargetClients(parentMessage, effectiveAskAllProviders,
                        request.getTargetProvider());

                sendSseEvent(emitter, emitLock, "user_message", Map.of(
                        "conversationId", conversation.getId(),
                        "currentMessageId", conversation.getCurrentMessageId(),
                        "userMessage", toMessageResponse(userMessage)
                ));

                runBroadcastTurnStreaming(emitter, emitLock, conversation, userMessage, targetClients,
                        request.getResponseLength(), null, 1, 1);

                maybeGenerateSmartTitle(conversation, parentMessage, request.getPrompt(), targetClients);

                if (isCompareMode && !hasExplicitCardTarget) {
                    try {
                        MessageResponse compSummary = generateTurnComparison(conversation.getId(), userMessage.getId());
                        sendSseEvent(emitter, emitLock, "comparison_summary", Map.of("summary", compSummary));
                    } catch (Exception ignored) {}
                }

                sendSseEvent(emitter, emitLock, "done", Map.of("conversationId", conversation.getId()));
                emitter.complete();
            } catch (Exception e) {
                sendSseEvent(emitter, emitLock, "fatal_error", Map.of("message", String.valueOf(e.getMessage())));
                emitter.completeWithError(e);
            }
        };

        new Thread(task, "chat-stream-" + System.currentTimeMillis()).start();
        return emitter;
    }

    public SseEmitter streamAutoDebate(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        Object emitLock = new Object();

        Runnable task = () -> {
            try {
                int requestedRounds = request.getDebateRounds() != null ? request.getDebateRounds() : 5;
                int rounds = Math.max(2, Math.min(requestedRounds, 6));

                Conversation conversation = resolveConversation(request);
                conversation.setMode("COMPARE");
                conversationRepository.save(conversation);

                Message parentMessage = resolveParentMessage(request, conversation);

                List<AiClient> debateClients = resolveDebateClients(conversation);
                if (debateClients.size() < 2) {
                    throw new InvalidBranchOperationException(
                            "Otomatik Tartışma Modu en az 2 sağlayıcı seçilmesini gerektirir.");
                }

                Map<AiProvider, String> personaHints = null;
                if (Boolean.TRUE.equals(request.getUseRoles())) {
                    personaHints = new EnumMap<>(AiProvider.class);
                    for (int i = 0; i < debateClients.size(); i++) {
                        personaHints.put(debateClients.get(i).getProvider(), DEBATE_ROLE_HINTS[i % DEBATE_ROLE_HINTS.length]);
                    }
                }

                sendSseEvent(emitter, emitLock, "conversation_created", Map.of("conversationId", conversation.getId()));

                // TUR 1
                Message currentLeaf = saveSystemTriggerMessage(conversation, parentMessage, request.getPrompt());
                sendSseEvent(emitter, emitLock, "turn_message",
                        Map.of("round", 1, "totalRounds", rounds, "message", toMessageResponse(currentLeaf)));
                maybeGenerateSmartTitle(conversation, parentMessage, request.getPrompt(), debateClients);
                runBroadcastTurnStreaming(emitter, emitLock, conversation, currentLeaf, debateClients,
                        request.getResponseLength(), personaHints, 1, rounds);

                // TUR 2..N
                for (int round = 2; round <= rounds; round++) {
                    String trigger = "🔁 Otomatik Tartışma - Tur " + round + "/" + rounds + ": Bir önceki turda " +
                            "diğer modellerin verdiği cevapları dikkatle değerlendir. Yanıtında şu 3 noktaya net biçimde değin:\n" +
                            "1. Diğer modellerin fikirlerine katılıp katılmadığın (Neden katıldın / katılmadın?)\n" +
                            "2. Önceki görüşünü değiştirip değiştirmediğin (Fikrin değiştiyse nedeni)\n" +
                            "3. Bu tur itibarıyla ortak vardığınız uzlaşı noktası.";
                    currentLeaf = saveSystemTriggerMessage(conversation, currentLeaf, trigger);
                    sendSseEvent(emitter, emitLock, "turn_message",
                            Map.of("round", round, "totalRounds", rounds, "message", toMessageResponse(currentLeaf)));
                    runBroadcastTurnStreaming(emitter, emitLock, conversation, currentLeaf, debateClients,
                            request.getResponseLength(), personaHints, round, rounds);
                }

                // NİHAİ SENTEZ & KONSENSÜS (SÜPER HIZLI VE TEK BİRLEŞİK RAPOR)
                String synthesisTrigger = "🏆 NİHAİ KARAR VE SENTEZ: Yukarıdaki " + rounds + " turluk tartışmayı ve 3 modelin görüşlerini analiz ederek aşağıdaki EXACT FORMATTA yanıt üret:\n\n" +
                        "[DEBATE_CONSENSUS]\n" +
                        "# 🏆 3 Modelin Ortak Katılımıyla Oluşturulmuş Nihai Sentez ve Cevap\n\n" +
                        "### 📊 Modeller Fikirlerini Nasıl Değiştirdi ve Uzlaştı?\n" +
                        "- **ChatGPT:** (Tartışmadaki tutumu, nerede esnedi veya katıldı?)\n" +
                        "- **Claude:** (Neden katıldı / katılmadı, hangi nüansı ekledi?)\n" +
                        "- **Gemini:** (Görüşü nasıl evrildi, nerede mutabık kaldı?)\n\n" +
                        "### 🤝 Modellerin Tam Anlaştığı Noktalar\n" +
                        "1. (Tüm modellerin mutabık kaldığı 1. nokta)\n" +
                        "2. (Tüm modellerin mutabık kaldığı 2. nokta)\n\n" +
                        "### ⚡ Farklı Düşünülen Nüanslar\n" +
                        "- (Hangi model hangi detayda farklı bir vurgu yapıyor?)\n\n" +
                        "### 💡 Ortak Tavsiye ve Yanıt (3 Modelin Ortak Aklı)\n" +
                        "(Kullanıcının sorusuna 3 modelin ortak katılımından ve tartışmasından süzülen eksiksiz, en kaliteli nihai yanıt)";

                Message synthesisLeaf = saveSystemTriggerMessage(conversation, currentLeaf, synthesisTrigger);
                sendSseEvent(emitter, emitLock, "synthesis_trigger",
                        Map.of("message", toMessageResponse(synthesisLeaf)));

                AiClient moderator = debateClients.get(0);
                List<AiMessage> synthesisContext = buildContext(conversation, synthesisLeaf, moderator.getProvider(),
                        request.getResponseLength(), null);
                MessageResponse synthesisResponse = callProviderAndSave(moderator,
                        AiRequest.builder().messages(synthesisContext).build(), conversation, synthesisLeaf);

                sendSseEvent(emitter, emitLock, "synthesis", Map.of("message", synthesisResponse));
                sendSseEvent(emitter, emitLock, "done", Map.of("conversationId", conversation.getId()));
                emitter.complete();
            } catch (Exception e) {
                sendSseEvent(emitter, emitLock, "fatal_error", Map.of("message", String.valueOf(e.getMessage())));
                emitter.completeWithError(e);
            }
        };

        new Thread(task, "chat-debate-stream-" + System.currentTimeMillis()).start();
        return emitter;
    }

    // Sistem tarafından otomatik üretilen bir "tur tetikleyici" USER mesajı kaydeder (ör. "Tur 2/5").
    // Normal kullanıcı mesajlarıyla AYNI tabloda tutulur ki mevcut frontend (her kartın kendi SMS'imsi
    // geçmişi, ChatResponse/ConversationResponse şeması) HİÇBİR ek değişiklik gerektirmeden bunları da
    // sıradan bir soru-cevap turu gibi gösterebilsin.
    private Message saveSystemTriggerMessage(Conversation conversation, Message parentMessage, String content) {
        Message message = new Message();
        message.setConversation(conversation);
        message.setParentMessage(parentMessage);
        message.setRole(Role.USER);
        message.setContent(content);
        message = messageRepository.save(message);
        conversation.setCurrentMessageId(message.getId());
        conversationRepository.save(conversation);
        return message;
    }

    // Bir konuşmanın seçili sağlayıcılarına (Conversation.providers CSV alanı) karşılık gelen
    // AiClient'ları döner. Boş/eksikse (eski konuşmalar vb.) TÜM kayıtlı client'lara düşer.
    private List<AiClient> resolveDebateClients(Conversation conversation) {
        List<String> providerNames = splitProviders(conversation.getProviders());
        if (providerNames.isEmpty()) {
            return new ArrayList<>(clientMap.values());
        }
        return providerNames.stream()
                .map(name -> {
                    try {
                        return clientMap.get(AiProvider.valueOf(name.trim().toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(c -> c != null)
                .collect(Collectors.toList());
    }

    /**
     * COMPARE modunda bir tur tamamlandığında (seçili AI modelleri cevap verdiğinde),
     * tüm cevaplar arasındaki temel farkları, odak noktalarını ve tonu özetleyen
     * hızlı bir "Karşılaştırma ve Farklar" analizi üretir.
     */
    public MessageResponse generateTurnComparison(Long conversationId, Long userMessageId) {
        Conversation conversation = getConversationOrThrow(conversationId);
        Message userMessage = messageRepository.findById(userMessageId)
                .orElseThrow(() -> new ResourceNotFoundException("Mesaj bulunamadı: " + userMessageId));

        List<Message> aiAnswers = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(m -> m.getRole() == Role.ASSISTANT
                        && m.getParentMessage() != null
                        && m.getParentMessage().getId().equals(userMessageId)
                        && (m.getContent() == null || !m.getContent().startsWith("[KARŞILAŞTIRMA VE FARKLAR]")))
                .collect(Collectors.toList());

        if (aiAnswers.isEmpty()) {
            throw new InvalidBranchOperationException("Bu turda karşılaştırılacak AI cevabı bulunamadı.");
        }

        // Zaten bu tur için üretilmiş bir karşılaştırma mesajı var mı?
        Optional<Message> existingComparison = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .filter(m -> m.getRole() == Role.ASSISTANT
                        && m.getContent() != null
                        && m.getContent().startsWith("[KARŞILAŞTIRMA VE FARKLAR]")
                        && m.getParentMessage() != null
                        && m.getParentMessage().getId().equals(userMessageId))
                .findFirst();

        if (existingComparison.isPresent()) {
            return toMessageResponse(existingComparison.get());
        }

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Kullanıcı Sorusu: \"").append(userMessage.getContent()).append("\"\n\n");
        promptBuilder.append("Modellerin bu soruya verdiği cevaplar aşağıdadır:\n\n");

        for (Message answer : aiAnswers) {
            String pName = providerLabel(answer.getAiProvider());
            promptBuilder.append("--- ").append(pName).append(" CEVABI ---\n");
            promptBuilder.append(answer.getContent()).append("\n\n");
        }

        promptBuilder.append("Talimat: Yukarıdaki yapay zeka cevaplarını tarafsız bir şekilde KARŞILAŞTIR ve FARKLARI ÖZETLE.\n");
        promptBuilder.append("Çıktı formatı (Markdown olarak):\n");
        promptBuilder.append("### ⚡ Cevaplar Arasındaki Temel Farklar\n");
        promptBuilder.append("- **Odak Noktaları & Üslup:** (Her modelin soruyu ele alış biçimi ve üslubu)\n");
        promptBuilder.append("- **Öne Çıkan Farklar:** (Örn. X modeli daha pratik kod sundu, Y modeli teorik açıkladı, Z modeli detaylı örnek verdi)\n");
        promptBuilder.append("- **Ortak / Ayrışan Noktalar:** (Modellerin hemfikir olduğu ve ayrıştığı ana hususlar)\n");
        promptBuilder.append("- **Kısa Tavsiye:** (Kullanıcının ihtiyacına göre hangi cevabın ne zaman tercih edilebileceği)\n");
        promptBuilder.append("Kısa, maddeler halinde ve net ol. Yanıt süresini uzatma.");

        AiClient moderator = clientMap.get(AiProvider.OPENAI) != null ? clientMap.get(AiProvider.OPENAI) : clientMap.values().iterator().next();

        List<AiMessage> messages = List.of(
                AiMessage.builder().role(Role.SYSTEM).content("Sen uzman bir AI Karşılaştırma ve Analiz Asistanısın. Farklı modellerin cevaplarını tarafsızca kıyaslarsın.").build(),
                AiMessage.builder().role(Role.USER).content(promptBuilder.toString()).build()
        );

        AiClientResponse clientResponse = moderator.sendPrompt(AiRequest.builder().messages(messages).build());

        Message summaryMsg = new Message();
        summaryMsg.setConversation(conversation);
        summaryMsg.setParentMessage(userMessage);
        summaryMsg.setRole(Role.ASSISTANT);
        summaryMsg.setAiProvider(moderator.getProvider());
        summaryMsg.setContent("[KARŞILAŞTIRMA VE FARKLAR]\n" + clientResponse.getContent());

        Message saved = messageRepository.save(summaryMsg);
        return toMessageResponse(saved);
    }

    /**
     * OTOMATİK TARTIŞMA MODU: Kullanıcı TEK bir soru sorar; bundan sonra seçili sağlayıcılar
     * (varsayılan 5 tur, çağıran istekte debateRounds ile 2-6 arası sınırlanabilir) KENDİ ARALARINDA,
     * kullanıcı müdahale etmeden otomatik olarak tartışır ve son turda bir "moderatör" model
     * tartışmanın nihai sentezini üretir.
     *
     * Akış:
     *  1. Tur 1: orijinal soru -> tüm sağlayıcılara birden (normal COMPARE turu gibi).
     *  2. Tur 2..N: her tur için sentetik bir "tetikleyici" USER mesajı eklenir ("diğerlerinin bir
     *     önceki turdaki cevaplarını değerlendir, savun/eleştir/geliştir"); buildCompareContext zaten
     *     TÜM geçmişi otomatik topladığı ve artık her sağlayıcının KENDİ eski cevabını
     *     "(SENİN ÖNCEKİ CEVABIN)" diye işaretlediği için (bkz. buildCompareContext), modeller
     *     otomatik olarak SADECE diğer ikisinin bir önceki (ve daha öncekilerin) cevaplarını
     *     eleştirir/savunur - ayrı bir "peer context" mantığı yazmaya gerek kalmadı.
     *  3. Son adım: bir moderatör (seçili sağlayıcıların ilki) tüm tartışmayı okuyup kullanıcının
     *     orijinal sorusuna TEK ve nihai bir cevap/sentez üretir.
     *
     * Dönen ConversationResponse, konuşmanın TAM mesaj listesini içerir - frontend mevcut
     * fetchConversation/setMessages mekanizmasını AYNEN kullanarak tüm turları (ve nihai sentezi)
     * her kartın kendi SMS-benzeri geçmişinde gösterebilir, ek bir DTO/parse mantığı gerekmez.
     */
    public ConversationResponse runAutoDebate(ChatRequest request) {
        int requestedRounds = request.getDebateRounds() != null ? request.getDebateRounds() : 5;
        int rounds = Math.max(2, Math.min(requestedRounds, 6)); // güvenlik: maliyet/gecikme kontrolü için 2-6 arası

        Conversation conversation = resolveConversation(request);
        // Otomatik Tartışma Modu, sağlayıcıların birbirini görmesini GEREKTİRİR - bu yalnızca
        // COMPARE modunun ortak/birleşik context mantığıyla (buildCompareContext) anlamlıdır.
        conversation.setMode("COMPARE");
        conversationRepository.save(conversation);

        Message parentMessage = resolveParentMessage(request, conversation);

        List<AiClient> debateClients = resolveDebateClients(conversation);
        if (debateClients.size() < 2) {
            throw new InvalidBranchOperationException(
                    "Otomatik Tartışma Modu en az 2 sağlayıcı seçilmesini gerektirir.");
        }

        // useRoles=true ise her sağlayıcıya SABİT bir tartışma rolü atanır (bkz. DEBATE_ROLE_HINTS) ve
        // bu rol TÜM turlar boyunca AYNI kalır (tutarlılık için - aksi halde model turdan tura "kimliğini"
        // değiştirmiş gibi görünürdü). useRoles=false/null ise personaHints null kalır, kimseye rol atanmaz.
        Map<AiProvider, String> personaHints = null;
        if (Boolean.TRUE.equals(request.getUseRoles())) {
            personaHints = new EnumMap<>(AiProvider.class);
            for (int i = 0; i < debateClients.size(); i++) {
                personaHints.put(debateClients.get(i).getProvider(), DEBATE_ROLE_HINTS[i % DEBATE_ROLE_HINTS.length]);
            }
        }
        final Map<AiProvider, String> finalPersonaHints = personaHints;

        // TUR 1: kullanıcının orijinal sorusu.
        Message currentLeaf = saveSystemTriggerMessage(conversation, parentMessage, request.getPrompt());
        maybeGenerateSmartTitle(conversation, parentMessage, request.getPrompt(), debateClients);
        runBroadcastTurn(conversation, currentLeaf, debateClients, request.getResponseLength(), finalPersonaHints);

        // TUR 2..N: sırayla, otomatik tetikleyici mesajlarla devam eder.
        for (int round = 2; round <= rounds; round++) {
            String trigger = "🔁 Otomatik Tartışma - Tur " + round + "/" + rounds + ": Bir önceki turda " +
                    "diğer modellerin verdiği cevapları dikkatle değerlendir. Yanıtında şu 3 noktaya net biçimde değin:\n" +
                    "1. Diğer modellerin fikirlerine katılıp katılmadığın (Neden katıldın / katılmadın?)\n" +
                    "2. Önceki görüşünü değiştirip değiştirmediğin (Fikrin değiştiyse nedeni)\n" +
                    "3. Bu tur itibarıyla ortak vardığınız uzlaşı noktası.";
            currentLeaf = saveSystemTriggerMessage(conversation, currentLeaf, trigger);
            runBroadcastTurn(conversation, currentLeaf, debateClients, request.getResponseLength(), finalPersonaHints);
        }

        // NİHAİ SENTEZ & KONSENSÜS (HIZLI VE TEMİZ KONSENSÜS RAPORU)
        String synthesisTrigger = "🏆 NİHAİ KARAR VE SENTEZ: Yukarıdaki " + rounds + " turluk tartışmayı analiz et ve aşağıdaki EXACT FORMATTA yanıt üret:\n\n" +
                "[DEBATE_CONSENSUS]\n" +
                "# 🏆 3 Modelin Ortak Kararı ve Nihai Cevabı\n\n" +
                "### 📊 Modeller Fikirlerini Nasıl Değiştirdi?\n" +
                "- **ChatGPT:** (Nasıl yaklaştı, nerede sabit kaldı veya esnedi?)\n" +
                "- **Claude:** (Neden katıldı / katılmadı, neye itiraz etti?)\n" +
                "- **Gemini:** (Görüşü nasıl evrildi, neden fikir değiştirdi?)\n\n" +
                "### 🤝 Modellerin Tam Anlaştığı Noktalar\n" +
                "1. (Tüm modellerin mutabık kaldığı 1. nokta)\n" +
                "2. (Tüm modellerin mutabık kaldığı 2. nokta)\n\n" +
                "### ⚡ Farklı Düşünülen Noktalar\n" +
                "- (Hangi model hangi detayda farklı düşünüyor?)\n\n" +
                "### 💡 Ortak Tavsiye ve Yanıt (Nihai Cevap)\n" +
                "(Kullanıcının orijinal sorusuna 3 modelin ortak aklından ve tartışmasından süzülen eksiksiz, en kaliteli nihai yanıt)";

        // NİHAİ SENTEZ & KONSENSÜS (ARKA PLANDA 3 MODELLİ AKRAN İNCELEMESİ VE MÜHÜRLEME)
        Message synthesisLeaf = currentLeaf;
        int numClients = debateClients.size();

        for (int i = 0; i < numClients; i++) {
            AiClient currentReviewer = debateClients.get(i);
            boolean isFinalSeal = (i == numClients - 1);

            String reviewTrigger;
            if (i == 0) {
                reviewTrigger = "[INTERNAL_SYNTHESIS_DRAFT] 1. MODEL SENTEZ TASLAĞI: Yukarıdaki " + rounds + " turluk tartışmayı analiz et ve ilk sentez taslağını oluştur.";
            } else if (!isFinalSeal) {
                reviewTrigger = "[INTERNAL_SYNTHESIS_REVIEW] " + (i + 1) + ". MODEL İNCELEMESİ VE DÜZELTME: İlk modelin hazırladığı sentez taslağını ve tüm tartışmayı incele. Varsa eksikleri tamamla, kendi perspektifini de ekleyerek daha güçlü ve doğru bir revize sentez sun.";
            } else {
                reviewTrigger = "🏆 NİHAİ KARAR VE SENTEZ (3 MODELİN ORTAK AKLI VE MÜHÜRÜ): Önceki modellerin sentez taslaklarını ve tüm tartışmayı denetle. 3 modelin de tam mutabık kaldığı ve onayladığı nihai kararı aşağıdaki EXACT FORMATTA yayınla:\n\n" +
                        "[DEBATE_CONSENSUS]\n" +
                        "# 🏆 3 Modelin Ortak Katılımıyla Oluşturulmuş Nihai Sentez ve Cevap\n\n" +
                        "### 📊 Modeller Fikirlerini Nasıl Değiştirdi ve Uzlaştı?\n" +
                        "- **ChatGPT:** (Tartışmadaki tutumu, nerede esnedi veya katıldı?)\n" +
                        "- **Claude:** (Neden katıldı / katılmadı, hangi nüansı ekledi?)\n" +
                        "- **Gemini:** (Görüşü nasıl evrildi, nerede mutabık kaldı?)\n\n" +
                        "### 🤝 Modellerin Tam Anlaştığı Noktalar\n" +
                        "1. (Tüm modellerin mutabık kaldığı 1. nokta)\n" +
                        "2. (Tüm modellerin mutabık kaldığı 2. nokta)\n\n" +
                        "### ⚡ Farklı Düşünülen Nüanslar\n" +
                        "- (Hangi model hangi detayda farklı bir vurgu yapıyor?)\n\n" +
                        "### 💡 Ortak Tavsiye ve Yanıt (3 Modelin Ortak Aklı)\n" +
                        "(Kullanıcının sorusuna 3 modelin aktif katılımından ve denetiminden süzülen eksiksiz, en kaliteli nihai yanıt)";
            }

            synthesisLeaf = saveSystemTriggerMessage(conversation, synthesisLeaf, reviewTrigger);
            List<AiMessage> synthesisContext = buildContext(conversation, synthesisLeaf, currentReviewer.getProvider(),
                    request.getResponseLength(), null);
            callProviderAndSave(currentReviewer,
                    AiRequest.builder().messages(synthesisContext).build(),
                    conversation, synthesisLeaf);
        }

        return toConversationResponse(conversation);
    }

    private MessageResponse callProviderAndSave(AiClient client, AiRequest aiRequest,
                                                Conversation conversation, Message parentMessage) {
        long startTime = System.currentTimeMillis();
        AiClientResponse clientResponse = client.sendPrompt(aiRequest);
        long latencyMs = System.currentTimeMillis() - startTime;

        int inputTokens = clientResponse.getInputTokens() != null
                ? clientResponse.getInputTokens()
                : estimateTokens(aiRequest.getMessages().stream().map(AiMessage::getContent).collect(Collectors.joining(" ")));
        int outputTokens = clientResponse.getOutputTokens() != null
                ? clientResponse.getOutputTokens()
                : estimateTokens(clientResponse.getContent());
        long finalLatency = clientResponse.getLatencyMs() != null ? clientResponse.getLatencyMs() : latencyMs;
        double cost = clientResponse.getEstimatedCost() != null
                ? clientResponse.getEstimatedCost()
                : calculateCost(client.getProvider(), inputTokens, outputTokens);

        Message aiMessage = new Message();
        aiMessage.setConversation(conversation);
        aiMessage.setParentMessage(parentMessage);
        aiMessage.setRole(Role.ASSISTANT);
        aiMessage.setAiProvider(client.getProvider());
        aiMessage.setContent(clientResponse.getContent());
        aiMessage.setLatencyMs(finalLatency);
        aiMessage.setInputTokens(inputTokens);
        aiMessage.setOutputTokens(outputTokens);
        aiMessage.setEstimatedCost(cost);

        Message saved = messageRepository.save(aiMessage);
        return toMessageResponse(saved);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    private double calculateCost(AiProvider provider, int inputTokens, int outputTokens) {
        if (provider == null) return 0.0;
        double inputPricePer1M;
        double outputPricePer1M;

        switch (provider) {
            case OPENAI:
                inputPricePer1M = 0.15;
                outputPricePer1M = 0.60;
                break;
            case CLAUDE:
                inputPricePer1M = 3.00;
                outputPricePer1M = 15.00;
                break;
            case GEMINI:
                inputPricePer1M = 0.10;
                outputPricePer1M = 0.40;
                break;
            default:
                inputPricePer1M = 0.20;
                outputPricePer1M = 0.80;
                break;
        }

        double cost = ((inputTokens / 1_000_000.0) * inputPricePer1M) + ((outputTokens / 1_000_000.0) * outputPricePer1M);
        return Math.round(cost * 1_000_000.0) / 1_000_000.0;
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

        boolean isAlreadySelected = message.isSelected();
        List<Message> updated = new ArrayList<>();
        for (Message sibling : siblings) {
            boolean shouldBeSelected = !isAlreadySelected && sibling.getId().equals(message.getId());
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
        return conversationRepository.findByUserIdOrderByPinnedDescCreatedAtDesc(userId).stream()
                .map(this::toConversationSummaryResponse)
                .collect(Collectors.toList());
    }

    // Kullanıcı Dashboard'da bir konuşmayı sabitlediğinde/sabitlemeyi kaldırdığında çağrılır.
    @Transactional
    public ConversationSummaryResponse togglePinConversation(Long conversationId) {
        Conversation conversation = getConversationOrThrow(conversationId);
        conversation.setPinned(!conversation.isPinned());
        Conversation saved = conversationRepository.save(conversation);
        return toConversationSummaryResponse(saved);
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
        conversation.setTitle(generateTitle(request.getPrompt()));
        conversation.setUserId(request.getUserId());
        if (request.getProviders() != null && !request.getProviders().isEmpty()) {
            conversation.setProviders(String.join(",", request.getProviders()));
        }
        if (request.getMode() != null) {
            conversation.setMode(request.getMode());
        }

        // Persona Ayarla (Özel Persona veya Varsayılan)
        Long personaId = request.getPersonaId();
        if (personaId != null) {
            personaRepository.findById(personaId).ifPresent(p -> {
                conversation.setPersonaId(p.getId());
                conversation.setPersonaName(p.getName());
            });
        } else {
            personaRepository.findByIsDefaultTrue().ifPresent(p -> {
                conversation.setPersonaId(p.getId());
                conversation.setPersonaName(p.getName());
            });
        }

        return conversationRepository.save(conversation);
    }

    private String resolvePersonaPrompt(Conversation conversation) {
        if (conversation.getPersonaId() != null) {
            Optional<Persona> personaOpt = personaRepository.findById(conversation.getPersonaId());
            if (personaOpt.isPresent()) {
                return personaOpt.get().getSystemPrompt();
            }
        }
        return personaRepository.findByIsDefaultTrue()
                .map(Persona::getSystemPrompt)
                .orElse("Sen bilgili, yardımsever ve dürüst bir yapay zeka asistanısın. Yanıtlarını net ve yapıcı bir dille sun.");
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
            "referans amaçlı bilgi olarak vereceğim. Bu özette her cevabın kime ait olduğu AÇIKÇA " +
            "işaretlenecek: \"(SENİN ÖNCEKİ CEVABIN)\" diye işaretlenenler SENİN daha önce söylediğin " +
            "şeylerdir, diğerleri (\"ChatGPT:\", \"Claude:\", \"Gemini:\" - senin adın DIŞINDAKİ etiketlerle) " +
            "BAŞKA yapay zekaların cevaplarıdır.\n\n" +
            "TARTIŞMACI TUTUM: DİĞER sağlayıcıların cevaplarını pasifçe kabul etme; onları eleştirel bir " +
            "gözle değerlendir. Eğer bir sağlayıcının cevabında eksik, yanlış, yanıltıcı veya zayıf bir " +
            "gerekçe görüyorsan bunu AÇIKÇA belirt ve neden katılmadığını kısaca gerekçelendir. ÖNEMLİ: " +
            "\"(SENİN ÖNCEKİ CEVABIN)\" diye işaretlenmiş cevabı ELEŞTİRMEN İSTENMİYOR - o zaten senin kendi " +
            "görüşün, kendi kendinle tartışma; onu sadece devam ettir, gerekirse yeni bilgi ışığında " +
            "GELİŞTİR/güncelle, ama sanki başka biriymiş gibi eleştirme. Katıldığın bir noktayı da " +
            "söyleyebilirsin ama sadece onaylamakla yetinme - kendi bağımsız değerlendirmeni yap, gerekirse " +
            "farklı bir sonuca var. Kullanıcı seni açıkça başka bir sağlayıcının cevabıyla karşılaştırıyorsa " +
            "(örn. \"Gemini şunu söyledi, sen ne düşünüyorsun\"), o cevaba doğrudan ve dürüstçe tepki ver: " +
            "sadece nazikçe \"farklı bakış açıları olabilir\" deyip geçme, gerçekten doğru bulmadığın yeri " +
            "söyle. Amaç kibarca hemfikir olmak değil, kullanıcıya en doğru/en iyi cevabı bulmakta yardımcı " +
            "olacak gerçek bir tartışma ortamı sunmak. Yine de saygılı bir üslup kullan, diğer sağlayıcıyı " +
            "aşağılama, sadece içeriği eleştir.\n\n" +
            "ÇOK ÖNEMLİ: cevabında ASLA \"[ChatGPT]\", \"[Claude]\", \"[Gemini]\" gibi etiketler kullanma, başka bir " +
            "sağlayıcı adına konuşma ya da referans özetini tekrar etme/kopyalama; kendi görüşünü, kendi doğal " +
            "sesinle, tek bir cevap olarak yaz (diğer sağlayıcıdan bahsederken \"Gemini\", \"ChatGPT\" gibi ismini " +
            "kullanabilirsin, bu yasak değil - yasak olan onun adına konuşmak/onun cevabını taklit etmek).";

    // Varsayılan olarak TÜM isteklere eklenen sistem talimatı: modelin bir yazılım geliştirici/
    // mühendis gibi teknik, kesin ve pratik cevaplar vermesini ister. Kullanıcı toggle ile
    // açıp kapatmıyor - her zaman aktif (bkz. buildContext extras). Soru programlama/kod ile
    // ilgili DEĞİLSE modelin normal genel bilgisiyle cevap vermesine izin verir, yani kod dışı
    // konularda zorla teknik jargon üretmeye ZORLAMAZ.
    private static final String DEFAULT_DEVELOPER_SYSTEM_HINT =
            "Sen deneyimli bir yazılım geliştirici/mühendis gibi düşünen bir asistansın. Kullanıcının " +
            "sorusu kod, programlama, mimari, hata ayıklama veya teknik bir konuyla ilgiliyse: net, " +
            "doğru ve uygulanabilir cevaplar ver; gerektiğinde çalışır durumda kod örnekleri, komutlar " +
            "veya adım adım talimatlar kullan; varsayımlarını belirt, iyi pratiklere ve okunabilirliğe " +
            "dikkat et, gereksiz laf kalabalığından kaçın. Kullanıcının sorusu programlama/teknik bir " +
            "konu DEĞİLSE bu talimatı görmezden gel ve konuya uygun şekilde normal, doğal bir cevap ver - " +
            "kod dışı konularda ZORLA teknik jargon veya yazılımcı üslubu kullanma.";

    private List<AiMessage> buildContext(Conversation conversation, Message leaf, AiProvider targetProvider,
                                          String responseLength, String personaHint) {
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

        List<AiMessage> context = compareMode
                ? buildCompareContext(history, answersByParent, leaf, targetProvider)
                : buildIndependentContext(history, answersByParent, leaf, targetProvider);

        // Cevap uzunluğu, varsayılan "yazılımcı gibi düşün" talimatı ve (varsa) tartışma rolü,
        // EK sistem mesajları olarak başa eklenir.
        // NOT: Anthropic/Gemini client'ları TÜM system-rollü mesajları zaten tek bir alanda
        // birleştiriyor (bkz. ClaudeClient/GeminiClient), OpenAI da birden fazla system mesajını
        // sorunsuz kabul ediyor - o yüzden sırası veya sayısı önemli değil, ekstra client
        // değişikliği gerekmedi.
        List<AiMessage> extras = new ArrayList<>();
        String dynamicPersonaPrompt = resolvePersonaPrompt(conversation);
        extras.add(AiMessage.builder().role(Role.SYSTEM).content(dynamicPersonaPrompt).build());
        if (personaHint != null && !personaHint.isBlank()) {
            extras.add(AiMessage.builder().role(Role.SYSTEM).content(personaHint).build());
        }
        String lengthHint = responseLengthHint(responseLength);
        if (lengthHint != null) {
            // Kasıtlı olarak SIRALAMADA EN SONA (kullanıcı mesajına en yakın) ekleniyor: modeller
            // system talimatına genelde en son okudukları/en yakın olan kısıtlamaya daha çok uyuyor.
            extras.add(AiMessage.builder().role(Role.SYSTEM).content(lengthHint).build());
        }
        List<AiMessage> combined = new ArrayList<>(extras);
        combined.addAll(context);
        return combined;
    }

    // Kullanıcının seçtiği "Yanıt Uzunluğu" tercihine göre ek bir sistem talimatı üretir.
    // NORMAL veya tanınmayan/boş bir değer için null döner (ek talimat eklenmez, mevcut davranış).
    private String responseLengthHint(String responseLength) {
        if (responseLength == null || responseLength.isBlank()) return null;
        return switch (responseLength.trim().toUpperCase()) {
            case "KISA" -> "<response_length_constraint>CRITICAL INSTRUCTION: KEEP YOUR RESPONSE EXTREMELY BRIEF AND CONCISE. Use maximum 3-4 short sentences or 60 words total. Avoid polite intros or summaries, answer directly.</response_length_constraint>\nÖNEMLİ (ZORUNLU YANIT UZUNLUĞU KURALI): Cevabını ÇOK KISA tut - en fazla 3-4 kısa cümle ya da 60 kelimeyi geçme. Gereksiz giriş/kapanış yapma, doğrudan cevaba gir.";
            case "DETAYLI" -> "<response_length_constraint>CRITICAL INSTRUCTION: PROVIDE AN EXTENSIVE AND DETAILED RESPONSE. Include examples, subheadings, and in-depth explanations.</response_length_constraint>\nÖNEMLİ (ZORUNLU YANIT UZUNLUĞU KURALI): Cevabını DETAYLI ve kapsamlı ver - örnekler, alt başlıklar ve gerekçelerle derinlemesine destekle.";
            default -> null; // NORMAL
        };
    }

    // Otomatik Tartışma Modu'nda (useRoles=true) her sağlayıcıya atanan sabit rollerin isimleri
    // (frontend'e/loglara gösterilebilir) ve modele verilecek talimat metinleri. Index sırası,
    // debateClients listesindeki sıraya (yani conversation.providers CSV sırasına) karşılık gelir.
    private static final String[] DEBATE_ROLE_HINTS = {
            "TARTIŞMA ROLÜN: PRATİK UYGULAYICI. Somut, uygulanabilir, adım adım çözümlere odaklan; " +
                    "soyut teoriden çok \"bunu nasıl hayata geçiririm\"e odaklan.",
            "TARTIŞMA ROLÜN: ELEŞTİRMEN / ŞEYTANIN AVUKATI. Diğerlerinin önerilerindeki riskleri, zayıf " +
                    "noktaları, gözden kaçan senaryoları aktif olarak ara ve sorgula; kolayca ikna olma.",
            "TARTIŞMA ROLÜN: SENTEZLEYİCİ / TUR REHBERİ. Farklı görüşleri bir araya getirmeye, ortak " +
                    "noktaları bulmaya ve tartışmayı ileri taşıyacak netleştirici sorular sormaya odaklan."
    };

    // targetProvider: bu context'in HANGİ sağlayıcıya gönderileceği. Referans blokunda o sağlayıcının
    // KENDİ önceki cevapları "(SENİN ÖNCEKİ CEVABIN)" diye işaretlenir - böylece model, COMPARE_SYSTEM_HINT'teki
    // "diğer sağlayıcıları eleştir" talimatını kendi geçmiş cevabına uygulayıp KENDİ KENDİNİ eleştirmez
    // (önceden gözlemlenen bug: örn. Claude'a giden referans blokunda Claude'un kendi eski cevabı da
    // diğerleriyle aynı şekilde etiketlenmişti, bu yüzden Claude bazen kendi cevabını da eleştiriyordu).
    private List<AiMessage> buildCompareContext(List<Message> history, Map<Long, List<Message>> answersByParent,
                                                 Message leaf, AiProvider targetProvider) {
        StringBuilder referenceBlock = new StringBuilder();
        boolean hasAnsweredHistory = false;

        for (Message m : history) {
            if (m.getRole() != Role.USER) continue;
            List<Message> answers = answersByParent.get(m.getId());
            if (answers == null || answers.isEmpty()) continue;

            hasAnsweredHistory = true;
            referenceBlock.append("Soru: ").append(m.getContent()).append("\n");
            answers.stream()
                    .sorted(Comparator.comparing(Message::getId))
                    .forEach(a -> {
                        boolean isOwnAnswer = targetProvider != null && a.getAiProvider() == targetProvider;
                        referenceBlock.append("- ")
                                .append(isOwnAnswer ? "SENİN ÖNCEKİ CEVABIN" : providerLabel(a.getAiProvider()))
                                .append(a.isSelected() ? " (kullanıcının tercih ettiği cevap): " : ": ")
                                .append(a.getContent())
                                .append("\n");
                    });
            referenceBlock.append("\n");
        }

        String effectiveLeafContent = adjustLeafContentForProvider(leaf.getContent(), targetProvider);

        List<AiMessage> context = new ArrayList<>();
        if (!hasAnsweredHistory) {
            context.add(AiMessage.builder().role(Role.USER).content(effectiveLeafContent).build());
            return context;
        }

        context.add(AiMessage.builder().role(Role.SYSTEM).content(COMPARE_SYSTEM_HINT).build());

        String userTurn = "=== Önceki karşılaştırma turları (REFERANS içindir; \"SENİN ÖNCEKİ CEVABIN\" diye " +
                "işaretlenenler HARİÇ bunlar senin sözlerin değildir) ===\n\n"
                + referenceBlock
                + "=== Referans sonu ===\n\n"
                + "Kullanıcının yeni mesajı:\n" + effectiveLeafContent;
        context.add(AiMessage.builder().role(Role.USER).content(userTurn).build());
        return context;
    }

    private List<AiMessage> buildIndependentContext(List<Message> history, Map<Long, List<Message>> answersByParent,
                                                      Message leaf, AiProvider targetProvider) {
        List<AiMessage> context = new ArrayList<>();

        for (Message m : history) {
            if (m.getRole() != Role.USER) continue;
            List<Message> answers = answersByParent.get(m.getId());
            if (answers == null || answers.isEmpty()) continue;

            Message ownAnswer = answers.stream()
                    .filter(a -> a.getAiProvider() == targetProvider)
                    .findFirst()
                    .orElse(null);
            if (ownAnswer == null) continue; // bu turu targetProvider cevaplamamış -> soru dahil hiç görmesin

            context.add(AiMessage.builder().role(Role.USER).content(m.getContent()).build());
            context.add(AiMessage.builder().role(Role.ASSISTANT).content(ownAnswer.getContent()).build());
        }

        String effectiveLeafContent = adjustLeafContentForProvider(leaf.getContent(), targetProvider);
        context.add(AiMessage.builder().role(Role.USER).content(effectiveLeafContent).build());
        return context;
    }

    private String adjustLeafContentForProvider(String rawContent, AiProvider targetProvider) {
        if (rawContent == null || !rawContent.contains("[Tartışma Modu -")) {
            return rawContent;
        }

        AiProvider quotedProvider = null;
        if (rawContent.contains("QUOTED_PROVIDER:CLAUDE") || rawContent.contains("Claude'in cevabını") || rawContent.contains("Claude'ın cevabını") || rawContent.contains("Claude şu cevabı verdi")) {
            quotedProvider = AiProvider.CLAUDE;
        } else if (rawContent.contains("QUOTED_PROVIDER:OPENAI") || rawContent.contains("ChatGPT'nin cevabını") || rawContent.contains("ChatGPT şu cevabı verdi")) {
            quotedProvider = AiProvider.OPENAI;
        } else if (rawContent.contains("QUOTED_PROVIDER:GEMINI") || rawContent.contains("Gemini'nin cevabını") || rawContent.contains("Gemini şu cevabı verdi")) {
            quotedProvider = AiProvider.GEMINI;
        }

        if (quotedProvider == null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("QUOTED_PROVIDER:([A-Z_]+)").matcher(rawContent);
            if (matcher.find()) {
                try {
                    quotedProvider = AiProvider.valueOf(matcher.group(1));
                } catch (Exception ignored) {}
            }
        }

        if (quotedProvider != null && targetProvider != null && quotedProvider == targetProvider) {
            String userRequest = rawContent;
            int userReqIndex = rawContent.indexOf("Bu cevap hakkında kullanıcının isteği:");
            if (userReqIndex != -1) {
                userRequest = rawContent.substring(userReqIndex + "Bu cevap hakkında kullanıcının isteği:".length()).trim();
                int stopIndex = userRequest.indexOf("\n\nYalnızca yukarıdaki");
                if (stopIndex != -1) {
                    userRequest = userRequest.substring(0, stopIndex).trim();
                }
            }

            String quotedText = "";
            int quoteStart = rawContent.indexOf("\"\"\"\n");
            int quoteEnd = rawContent.indexOf("\n\"\"\"");
            if (quoteStart != -1 && quoteEnd != -1 && quoteEnd > quoteStart) {
                quotedText = rawContent.substring(quoteStart + 4, quoteEnd).trim();
            }

            return "[Tartışma Modu - Bu senin kendi cevabındır]\n" +
                   "Önceki turda verdiğin cevabın:\n\"\"\"\n" + quotedText + "\n\"\"\"\n\n" +
                   "Kullanıcı senin bu cevabın hakkında şu soruyu/isteği iletti: " + userRequest + "\n\n" +
                   "Talimat: Kendi görüşünü ve pozisyonunu savun, daha ayrıntılı açıkla, netleştir veya kullanıcının sorusunu kendi bakış açınla yanıtla. Kendi kendinle 3. şahıs gibi tartışma veya kendi cevabını başkasıymış gibi eleştirme.";
        }

        return rawContent;
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
                .personaId(conversation.getPersonaId())
                .personaName(conversation.getPersonaName())
                .pinned(conversation.isPinned())
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
                .personaId(conversation.getPersonaId())
                .personaName(conversation.getPersonaName())
                .pinned(conversation.isPinned())
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
                .latencyMs(message.getLatencyMs())
                .inputTokens(message.getInputTokens())
                .outputTokens(message.getOutputTokens())
                .estimatedCost(message.getEstimatedCost())
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