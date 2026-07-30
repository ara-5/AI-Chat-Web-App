package com.example.chatbot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "openai.base-url=http://localhost:1",
        "openai.api-key=test-key",
        "openai.model=test-model"
})
class ChatbotApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context wires up correctly with the required
        // openai.* configuration properties present.
    }
}
