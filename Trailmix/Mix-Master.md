# Mix-Master

> Offline AI chat over meeting context and Trail history. Streaming tokens via Ollama. No cloud.

## Surfaces

| Surface | Scope |
|---|---|
| Hub drawer + global ask bar | `scope: 'hub'` — history summaries + optional opened session transcript |
| Meeting chat widget | `scope: 'meeting'` — current transcript + notes first; history secondary |

## IPC

| Channel | Role |
|---|---|
| `chat:query` | Start stream; returns `{ requestId }` |
| `llm:stream-chunk` | Tokens / done / error |
| `llm:cancel` | Stop generation (meeting Stop button) |
| `chat:get-recipes` | Recipe pills |
| `chat:mix-enhance` | The Mix blend (see [[The Mix]]) |

Hub clients should pass object payloads:

```js
window.api.chatQuery({ query, sessionId: openedSessionId, scope: 'hub' })
```

Main resolves transcript via `resolveTranscriptForChat` (DB/memory) — **do not** ship empty `"No transcript active."` strings from the hub.

## Context assembly (`register-all` CHAT_QUERY)

| Scope | Context |
|---|---|
| Hub | Up to ~20 recent session summaries/actions (from last 40 by mtime) |
| Meeting | Current transcript + notes; recent 7-day titles as secondary |
| Recipes | Structured prompts from `lib/chat-recipes.js`; stricter system prompt |

Transcript text for enrichment/chat is truncated (~48k chars, tail-weighted) where applicable.

## LLM service

`services/LLMInferenceService.js`

- `queryComplete` — timeouts/retries
- `streamToWindow` — rAF-friendly chunk delivery to renderer
- Target: `http://localhost:11434/api/generate`
- Model from `settings.selectedLlm`

## UX notes

- Closing Mix-Master drawer must **not** wipe chat history
- Meeting streams batch DOM updates with `requestAnimationFrame`
- Toasts for errors (no `alert()`)

## Related

[[The Mix]] · [[The Trail]] · [[Settings & Configuration]] · [[Processing Pipeline]]
