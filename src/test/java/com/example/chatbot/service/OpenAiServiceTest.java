package com.example.chatbot.service;

import com.example.chatbot.config.OpenAiProperties;
import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.ChatResponse;
import com.example.chatbot.exception.UpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiServiceTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private OpenAiService serviceFor(String apiKey) {
        OpenAiProperties properties = new OpenAiProperties(
                server.url("/").toString(), apiKey, "test-model",
                Duration.ofSeconds(5), Duration.ofSeconds(5));

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();

        return new OpenAiService(restClient, properties, new ObjectMapper());
    }

    @Test
    void returnsReplyOnSuccess() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "id": "chatcmpl-1",
                      "model": "test-model",
                      "choices": [
                        {"index": 0, "message": {"role": "assistant", "content": "Hello there"}, "finish_reason": "stop"}
                      ]
                    }
                    """));

        ChatResponse response = serviceFor("test-key").getChatCompletion(new ChatRequest("Hi", null));

        assertThat(response.reply()).isEqualTo("Hello there");
    }

    @Test
    void throwsUpstreamExceptionWhenApiKeyMissing() {
        assertThatThrownBy(() -> serviceFor("").getChatCompletion(new ChatRequest("Hi", null)))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void mapsUpstream401ToBadGateway() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\": {\"message\": \"Incorrect API key provided\"}}"));

        assertThatThrownBy(() -> serviceFor("bad-key").getChatCompletion(new ChatRequest("Hi", null)))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("credentials");
    }
}
