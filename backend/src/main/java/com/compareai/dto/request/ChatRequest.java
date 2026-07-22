package com.compareai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @NotBlank
    private String prompt;
}