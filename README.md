# tellev

Native Android architecture shell for a SillyTavern-compatible client.

This repository is intentionally only the architecture layer. It defines the app
module, core interfaces, compatibility data layout, prompt pipeline, provider
adapter boundary, extension host boundary, native plugin boundary, and a minimal
Compose entry point. Feature-complete implementation should happen inside these
boundaries instead of replacing them.

## Baseline

- App name: `tellev`
- Package: `app.tellev`
- Minimum Android: 12 / API 31
- UI: Kotlin + Jetpack Compose + Material 3
- Network: OkHttp
- Serialization: Kotlin Serialization
- License: AGPL-3.0
- SillyTavern baseline: `release`, version `1.18.0`, commit `51ad27fb86d39a3daca3adaa970375c9670c12df`

## Current State

- Android project skeleton is present under `app/`.
- `StDataStore` and `FileStDataStore` define SillyTavern-compatible storage roots.
- `PromptEngine` defines the prompt-building boundary.
- `ProviderAdapter` and `OpenAiCompatibleAdapter` define remote model integration.
- `ExtensionHost` and `WebViewJsExtensionHost` define the JS extension bridge.
- `NativePluginApi` defines the non-Node native plugin replacement.
- `SecretStore` has an Android Keystore-backed implementation.
- A minimal Compose shell exists for handoff and visual direction.

## Build Note

The Gradle wrapper is included in the repository (`gradlew`, `gradlew.bat`,
`gradle/wrapper/*`).  Open this folder in Android Studio to let it sync the
Gradle project, then run:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

The wrapper uses Gradle 8.11.1 with AGP 8.10.1 and Kotlin 2.1.21.

## AI Handoff

Read `docs/ARCHITECTURE.md` first, then implement tasks from `docs/AI_TASKS.md`.
Do not replace the public interfaces unless a task explicitly says to evolve
them with tests and migration notes.
