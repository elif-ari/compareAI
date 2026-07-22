package com.compareai.entity;

import com.compareai.enums.Role;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Bu mesaj hangi konuşmaya ait?
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    // Bu mesaj hangi mesajın devamı?
    // İlk kullanıcı mesajında null olur.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_message_id")
    private Message parentMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    // Kullanıcı mesajlarında null olur.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AiProvider aiProvider;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // Kullanıcının "bu cevapla devam et" dediği AI mesajı.
    @Column(nullable = false)
    private boolean selected = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}