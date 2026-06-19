# tellev Architecture

## Non-Negotiable Decisions

- The app is native Android, not a WebView wrapper around SillyTavern.
- It does not run local LLMs. Providers are remote APIs or user-hosted LAN services.
- Data compatibility uses SillyTavern file formats as the source of truth.
- SQLite/Room may be added only as a rebuildable index/cache.
- Third-party frontend extensions run in an isolated JS host and talk through bridges.
- Node server plugins are not executed in-process. They must be ported to `NativePluginApi`.
- Public releases must remain AGPL-3.0 and clearly attribute SillyTavern.

## Layers

### UI Layer

Compose screens live under `feature/*` and `ui/*`.

The main shell is `TellevRoot`. It owns navigation shape only. Feature screens
must talk to use cases or core services, not directly to file paths or provider
HTTP payloads.

### Storage Layer

`StDataStore` is the stable boundary for SillyTavern-compatible files.

`FileStDataStore` owns:

- directory bootstrapping
- raw JSON preservation
- import/export paths
- compatibility parsing
- future PNG character-card metadata parsing

Do not hide SillyTavern fields just because tellev does not understand them yet.
Preserve unknown fields in `raw`.

### Prompt Layer

`PromptEngine` converts app state into provider-ready prompt messages.

`DefaultPromptEngine` is deliberately small. Future work must port SillyTavern
prompt assembly behavior here: lorebook depth, insertion order, instruct mode,
reasoning blocks, tool calling, macros, group chat ordering, and tokenizer-aware
budgeting.

### Provider Layer

Every remote backend implements `ProviderAdapter`.

Adapters should normalize:

- status checks
- model listing
- streaming chunks
- cancellation
- retryable errors
- provider-specific preset fields

`OpenAiCompatibleAdapter` is the first concrete adapter and should become the
template for Anthropic, Gemini, OpenRouter, Kobold, TextGen, Ollama, NovelAI,
Horde, image, speech, translation, search, and vector providers.

### Extension Layer

`ExtensionHost` is the compatibility boundary for SillyTavern-style frontend
extensions.

`WebViewJsExtensionHost` is the sandboxed implementation. Each extension runs
in its own WebView with a `tellevNative` bridge plus a **SillyTavern / 酒馆助手
compatibility shim** that exposes the globals real frontend extensions and
JS-Slash-Runner scripts depend on: `SillyTavern`, `getContext`, `eventSource`,
`event_types`, `TavernHelper`, `executeSlashCommandsWithOptions`,
`executeSlashCommands`, and a `fetch` override routing same-origin `/api/`
requests through the native virtual API.

Implemented compatibility scope:
- event bus (`eventSource` + `event_types`) mirroring `StEventCatalog`
- `SillyTavern.getContext()` backed by a pluggable `ExtensionContextProvider`
- `TavernHelper` script / variable / message helpers
- slash commands (single + batched) with autocomplete metadata
- virtual `/api/` routing to `StDataStore`, providers, and secrets
- per-extension settings, capabilities, and permission gating
- async permission request / grant flow closed through the UI layer

Out of scope (do not assume these work): arbitrary Node server plugins,
direct filesystem access from JS, non-`/api` network fetches (blocked by CSP),
and in-place chat-message field mutation (returns 501; owned by the UI layer).

The host is assembled in `TellevGraph` and wired into `ExtensionsViewModel`,
which scans the `extensions/` directory for installed extensions and drives
real `load` / `unload` through the host.

### Plugin Layer

`NativePluginApi` is the replacement for server-side Node plugins. It must stay
permissioned and explicit. Do not add arbitrary code execution.

## Data Root

The default app-private SillyTavern-compatible data root is:

```text
context.filesDir/st-data/
```

The directory names intentionally mirror SillyTavern's `USER_DIRECTORY_TEMPLATE`.

