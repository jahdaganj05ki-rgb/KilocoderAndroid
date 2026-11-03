# KiloCoder for Android

Standalone, native Android coding agent. The agent runs on the phone; every Android build runs on GitHub Actions.

This directory contains M2: the on-device agent loop, an app-private workspace, seven file tools, persisted sessions, deterministic context compaction, and the streaming chat UI from M1.

## M2 features

- Agent loop with up to six tool steps per user turn. Tool calls and results stream into the chat as separate cards.
- App-private workspace under `filesDir/workspace`. Every path is canonicalized and checked against the workspace root; symlinks that escape it are not followed. No storage permission and no shell.
- Seven workspace tools: `list_files`, `read_file`, `write_file`, `replace_in_file`, `search_files`, `make_directory`, and `delete_file`.
- Room-backed sessions and message history, including assistant tool calls and tool results. Sessions can be reopened or deleted from the chat.
- Deterministic compaction: when the history exceeds 28,000 characters, older turns are summarized locally, the last ten turns are kept, and no extra model call is made.
- Existing account rotation and failover are reused for every model step, including tool-calling turns.
- Tool output is capped at 12,000 characters per result. Reads are capped at 400,000 bytes; writes at 1,000,000 characters.
- Chat UI shows reasoning, tool cards, tool errors, the selected account, the compaction marker, and saved sessions.
- English and German UI strings.

## M1 features

- 17 built-in API-key providers: OpenAI, Anthropic, Google Gemini, OpenRouter, Groq, Mistral, DeepSeek, xAI, Together, Fireworks, Cerebras, Perplexity, Moonshot, Z.AI, Kilo Gateway, Azure OpenAI, and a custom OpenAI-compatible endpoint.
- Three wire protocols: OpenAI-compatible SSE, Anthropic Messages, and Gemini `streamGenerateContent`.
- Room database with any number of accounts per provider, ordering, enable/disable, active-account selection, usage counters, cooldowns, and per-account last error.
- API keys encrypted with AES-256-GCM in the Android Keystore. Android backup is disabled.
- Rotation modes: manual, next, round robin, failover, least used, and weighted random. Automatic failover, maximum switches, and cooldowns are configurable per provider.
- Streaming chat with reasoning deltas, account selection (automatic or pinned), stop, and a new-chat action.

## Toolchain

The versions are pinned in `gradle/libs.versions.toml` and validated by CI. Compose 1.12 requires `compileSdk 37` and AGP 9, so the app stays on the latest Compose 1.11 BOM with an AGP 8 baseline.

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
gradle :app:testDebugUnitTest :app:assembleDebug
```

## Agent loop

1. The ViewModel sends the visible history plus a workspace-aware system prompt to `AgentRepository`.
2. The repository compacts the history if needed, then selects an account and streams one model turn.
3. Text, reasoning, and tool-call deltas are emitted to the UI. When the turn finishes with tool calls, each call is executed against the workspace and its result is sent back to the model.
4. The loop stops on a normal answer, on failure, or after six tool steps. The completed assistant answer and tool transcript are persisted to Room.

Each provider adapter converts the same `ChatTurn` history into its own format:

- OpenAI-compatible: `assistant.tool_calls` plus `tool` messages.
- Anthropic: `tool_use` content blocks plus grouped `tool_result` blocks.
- Gemini: `functionCall` parts plus grouped `functionResponse` parts.

## Workspace limits

| Operation | Limit |
|---|---|
| Read file | 400,000 bytes |
| Write file | 1,000,000 characters |
| Search file | 256,000 bytes |
| List entries | 500 |
| Recursion | 8 levels |
| Tool result in chat | 12,000 characters |
| Ignored directories | `.git`, `build`, `.gradle`, `node_modules`, `.idea`, `.kotlin` |

The workspace is app-private. It is not shared storage, and the app does not request `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE`. A later milestone can add repository checkout and GitHub Actions dispatch.

## Structure

```text
android/
  app/src/main/java/com/kilocoder/android/
    KiloCoderApp.kt              Application entry point
    MainActivity.kt              Compose host activity
    AppContainer.kt              Room, crypto, clients, repositories, agent
    data/agent/                  Agent loop, workspace tools, compaction
    data/crypto/                 Keystore-backed AES-GCM secret box
    data/db/                     Accounts, policies, sessions, DAOs, Room database
    data/provider/               Catalog, protocol clients, SSE parsers, tool schemas
    data/repo/                   Account CRUD, streaming chat, session persistence
    data/workspace/              Sandboxed workspace file operations
    domain/account/              Rotation modes and account selector
    ui/AppRoot.kt                Bottom navigation and editor host
    ui/accounts/                 Overview, provider detail, editor
    ui/chat/                     Agent ViewModel, chat screen, sessions dialog
    ui/settings/                 Security, workspace, local data, and about
    ui/theme/                    Color, type, and Material 3 theme
  app/src/main/res/              Strings (en/de), launcher icon, platform theme
  .github/workflows/android.yml  CI build at the repository root
```

## Security notes

- API keys are encrypted before they are written to Room. Plain keys are never logged.
- `android:allowBackup="false"` keeps the encrypted database out of Android cloud backup.
- The agent has no shell, no network tools, and no access outside the app-private workspace. Path traversal and escaping symlinks are rejected.
- Tool arguments and results are untrusted model output. The workspace applies size limits and canonical-path checks before every operation.
- The app is a separate package (`com.kilocoder.android`) and does not use Kilo Code branding.
- Portions of the agent design are derived from Kilo Code and OpenCode, both MIT licensed.

## Roadmap

| Milestone | Scope |
|---|---|
| M0 | Gradle scaffold, Compose shell, CI build, installable debug APK |
| M1 | Provider catalog, encrypted multi-account storage, rotation, streaming chat |
| M2 | Agent loop, file tools, app-private workspace, sessions, compaction |
| M3 | GitHub PAT, repository push, Actions dispatch, logs, artifact install |
| M4 | German/English polish, self-update, rollback, release signing |
