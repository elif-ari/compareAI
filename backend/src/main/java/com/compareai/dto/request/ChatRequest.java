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
}
