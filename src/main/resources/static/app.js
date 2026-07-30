(() => {
  "use strict";

  // ── Configure marked (markdown parser) ──────────────────────────────────────
  marked.setOptions({
    gfm: true,       // GitHub-flavoured markdown
    breaks: true,    // Single newlines become <br>
  });

  // ── DOM refs ─────────────────────────────────────────────────────────────────
  const form        = document.getElementById("chat-form");
  const input       = document.getElementById("message-input");
  const sendButton  = document.getElementById("send-button");
  const messagesEl  = document.getElementById("messages");
  const errorBanner = document.getElementById("error-banner");
  const clearButton = document.getElementById("clear-button");

  // ── Conversation state ───────────────────────────────────────────────────────
  // History is maintained client-side and sent with every request so the
  // backend stays stateless. Excludes the in-flight message.
  let history = [];

  // ── Helpers ──────────────────────────────────────────────────────────────────

  function renderMarkdown(text) {
    // 1. marked.parse() converts markdown to HTML.
    // 2. DOMPurify.sanitize() strips any dangerous tags/attributes before
    //    the HTML is injected into the DOM via innerHTML — prevents XSS if
    //    the upstream model returns malicious HTML in its response.
    const rawHtml = marked.parse(text, { mangle: false, headerIds: false });
    return DOMPurify.sanitize(rawHtml);
  }

  function appendUserMessage(text) {
    const div = document.createElement("div");
    div.className = "message user";
    div.textContent = text;           // user text is never parsed as markdown
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

  // ── Streaming send ────────────────────────────────────────────────────────────

  async function sendMessageStreaming(text) {
    return new Promise((resolve, reject) => {
      // POST the chat request and receive an SSE stream back.
      // We use fetch rather than EventSource because EventSource only supports GET.
      fetch("/api/chat/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: text, history }),
      })
        .then((response) => {
          if (!response.ok) {
            // Non-2xx before any streaming starts (e.g. 400 validation error)
            return response.json().catch(() => null).then((data) => {
              reject(new Error(data?.message ?? `Request failed (HTTP ${response.status})`));
            });
          }

          const placeholderEl = appendAssistantPlaceholder();
          let accumulated     = "";
          let firstToken      = true;

          const reader  = response.body.getReader();
          const decoder = new TextDecoder();
          let   buffer  = "";

          // ReadableStream reader: parses the raw SSE bytes
          function pump() {
            reader.read().then(({ done, value }) => {
              if (done) {
                // Stream closed without a [DONE] sentinel — treat as complete.
                finaliseAssistantMessage(placeholderEl, accumulated);
                resolve(accumulated);
                return;
              }

              buffer += decoder.decode(value, { stream: true });

              // SSE lines are separated by "\n"; events by "\n\n"
              const lines = buffer.split("\n");
              buffer = lines.pop(); // keep incomplete last line

              for (const line of lines) {
                if (line.startsWith("event: error")) continue;   // handled next line
                if (line.startsWith("data: ")) {
                  const data = line.slice(6);
                  // error data line
                  if (buffer.includes("event: error") || previousLineWasError) {
                    finaliseAssistantMessage(placeholderEl, accumulated || "");
                    reject(new Error(data));
                    reader.cancel();
                    return;
                  }
                }
                if (line.startsWith("event: done")) {
                  finaliseAssistantMessage(placeholderEl, accumulated);
                  resolve(accumulated);
                  reader.cancel();
                  return;
                }
                if (line.startsWith("event: token")) {
                  // token data is on the next line — handled below
                }
              }

              // Simpler, more robust SSE parsing:
              // Re-parse accumulated buffer as named events
              pump();
            }).catch((err) => {
              reject(err);
            });
          }

          // ── Robust SSE event parser ────────────────────────────────────────
          // Use a TransformStream-based line-by-line approach for reliability.
          let currentEvent = null;
          let previousLineWasError = false;

          async function pumpLines() {
            const lineReader = response.body
              .pipeThrough(new TextDecoderStream())
              .pipeThrough(new TransformStream({
                transform(chunk, controller) {
                  for (const char of chunk) controller.enqueue(char);
                },
              }));

            // Collect characters into lines
            let lineBuffer = "";
            const lineStream = response.body
              .pipeThrough(new TextDecoderStream());

            const lineReaderStream = lineStream.getReader();

            let textBuffer = "";
            let isFirstToken = true;

            async function processChunk() {
              const { done, value } = await lineReaderStream.read();
              if (done) {
                finaliseAssistantMessage(placeholderEl, accumulated);
                resolve(accumulated);
                return;
              }

              textBuffer += value;
              const parts = textBuffer.split("\n");
              textBuffer = parts.pop();

              for (const rawLine of parts) {
                const line = rawLine.trimEnd();
                if (line.startsWith("event: ")) {
                  currentEvent = line.slice(7);
                } else if (line.startsWith("data: ")) {
                  const data = line.slice(6);
                  if (currentEvent === "token") {
                    if (isFirstToken) {
                      // Replace the typing indicator with empty content
                      placeholderEl.innerHTML = "";
                      isFirstToken = false;
                    }
                    accumulated += data;
                    // Live-render partial markdown as tokens arrive
                    placeholderEl.innerHTML = renderMarkdown(accumulated);
                    scrollToBottom();
                  } else if (currentEvent === "done") {
                    finaliseAssistantMessage(placeholderEl, accumulated);
                    resolve(accumulated);
                    return;
                  } else if (currentEvent === "error") {
                    placeholderEl.remove();
                    reject(new Error(data));
                    return;
                  }
                  currentEvent = null;
                }
              }

              await processChunk();
            }

            await processChunk();
          }

          pumpLines().catch(reject);
        })
        .catch(reject);
    });
  }

  // ── Form submit handler ───────────────────────────────────────────────────────

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
      // Only push to history after a successful round-trip
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
