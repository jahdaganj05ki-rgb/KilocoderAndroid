# KiloCoder for Android

Standalone, native Android coding agent. The agent runs on the phone; every Android build runs on GitHub Actions.

This directory is the M0 foundation: a Compose app, a pinned toolchain, and a CI workflow that produces an installable debug APK.

## Toolchain

The versions are pinned in `gradle/libs.versions.toml` and validated by CI. Compose 1.12 requires `compileSdk 37` and AGP 9, so M0 stays on the latest Compose 1.11 BOM with an AGP 8 baseline.

| Component | Version |
|---|---|
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.14.3 (supplied by CI) |
| Kotlin | 2.3.20 |
| Compose BOM | 2026.06.01 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| JDK | 17 |

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
    KiloCoderApp.kt          Application entry point
    MainActivity.kt          Compose host activity
    ui/KiloCoderScreen.kt    M0 welcome and status screen
    ui/theme/                Color, type, and Material 3 theme
  app/src/main/res/          Strings (en/de), launcher icon, platform theme
  .github/workflows/android.yml (repository root)
```

## Roadmap

| Milestone | Scope |
|---|---|
| M0 | Gradle scaffold, Compose shell, CI build, installable debug APK |
| M1 | Provider catalog, encrypted multi-account storage, rotation, streaming chat |
| M2 | Agent loop, tools, workspace, sessions, compaction, permissions |
| M3 | GitHub PAT, repository push, Actions dispatch, logs, artifact install |
| M4 | German/English polish, self-update, rollback, release signing |

## Security notes

- M0 stores no credentials yet.
- The app is a separate package (`com.kilocoder.android`) and does not use Kilo Code branding.
- Portions of the agent design are derived from Kilo Code and OpenCode, both MIT licensed.
