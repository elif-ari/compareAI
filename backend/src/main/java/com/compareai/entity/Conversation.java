package com.compareai.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "conversations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Kullanici baslik girmezse otomatik bir baslik atanabilir (ilk mesajdan turetilerek)
    @Column(nullable = false, length = 255)
    private String title;

    // Git'teki HEAD gibi: kullanıcının şu an konuşmada bulunduğu dalın ucundaki mesaj.
    // Yeni konuşmada henüz mesaj yokken null olur.
    @Column(name = "current_message_id")
    private Long currentMessageId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Bir konusmanin icinde birden fazla mesaj olur.
    // mappedBy = "conversation" -> iliskinin sahibi Message tarafindaki 'conversation' alanidir.
    // cascade = ALL -> Conversation silinirse ona bagli tum Message'lar da silinir.
    // orphanRemoval = true -> listeden bir Message cikarilirsa veritabanindan da silinir.
    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Message> messages = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Iki yonlu iliskiyi tutarli yonetmek icin yardimci metod
    public void addMessage(Message message) {
        messages.add(message);
        message.setConversation(this);
    }
}
