package com.compareai.controller;

import com.compareai.dto.response.AIResponse;
import com.compareai.service.ChatService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public List<AIResponse> sendMessage(@RequestBody String prompt) {
        return chatService.sendMessage(prompt);
    }

}