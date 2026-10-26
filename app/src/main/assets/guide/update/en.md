<!-- release-version: 1.7.1.8 -->

## 1.7.1.8: More stable complex cards

Repeated script reads and multiple worldbooks use less memory, improving stability on complex character card setup pages.

<!-- where -->
- Chat → Select a character → Card view

<!-- steps -->
1. Keep using your existing cards, presets and worldbooks; no reimport is needed.
2. Open the card interface and use the card's controls to enter information or change options.
3. The optimization applies automatically without changing scripts or disabling card features.

## Faster worldbook reads

Scripts requesting one worldbook now query that book alone, reducing unrelated text transfers and repeated parsing. Existing entries, bindings and script APIs remain available.

<!-- where -->
- Worldbooks → An existing worldbook
- Chat → A card or script that uses worldbooks

<!-- steps -->
1. View and edit worldbooks as usual; scripts can read saved changes.
2. Keep your existing character and chat worldbook bindings without reconfiguring them.
3. Keep multiple reference books available; full data reads by complex scripts can still take time.

## AI creation follows your edits

The creation assistant edits the requested scope, preserves established details and asks fewer repeated questions. Character drafts read linked references without automatically rewriting worldbook entries.

<!-- where -->
- Characters or Worldbooks → AI Create
- Settings → About → Update guide

<!-- steps -->
1. Specify a field or passage to edit, and optionally provide a writing sample or style preferences.
2. Choose to generate a draft or let AI decide to write from the information available; major conflicts still prompt a question.
3. In one-entry-at-a-time worldbook discussions, confirm each proposal before it is written. Review the draft before saving.
