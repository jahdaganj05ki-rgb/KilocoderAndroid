# KiloCoder for Android

Standalone, native Android coding agent. The agent runs on the phone; every Android build runs on GitHub Actions.

This directory contains M0–M3 and the preparation for Phase 1. M3 is the encrypted GitHub PAT connection, workspace push through the Git Data API, workflow dispatch, workflow-run selection, build logs, artifact download, and APK installation. Phase 1 prepares the on-device build of arbitrary Android projects with PRoot; M1.0 is decided and the M1.1 smoke test is built and packaged, but it has not run on the phone yet.

## Status

| Stage | Scope | State |
|---|---|---|
| M0 | Gradle scaffold, Compose shell, CI build, installable debug APK | done |
| M1 | Provider catalog, encrypted accounts, rotation, streaming chat | done |
| M2 | Agent loop, workspace tools, sessions, compaction | done |
| M3 | GitHub PAT, push, Actions dispatch, logs, artifact install | done |
| Phase 1 · M1.0 | Decisions, fixtures, project criteria, M1.1 acceptance | done |
| Phase 1 · M1.1 | PRoot smoke test on the Moto G84 | harness built in CI; device run open |
| Phase 1 · M1.2–M1.6 | Toolchain pack, hello build, multi-project, app integration, hardening | planned |
| M4 | German/English polish, self-update, rollback, release signing | open |

The binding rules for agents live in [`AGENTS.md`](AGENTS.md): builds only on GitHub Actions, changes only on `android/<topic>` branches, and the README is updated with every push.

## M3 features

- One encrypted GitHub connection with owner, repository, branch, workflow file, and login.
- PAT validation against `GET /user` and the selected repository before anything is stored.
- Workspace push as one Git commit through the GitHub Git Data API. No git binary is bundled, and no shell runs on the phone.
- Empty repositories are initialized with a bootstrap commit, then receive the workspace commit. Existing branches receive a fast-forward commit.
- Workflow dispatch for the configured branch and workflow file.
- Latest 15 workflow runs for the branch, with status, conclusion, run number, commit SHA, and timestamps.
- Build-log download from the run logs endpoint, ZIP extraction, and a 200,000-character display cap.
- Artifact listing and APK download from the selected run. The system installer is opened through a scoped `FileProvider` URI.
- Sensitive files are never pushed: `.env` files, `local.properties`, keystores, private keys, and common certificate files are skipped.
- English and German UI strings.
- A manual GitHub Actions E2E workflow that pushes, dispatches, reads logs, and downloads an APK against a disposable test repository.

## GitHub token

The PAT is encrypted with the same Android Keystore-backed AES-256-GCM box as provider keys. Only the last four characters and the GitHub login are stored in clear text.

One token is intentional. GitHub does not provide an API to create PATs, and a PAT cannot grant additional access rights. The same `repo`/`workflow` token covers repository creation and Actions secrets when those API calls are added; no token broker or child tokens are required.

Required permissions:

- Classic PAT: `repo` and `workflow`. Add `read:org` only if the repository is owned by an organization that requires it.
- Fine-grained PAT: `Contents` read/write and `Actions` read/write for the selected repository.

The repository must already exist. The workflow file must exist on the selected branch; the default is `android.yml`. A push or dispatch against a protected branch can be rejected by GitHub and is shown as an error.

## Workflow

1. Enter the PAT, owner, repository, branch, and workflow file, then connect.
2. Push the app-private workspace. The snapshot becomes a commit on the selected branch.
3. Start the Android build through workflow dispatch.
4. Refresh runs, select a run, and load its logs or artifacts.
5. Install the APK artifact. Android may ask for permission to install apps from KiloCoder.

The push uses the tree, commit, and reference endpoints. It sends a complete snapshot; files missing from the workspace are deleted from the new tree. Binary files, ignored directories, files over 1 MB, and snapshots over 5 MB are skipped. A run's logs are capped at 50 MB compressed and 200,000 displayed characters.

## M2 features

- Agent loop with up to six tool steps per user turn. Tool calls and results stream into the chat as separate cards.
- App-private workspace under `filesDir/workspace`. Every path is canonicalized and checked against the workspace root; symlinks that escape it are not followed. No storage permission and no shell.
- Seven workspace tools: `list_files`, `read_file`, `write_file`, `replace_in_file`, `search_files`, `make_directory`, and `delete_file`.
- Room-backed sessions and message history, including assistant tool calls and tool results. Sessions can be reopened or deleted from the chat.
- Deterministic compaction: when the history exceeds 28,000 characters, older turns are summarized locally, the last ten turns are kept, and no extra model call is made.
- Existing account rotation and failover are reused for every model step, including tool-calling turns.

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

Builds are intentionally remote. Neither the phone nor the development environment needs a JDK, the Android SDK, or Gradle.

`.github/workflows/android.yml` runs on push and pull request with changes under `android/**`, and manually through `workflow_dispatch`:

```sh
# On CI, after checkout:
cd android
gradle :app:testDebugUnitTest
gradle :app:assembleDebug -PversionCode="$GITHUB_RUN_NUMBER" -PversionName="0.1.$GITHUB_RUN_NUMBER"
```

The workflow uploads `app/build/outputs/apk/debug/app-debug.apk` as the `kilocoder-debug-apk` artifact. Because the version name is derived from the run number, each run gets a version like `0.1.<run_number>`; the run page shows the exact value. On a successful push or manual run, the workflow also publishes a GitHub release tagged `v0.1.<run_number>` with `kilocoder-debug-0.1.<run_number>.apk`; pull-request builds skip the release step. The built-in `GITHUB_TOKEN` with `contents: write` is used, so no extra secret is required.

Local builds of this app are not part of the workflow. Static checks that need no Android toolchain still run locally: Markdown table padding, XML/TOML syntax, `git diff --check`, and `readelf` on checked-in binaries.

## Branching

- `main` is the integration branch and is not pushed to directly.
- Every change runs on `android/<topic>`, for example `android/m1.1-proot-smoke`.
- Pushes name the target branch explicitly.
- The README is updated in the same commit as the change that moves the project state.

## Phase 1: building other Android projects on the phone

Phase 1 proves and integrates one local debug build for supported Android-Gradle projects: the user picks a project, taps **Build**, the app builds it with real Gradle inside a PRoot container, and the Android installer opens for the debug APK. KiloCoder itself is one fixture, not the boundary.

Decisions are recorded in [`PHASE1_M1_0_DECISIONS.md`](PHASE1_M1_0_DECISIONS.md); the full plan is [`PHASE1_LOCAL_BUILD_PLAN.md`](PHASE1_LOCAL_BUILD_PLAN.md).

| Topic | Decision |
|---|---|
| Rootfs | Debian 12 bookworm arm64, `debian-bookworm-aarch64-pd-v4.7.0.tar.xz`, 42.894.344 bytes, SHA-256 `4baa3228…b8df8` |
| Delivery format | one `tar.zst`, extracted with symlink, hardlink and mode preservation |
| Bitness | arm64 only, no root |
| Toolchain | Gradle 8.14.3, JDK 17, AGP 8.7.3–8.13.2, compileSdk 35/36, build-tools 35.0.0 |
| Projects | `settings.gradle*`, application module `:app`, `assembleDebug`, no NDK/Flutter/React Native |
| Hosting | GitHub Releases with component-wise assets and pinned SHA-256 |

### M1.1 smoke test

`app/src/debug/` contains a debug-only activity that appears as a second launcher entry, **M1.1 PRoot-Test**. It downloads the pinned rootfs, extracts it, starts PRoot from `nativeLibraryDir`, and checks the PRoot assumptions: `--version`, guest `echo`, `readlink /bin/sh`, guest `bash`, `uname -m`/`PAGE_SIZE`, exit-code propagation, 16-KB ELF alignment, and cancellation. The optional steps install `openjdk-17-jre-headless` in the guest and run `java -version`.

The PRoot binaries (PRoot, loader, talloc, android-shmem) are pinned Termux packages with recorded SHA-256 hashes; [`tools/m1_1/BINARIES.md`](tools/m1_1/BINARIES.md) documents provenance, the private `libtalloc.so.2` copy used to satisfy the SONAME, and the GPL-2.0 note. M1.2 replaces them with an own NDK r28 build.

The device run has not happened yet, so the Go/No-Go for PRoot under `targetSdk 36` is open. Install steps, expected output and failure modes are in [`PHASE1_M1_1_RUNBOOK.md`](PHASE1_M1_1_RUNBOOK.md). The release APK never contains the smoke activity or the PRoot libraries.

### Test fixtures

Three projects cover the toolchain corridor:

| Fixture | AGP | compileSdk | Role |
|---|---|---|---|
| `test-fixtures/hello-local-build` | 8.13.2 | 36 | first real build, upper edge |
| `test-fixtures/generic-sdk35` | 8.7.3 | 35 | lower edge, "not only KiloCoder" |
| `android/` itself | 8.13.2 | 36 | real app with Compose, Room/KSP, OkHttp |

## M3 end-to-end test

The manual workflow `.github/workflows/android-m3-e2e.yml` exercises the real GitHub path without putting a token in the repository or chat. It uses the repository secret `KILOCODER_E2E_PAT` only in the API job and creates a private, disposable repository for each run.

Required secret:

- Classic PAT: `repo`, `workflow`, and `delete_repo`.
- Fine-grained PAT: `Contents` read/write, `Actions` read/write, and `Administration` read/write, with access that can create the new temporary repository. A classic PAT is simpler because the temporary repository does not exist when the token is configured.

Select **Actions → android-m3-e2e → Run workflow**, or run:

```sh
gh workflow run android-m3-e2e.yml
```

GitHub exposes `workflow_dispatch` in the Actions UI only when this workflow file is on the repository's default branch. On a feature branch, merge or push the workflow to the default branch first.

The API job:

1. Creates `kilocoder-m3-e2e-<run-id>-<attempt>` under the PAT owner.
2. Builds a temporary workspace from the current `android/` tree and `.github/workflows/android.yml`. The copied workflow keeps only `workflow_dispatch` so the push does not start a duplicate build.
3. Runs `M3E2eTest` against the production `GitHubApi`: validates the PAT and repository, pushes the snapshot as a root commit to the repository's default branch, dispatches the Android workflow, waits for the run, downloads the logs, and downloads the APK artifact.
4. Uploads `m3-e2e-report` and `m3-e2e-apk`, then deletes the temporary repository unless `keep_repo` is enabled.

The workflow stops after downloading and uploading the APK. Emulator installation is intentionally not part of CI because boot time dominates the run. Install the APK manually on a device or emulator.

The test class is skipped in normal `testDebugUnitTest` runs. The workflow tests the GitHub API path and downloads the APK; the in-app Install button and the Android package-installer dialog remain a manual device check.

### Manual APK installation and screenshot

Use a device or emulator with USB debugging enabled.

1. Download the `m3-e2e-apk` artifact from the workflow run and extract `kilocoder-debug.apk`.
2. Install the APK:
   ```sh
   adb install -r kilocoder-debug.apk
   ```
   Alternatively, open the APK on the device and confirm the Android package-installer dialog.
3. Launch **KiloCoder** and confirm that the app stays open. The first screen may be the setup screen; this check does not require an account.
4. Capture the screen and the app log:
   ```sh
   adb exec-out screencap -p > m3-e2e.png
   adb logcat -d > logcat.txt
   ```
5. Verify that `logcat.txt` contains no `FATAL EXCEPTION` or crash from `com.kilocoder.android`, then attach `m3-e2e.png` and `logcat.txt` to the test notes.

If cleanup fails because `delete_repo` or `Administration` write is missing, the run fails and the private temporary repository remains. Delete it manually or re-run with the required scope.

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
| GitHub push snapshot | 500 files / 5 MB |
| GitHub push file | 1 MB |
| Ignored directories | `.git`, `build`, `.gradle`, `node_modules`, `.idea`, `.kotlin` |

The workspace is app-private. It is not shared storage, and the app does not request `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE`. APK installation uses `REQUEST_INSTALL_PACKAGES` and only exposes downloaded artifacts through a cache-scoped `FileProvider`.

## Structure

```text
android/
  AGENTS.md                    project rules: CI-only builds, branches, README upkeep
  README.md                    this file
  PHASE1_LOCAL_BUILD_PLAN.md   Phase 1 plan and milestones
  PHASE1_M1_0_DECISIONS.md     rootfs, toolchain, fixtures, M1.1 acceptance
  PHASE1_M1_1_RUNBOOK.md       install, run and evaluate the PRoot smoke test
  app/src/main/java/com/kilocoder/android/
    KiloCoderApp.kt            Application entry point
    MainActivity.kt            Compose host activity
    AppContainer.kt            Room, crypto, clients, repositories, agent, GitHub
    data/agent/                Agent loop, workspace tools, compaction
    data/crypto/               Keystore-backed AES-GCM secret box
    data/db/                   Accounts, policies, sessions, GitHub config, DAOs
    data/github/               GitHub API, push, runs, logs, artifacts, live E2E test
    data/install/              Scoped APK installer intent
    data/provider/             Catalog, protocol clients, SSE parsers, tool schemas
    data/repo/                 Account CRUD, streaming chat, session persistence
    data/workspace/            Sandboxed workspace file operations and snapshots
    domain/account/            Rotation modes and account selector
    ui/AppRoot.kt              Bottom navigation and editor host
    ui/accounts/               Overview, provider detail, editor
    ui/chat/                   Agent ViewModel, chat screen, sessions dialog
    ui/github/                 GitHub connection, build, run, log, artifact UI
    ui/settings/               Security, workspace, local data, and about
    ui/theme/                  Color, type, and Material 3 theme
  app/src/debug/               M1.1 smoke test, debug builds only
    AndroidManifest.xml        second launcher entry, extractNativeLibs
    java/.../m11/              smoke activity, runner, PRoot engine, tar.xz extractor, ELF check
    jniLibs/arm64-v8a/         PRoot, loader, talloc, android-shmem
  app/src/main/res/            Strings (en/de), launcher icon, platform theme
  test-fixtures/               hello-local-build and generic-sdk35
  tools/m1_1/BINARIES.md       provenance and hashes of the PRoot binaries
  .github/workflows/android.yml  CI build at the repository root
  .github/workflows/android-m3-e2e.yml  manual live GitHub E2E
```

## Security notes

- API keys and the GitHub PAT are encrypted before they are written to Room. Plain secrets are never logged.
- `android:allowBackup="false"` keeps the encrypted database out of Android cloud backup.
- The agent has no shell, no network tools, and no access outside the app-private workspace. Path traversal and escaping symlinks are rejected.
- GitHub pushes omit sensitive local files and never upload the encrypted token database.
- APK downloads are written only under `cacheDir/github`; the `FileProvider` exposes that directory only. The user must confirm every install in the system installer.
- Tool arguments, model output, logs, and artifact names are untrusted. The app applies size limits and path checks before handling them.
- The app is a separate package (`com.kilocoder.android`).
- PRoot is not a security sandbox. A Phase 1 build runs under the app's own UID and can read app files it knows about; the plan therefore mounts only the project, the SDK and a separate Gradle home, and never mounts API keys, PATs or keystores.
- Portions of the agent design are derived from Kilo Code and OpenCode, both MIT licensed.

## Roadmap

| Milestone | Scope | State |
|---|---|---|
| M0 | Gradle scaffold, Compose shell, CI build, installable debug APK | done |
| M1 | Provider catalog, encrypted multi-account storage, rotation, streaming chat | done |
| M2 | Agent loop, file tools, app-private workspace, sessions, compaction | done |
| M3 | GitHub PAT, workspace push, Actions dispatch, logs, artifact install | done |
| Phase 1 · M1.0 | Decisions, fixtures, project criteria, M1.1 acceptance | done |
| Phase 1 · M1.1 | PRoot smoke test on the Moto G84 | device run open |
| Phase 1 · M1.2 | Toolchain pack with JDK 17, Gradle 8.14.3, SDK 35/36, static aarch64 tools | planned |
| Phase 1 · M1.3–M1.6 | Hello-world build, three-project matrix, app integration, hardening | planned |
| M4 | German/English polish, self-update, rollback, release signing | open |
