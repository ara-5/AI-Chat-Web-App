(() => {
  "use strict";

  // ── Configure marked (markdown parser) ──────────────────────────────────────
  marked.setOptions({
    gfm: true,     // GitHub-flavoured markdown
    breaks: true,  // single newlines → <br>
  });

  // ── DOM refs ─────────────────────────────────────────────────────────────────
  const form        = document.getElementById("chat-form");
  const input       = document.getElementById("message-input");
  const sendButton  = document.getElementById("send-button");
  const messagesEl  = document.getElementById("messages");
  const errorBanner = document.getElementById("error-banner");
  const clearButton = document.getElementById("clear-button");

  // ── Conversation state ───────────────────────────────────────────────────────
  // History is maintained client-side and replayed on every request so the
  // backend stays stateless. Excludes the in-flight message.
  let history = [];

  // ── Helpers ──────────────────────────────────────────────────────────────────

  function renderMarkdown(text) {
    // marked.parse() converts markdown → HTML.
    // DOMPurify.sanitize() strips dangerous tags/attributes before the HTML is
    // written to innerHTML — prevents XSS from malicious model responses.
    const rawHtml = marked.parse(text, { mangle: false, headerIds: false });
    return DOMPurify.sanitize(rawHtml);
  }

  function appendUserMessage(text) {
    const div = document.createElement("div");
    div.className = "message user";
    div.textContent = text; // user text is never parsed as markdown
    messagesEl.appendChild(div);
    scrollToBottom();
    return div;
  }

  function appendAssistantPlaceholder() {
    const div = document.createElement("div");
    div.className = "message assistant";
    div.innerHTML = `<span class="typing-indicator">
      <span></span><span></span><span></span>
    </span>`;
    messagesEl.appendChild(div);
    scrollToBottom();
    return div;
  }

  function finaliseAssistantMessage(el, fullText) {
    el.innerHTML = renderMarkdown(fullText);
    scrollToBottom();
  }

  function showError(message) {
    errorBanner.textContent = message;
    errorBanner.hidden = false;
  }

  function clearError() {
    errorBanner.hidden = true;
    errorBanner.textContent = "";
  }

  function setSending(isSending) {
    sendButton.disabled  = isSending;
    input.disabled       = isSending;
    clearButton.disabled = isSending;
  }

  function scrollToBottom() {
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function autoGrow() {
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 140) + "px";
  }

  // ── SSE streaming send ────────────────────────────────────────────────────────
  //
  // We use fetch() with method POST rather than the native EventSource API
  // because EventSource only supports GET requests and cannot send a JSON body.
  //
  // The response body is piped through TextDecoderStream to get a string reader,
  // then consumed line-by-line. Each SSE event is two lines:
  //   event: <name>
  //   data:  <payload>
  // followed by a blank line. We track the current event name across lines.

  async function sendMessageStreaming(text) {
    const response = await fetch("/api/chat/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message: text, history }),
    });

    if (!response.ok) {
      // Non-2xx before streaming starts (e.g. 400 validation error from Spring)
      const data = await response.json().catch(() => null);
      throw new Error(data?.message ?? `Request failed (HTTP ${response.status})`);
    }

    const placeholderEl = appendAssistantPlaceholder();
    let accumulated  = "";
    let isFirstToken = true;
    let currentEvent = null;
    let textBuffer   = "";

    // Pipe the response body through TextDecoderStream and read line-by-line.
    // response.body is consumed exactly once — no double-pipe, no locked-stream errors.
    const reader = response.body
      .pipeThrough(new TextDecoderStream())
      .getReader();

    try {
      while (true) {
        const { done, value } = await reader.read();

        if (done) {
          // Stream closed without a done event — treat accumulated text as complete.
          finaliseAssistantMessage(placeholderEl, accumulated);
          return accumulated;
        }

        textBuffer += value;

        // Split on newlines; keep the incomplete last fragment in the buffer.
        const lines = textBuffer.split("\n");
        textBuffer = lines.pop();

        for (const rawLine of lines) {
          const line = rawLine.trimEnd();

          if (line.startsWith("event: ")) {
            currentEvent = line.slice(7);

          } else if (line.startsWith("data: ")) {
            const data = line.slice(6);

            if (currentEvent === "token") {
              if (isFirstToken) {
                placeholderEl.innerHTML = ""; // clear typing indicator
                isFirstToken = false;
              }
              accumulated += data;
              // Live-render partial markdown as tokens stream in
              placeholderEl.innerHTML = renderMarkdown(accumulated);
              scrollToBottom();

            } else if (currentEvent === "done") {
              finaliseAssistantMessage(placeholderEl, accumulated);
              reader.cancel();
              return accumulated;

            } else if (currentEvent === "error") {
              placeholderEl.remove();
              reader.cancel();
              throw new Error(data);
            }

            currentEvent = null; // reset after each data line
          }
          // blank lines (SSE event separators) are ignored implicitly
        }
      }
    } catch (err) {
      reader.cancel().catch(() => {});
      throw err;
    }
  }

  // ── Form submit ───────────────────────────────────────────────────────────────

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const text = input.value.trim();
    if (!text) return;

    clearError();
    appendUserMessage(text);
    input.value = "";
    autoGrow();
    setSending(true);

    try {
      const reply = await sendMessageStreaming(text);
      // Push to history only after a successful round-trip
      history.push({ role: "user",      content: text  });
      history.push({ role: "assistant", content: reply });
    } catch (err) {
      showError(err.message || "Something went wrong. Please try again.");
    } finally {
      setSending(false);
      input.focus();
    }
  });

  // ── Clear conversation ────────────────────────────────────────────────────────

  clearButton.addEventListener("click", () => {
    if (history.length === 0 && messagesEl.childElementCount === 0) return;
    history = [];
    messagesEl.innerHTML = "";
    clearError();
    input.focus();
  });

  // ── Auto-grow textarea ────────────────────────────────────────────────────────

  input.addEventListener("input", autoGrow);

  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      form.requestSubmit();
    }
  });
})();
