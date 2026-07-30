package com.example.chatbot.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One JSON object from the OpenAI /chat/completions SSE stream.
 * Each line is "data: {json}" where the JSON has this shape.
 * The final line is "data: [DONE]" and is handled separately.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiStreamChunk(
        String id,
        String model,
        List<StreamChoice> choices
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamChoice(int index, Delta delta, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String role, String content) {
    }
}
