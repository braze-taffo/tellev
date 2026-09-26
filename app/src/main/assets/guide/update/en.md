<!-- tellev update guide. Format: `## ` starts a page; `<!-- where -->` is followed by entry points,
     `<!-- steps -->` by usage steps. Translate the prose only — never touch the `## ` lines or any
     `<!-- -->` marker in this file. -->

## AI co-creation

Let the AI turn a one-line idea into a usable character card or world book. It can ask questions first to pin the setting down, or hand you a first draft straight away.

<!-- where -->
- Characters tab, top bar: "AI Create"
- World Books tab, top bar: "AI Create"
- Character list, long-press a card: "AI Edit", "Write world book from this card"

<!-- steps -->
1. On the Characters or World Books tab, tap "AI Create" in the top bar and describe what you want in one sentence. Not sure yet? Pick "Start guided creation" and let it ask you step by step.
2. Say what you want changed right in the chat. The "Advanced assets" and "Frontend preview" tabs show the scripts and UI drafts it wrote (new scripts stay off until you enable them).
3. For a world book you can paste a long source text: it extracts entries paragraph by paragraph, and you can resume the extraction later instead of starting over.
4. When you are done, tap "Save to character list" or "Save to world books", or use "Export JSON" / "Export PNG card" (PNG export needs a cover, so pick one first).

## Long-term memory

The AI extracts key setting and plot points from your chats and stores them, then feeds them back into later turns, so long conversations stop forgetting what came before.

<!-- where -->
- Extensions → Long-term memory (master switch and "Model & retrieval settings")
- Chat page, "More options" → "Long-term memory…"

<!-- steps -->
1. First open Long-term memory on the Extensions tab and give it a model under "Model & retrieval settings" (reuse an existing connection, or fill in a separate URL and key).
2. Open a chat and tap "More options → Long-term memory…". The first time you choose a memory mode for that chat; it cannot be changed afterwards, so choose deliberately.
3. Just keep chatting — memories are extracted automatically. On the memory screen you can show "Active memories only" and "Correct memory" or delete single entries.
4. Use "Backfill history" for older chats. If editing the chat invalidates old results, run "Rebuild vector index".

## Card scripts are more compatible

Cards that ship Tavern Helper scripts — variable frameworks, status bars, battle overlays — now get much wider support: world book reads and writes, chat message writes, regex scripts and template functions really take effect, and a failing script no longer takes the whole app down.

<!-- where -->
- Chat top bar: "Card view" (only appears when the card ships UI scripts)
- Extensions → Built-in compatibility modules (Tavern Helper compatibility / Character card regex / Character card world book)
- Extensions → Tavern Helper settings, EJS prompt template settings

<!-- steps -->
1. Import a card with scripts, open the chat and wait for it to finish initialising (cards with many scripts can take up to a minute and a half).
2. Tap "Card view" in the top bar to open the script panel full screen; its state survives closing it.
3. If a script does nothing, check on the Extensions tab that "Tavern Helper compatibility" is on before reporting it.

## Four interface languages

The interface can be switched between 简体中文 / English / 日本語 / 한국어, or follow the system language.

<!-- where -->
- Settings → Language

<!-- steps -->
1. On the Settings tab, find "Language" and pick the one you want — the interface switches immediately.
2. Pick "Follow system" to track your phone's language.
3. Only the interface is translated; prompts sent to the model and already saved data are left as they are.

## Lighter on memory, fewer freezes

With many world book entries or creation drafts (a couple of hundred, say), opening the World Books tab or the creation editor used to stutter or even crash. This release addresses that specifically.

<!-- where -->
- No setup needed — applies to the World Books tab and the creation editor automatically

<!-- steps -->
1. Nothing to do: the world book list now loads summaries only, and the world book panel inside the creation editor loads on demand.
2. Chat fixes came along too: an empty reply from your provider now raises a clear error instead of leaving an empty bubble, and stopping a streaming reply drops the connection immediately.
3. If a large world book still stutters, report it from "Settings → About" with the entry count.

## Things worth knowing

A few behaviours that are easy to mistake for bugs, spelled out up front.

<!-- where -->
- "Settings → About" lets you reopen this guide at any time

<!-- steps -->
1. Tapping an option only fills the input draft — it never sends the message for you. Sending is always your call.
2. "Card view" may need a moment on first open while scripts initialise; the more scripts, the longer it takes.
3. /setinput inside the extension sandbox is still not implemented, and on-device image generation only exists in the separate image-gen build, not in the standard one.
