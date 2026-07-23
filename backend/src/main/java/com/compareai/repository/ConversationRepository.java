package com.compareai.repository;

import com.compareai.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    // JpaRepository sayesinde save(), findById(), findAll(), deleteById() vb.
    // metodlar hazir geliyor, ekstra kod yazmamiza gerek yok.
}
