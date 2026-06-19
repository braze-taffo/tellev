# AI Implementation Tasks

Use this file as the work queue for lower-cost agents. Keep changes inside the
listed modules and add tests for each task.

> **Status note (updated):** Sections 1–2 are backed by unit tests under
> `app/src/test`. Sections 3–6 are implemented in source but their test
> coverage is partial or missing — checkboxes below now reflect reality
> rather than intent. Add the missing tests before striking any remaining
> `[ ]`.

## 1. Storage Compatibility

- [x] Implement PNG/WebP character-card metadata parsing and writing.
- [x] Port SillyTavern character import/export behavior for JSON, PNG, CHARX, and BYAF.
- [x] Parse SillyTavern chat JSONL into typed `ChatMessage` while preserving unknown fields.
- [x] Implement world book parsing including entry flags, priorities, depth, order, and selective keys.
- [x] Implement ZIP backup export/import with path traversal protection.
- [x] Add golden tests using fixtures copied from the SillyTavern baseline.

## 2. Prompt Compatibility

- [x] Port macros: `{{char}}`, `{{user}}`, date/time, random, variables, and extension-provided macros.
- [x] Port instruct mode and context templates.
- [x] Port world info insertion rules and tokenizer-aware context budgeting.
- [x] Port group chat speaker ordering and character-specific examples.
- [x] Add golden tests comparing generated provider payloads against the SillyTavern baseline.

## 3. Provider Adapters

- [x] Finish OpenAI-compatible streaming, cancellation, tool calling, vision, logprobs, and reasoning fields.
- [x] Add Anthropic, Gemini/Google, OpenRouter, NovelAI, Kobold/KoboldCpp, TextGen/Ooba, Ollama, llama.cpp server, Horde, Azure.
- [x] Add image generation adapters for Stable Diffusion-compatible APIs and OpenAI-compatible image APIs.
- [x] Add speech, transcription, translation, search, and embeddings/vector adapters.
- [ ] Add mock-server contract tests for each provider. (partial: only `OpenAiCompatibleAdapterTest` exists)

## 4. Extension Compatibility

- [x] Implement SillyTavern event names and payload shapes.
- [x] Implement slash command registration, autocomplete metadata, execution results, and command piping.
- [x] Implement extension settings storage under the SillyTavern-compatible extension directory.
- [x] Implement virtual `/api/*` endpoints for storage, characters, chats, world info, settings, secrets, and providers.
- [ ] Implement permission prompts for network, storage, provider requests, clipboard, and UI panels. (auto-grant on install + async request/grant flow exist; interactive UI prompts not yet)
- [ ] Add unit tests for the extension layer (`VirtualApiRouter` routing, permission gating, slash-command round-trip, `WebViewJsExtensionHost` shim).

## 5. Native UI

- [x] Replace placeholder screens with production Compose screens.
- [x] Chat: swipes, regenerate, edit, delete, bookmark, attachments, streaming, stop generation, retry.
- [x] Characters: list, tags, folders, import, edit, duplicate, export.
- [x] World: world books, entries, search, activation preview.
- [x] Settings: provider setup, presets, secrets, themes, backups, extension management.
- [x] Tablet: two-pane chat and three-pane management layouts.
- [ ] Add screenshot tests for small phone, large phone, foldable, tablet, dark mode, and long text.

## 6. Security and Compliance

- [x] Add source-code link and AGPL notice screen.
- [x] Add privacy/export warnings for backups and logs.
- [x] Keep secrets in `SecretStore`; never write API keys to plain JSON exports unless the user explicitly chooses that.
- [x] Harden `WebViewJsExtensionHost` with origin checks, capability tokens, and per-extension permissions.
- [x] Add dependency/license report generation before public release.
- [ ] Restrict cleartext traffic to loopback only via `network_security_config.xml` and document the LAN-provider limitation. (config added; LAN HTTPS guidance pending)
