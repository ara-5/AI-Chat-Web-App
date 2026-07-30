package com.example.chatbot.dto;

/** Response body returned by POST /api/chat on success. */
public record ChatResponse(String reply, String model) {
}
