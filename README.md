# KiloCoder

KiloCoder is a native Android coding agent. It runs on the phone: you chat with a model, the agent edits an app-private workspace, and Android builds run on GitHub Actions and come back as an installable APK. No desktop, no second app, no local Android toolchain.

- Package: `com.kilocoder.android`
- Platform: Android 11+, arm64
- License: MIT

## Current state

| Stage | Scope | State |
|---|---|---|
| M0 | Gradle scaffold, Compose shell, CI build, installable debug APK | done |
| M1 | Provider catalog, encrypted accounts, rotation, streaming chat | done |
| M2 | Agent loop, workspace tools, sessions, compaction | done |
| M3 | GitHub PAT, workspace push, Actions dispatch, logs, artifact install | done |
| Phase 1 · M1.0 | Decisions, fixtures, project criteria, acceptance criteria | done |
| Phase 1 · M1.1 | PRoot smoke test for on-device builds | done on the Moto G84 (Android 15); 16-KB runtime in Phase 2 |
| Phase 1 · M1.2 | Own PRoot build with NDK r28 in CI, 16-KB ELF check, toolchain pack | in progress: own build verified on device; pack open |
| Phase 1 · M1.3–M1.6 | Hello build, multi-project build, app integration, hardening | planned |
| M4 | German/English polish, self-update, rollback, release signing | open |

The current Phase 1 work lives on the `android/m1.2-proot-ndk` branch. Details are in [`android/PHASE1_LOCAL_BUILD_PLAN.md`](android/PHASE1_LOCAL_BUILD_PLAN.md), [`android/PHASE1_M1_0_DECISIONS.md`](android/PHASE1_M1_0_DECISIONS.md) and [`android/PHASE1_M1_1_RUNBOOK.md`](android/PHASE1_M1_1_RUNBOOK.md).

## What it does

### Providers and accounts

- 17 built-in providers, including OpenAI, Anthropic, Google Gemini, OpenRouter, Groq, Mistral, DeepSeek and Azure OpenAI, plus a custom OpenAI-compatible endpoint.
- Three wire protocols: OpenAI-compatible SSE, Anthropic Messages, and Gemini `streamGenerateContent`.
- Any number of accounts per provider, ordering, enable/disable, usage counters, cooldowns, and per-account last error.
- Rotation modes: manual, next, round robin, failover, least used, and weighted random, with automatic failover, maximum switches, and cooldowns.
- API keys are encrypted with AES-256-GCM in the Android Keystore; Android backup is disabled.

### Agent and workspace

- Agent loop with up to six tool steps per user turn; text, reasoning, and tool calls stream into the chat.
- Seven workspace tools: `list_files`, `read_file`, `write_file`, `replace_in_file`, `search_files`, `make_directory`, `delete_file`.
- App-private workspace under `filesDir/workspace`; every path is canonicalized and checked against the root, escaping symlinks are rejected.
- Room-backed sessions and message history, including tool calls and results.
- Deterministic compaction at 28,000 characters: older turns are summarized locally, the last ten turns are kept, no extra model call.

### GitHub build pipeline

- One encrypted classic PAT with `repo` and `workflow`.
- Workspace push as one commit through the GitHub Git Data API; no git binary and no shell on the phone.
- Workflow dispatch, the latest 15 runs per branch, build logs with a 200,000-character cap, artifact listing and APK install through the system installer.
- Sensitive files are never pushed: `.env` files, `local.properties`, keystores, private keys, and certificates.

### On-device builds (Phase 1, in progress)

Phase 1 adds local debug builds for supported Android-Gradle projects: pick a project, tap **Build**, Gradle runs inside a PRoot container on the phone, and the installer opens. KiloCoder itself is one test fixture, not the boundary.

| Topic | Decision |
|---|---|
| Rootfs | Debian 12 bookworm arm64, pinned tarball with SHA-256 |
| Format | `tar.zst` with symlink, hardlink and mode preservation |
| Toolchain | Gradle 8.14.3, JDK 17, AGP 8.7.3–8.13.2, compileSdk 35/36, build-tools 35.0.0 |
| Projects | `settings.gradle*`, application module `:app`, `assembleDebug`, no NDK/Flutter/React Native |
| Hosting | GitHub Releases with component-wise assets and pinned SHA-256 |

PRoot, its loader, talloc and libandroid-shmem are built from pinned sources with NDK r28 in CI on every Android build. The build fails if any ELF `PT_LOAD` segment is not 16-KB aligned; a runtime 16-KB test stays in Phase 2. The resulting libraries passed the M1.1 smoke test on the Moto G84, including the on-device ELF check (`align=0x4000` for all four).

The debug build carries an **M1.1 PRoot-Test** launcher entry that downloads the pinned rootfs, extracts it, and checks PRoot under `targetSdk 36`. The device run on 2026-11-03 passed 12 of 12 checks on the Moto G84 (Android 15), first with the Termux reference binaries and again with the own NDK r28 build; the Go/No-Go is GO, and the 16-KB runtime test stays in Phase 2.

## Build

Builds run only on GitHub Actions. Neither the phone nor a development machine needs a JDK, the Android SDK, or Gradle.

- Workflow: [`.github/workflows/android.yml`](.github/workflows/android.yml)
- Triggers: push and pull request with changes under `android/**`, plus `workflow_dispatch`
- Steps: `:app:testDebugUnitTest`, then `:app:assembleDebug` with `-PversionName=0.1.$GITHUB_RUN_NUMBER`
- Artifact: `kilocoder-debug-apk` (`app-debug.apk`)
- Release: every successful push or manual run publishes `v0.1.<run_number>` with `kilocoder-debug-0.1.<run_number>.apk` under Releases; pull-request builds publish nothing

Each run produces a version like `0.1.<run_number>`; the run page shows the exact value. Install only artifacts from a run you trust.

## Branching

- `main` is the integration branch and is not pushed to directly.
- Every change runs on `android/<topic>`, for example `android/m1.1-proot-smoke`.
- Pushes name the target branch explicitly.
- The root `README.md` and `android/README.md` are updated in the same commit as the change that moves the project state.
- The binding rules live in [`AGENTS.md`](AGENTS.md) and [`android/AGENTS.md`](android/AGENTS.md).

## Quick start

1. Open **Actions → android → latest successful run → Artifacts** and download `kilocoder-debug-apk`.
2. Install the APK. Android may ask you to allow installs from KiloCoder.
3. Open **Accounts**, add a provider API key, and test the connection.
4. Open **GitHub**, enter owner, repository, branch and workflow file, connect with a classic PAT (`repo`, `workflow`).
5. Push the workspace, start the Android build, select the run, load the artifact and install it.
6. For the PRoot check, open the second launcher entry **M1.1 PRoot-Test** and press **Start**; the report is written to `filesDir/m11/report.txt` and can be copied to the clipboard.

## Repository layout

```text
android/
  app/                     Android app (Compose UI, Room, providers, agent, GitHub)
  app/src/debug/           M1.1 PRoot smoke test and its native libraries
  test-fixtures/           hello-local-build, generic-sdk35, plus this app
  tools/m1_1/              provenance and hashes of the PRoot binaries
  PHASE1_LOCAL_BUILD_PLAN.md
  PHASE1_M1_0_DECISIONS.md
  PHASE1_M1_1_RUNBOOK.md
  AGENTS.md                project rules
  README.md                app-specific documentation
.github/workflows/
  android.yml              CI build and debug APK artifact
  android-m3-e2e.yml       manual end-to-end GitHub test
```

## Security notes

- API keys and the GitHub PAT are encrypted before they reach the Room database; plain secrets are never logged.
- `android:allowBackup="false"` keeps the encrypted database out of Android cloud backup.
- The agent has no shell, no network tools, and no access outside the app-private workspace. Path traversal and escaping symlinks are rejected.
- GitHub pushes omit sensitive local files and never upload the encrypted token database.
- Downloaded APKs live only under `cacheDir/github`; the `FileProvider` exposes that directory only, and every install is confirmed in the system installer.
- PRoot is not a security sandbox. A Phase 1 build runs under the app's UID and can read app files it knows about, so only the project, the SDK and a separate Gradle home are mounted; API keys, PATs and keystores are never mounted.

## Roadmap

| Milestone | Scope | State |
|---|---|---|
| M0 | Gradle scaffold, Compose shell, CI build, installable debug APK | done |
| M1 | Provider catalog, encrypted multi-account storage, rotation, streaming chat | done |
| M2 | Agent loop, file tools, app-private workspace, sessions, compaction | done |
| M3 | GitHub PAT, workspace push, Actions dispatch, logs, artifact install | done |
| Phase 1 · M1.0 | Decisions, fixtures, project criteria, M1.1 acceptance | done |
| Phase 1 · M1.1 | PRoot smoke test on the Moto G84 | done on the Moto G84 (Android 15); 16-KB runtime in Phase 2 |
| Phase 1 · M1.2 | Toolchain pack with JDK 17, Gradle 8.14.3, SDK 35/36, static aarch64 tools | in progress: own PRoot build device-verified; pack open |
| Phase 1 · M1.3–M1.6 | Hello-world build, three-project matrix, app integration, hardening | planned |
| M4 | German/English polish, self-update, rollback, release signing | open |

## License

MIT. See [`LICENSE`](LICENSE).
