package com.example.chatbot.controller;

import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.ChatResponse;
import com.example.chatbot.service.OpenAiService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class ChatController {

    private final OpenAiService openAiService;

    // Virtual-thread executor (Java 21+) — zero overhead for blocking I/O;
    // each streaming request runs on its own virtual thread so Tomcat's
    // carrier threads are never pinned during the upstream read.
    private final ExecutorService streamingExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(OpenAiService openAiService) {
        this.openAiService = openAiService;
    }

    /**
     * Non-streaming endpoint — returns the full assistant reply as JSON.
     * Kept intact for backward compatibility and test coverage.
     */
    @PostMapping("/api/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = openAiService.getChatCompletion(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Streaming endpoint — returns an SSE stream of token delta events.
     *
     * Event types emitted:
     *   token  — one content delta (may be multiple characters)
     *   done   — stream is complete; frontend should finalise the message
     *   error  — upstream or configuration failure; data is the error message
     *
     * The upstream call is made on a virtual thread so the Tomcat carrier
     * thread is freed immediately after the SseEmitter is returned.
     */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request) {
        // 90-second timeout matches the read timeout on the RestClient,
        // giving the upstream API plenty of time for very long responses.
        SseEmitter emitter = new SseEmitter(90_000L);

        emitter.onTimeout(emitter::complete);
        emitter.onError(ex -> emitter.complete());

        streamingExecutor.execute(() -> openAiService.streamChatCompletion(request, emitter));

        return emitter;
    }
}
