package com.compareai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    private Long conversationId;

    // Yeni konusma acilirken (conversationId bos oldugunda) bu konusmanin sahibi olacak
    // kullanici. Var olan bir konusmaya devam ederken gonderilmesine gerek yok, zaten
    // Conversation uzerinde saklaniyor.
    private Long userId;

    // Yeni mesaj hangi mesajın devamı?
    // İlk mesaj ise null olabilir.
    private Long parentMessageId;

    // true  -> üst orta bar: hangi daldan devam ediliyorsa edilsin, 3 sağlayıcıya BİRDEN sor (yeni karşılaştırma turu)
    // false -> bir AI konteynerinin kendi mini bar'ı: SADECE o dalın sağlayıcısına sor
    // null/gönderilmezse -> eski davranış: parent bir ASSISTANT mesajıysa tek sağlayıcı, değilse 3'ü de
    private Boolean askAllProviders;

    // askAllProviders=false olduğunda mesajın hangi sağlayıcıya (OPENAI/CLAUDE/GEMINI) gideceğini
    // AÇIKÇA belirtir. Bunu eklememizin sebebi: Compare modunda "X ile devam et" dendiğinde
    // parentMessageId artık illa X'in kendi cevabı olmuyor (bkz. ChatService#resolveSingleProvider) -
    // o yüzden hedef sağlayıcıyı parent'tan çıkarmak yerine doğrudan burada söylüyoruz.
    // Boş bırakılırsa eski davranışa (parent'ın provider'ı) düşülür (geriye dönük uyumluluk).
    private String targetProvider;

    // Yeni Sohbet ekranında seçilen sağlayıcılar (ör. ["OPENAI","CLAUDE"]).
    // Yalnızca conversationId boşken (yeni konuşma oluşturulurken) dikkate alınır.
    private List<String> providers;

    // Yeni Sohbet ekranında seçilen mod: INDEPENDENT | COMPARE.
    // Yalnızca conversationId boşken (yeni konuşma oluşturulurken) dikkate alınır.
    // Not: COMPARE modunun ortak bağlam davranışı ikinci geliştirme aşamasında eklenecek;
    // şimdilik yalnızca konuşma üzerinde saklanır.
    private String mode;

    @NotBlank
    private String prompt;

    // Otomatik Tartışma Modu (bkz. ChatService#runAutoDebate) için: sağlayıcıların kendi aralarında
    // kaç tur konuşacağı (varsayılan 5, güvenlik için 2-6 arasına sınırlanır). Yalnızca
    // POST /api/chat/debate endpoint'inde kullanılır, normal sendMessage'da dikkate alınmaz.
    private Integer debateRounds;

    // Cevap uzunluğu tercihi: "KISA" | "NORMAL" | "DETAYLI". Her mesajda ayrı ayrı gönderilebilir
    // (Conversation'da KALICI olarak saklanmaz) - kullanıcı istediği turda kısa, istediği turda
    // detaylı cevap isteyebilir. Boş/null veya tanınmayan değer -> NORMAL (ek talimat yok).
    private String responseLength;

    // SADECE Otomatik Tartışma Modu'nda (debate) kullanılır: true ise her sağlayıcıya sabit bir
    // TARTIŞMA ROLÜ atanır (Pratik Uygulayıcı / Eleştirmen / Sentezleyici-Tur Rehberi) - bkz.
    // ChatService#DEBATE_ROLE_HINTS. false/null ise roller atanmaz, herkes genel/nötr tartışır.
    private Boolean useRoles;
}
