# MVU differential validation tools

The implementation project is tellev. Sibling repositories are read-only fixed references. These commands do not publish a release or modify the production Android package.

## Build inputs

From tellev, run `npm --prefix tools/mvu ci`, `npm --prefix tools/mvu run build`, then `gradlew.bat :app:testDebugUnitTest` to export the actual legacy host HTML used by browser integration tests. `npm --prefix tools/mvu test` needs the user-owned workspace-root `card_4.7.json`. Never copy model credentials into test fixtures.

## Independent upstream environment

1. Run `python tools/mvu/prepare-oracle.py`. It extracts manifest-pinned Git revisions into ignored `build/mvu-oracle`; it refuses a conflicting marker.
2. Run `npm ci` from `build/mvu-oracle/SillyTavern`.
3. Run `node tools/mvu/start-oracle.mjs` from tellev in a separate terminal. It serves only the configured localhost port 18181. The launcher prevents parent Git repository discovery. Stop that terminal's process when finished.
4. From `tools/mvu`, run `node oracle-smoke.mjs`, `node oracle-generation.mjs`, and `node oracle-generation.mjs --dao`. The last command needs the root card and `3.27【可待】甲戌.json` preset. Actual helper/template distributions run in a clean headless Edge profile; no existing user browser profile is used. The exact known MVU/Zod resource URLs are fulfilled with the pinned real bundles, not tellev bridge code. All model generation uses the loopback replay service.
5. Run `node replay-baseline.mjs` for three independently repeated upstream-vs-tellev variable/message/EJS/basic-event observations. Nonzero exit is expected while documented differences remain; do not suppress it in an acceptance pipeline.
6. Run `node oracle-message-mutations.mjs` to regenerate the ten message-mutation goldens from actual upstream, three repeats each. It overwrites only `app/src/test/resources/fixtures/upstream-message-mutations.json`; review that diff. The JVM TavernChatMutationTest compares native mutation and persistence against this fixture.

Results are under ignored `build/mvu-oracle/results`. Full upstream generation reports contain user-owned card/preset text and complete prompts; do not publish them without reviewing content. Generation probes currently establish the oracle capture path, not final prompt equality. Fixed time/randomness/ID sources and comprehensive protocol fixtures are still pending.

## API inventory

`node audit-api.mjs` enumerates actual exposed function objects. The original 374-entry baseline and expanded 414-entry baseline are both retained under `contracts`; later audits write `build/mvu-api-current.json`. `node api-matrix.mjs` checks local reference HEADs, locates source candidates, and generates `contracts/api-contracts.json` plus `docs/MVU-API-MATRIX.md`. All contracts remain pending until their complete behavior, failures and calling convention have executable evidence; aliases and placeholder candidates are not accepted implementations.

## Android isolation

Copy the user-owned card and preset into ignored `app/build/mvu-fixtures/dao.json` and `jiaxu.json`. Build `:app:assembleMvuValidation :app:assembleMvuValidationAndroidTest`. Install `app-mvuValidation.apk` and `app-mvuValidation-androidTest.apk` with `adb install -r`, then run:

```powershell
adb shell am instrument -w app.tellev.mvuvalidation.test/androidx.test.runner.AndroidJUnitRunner
```

`TavernFrontendLifecycleTest` mounts the real Compose ChatScreen and exercises a local HTML button, chat switching, stale request rejection, persistence and WebView replacement. It never calls a model. The input helper and process-crash helper are intentionally skipped without explicit arguments.

For actual process termination and recovery, run `tools/mvu/android-storage-replay.ps1 -Serial <device>`. This targets only the validation package and kills its test process at four journal stages; each is followed by a separate recovery process. Output is saved in `android-storage-process-death.json`. Physical disk-full and power-cut tests are not covered by this driver.

Run `:app:lintDebug` and `git diff --check`. Passing JVM/lint/current Android checks is not full MVU, API31, performance, Sakura or real-model acceptance. See `docs/MVU-COMPATIBILITY.md` for remaining gaps.
