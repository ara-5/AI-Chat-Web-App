package com.example.chatbot.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Response body shape returned by the OpenAI /chat/completions endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatResponse(
        String id,
        String model,
        List<Choice> choices
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(int index, ChoiceMessage message, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChoiceMessage(String role, String content) {
    }
}
