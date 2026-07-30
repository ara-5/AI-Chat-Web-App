package com.example.chatbot.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Thrown when the upstream OpenAI-compatible API returns an error, times
 * out, or is unreachable. Carries the HTTP status we should reflect back
 * to the frontend, and a human-readable, non-sensitive message (never the
 * raw upstream body, which could include request internals).
 */
public class UpstreamException extends RuntimeException {

    private final HttpStatusCode statusCode;

    public UpstreamException(HttpStatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public UpstreamException(HttpStatusCode statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }
}
