package com.example.chatbot.service;

import com.example.chatbot.config.OpenAiProperties;
import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.ChatResponse;
import com.example.chatbot.dto.Message;
import com.example.chatbot.dto.openai.OpenAiChatRequest;
import com.example.chatbot.dto.openai.OpenAiChatResponse;
import com.example.chatbot.dto.openai.OpenAiErrorResponse;
import com.example.chatbot.dto.openai.OpenAiStreamChunk;
import com.example.chatbot.exception.UpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to an OpenAI-compatible /chat/completions endpoint and translates
 * both the happy path and every failure mode into something the rest of
 * the app can work with.
 *
 * Supports two modes:
 *  - getChatCompletion()       → blocking, returns a full ChatResponse JSON
 *  - streamChatCompletion()    → non-blocking SSE, pushes tokens as they arrive
 */
@Service
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);
    private static final String SSE_DONE_SENTINEL = "[DONE]";

    private final RestClient restClient;
    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiService(RestClient openAiRestClient, OpenAiProperties properties, ObjectMapper objectMapper) {
        this.restClient = openAiRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Non-streaming (original) path
    // -------------------------------------------------------------------------

    public ChatResponse getChatCompletion(ChatRequest chatRequest) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new UpstreamException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The chat service is not configured. Set the OPENAI_API_KEY environment variable.");
        }

        List<Message> messages = new ArrayList<>(chatRequest.historyOrEmpty());
        messages.add(new Message("user", chatRequest.message()));

        OpenAiChatRequest upstreamRequest = new OpenAiChatRequest(properties.model(), messages, 0.7);

        try {
            OpenAiChatResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .body(upstreamRequest)
                    .retrieve()
                    .onStatus(status -> status.value() == 401, (req, res) -> {
                        throw new UpstreamException(HttpStatus.BAD_GATEWAY,
                                "The upstream API rejected our credentials. Check OPENAI_API_KEY.");
                    })
                    .onStatus(status -> status.value() == 429, (req, res) -> {
                        throw new UpstreamException(HttpStatus.TOO_MANY_REQUESTS,
                                "The upstream API is rate-limiting requests. Please try again shortly.");
                    })
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, res) -> {
                        String detail = extractErrorMessage(res.getStatusCode(), res.getBody());
                        HttpStatus mapped = res.getStatusCode().is5xxServerError()
                                ? HttpStatus.BAD_GATEWAY
                                : HttpStatus.BAD_REQUEST;
                        throw new UpstreamException(mapped, detail);
                    })
                    .body(OpenAiChatResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new UpstreamException(HttpStatus.BAD_GATEWAY, "The upstream API returned an empty response.");
            }

            String reply = response.choices().get(0).message().content();
            String modelUsed = response.model() != null ? response.model() : properties.model();
            return new ChatResponse(reply, modelUsed);

        } catch (ResourceAccessException ex) {
            log.warn("Timed out or failed to reach upstream chat API", ex);
            throw new UpstreamException(HttpStatus.GATEWAY_TIMEOUT,
                    "Timed out reaching the upstream chat API. Please try again.", ex);
        }
    }

    // -------------------------------------------------------------------------
    // Streaming path
    // -------------------------------------------------------------------------

    /**
     * Calls the upstream with {@code stream: true}, reads the {@code text/event-stream}
     * response line-by-line on the calling thread, and pushes each delta token to the
     * provided {@link SseEmitter}.
     *
     * The emitter is completed (or terminated with an error event) before this method
     * returns. Callers should create the emitter with a suitable timeout and register
     * an {@code onTimeout} callback, but the happy-path lifecycle is fully managed here.
     */
    public void streamChatCompletion(ChatRequest chatRequest, SseEmitter emitter) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            sendErrorEvent(emitter, "The chat service is not configured. Set the OPENAI_API_KEY environment variable.");
            return;
        }

        List<Message> messages = new ArrayList<>(chatRequest.historyOrEmpty());
        messages.add(new Message("user", chatRequest.message()));

        OpenAiChatRequest upstreamRequest = new OpenAiChatRequest(properties.model(), messages, 0.7, Boolean.TRUE);

        try {
            restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Accept", "text/event-stream")
                    .body(upstreamRequest)
                    .exchange((req, res) -> {
                        HttpStatusCode status = res.getStatusCode();

                        if (status.value() == 401) {
                            sendErrorEvent(emitter, "The upstream API rejected our credentials. Check OPENAI_API_KEY.");
                            return null;
                        }
                        if (status.value() == 429) {
                            sendErrorEvent(emitter, "The upstream API is rate-limiting requests. Please try again shortly.");
                            return null;
                        }
                        if (status.is4xxClientError() || status.is5xxServerError()) {
                            String detail = extractErrorMessage(status, res.getBody());
                            sendErrorEvent(emitter, detail);
                            return null;
                        }

                        // Read the SSE stream line-by-line and forward each delta token
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {

                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("data: ")) {
                                    String payload = line.substring(6).strip();
                                    if (SSE_DONE_SENTINEL.equals(payload)) {
                                        emitter.send(SseEmitter.event().name("done").data(""));
                                        break;
                                    }
                                    try {
                                        OpenAiStreamChunk chunk = objectMapper.readValue(payload, OpenAiStreamChunk.class);
                                        if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                                            String delta = chunk.choices().get(0).delta().content();
                                            if (delta != null && !delta.isEmpty()) {
                                                emitter.send(SseEmitter.event().name("token").data(delta));
                                            }
                                        }
                                    } catch (Exception parseEx) {
                                        log.debug("Skipping unparseable SSE chunk: {}", payload);
                                    }
                                }
                            }
                        } catch (IOException ioEx) {
                            log.warn("IO error reading upstream SSE stream", ioEx);
                            sendErrorEvent(emitter, "Connection to the upstream chat API was interrupted.");
                        }
                        return null;
                    });

            emitter.complete();

        } catch (ResourceAccessException ex) {
            log.warn("Timed out or failed to reach upstream chat API (streaming)", ex);
            sendErrorEvent(emitter, "Timed out reaching the upstream chat API. Please try again.");
        } catch (Exception ex) {
            log.error("Unexpected error during streaming chat completion", ex);
            sendErrorEvent(emitter, "Something went wrong. Please try again.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void sendErrorEvent(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (IOException ignored) {
            emitter.completeWithError(new RuntimeException(message));
        }
    }

    private String extractErrorMessage(HttpStatusCode status, java.io.InputStream body) {
        try {
            OpenAiErrorResponse parsed = objectMapper.readValue(body, OpenAiErrorResponse.class);
            if (parsed.error() != null && parsed.error().message() != null) {
                return parsed.error().message();
            }
        } catch (Exception ignored) {
            // Fall through to a generic message; we never surface raw upstream bytes.
        }
        return "The upstream API returned an error (HTTP " + status.value() + ").";
    }
}
