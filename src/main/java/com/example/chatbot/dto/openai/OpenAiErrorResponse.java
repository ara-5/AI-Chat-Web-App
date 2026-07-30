package com.example.chatbot.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Error body shape returned by OpenAI-compatible APIs, e.g. {"error": {"message": "...", "type": "...", "code": "..."}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiErrorResponse(ErrorDetail error) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorDetail(String message, String type, String code) {
    }
}
