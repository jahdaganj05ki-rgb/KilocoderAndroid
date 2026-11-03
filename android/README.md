# KiloCoder for Android

Standalone, native Android coding agent. The agent runs on the phone; every Android build runs on GitHub Actions.

This directory contains M1: the provider catalog, encrypted multi-account storage, rotation/failover, and a streaming chat UI. M0's Compose shell and CI pipeline remain the base.

## M1 features

- 17 built-in API-key providers: OpenAI, Anthropic, Google Gemini, OpenRouter, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, Cerebras, Perplexity, Moonshot, Z.AI, Kilo Gateway, Azure OpenAI, and a custom OpenAI-compatible endpoint.
- Three wire protocols: OpenAI-compatible SSE, Anthropic Messages, and Gemini `streamGenerateContent`.
- Room database with any number of accounts per provider, ordering, enable/disable, active-account selection, usage counters, cooldowns, and per-account last error.
- API keys encrypted with AES-256-GCM in the Android Keystore. Android backup is disabled.
- Rotation modes: manual, next, round robin, failover, least used, and weighted random. Automatic failover, maximum switches, and cooldowns are configurable per provider.
- Streaming chat with reasoning deltas, account selection (automatic or pinned), stop, and a new-chat action.
- English and German UI strings.

## Toolchain

The versions are pinned in `gradle/libs.versions.toml` and validated by CI. Compose 1.12 requires `compileSdk 37` and AGP 9, so M1 stays on the latest Compose 1.11 BOM with an AGP 8 baseline.

| Component | Version |
|---|---|
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.14.3 (supplied by CI) |
| Kotlin | 2.3.20 |
| Compose BOM | 2026.06.01 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| JDK | 17 |
| OkHttp | 5.4.0 |

OkHttp is pinned to 5.4.0: 5.5.1 requires `compileSdk 37`, while 5.4.0 is the newest release that still supports the project's `compileSdk 36` / AGP 8 baseline.

## Build

Builds are intentionally remote. The phone does not need a JDK, the Android SDK, or Gradle.

```sh
# On CI, after checkout:
cd android
gradle :app:testDebugUnitTest
gradle :app:assembleDebug -PversionCode="$GITHUB_RUN_NUMBER" -PversionName="0.1.$GITHUB_RUN_NUMBER"
```

The workflow uploads `app/build/outputs/apk/debug/app-debug.apk` as the `kilocoder-debug-apk` artifact.

A local build is possible with JDK 17 and the Android SDK, but it is only a developer convenience:

```sh
cd android
gradle :app:assembleDebug
```

## Structure

```text
android/
  app/src/main/java/com/kilocoder/android/
    KiloCoderApp.kt              Application entry point
    MainActivity.kt              Compose host activity
    AppContainer.kt              Room, crypto, clients, repositories
    data/crypto/                 Keystore-backed AES-GCM secret box
    data/db/                     Accounts, policies, DAOs, Room database
    data/provider/               Catalog, protocol clients, SSE parsers
    data/repo/                   Account CRUD and streaming/failover chat
    domain/account/              Rotation modes and account selector
    ui/AppRoot.kt                Bottom navigation and editor host
    ui/accounts/                 Overview, provider detail, editor
    ui/chat/                     Streaming chat ViewModel and screen
    ui/settings/                 Security, local data, and about
    ui/theme/                    Color, type, and Material 3 theme
  app/src/main/res/              Strings (en/de), launcher icon, platform theme
  .github/workflows/android.yml  CI build at the repository root
```

## Security notes

- API keys are encrypted before they are written to Room. Plain keys are never logged.
- `android:allowBackup="false"` keeps the encrypted database out of Android cloud backup.
- The app is a separate package (`com.kilocoder.android`) and does not use Kilo Code branding.
- Portions of the agent design are derived from Kilo Code and OpenCode, both MIT licensed.

## Roadmap

| Milestone | Scope |
|---|---|
| M0 | Gradle scaffold, Compose shell, CI build, installable debug APK |
| M1 | Provider catalog, encrypted multi-account storage, rotation, streaming chat |
| M2 | Agent loop, tools, workspace, sessions, compaction, permissions |
| M3 | GitHub PAT, repository push, Actions dispatch, logs, artifact install |
| M4 | German/English polish, self-update, rollback, release signing |
