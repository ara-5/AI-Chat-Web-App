package com.example.chatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;

/**
 * Binds the openai.* configuration properties, which in turn are backed by
 * environment variables (see application.yml). Nothing here is hardcoded --
 * the base URL, API key and model are all supplied at deploy time.
 */
@ConfigurationProperties(prefix = "openai")
@Validated
public record OpenAiProperties(
        @NotBlank String baseUrl,
        String apiKey,
        @NotBlank String model,
        Duration connectTimeout,
        Duration readTimeout
) {
    public OpenAiProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(10);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(60);
        }
    }
}
