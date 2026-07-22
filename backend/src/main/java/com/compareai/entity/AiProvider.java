package com.compareai.entity;

/**
 * Cevabin hangi yapay zeka servisinden geldigini belirtir.
 * Kullanici mesajlarinda bu alan null olur (kullanici bir "provider" degildir).
 */
public enum AiProvider {
    OPENAI,
    CLAUDE,
    GEMINI
}
