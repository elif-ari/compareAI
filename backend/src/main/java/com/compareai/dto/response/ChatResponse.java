package com.compareai.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private Long conversationId;

    // Konuşmanın şu anki HEAD'i (kullanıcı bir AI cevabını seçene kadar bu, gönderilen kullanıcı mesajının id'sidir)
    private Long currentMessageId;

    // Az önce kaydedilen kullanıcı mesajı
    private MessageResponse userMessage;

    // Aynı kullanıcı mesajına verilen, farklı sağlayıcılardan gelen AI cevapları (kardeş dallar)
    private List<MessageResponse> aiResponses;
}