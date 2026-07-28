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

    // Bu konusma hangi kullaniciya ait (app_users.id). Dashboard'daki "Sohbet Gecmisi"
    // listesi bu alana gore filtrelenir. Eski/test verilerinde null olabilecegi icin
    // nullable birakildi, ama yeni konusmalar ChatService uzerinden hep bir userId ile acilir.
    @Column(name = "user_id")
    private Long userId;

    // Kullanici baslik girmezse otomatik bir baslik atanabilir (ilk mesajdan turetilerek)
    @Column(nullable = false, length = 255)
    private String title;

    // Git'teki HEAD gibi: kullanıcının şu an konuşmada bulunduğu dalın ucundaki mesaj.
    // Yeni konuşmada henüz mesaj yokken null olur.
    @Column(name = "current_message_id")
    private Long currentMessageId;

    // Yeni sohbet ekranında seçilen sağlayıcılar (ör. "OPENAI,CLAUDE").
    // Basitlik için tek bir kolonda virgülle ayrılmış olarak tutulur.
    @Column(length = 255)
    private String providers;

    // Yeni sohbet ekranında seçilen çalışma modu: INDEPENDENT | COMPARE
    @Column(length = 20)
    private String mode;

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
