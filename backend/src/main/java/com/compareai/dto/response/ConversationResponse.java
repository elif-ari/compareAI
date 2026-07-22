package com.compareai.dto.response;

import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationResponse {

    private Long id;

    private String title;

    // Konuşmanın şu anki HEAD'i - frontend hangi dalın "aktif" gösterileceğini buradan anlar
    private Long currentMessageId;

    // Konuşmadaki TÜM mesajlar (tüm dallar dahil), her biri parentMessageId taşır.
    // Frontend bu düz listeden ağacı kurar.
    private List<MessageResponse> messages;

}