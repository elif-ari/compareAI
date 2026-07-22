package com.compareai.repository;

import com.compareai.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    // Bir konusmaya ait tum mesajlari, gonderilme sirasina gore getirir.
    // Metod adindan Spring Data JPA otomatik SQL sorgusu uretir, biz hic SQL yazmiyoruz.

    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
}
