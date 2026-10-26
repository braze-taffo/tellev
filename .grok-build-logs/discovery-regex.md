# Discovery: import standalone regex

Date: 2026-10-17. Branch: `feat/import-standalone-regex` (from `master`).
Scope of this note: storage, apply path, Extensions UI, SillyTavern JSON shape, and a 5-segment plan.
No product code in this segment.

Hard constraint for later segments: do not touch `NativePluginApi`, the Node plugin host, or how plugins/scripts are registered as extensions. Do not change `host.js` `getTavernRegexes` / `replaceTavernRegexes` scope checks (they reject `global` / `preset` / `all` on purpose so a script cannot silently write into the current card).

## 1. Where regex scripts live today

Three sources, only two of which production actually loads.

### Character card (scoped / "角色卡正则")

Source of truth: `data.extensions.regex_scripts` inside the card JSON (or the JSON embedded in a PNG/WebP). `CharacterRepository.saveCharacter` writes the card, then `EmbeddedAssetsCoordinator.saveEmbeddedCharacterAssets` mirrors the array to:

`extensions/character-assets/<characterId>/regex_scripts.json`

plus a count in `manifest.json`. Bootstrap rebuilds that mirror (`FileStDataStore.bootstrap` → `rebuildEmbeddedCharacterAssets`). The mirror is a derived copy. Toggles must write the card, not the mirror file. `saveCharacter` rewrites PNG/WebP metadata, so a card toggle mutates the imported image.

### Preset

`GenerationPreset.extensions.regex_scripts`. Imported with the preset JSON (`extensions` on the preset object). Settings only displays a count (`SettingsPresetSection`); there is no per-script switch.

### Legacy disabled-id sidecar (do not reuse)

`StDirectoryLayout.regexActivation` = `<data>/regex-activation.json`.

Shape:

```json
{ "disabled": { "<characterId>": ["scriptId", "..."] } }
```

`SettingsRepository.readDisabledRegexScriptIds` / `saveDisabledRegexScriptIds` still round-trip this map. On every bootstrap, `migrateLegacyRegexActivation` copies those ids into each card's `regex_scripts[].disabled` via `CharacterRegexApplier.withScriptEnabled(..., enabled = false)` and deletes the file when every write succeeds. Comment on `StDataStore`: retained only for that one-time migration. Empty sets are omitted.

`ExtensionsScreen` still comments that `ChatViewModel.selectCharacter` clears a character's disabled set. That is stale. `ChatViewModel` does not read or write regex activation.

### Global / standalone

`CharacterRegexApplier.RegexExecutionContext.globalScripts` exists and is concatenated first (`global + preset + card`). Production call sites never pass it. The only caller is `RegexLargeHtmlTest`. There is no on-disk global regex list.

`host.js` `displayRegex` (used by `formatAsDisplayedMessage`) already prepends `extension_settings.regex`, then preset `regex_scripts`, then the card. `WebViewJsExtensionHost.snapshotExtensionSettings` only copies per-extension `settings.json` objects. Nothing writes an `extension_settings.regex` array, so that JS path is also empty.

`ExtensionSettingsStore` persists `{extensionsDir}/{id}/settings.json` and `snapshotExtensionSettings` casts each file to a `JsonObject`. SillyTavern stores global scripts as the array `extension_settings.regex`, not an object. Do not drop that array into the settings store.

SillyTavern (release `engine.js`) keeps globals in `extension_settings.regex` (`SCRIPT_TYPES.GLOBAL`). Scoped scripts are `characters[chid].data.extensions.regex_scripts`. Preset scripts are the preset extension field `regex_scripts`. `getRegexScripts` walks `Object.values(SCRIPT_TYPES)`, which is insertion order GLOBAL (0), PRESET (2), SCOPED (1). Tellev's fold order matches that.

## 2. How they are applied

`app/src/main/java/app/tellev/core/regex/CharacterRegexApplier.kt`.

Placement constants: user input `1`, AI output `2`, world info `5`. Reasoning uses a forced placement `6` in `TavernMessageRenderer`. Slash-command placement `3` is not selected by any Kotlin path. `MD_DISPLAY` `0` is ignored.

Role → placement, unless `forcedPlacement` is set: `User` → 1, `Character`/`Assistant` → 2, anything else returns the text unchanged (so world-info and reasoning must pass the forced placement).

A script runs only when all of these hold:

- `disabled != true` (missing `disabled` means enabled)
- `placement` contains the current placement (`int` or array of ints)
- not an edit, or `runOnEdit == true`
- `depth` is within `minDepth` / `maxDepth` when those ints are present (`null` is ignored)
- phase matches the flags:
  - `Normal`: neither `markdownOnly` nor `promptOnly`
  - `Display`: `markdownOnly`
  - `Prompt`: `promptOnly`

Phase helpers run Normal first, then Display or Prompt (`includeNormal` can skip the first pass when the message is already `markNormalProcessed`).

Pattern fields, standard name wins:

- `findRegex`, else `regex`. Separate `flags` is used only when `findRegex` is absent (RP Hub). `findRegex` is split like ST `regexFromString`: last `/` delimiter, flags `gmixXsuUAJ` / compile set `dgimsuvy`.
- `replaceString`, else `replacement`.
- `trimStrings` (string array). The fixture field `trim` is not read.
- `substituteRegex`: `1` raw macros, `2` escaped macros, else the pattern is literal.
- `{{match}}` and `$n` / `$<name>` in the replacement. `$&` stays literal.

Stable id: `id` if non-blank, else `idx:<index>` (`scriptIdentifier`). `summarizeScripts` name: `scriptName`, else `name`, else `findRegex`, else the unnamed-script string. `enabled` is `disabled != true`.

`withScriptEnabled(card, scriptId, enabled)` flips that script's `disabled` inside the card and preserves every other field.

### Production call sites (all leave `globalScripts` empty)

- `ChatTextProcessing.process` → `applyNormal` (greeting / user / edit persistence)
- `PromptEngine` → `applyForPrompt` (history and current user input) and `applyWorldInfoForPrompt`
- `TavernMessageRenderer.renderMessageParts` → `applyForDisplay`, plus a direct `RegexExecutionContext` for reasoning at placement 6
- `ChatGenerationCoordinator` → `applyNormal` on an interrupted partial

The four `apply*` helpers build the context themselves and have no `globalScripts` parameter. `renderMessageParts` builds one context by hand for reasoning. A later segment must pass the array into both.

Do not read the file inside `apply`. Render runs per message. Load the array once at the chat/generation boundary and pass it down. The applier already refuses a process-wide singleton for macros; keep the same explicit style.

## 3. Extensions UI

`ExtensionsViewModel` built-in row `character-regex` is a locked description ("自动读取并应用角色卡内嵌 regex_scripts"). It is not a script host.

Below that, one card per `extensions/character-assets/<id>/` directory:

- chips: world book, regex count, TavernHelper counts
- expandable list from `regex_scripts.json` via `summarizeScripts`
- `Switch` calls `toggleRegexScript` → `readCharacter` → `withScriptEnabled` → `saveCharacter` → re-read assets

`ExtensionsUiState.disabledRegexScripts` is loaded from the legacy sidecar and passed into `CharacterAssetCard` as `disabledScriptIds`. The composable never reads that parameter. The switch uses `script.enabled` from the card mirror. After a successful toggle the ViewModel also sets `disabledRegexScripts` to `emptyMap()`.

Import pattern to copy: `WorldBooksListScreen` uses `ActivityResultContracts.GetContent()` launched with `"*/*"` (SAF MIME filters hide `.json` otherwise), reads bytes off the main thread, and hands them to the ViewModel. Put the same control on `ExtensionsScreen`, not on a plugin entry.

Strings live in `values/strings.xml`, `values-en`, `values-ja`, `values-ko`, and the fallback map in `S.kt`. New copy has to follow that.

## 4. SillyTavern standalone JSON

Locked against SillyTavern release `public/scripts/extensions/regex/index.js` and `char-data.js` `RegexScriptData` (fetched 2026-10-17).

Single-script export (`export_regex`): one object, pretty-printed, filename `regex-<sanitized scriptName>.json`.

```json
{
  "id": "uuid",
  "scriptName": "name",
  "findRegex": "/pattern/g",
  "replaceString": "replacement",
  "trimStrings": ["optional"],
  "placement": [1, 2],
  "disabled": false,
  "markdownOnly": true,
  "promptOnly": false,
  "runOnEdit": true,
  "substituteRegex": 0,
  "minDepth": null,
  "maxDepth": null
}
```

`minDepth` / `maxDepth` are `parseInt` of the inputs, so an empty box becomes `null` (NaN does not survive `JSON.stringify`). `placement` values: 1 user, 2 AI, 3 slash, 5 world info, 6 reasoning. `0` is deprecated.

Bulk export (`bulk_export_regex`): a raw JSON **array** of those objects, not `{ "scripts": [...] }`.

Import (`onRegexImportFileChange`): parse JSON; if it is an array, import each element, otherwise import the object. `onRegexImportObjectChange` requires `scriptName`, then **replaces `id` with a new UUID**, appends to the selected list (global by default in the UI), and saves. It does not require `findRegex` at import time (`saveRegexScript` only warns). Tellev's creation tool is stricter: `upsert_regex` requires `scriptName`, `findRegex`, and a non-empty `placement` array, and only allows the ST field set above (`CreationAdvancedAssets`).

### What the fixtures actually are

`app/src/test/resources/qunxing-regex.json` is **not** a standalone ST export. It is a partial card:

`{ "data": { "name", "extensions": { "regex_scripts": [ ... ] } } }`

Each script uses RP Hub names the applier already accepts at runtime: `name`, `regex`, `flags`, `replacement`, plus `placement`, `markdownOnly`, `promptOnly`, `minDepth`, `maxDepth`, `disabled`. No `id`, no `scriptName`, no `findRegex`.

`fixtures/xiangcun-greeting.json` is a full v3 card. Scripts use ST names, but `trim` instead of `trimStrings` (the applier ignores `trim`).

`tools/message-host-eval/fixtures/dream-big-discuss-rule.json` is one ST-shaped object (`scriptName`, `findRegex`, `replaceString`, `markdownOnly`, `runOnEdit`) plus a harness `_note`. Closest fixture to a standalone export.

`CharacterRegexApplier` already runs both shapes. Import should store one normalized ST object so later toggles and `summarizeScripts` see `id` + `scriptName`. Preserve unknown fields. Assign a new id on import, matching ST, so a re-import does not collide.

Reject a character-card document (`spec` / `data.extensions.regex_scripts`) in v1. That file is a card import, not a standalone script. Accept:

1. one ST (or RP Hub) script object
2. a JSON array of those objects (ST bulk export)

Require a non-blank name (`scriptName` or `name`) and a non-blank pattern (`findRegex` or `regex`). Placement may be empty (ST allows it; the script simply never matches). Do not invent a `{ "scripts": [...] }` wrapper; ST does not write one.

## 5. Safest import entry

New tellev-owned file at the data root, same idea as `regex-activation.json` but a different file and a different shape:

`<data>/global-regex.json` = a JSON array of script objects.

Reasons:

- `globalScripts` is already the apply slot and already runs before preset and card.
- Enable/disable is the script's own `disabled` flag, which `apply` already honors. No second disabled-set.
- Do not write the import into the open character card or its PNG.
- Do not revive `regex-activation.json`.
- Do not register an extension or call `NativePluginApi`.
- Do not route the write through `replaceTavernRegexes`.

Toggle = flip `disabled` on that array element and rewrite the file (a small sibling of `withScriptEnabled`, on a `JsonArray` instead of a card). UI lists the array with the existing `summarizeScripts` + `Switch`. Import button on the Extensions screen, `GetContent("*/*")`.

Optional later, not required for chat: when building `getContext()`, also set `extension_settings.regex` to this array so `formatAsDisplayedMessage` matches native rendering. That is context assembly, not plugin registration. Skip it if it forces `snapshotExtensionSettings` to accept a non-object.

## 6. Later segments

Each segment is one commit. Tests are unit tests. No plugin/script entry changes in any segment.

1. **Parse only.** `core/regex` function: text → `List<JsonObject>` in ST field shape (`scriptName`, `findRegex`, `replaceString`, new `id`, RP Hub aliases copied across, other fields kept). Tests: ST object, ST array, RP Hub object (`qunxing` element), reject card wrapper / missing name / missing pattern. No disk, no UI, no apply change.

2. **Persist.** `StDirectoryLayout` + `SettingsRepository` + `StDataStore`: read array, append imported scripts, toggle `disabled` by id. File `global-regex.json`. Round-trip test next to the existing regex-activation tests. Do not migrate the legacy sidecar and do not write cards.

3. **Apply.** Add an optional `globalScripts` argument to `applyForDisplay` / `applyNormal` / `applyForPrompt` / `applyWorldInfoForPrompt` (default empty, so current tests stay valid). Pass the loaded array from `ChatTextProcessing`, `PromptEngine`, `TavernMessageRenderer` (including the reasoning context), and `ChatGenerationCoordinator`. Load once per generation/render, not per script. One applier test: global runs before preset and card; `disabled: true` is skipped.

4. **Extensions UI.** A "全局正则" block: list, switch, import. Reload the chat-side copy when the screen writes (same `LaunchedEffect` refresh idea the asset list already uses). Strings in the four `values*` files and `S.kt`.

5. **JS display parity, only if segment 4 is in and still small.** Put the same array on `extension_settings.regex` inside the tavern context object so `host.js` `displayRegex` sees it. Do not change the tavern regex get/replace scope guard.
