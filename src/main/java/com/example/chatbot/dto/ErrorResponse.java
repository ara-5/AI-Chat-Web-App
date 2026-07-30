package com.example.chatbot.dto;

import java.time.Instant;

/** Uniform error shape returned to the frontend for any failure. */
public record ErrorResponse(String error, String message, Instant timestamp) {
    public static ErrorResponse of(String error, String message) {
        return new ErrorResponse(error, message, Instant.now());
    }
}
