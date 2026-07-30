package com.example.chatbot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A single turn in the conversation, shaped to match the OpenAI chat
 * completions message format ({"role": "...", "content": "..."}).
 */
public record Message(
        @NotBlank
        @Pattern(regexp = "system|user|assistant", message = "role must be one of: system, user, assistant")
        String role,

        @NotBlank(message = "content must not be blank")
        String content
) {
}
