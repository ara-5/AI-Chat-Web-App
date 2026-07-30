package com.example.chatbot.dto.openai;

import com.example.chatbot.dto.Message;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Request body shape expected by the OpenAI /chat/completions endpoint. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Boolean stream
) {
    /** Convenience constructor for non-streaming requests (original behaviour). */
    public OpenAiChatRequest(String model, List<Message> messages, Double temperature) {
        this(model, messages, temperature, null);
    }
}
