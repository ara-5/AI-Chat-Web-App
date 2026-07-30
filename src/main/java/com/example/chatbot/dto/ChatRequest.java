package com.example.chatbot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body accepted by POST /api/chat.
 *
 * `message` is the new prompt from the user. `history` is optional prior
 * conversation context (excluding the new message) so the backend can stay
 * stateless while the frontend owns the conversation state.
 */
public record ChatRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 8000, message = "message must be 8000 characters or fewer")
        String message,

        @Valid
        List<Message> history
) {
    public List<Message> historyOrEmpty() {
        return history == null ? List.of() : history;
    }
}
