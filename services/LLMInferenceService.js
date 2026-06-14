class LLMInferenceService {
  constructor() {
    this.activeStreams = new Map();
  }

  async queryComplete(prompt, model, systemPrompt = '') {
    const response = await fetch('http://localhost:11434/api/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model,
        prompt,
        system: systemPrompt,
        stream: false
      })
    });

    if (!response.ok) {
      throw new Error(`HTTP error ${response.status}`);
    }

    const data = await response.json();
    return data.response;
  }

  async streamToWindow(window, requestId, prompt, model, systemPrompt = '') {
    const abortController = new AbortController();
    this.activeStreams.set(requestId, abortController);

    let fullResponse = '';

    try {
      const response = await fetch('http://localhost:11434/api/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model,
          prompt,
          system: systemPrompt,
          stream: true
        }),
        signal: abortController.signal
      });

      if (!response.ok) {
        throw new Error(`HTTP error ${response.status}`);
      }

      if (!response.body) {
        throw new Error('Streaming body unavailable');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed) continue;

          let payload;
          try {
            payload = JSON.parse(trimmed);
          } catch (parseErr) {
            continue;
          }

          if (payload.response) {
            fullResponse += payload.response;
            if (window && !window.isDestroyed()) {
              window.webContents.send('llm:stream-chunk', {
                requestId,
                token: payload.response,
                done: false
              });
            }
          }

          if (payload.done) {
            if (window && !window.isDestroyed()) {
              window.webContents.send('llm:stream-chunk', {
                requestId,
                token: '',
                done: true,
                fullResponse
              });
            }
            return fullResponse;
          }
        }
      }

      if (window && !window.isDestroyed()) {
        window.webContents.send('llm:stream-chunk', {
          requestId,
          token: '',
          done: true,
          fullResponse
        });
      }

      return fullResponse;
    } catch (error) {
      if (error.name === 'AbortError') {
        if (window && !window.isDestroyed()) {
          window.webContents.send('llm:stream-chunk', {
            requestId,
            token: '',
            done: true,
            cancelled: true,
            fullResponse
          });
        }
        return fullResponse;
      }

      if (window && !window.isDestroyed()) {
        window.webContents.send('llm:stream-chunk', {
          requestId,
          token: '',
          done: true,
          error: error.message,
          fullResponse
        });
      }

      throw error;
    } finally {
      this.activeStreams.delete(requestId);
    }
  }

  cancelStream(requestId) {
    const controller = this.activeStreams.get(requestId);
    if (controller) {
      controller.abort();
      this.activeStreams.delete(requestId);
      return true;
    }
    return false;
  }
}

module.exports = {
  LLMInferenceService
};
