<!-- release-version: 1.7.1.9 -->

## 1.7.1.9: Import regex files directly

Import regex JSON exported from SillyTavern without reimporting a character card. Both a single rule and an array of rules are supported.

<!-- where -->
- Extensions → Import regex

<!-- steps -->
1. Tap "Import regex", choose a JSON file and review rule names, count and enabled states.
2. Choose "Global" to use the rules across all characters, or choose "Character" and search for the target character.
3. Tap "Import". Global rules can be imported even when you have no characters yet.

## Same-name replacement and import checks

Rules with the same name in the selected scope are replaced; other rules are kept. Enabled states come from the file. Duplicate names or invalid entries reject the whole batch with an explanation.

<!-- where -->
- Extensions → Import regex → Preview and scope selection

<!-- steps -->
1. Check the target scope before importing; same-name replacement affects only that scope.
2. If duplicate names cannot be matched uniquely in the file or target scope, resolve the conflict and import again.
3. Check the added and replaced counts after import. Files must be 12 MB or smaller.

## Switch rules on or off, or delete them

Global rules have their own list. Character rules remain in the character asset cards. Rules and their enabled states are included in backups.

<!-- where -->
- Extensions → Global regex
- Extensions → Character assets → Expand regex scripts

<!-- steps -->
1. Use the switch beside a rule to enable or disable it. Rules run in global, preset, then character order.
2. Tap the delete icon and confirm to remove only that rule.
3. Display-rule changes refresh the chat. Saved messages do not run normal replacements again.
