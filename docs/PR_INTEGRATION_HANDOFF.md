# PR #11 / #12 integration

## Baselines

- Mainline: `9f2f19f553772e668cf08502f3723e46974006e2` (1.7.1.6).
- PR #11: `e6a8318490e92d1d2861723093430b77957d63fd`, based on 1.7.1.2.
- PR #12: `6b27c58a2ced7333b1e6c5a74f6399a2c53c54a2`, separate UI rebuild lineage.
- Integration runs in an isolated worktree; the local uncommitted creation changes remain outside this integration.

## PR #11

Select only the script-closing escape correction from the author's patch. `Regex.replace` interprets the replacement's backslash; `Regex.escapeReplacement` preserves the intended JavaScript escape. This does not introduce new script consent, network policy, prompt semantics or storage behavior.

- Reproduction against mainline: `ScriptSourceEscapingTest`, 3 cases, 2 failures.
- After selected correction: the same 3 cases pass, including decoded string value and untouched ordinary JavaScript.
- Merge history retains the original PR head as a parent; the resulting tree includes only this correction and its validation.

## PR #12

Selectively integrate the author's DSH theme, composer, icons and session drawer with the current mainline routes and renderer. Settings → Appearance → Interface style offers Classic / DSH; Classic remains the default. Both styles respect Light / Dark / System. The classic accent choice stays available in Classic, and its value survives switching to DSH and back.

Source provenance:

- DSH theme: PR #12 head `6b27c58`.
- Composer and icons: the author's UI follow-up commit `6243768`.
- Drawer: the author's initial UI commit `acde811`, adapted to mainline's session summary and supported actions.
- The merge retains the original PR #12 head as its second parent. Its other backend, provider, prompt, storage and creation changes are excluded from the resulting tree.

Integration details:

- Share the existing ChatViewModel, generation lifecycle, message renderer, card frontend and MVU bridge. Pass the current chat state explicitly through the drawer's content slot.
- Preserve drafts and pending attachments when navigation saves the chat page. Rendering helpers, session ownership and script-facing message semantics remain on mainline.
- Keep session switching/deletion and image generation/cancellation on their existing callbacks. Opening the current session does not stop an active generation. Drawer Back closes the drawer before leaving the conversation.
- Give Send / Stop separate accessibility identities so screen readers observe the same action as the visible button.
- The frontend lifecycle test waits for the destination document and checks old runtime rejection; it supports the renderer's existing native WebView reuse instead of requiring a different Java object.

## Final validation

- Clean build: `:app:clean :app:testDebugUnitTest :app:lintDebug :app:assembleMvuValidation :app:assembleMvuValidationAndroidTest` succeeded; all 92 tasks executed.
- JVM: 1100 cases, 1098 passed, 2 external-fixture cases skipped, 0 failures/errors.
- Lint: 0 errors. Existing warnings are recorded in the local report.
- JavaScript compatibility: 42 / 42 passed against the integration worktree's production assets.
- Android 14 emulator: 20 selected cases, 19 passed and 1 optional supplied-card replay skipped; runner result `OK (20 tests)`. Covers persisted style/dark-mode selection, draft retention, drawer Back/switch/delete, generation during style changes, completed stream display, Stop cancellation, original navigation, both styles' frontend persistence/stale-runtime rejection, mixed rendering, panel sizing/input and script hosting.
- Source comparison against `9f2f19f`: provider, prompt, creation and compatibility assets remain unchanged. Application ID, versionCode 48, versionName 1.7.1.6 and channel/build configuration remain unchanged.
- `git diff --check` passed.

Validation uses an Android emulator and a controlled provider. Physical-device and live-provider acceptance were not performed. No release tag or APK publication is part of this integration.
