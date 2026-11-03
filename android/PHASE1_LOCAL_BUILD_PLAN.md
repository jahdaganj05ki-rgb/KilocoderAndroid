# Phase 1: On-device APK build

- Status: plan, not implemented
- Date: 2026-11-03
- Scope owner: KiloCoder Android
- Baseline commit: `f67f4a093c`
- Target device: Motorola Moto G84 5G, Android 15 (API 35), 12 GB RAM + 4 GB RAM Boost, 256 GB storage (149 GB/256 GB shown; at least 100 GB free)
- Build budget: 20–40 minutes accepted; hard stop at 60 minutes
- Device check: Schalter vorhanden, aktiv und nach Neustart persistent

## 1. Ziel

Phase 1 beweist und integriert **einen** lokalen Debug-Build für **unterstützte Android-Gradle-Projekte** auf dem Telefon:

> Der Nutzer wählt in KiloCoder ein Android-Projekt aus, tippt auf **Build**, die App baut es mit einem echten Gradle in einem PRoot-Container und öffnet anschließend den Android-Installer für die erzeugte Debug-APK.

KiloCoder ist dabei nur ein Testprojekt, nicht die Grenze des Projektumfangs.

Es gibt dabei:

- keinen PC,
- keinen Export,
- keine zweite App,
- keinen GitHub-Actions-Build für diesen Pfad,
- keine NDK-, Flutter- oder React-Native-Unterstützung,
- keine Release-Signierung,
- keine Anbindung an den Secret-Vault.

Der bestehende GitHub-Actions-Pfad bleibt unverändert und dient weiterhin als Vergleich und Fallback.

### 1.1 Unterstützte Projekte

Phase 1 unterstützt **mehrere Android-Gradle-App-Projekte**, nicht nur KiloCoder:

- Projektordner im app-privaten Workspace mit `settings.gradle` oder `settings.gradle.kts`, Gradle Wrapper und einem Application-Modul `:app`.
- Debug-Build über `:app:assembleDebug`; keine Release-APK, kein AAB.
- Kein NDK/C++, Flutter oder React Native.
- Explizite Projektauswahl im Build-Tab; keine automatische Erkennung ohne Auswahl.
- Ein Projekt nach dem anderen; kein paralleler Build.

**Toolchain-Korridor für Phase 1:**

| Dimension | Phase 1 |
|---|---|
| Gradle | 8.14.3, System-Gradle; Wrapper wird nur validiert, nicht ausgeführt |
| JDK | 17 |
| AGP | 8.7.3 bis 8.13.2; Tests mit 8.7.3 und 8.13.2 |
| compileSdk | 35 und 36; beide Platform-JARs im Pack |
| Modul | Application-Modul `:app` |
| Build-Variante | `assembleDebug`; keine Flavors, Splits oder Multi-Modul-Sonderfälle |
| NDK/Flutter/RN | nicht unterstützt |
| Private Maven-Repos | nicht unterstützt |

Projekte außerhalb dieses Korridors werden mit einer konkreten Fehlermeldung abgelehnt, statt einen unklaren Gradle-Fehler zu zeigen. Die Projektauswahl prüft `settings.gradle*`, Wrapper und `:app`; sie ersetzt nicht die Toolchain-Prüfung.

## 2. Nicht in Phase 1

- NDK/C++
- Flutter
- React Native
- Release-APK, AAB oder Play-Signing
- Nicht-Android-Projekte und andere Build-Systeme
- automatische Projekt-Erkennung ohne explizite Projektauswahl
- Multi-Modul-Sonderfälle; Phase 1 testet ein Application-Modul (`:app`)
- mehrere GitHub-Konten oder automatische Secret-Provisionierung
- Agent-Tool `build_apk`
- F-Droid-Veröffentlichung
- Hintergrund-Builds ohne sichtbare App
- Wiederaufnahme eines Builds nach Prozess-Tod
- Builds aus nicht vertrauenswürdigen Repositories

Diese Punkte kommen in späteren Phasen. Phase 1 muss zuerst die technische Machbarkeit und die Speicher-/Zeitgrenzen belegen.

## 3. Recherche-Ergebnisse und Konsequenzen

### 3.1 Android blockiert Exec aus dem App-Datenverzeichnis

Ab `targetSdk 29` verbietet SELinux `execute_no_trans` auf `app_data_file`. Das gilt auch für `targetSdk 36`; die Domain ist dann `untrusted_app`. Ein `chmod +x` auf eine Datei in `filesDir` hilft nicht.

Quellen:

- https://developer.android.com/about/versions/10/behavior-changes-10
- https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te
- https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/seapp_contexts
- https://github.com/termux/termux-app/issues/1072

Konsequenz: PRoot darf nicht als normales Executable in `filesDir` liegen. Die App muss die PRoot-Binaries als Native Libraries ausliefern.

### 3.2 Native Libraries sind ausführbar

`/data/app/.../lib/<abi>/` ist `apk_data_file`; `app.te` erlaubt allen App-Domains `x_file_perms` darauf. Damit kann `libproot.so` aus `applicationInfo.nativeLibraryDir` gestartet werden. Das ist der einzige belastbare Weg für `targetSdk 36`.

Quellen:

- https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app.te
- https://github.com/termux/termux-app/issues/2155

Konsequenz:

- `libproot.so`, `libproot-loader.so` und die benötigte `libtalloc`-Bibliothek werden in `jniLibs/arm64-v8a/` gepackt.
- `android:extractNativeLibs="true"` ist Pflicht.
- Der Pfad wird zur Laufzeit über `applicationInfo.nativeLibraryDir` ermittelt.
- `PROOT_LOADER` zeigt auf `nativeLibraryDir/libproot-loader.so`.
- `LD_LIBRARY_PATH` zeigt auf `nativeLibraryDir`.
- Das Rootfs bleibt in `filesDir`; Guest-Binaries werden über PRoot und seinen Loader gestartet, nicht direkt vom Kernel.

### 3.3 Der kritische Go/No-Go-Punkt

Die Aussage „PRoot umgeht die Exec-Sperre für Guest-Binaries“ ist plausibel, aber für `targetSdk 36` nicht durch einen belastbaren End-to-End-Beleg gedeckt. Termux selbst bleibt bei `targetSdk 28`; AndroidIDE ist eingestellt; die meisten PRoot-Anleitungen laufen in Termux, nicht in einer fremden App.

Deshalb ist **M1.1 ein echter Gerätetest** und kein Dokumentationsbeweis. Wenn PRoot mit `targetSdk 36` seine Guest-Binaries nicht startet, wird Phase 1 an dieser Stelle gestoppt und die Fallback-Entscheidung getroffen.

### 3.4 Debian/glibc statt Termux-Bionic als Phase-1-Baseline

Termux-Bionic ist kleiner, aber als **eingebettetes** Rootfs in einer fremden App nicht belegt:

- Termux-Pakete sind auf `com.termux` und `$PREFIX` festgelegt.
- Ein Umzug auf `com.kilocoder.android` erfordert Prefix-Rebuilds oder Bind-Mount-Tricks.
- Die Kombination „Bionic-Rootfs + PRoot“ ist nicht der dokumentierte Termux-Pfad.
- Ein Debian-/glibc-Rootfs ist der in Termux `proot-distro` und in einem dokumentierten On-Device-Gradle-Build erprobte Pfad.

Quellen:

- https://github.com/termux/proot-distro
- https://github.com/sam1am/termux_crt/blob/main/BUILDING.md
- https://github.com/termux/termux-packages/pull/30323

Konsequenz: **Phase 1 nutzt Debian/glibc.** Bionic wird nach M1.4 nur dann als Optimierung geprüft, wenn Speicher oder Downloadgröße nicht akzeptabel sind.

### 3.5 aapt2 ist der reale Toolchain-Blocker

Google liefert Build-Tools und das in AGP eingebettete `aapt2` nur für `linux-x86_64`. Auf arm64 muss ein natives aarch64-`aapt2` verwendet werden.

**Entscheidung:** Die statischen aarch64-Tools aus `lzhiyong/android-sdk-tools` (Release `35.0.2`) sind die einzige Toolquelle für Phase 1. Der Termux-aapt2-PR bleibt nur Referenz: Diese Pakete sind Bionic-dynamisch und laufen nicht zuverlässig in einem Debian-/glibc-Rootfs.

**Korrektur vom 2026-11-03:** Ein Release `36.0.0` existiert nicht. Die Tags des Repos sind `33.0.3`, `34.0.3` und `35.0.2`; `releases/tags/36.0.0` liefert HTTP 404. Phase 1 pinnt deshalb `35.0.2` (Asset `android-sdk-tools-static-aarch64.zip`, 14.401.170 Bytes, Apache-2.0). Der Pack enthält dazu build-tools 35.0.0. Ob aapt2 35.0.2 mit AGP 8.13.2/compileSdk 36 zusammenarbeitet, prüft M1.2 mit einem echten Link; Fallback ist der Korridor mit AGP 8.7.3/compileSdk 35. Details: [PHASE1_M1_0_DECISIONS.md](PHASE1_M1_0_DECISIONS.md), Abschnitt 4.

Konsequenz:

- `android.aapt2FromMavenOverride` zeigt auf das statische aarch64-`aapt2` im Rootfs.
- `zipalign` ist **kein Java-Tool**; die statische aarch64-Version wird explizit in den Pack gelegt.
- `d8`, `apksigner` und `aidl` laufen als Java-Tools im JDK.
- `aapt2` und `zipalign` werden mit `aapt2 version`, `zipalign` und `readelf` geprüft; die Version muss zu build-tools 35.0.0 passen.
- `android.jar` für API 35 und 36 ist architekturneutral und kommt aus den offiziellen Platform-Zips.
- `sdkmanager` wird in Phase 1 nicht benötigt; die Pakete werden direkt in den Toolchain-Pack gelegt.

### 3.6 Foreground Service

Für einen 10–30-Minuten-Build ist `dataSync` der passende Typ:

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `POST_NOTIFICATIONS` für die sichtbare Notification
- `WAKE_LOCK`
- `android:foregroundServiceType="dataSync"`
- `startForeground()` aus einer sichtbaren Activity
- `onTimeout()` implementieren und den Build sauber stoppen
- `POST_NOTIFICATIONS` zur Laufzeit anfragen; bei Ablehnung bleibt der FGS aktiv, aber die App zeigt den Status im Vordergrund/Task-Manager
- `NotificationChannel`, `stopForeground()`, `START_NOT_STICKY`, `onDestroy()` und `onTaskRemoved()` implementieren

Manifest-Pflicht:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<service
    android:name=".build.BuildService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

Android 15 begrenzt `dataSync` auf 6 Stunden pro 24 Stunden. Das ist für Phase 1 ausreichend; die App beendet Builds zusätzlich nach 60 Minuten. Der Zielkorridor für den ersten Build ist 20–40 Minuten; Downloads und Maven-Auflösung können den ersten Lauf verlängern.

Quellen:

- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/about/versions/15/behavior-changes-15

Konsequenz: Der Build läuft in einem **gestarteten Foreground Service**, nicht in WorkManager. WorkManager ist nur für spätere Wiederaufnahme interessant.

### 3.7 16-KB-Seiten

Android 15/16 können 16-KB-Seiten verwenden. Native Binaries müssen entsprechend ausgerichtet sein. Termux hat seine Pakete nach NDK r28 neu gebaut; für eigene PRoot-Binaries gilt dasselbe. AGP 8.13.2 und NDK r28+ sind die richtige Basis.

Das Moto G84 ist ein Android-15-Gerät, aber **kein belegtes 16-KB-Testgerät**: Ein „Boot with 16 KB page size“-Schalter ist auf den vorhandenen Entwickleroptionen nicht sichtbar, und öffentliche Quellen nennen ihn für das G84 nicht. Ein Lauf auf dem G84 beweist daher keine 16-KB-Kompatibilität. Der Build-Host darf 4-KB-Seiten haben; geprüft wird das **erzeugte APK**.

#### 3.7.1 Testoptionen

| Option | Was sie prüft | Pro | Contra |
|---|---|---|---|
| Statische ELF-/ZIP-Prüfung | Ausrichtung und RELRO aller `.so` im APK | kein Zusatzgerät, sofort, reproduzierbar | kein Laufzeitbeweis |
| 16-KB-Emulator, arm64 | echtes Laden der arm64-Libraries auf 16-KB-Kernel | kein physisches Gerät, wiederholbar | PC/Cloud nötig; arm64-Emulator langsam; nicht das G84 |
| 16-KB-Pixel/Leihgerät | reale Hardware und Kernel | bester Laufzeitbeweis | Gerät nötig; Toggle ggf. mit OEM-Unlock/Data-Wipe |
| Cloud-Gerät | reale 16-KB-Hardware | kein eigenes Gerät | Upload, Konto/Kosten, Datenschutz |

Statisch ist **immer Pflicht**:

- **APK:** `zipalign -c -P 16 4 app-debug.apk`, `llvm-readelf -Wl` (LOAD-Align `2**14`) und RELRO-Prüfung.
- **Guest:** Rootfs-Binaries, JDK-`.so`, `aapt2`, `zipalign`, PRoot und Loader mit derselben Prüfung.

**Entscheidung (2026-11-03):** Phase 1 prüft nur statisch und baut auf dem G84. Ein echter 16-KB-Lauf wird Phase 2; er wird nur nachgerüstet, wenn ein ARM64-16-KB-Emulator, ein 16-KB-Pixel/Leihgerät oder ein Cloud-Gerät verfügbar ist. Das blockiert M1.1 nicht und hält das „kein PC“-Ziel intakt. Ein 4-KB-Lauf auf dem G84 ersetzt den 16-KB-Test nicht.

#### 3.7.2 Guest-Toolchain

Die APK-Prüfung allein reicht nicht. Auch die Gast-Toolchain muss auf 16-KB-Geräten ladbar sein:

- Debian-Binaries (`/bin/bash`, `/usr/bin/java`)
- JDK-Native-Libraries
- `aapt2`
- `zipalign`
- PRoot/Loader

Phase 1 prüft diese statisch, auch wenn der Lauf auf dem 4-KB-G84 stattfindet. Phase 2 führt den echten Lauf auf einem 16-KB-Gerät oder Emulator aus. Ein 4-KB-only **APK** ist nicht akzeptabel; ein 4-KB-Build-Host ist erlaubt.

Quellen:

- https://developer.android.com/guide/practices/page-sizes
- https://github.com/termux/termux-packages/issues/21688

Konsequenz: PRoot, Loader, Guest-Binaries, JDK, aapt2 und zipalign mit NDK r28+ bzw. 16-KB-tauglichen Builds erzeugen und prüfen.

### 3.8 Kein Root

Das Moto G84 ist nicht gerootet. Phase 1 setzt **kein Root** voraus; PRoot ist genau für unprivilegierte Android-Apps gebaut. Root wäre für diesen Plan weder nötig noch hilfreich.

Was ohne Root gilt:

- **PRoot statt chroot:** Kein `chroot`, `mount`, `setuid` oder `su`. PRoot emuliert Rootfs, Bind-Mounts und `chroot` über `ptrace` im Userspace.
- **`-0` ist nur ein Fake-Root:** Innerhalb des Guests sieht der Build `root`, aber die App bekommt keine Android-Systemrechte. `apt` kann als Fake-Root arbeiten; echte Systemänderungen sind nicht möglich.
- **W^X bleibt aktiv:** Dateien in `filesDir` bleiben nicht ausführbar. Deshalb liegen PRoot und Loader als Native Libraries im APK und werden aus `nativeLibraryDir` gestartet.
- **Alles app-private:** JDK, Gradle, SDK, Debian-Rootfs und Caches liegen unter `filesDir`; es gibt keine systemweite Installation und keinen Zugriff auf `/data/local/tmp`.
- **Keine echte Sandbox:** Ohne Root bleibt der Build unter derselben Android-UID wie die App. PRoot isoliert das Dateisystem, aber nicht den Prozess. Der Build kann weiterhin App-Dateien lesen, wenn er sie kennt.
- **Kein Zugriff auf andere Apps:** Das ist ein Vorteil: Der Build kommt nicht an deren Daten. Das Moto G84 bleibt ein normales, nicht gerootetes Gerät.
- **APK-Installation:** Läuft über `REQUEST_INSTALL_PACKAGES` und den Systeminstaller; der Nutzer bestätigt jede Installation. Kein Root nötig.
- **Foreground Service:** Funktioniert ohne Root; `dataSync`, Notification und WakeLock bleiben wie geplant.
- **16-KB-Prüfung:** Die ELF-Ausrichtung kann die App oder ein Guest-Kommando prüfen; Root ist nicht nötig.
- **targetSdk-28-Fallback:** Wäre ebenfalls ohne Root möglich, bleibt aber die letzte Option und widerspricht dem modernen App-Verhalten.

Der einzige neue, harte No-Root-Punkt ist der **Android Phantom Process Killer**:

- Android 12+ kann zusätzliche Child-Prozesse beenden, wenn zu viele gleichzeitig laufen. Gradle und Kotlin starten mehrere Worker und Daemons.
- Auf Android 14+ gibt es in den Entwickleroptionen den Schalter **„Disable child process restrictions“**. Auf dem Moto G84 ist er als **„Beschränkungen für Unterprozesse deaktivieren“** vorhanden und aktiv; siehe 3.8.1.
- Falls der Schalter fehlt, bleibt nur der ADB-Weg (`settings put global settings_enable_monitor_phantom_procs false`) oder eine Reduzierung der Gradle-Worker. Der ADB-Weg ist ohne PC unpraktisch und daher kein Phase-1-Standard.
- Der Schalter ist **kein Root** und keine App-Berechtigung; er ist eine einmalige Geräte-Einstellung des Nutzers.
- Die App muss den Zustand erkennen können und eine verständliche Anleitung anzeigen, wenn ein Build mit Signal 9 abgebrochen wird.
- Zusätzlich sollte der Build mit `--max-workers=2` starten, um die Prozesszahl niedrig zu halten.

#### 3.8.1 Konkrete Prüfung auf dem Moto G84

Status: **bestätigt am 2026-11-03** — Schalter vorhanden, aktiv und nach Neustart persistent.

1. Einstellungen → Über das Telefon → Build-Nummer siebenmal antippen.
2. Einstellungen → System → Entwickleroptionen öffnen.
3. Nach **„Disable child process restrictions“** suchen; die deutsche Übersetzung kann z. B. „Einschränkungen für untergeordnete Prozesse deaktivieren“ lauten.
4. Den Schalter aktivieren und das Gerät neu starten.
5. Entwickleroptionen danach **nicht wieder deaktivieren**; auf Android 14+ wird der Schalter sonst automatisch unwirksam.

Ergebnis am 2026-11-03:

- **„Beschränkungen für Unterprozesse deaktivieren“** ist vorhanden und **eingeschaltet**.
- **„Hintergrundprozesslimit“** steht auf **„Standardlimit“** und bleibt dort.
- Die Entwickleroptionen bleiben aktiv; sonst wird der Schalter auf Android 14+ automatisch unwirksam.
- Nach einem Neustart geprüft: Der Schalter bleibt aktiv; die Entwickleroptionen bleiben ebenfalls aktiv.

Wichtig: „Hintergrundprozesslimit“ ist **nicht** dieselbe Einstellung. Es begrenzt die Zahl der Hintergrundprozesse. „Standardlimit“ ist richtig; „Keine Hintergrundprozesse“ bis „Höchstens 4 Prozesse“ sind für den Build schädlich.

Falls der Schalter fehlt: zuerst prüfen, ob die Entwickleroptionen wirklich aktiv sind. Motorola kann den Eintrag in der System-Settings-Suche anders benennen oder ausblenden. Ohne Schalter bleibt der ADB-Weg oder eine Reduzierung der Gradle-Worker; beides muss in M1.1 getestet werden.

Optionale ADB-Verifikation, falls ADB verfügbar ist:

```sh
adb shell settings get global settings_enable_monitor_phantom_procs
adb shell device_config get activity_manager max_phantom_processes
```

Die App soll den Zustand nicht raten: Sie erkennt einen Build-Abbruch mit Signal 9 und zeigt dann die Anleitung für den Entwickler-Schalter an.

### 3.9 GitHub-Authentifizierung

Phase 1 verwendet **genau einen Classic PAT** mit den Scopes `repo` und `workflow`. Der bestehende Code speichert ihn bereits verschlüsselt über den Android Keystore und nutzt ihn für User-Prüfung, Repo-Zugriff, Push, Workflow-Dispatch, Logs und Artefakte.

Es gibt **keinen Token-Broker und keine Token-Erzeugung**. GitHub bietet keine API zum Erzeugen von PATs; ein PAT kann außerdem keine zusätzlichen Rechte vergeben. Eine GitHub App ist nicht Phase 1, weil sie einen Private Key und ein Backend benötigt.

Wenn die App später Repositories erstellt oder Actions-Secrets setzt, verwendet sie denselben PAT und ergänzt die entsprechenden API-Aufrufe. Es werden keine Kind-Tokens oder separaten Repo-Tokens benötigt.

## 4. Architektur

```text
MainActivity
  └── BuildScreen / BuildViewModel
        ├── ToolchainRepository
        │     ├── Download + SHA-256 + Entpacken
        │     └── ToolchainState
        ├── BuildRepository
        │     ├── BuildJobEntity (Room)
        │     ├── Workspace-Kopie
        │     └── BuildEvent-Flow
        └── BuildService (Foreground, dataSync)
              ├── Notification
              ├── PARTIAL_WAKE_LOCK
              ├── ProotEngine
              │     ├── nativeLibraryDir/libproot.so
              │     ├── nativeLibraryDir/libproot-loader.so
              │     ├── filesDir/toolchains/<version>/rootfs
              │     ├── filesDir/builds/<job>/project
              │     └── filesDir/builds/<job>/out
              └── ProcessBuilder + Log-Reader
```

### 4.1 Verzeichnisse

```text
filesDir/
  toolchains/
    <version>/
      rootfs/                 Debian arm64
      sdk/                    android-36, build-tools, platform-tools
      gradle/                 Gradle 8.14.3
      manifest.json           Version, SHA-256, Größen
  builds/
    <job>/
      project/                Kopie des Workspace
      home/                   HOME, Gradle-Caches
      out/                    app-debug.apk, build.log
  workspace/                  bestehender Agent-Workspace
```

`cacheDir/local-build/` enthält nur die fertige APK für den `FileProvider`.

### 4.2 PRoot-Aufruf

Der genaue Aufruf wird in M1.1 verifiziert. Zielbild:

```sh
PROOT_LOADER="$NATIVE/libproot-loader.so"
PROOT_TMP_DIR="$FILES/tmp"
LD_LIBRARY_PATH="$NATIVE"

"$NATIVE/libproot.so" \
  --link2symlink \
  --kill-on-exit \
  --sysvipc \
  -0 \
  -r "$ROOTFS" \
  -b /dev -b /proc -b /sys \
  -b "$PROJECT:/workspace" \
  -b "$SDK:/opt/android-sdk" \
  -b "$HOME:/root" \
  -w /workspace \
  /usr/bin/env -i \
    HOME=/root \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_USER_HOME=/root/.android \
    GRADLE_USER_HOME=/root/.gradle \
    GRADLE_OPTS=-Dorg.gradle.parallel=false \
    TMPDIR=/tmp \
    PATH=/usr/local/bin:/usr/bin:/bin \
    /bin/bash -lc 'gradle --no-daemon --max-workers=2 --console=plain :app:assembleDebug'
```

Wichtige Regeln:

- Kein `./gradlew` verwenden. Der Wrapper wird nur validiert; ausgeführt wird das System-Gradle 8.14.3 aus dem Toolchain-Pack.
- `gradle` kommt aus dem Toolchain-Pack.
- `--max-workers=2` und `org.gradle.parallel=false` sind im ersten Test Pflicht.
- `--no-daemon` gilt für den ersten Test. Eine warme Daemon-Variante wird erst nach Messung geprüft; ein Daemon muss beim Abbruch mit beendet werden.
- `local.properties` wird im Build-Projekt mit `sdk.dir=/opt/android-sdk` neu geschrieben.
- `android.aapt2FromMavenOverride` wird in `$HOME/.gradle/gradle.properties` gesetzt.
- `LD_PRELOAD` wird geleert.
- `PROOT_TMP_DIR` liegt nicht auf `/sdcard`.
- Der Prozess wird mit `--kill-on-exit` gestartet; die App beendet bei Abbruch zusätzlich den Prozessbaum.

### 4.3 Toolchain-Pack

Inhalt:

| Komponente | Version | Quelle |
|---|---|---|
| Debian Rootfs | 12 bookworm arm64 | `debian-bookworm-aarch64-pd-v4.7.0.tar.xz`, gepinnt per SHA-256, Auslieferung als `tar.zst` |
| OpenJDK | 17 | Debian `openjdk-17-jdk-headless` |
| Gradle | 8.14.3 | Gradle-Distribution |
| Android Platform | 35 + 36 | offizielle Platform-Zips |
| Build Tools | 35.0.0 | statische aarch64-Tools |
| aapt2 | 35.0.2 | statisch aarch64 (`android-sdk-tools`) |
| zipalign | 35.0.2 | statisch aarch64 (`android-sdk-tools`) |
| PRoot | Termux-Fork | eigener NDK-Build |

Das Rootfs wird als `tar.zst` ausgeliefert, nicht als normales ZIP. Der Extractor muss Symlinks, Unix-Modi und Hardlinks erhalten; `ZipInputStream` allein reicht nicht.

Das Pack wird versioniert, per HTTPS geladen, mit SHA-256 geprüft und in einem temporären Verzeichnis entpackt. Erst nach erfolgreicher Prüfung wird es atomar nach `toolchains/<version>/` verschoben.

Phase 1 darf das Pack für Entwicklungszwecke auch aus einem lokalen Test-URL beziehen. Der produktive Downloadpfad muss aber ohne PC funktionieren.

### 4.4 Build-Ablauf

1. Nutzer öffnet **Build**.
2. App prüft Toolchain, Speicher und Workspace.
3. `ProjectCopier` kopiert das ausgewählte Projekt roh nach `builds/<job>/project` (binärsafe; ohne `.git`, `build`, `.gradle`, `.idea`, `.kotlin`, `node_modules`). **Nicht** `WorkspaceRepository.snapshot()` verwenden: Der Snapshot ist text-only, max. 500 Dateien/5 MB und überspringt Binärdateien.
4. App schreibt `local.properties` und die Gradle-Properties.
5. App startet `BuildService` mit `dataSync`.
6. Service startet PRoot und liest stdout/stderr zeilenweise.
7. Logs werden in `build.log` und als Flow an die UI geschrieben.
8. Die APK-Ausgabe wird dynamisch unter `app/build/outputs/apk/debug/` gesucht (Flavors/Splits berücksichtigen) und nach `cacheDir/local-build/<job>/` kopiert.
9. Die UI zeigt **Installieren**; der bestehende `ApkInstaller` öffnet den Systeminstaller.
10. Bei Fehler bleiben Log, Exit-Code und letzte Zeilen sichtbar.

### 4.5 Datenmodell

Neue Room-Entität `BuildJobEntity`:

```text
id: String
status: QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELLED
projectName: String
startedAt: Long
finishedAt: Long?
exitCode: Int?
apkPath: String?
logPath: String
error: String?
```

Datenbankversion 3 → 4 mit Migration. Keine Secrets in der Entität.

### 4.6 UI

Neuer fünfter Tab **Build**:

- Toolchain-Karte: installiert / fehlt / Downloadgröße / Speicher
- Projektauswahl: unterstützte Android-Projekte im Workspace, zuletzt verwendet
- Projekt-Karte: Workspace-Pfad, Modul und geschätzte Größe
- Build-Button
- Fortschritt und Status
- Log-Ansicht mit maximal 5.000 Zeilen im Speicher
- Abbrechen
- Installieren
- Fehlerzustand mit konkreter Ursache

Der Tab ist immer sichtbar. Ohne installierte Toolchain zeigt er die Installationskarte; nach der Installation den Build-Bereich.

### 4.7 Toolchain-Hosting

„Hosting des Toolchain-Packs“ bedeutet: **Wo liegt die 1–2-GB-Datei, die die App beim ersten Start herunterlädt?** Der Build selbst bleibt lokal; gehostet wird nur das FOSS-Toolchain-Archiv (Debian-Rootfs, JDK, Gradle, SDK, aapt2).

Anforderungen:

- HTTPS und stabile Download-URL
- SHA-256 pro Datei
- Unterstützung für große Dateien und idealerweise Range-Requests
- versionierte, unveränderliche Pakete
- keine Secrets im Paket
- Lizenz- und Quellenhinweise
- für einen einzelnen Nutzer ohne Serverwartung

Optionen:

| Option | Pro | Contra |
|---|---|---|
| GitHub Releases | kostenlos, CDN, versioniert, Assets bis 2 GiB, öffentlich ohne Token, einfache SHA-Dateien | 2-GiB-Limit pro Asset, mehrere Teile nötig, öffentlich, keine SLA, private Repos brauchen Token |
| Cloudflare R2 | 10 GB frei, kein Egress-Entgelt, S3-API, Range, versionierte Objekte, signed URLs möglich | Konto und Konfiguration nötig, öffentlicher Bucket oder Worker, mehr Komplexität |
| Eigener Server | volle Kontrolle, privat, Range, Auth | Server, TLS, Kosten, Ausfälle und Wartung |
| APK/OBB bündeln | offline, kein Hosting | APK wird riesig, Updates laden alles neu, F-Droid/Play unpraktisch |
| Google Drive/Mega | schnell eingerichtet | unzuverlässige Direktlinks, Quoten, Captcha, kein sauberes Resume/Checksum |
| IPFS/Torrent | content-addressed, resumierbar, günstig | unzuverlässig, blockiert, komplexe App-Integration |
| Auf dem Gerät aus Quellen bauen | kleinerer initialer Download | viele Quellen, langsam, fragil, schlecht reproduzierbar |

**Entscheidung für Phase 1:** GitHub Releases mit **komponentenweise getrennten Assets** (Rootfs als `tar.zst`, JDK, Gradle, SDK 35/36, Build-Tools/aapt2/zipalign), jeweils unter 2 GiB, plus `manifest.json` mit Version, Größe und SHA-256. Die SHA-256-Werte werden in der App gepinnt, nicht nur aus demselben Download geladen. Das Paket enthält keine Nutzerdaten und kann öffentlich sein. **Cloudflare R2 bleibt nur Fallback**, falls ein Asset das 2-GiB-Limit überschreitet oder GitHub-Downloads unzuverlässig sind.

Nicht empfohlen für Phase 1: APK-Bundling, Google Drive/Mega, Torrent und ein eigener Server. Diese erhöhen Größe, Ausfallrisiko oder Wartung, ohne den Build besser zu machen.

## 5. Arbeitspakete und Meilensteine

### M1.0 — Entscheidungen und Testfixture (1–2 Tage)

Status: **abgeschlossen am 2026-11-03**. Alle Festlegungen stehen in [PHASE1_M1_0_DECISIONS.md](PHASE1_M1_0_DECISIONS.md).

- [x] Debian-Rootfs-Version und Downloadquelle festlegen: Debian 12 bookworm arm64, `debian-bookworm-aarch64-pd-v4.7.0.tar.xz`, SHA-256 gepinnt.
- [x] Zielgerät dokumentiert: Moto G84 5G, Android 15, 12 GB RAM + 4 GB RAM Boost, 256 GB Speicher, mindestens 100 GB frei.
- [x] Mindestanforderungen dokumentiert: arm64, Android 11+ (Testgerät Android 15), 8 GB RAM empfohlen, 6 GB Minimum, 10 GB frei.
- [x] Testprojekt `hello-local-build` mit AGP 8.13.2, Kotlin 2.3.20 und minimaler Activity angelegt.
- [x] KiloCoder (`android/`) als zweites Testprojekt festgelegt; KiloCoder ist nicht die einzige Projektgrenze.
- [x] Projektauswahl-Kriterien für weitere Android-Projekte dokumentiert (8 Prüfpunkte).
- [x] Rootfs-Format `tar.zst` und symlink-/mode-erhaltender Extractor festgelegt; zstd-Decoder entschieden.
- [x] `android-sdk-tools` **35.0.2** als einzige aapt2/zipalign-Quelle gepinnt; 36.0.0 existiert nicht (Korrektur dokumentiert).
- [x] Projektmatrix (SDK 35/36, AGP 8.7.3/8.13.2) und drittes Fixture `generic-sdk35` festgelegt und angelegt.
- [x] `ProjectCopier` (raw copy, Binärdateien) und FGS-Manifestaufgaben festgelegt.
- [x] Lizenzprüfung durchgeführt: PRoot GPLv2, Debian-Pakete, JDK, Gradle, aapt2 (Apache-2.0 im Repo geprüft).
- [x] Hosting entschieden: GitHub Releases; Pack in Komponenten unter 2 GiB aufteilen. R2 bleibt nur Fallback.
- [x] Phantom-Process-Killer auf dem Moto G84 geprüft: „Beschränkungen für Unterprozesse deaktivieren“ ist vorhanden und aktiv.
- [x] Nach einem Neustart geprüft: „Beschränkungen für Unterprozesse deaktivieren“ bleibt aktiv.
- [x] 16-KB-Teststrategie festgelegt: Phase 1 nur statisch; echter 16-KB-Lauf in Phase 2, falls Emulator, Pixel/Leihgerät oder Cloud-Gerät verfügbar.
- [x] Hosting- und Prozesskiller-Ergebnis in diesen Plan eingetragen.
- [x] GitHub-Auth entschieden: ein Classic PAT mit `repo` und `workflow`; kein Token-Broker und keine Token-Erzeugung.
- [x] Akzeptanzkriterien für M1.1 festgelegt: 13 Prüfpunkte in [PHASE1_M1_0_DECISIONS.md](PHASE1_M1_0_DECISIONS.md), Abschnitt 10.

**Exit:** Testprojekte und Gerät stehen bereit.

### M1.1 — PRoot-Smoke-Test (2–3 Tage)

Status: **Harness vorbereitet am 2026-11-03, Gerätelauf offen.** Debug-APK mit PRoot-Binaries, gepinntem Rootfs-Download, symlink-erhaltendem Extraktor und Smoke-Screen liegen vor; Ablauf und Fehlerbilder stehen in [PHASE1_M1_1_RUNBOOK.md](PHASE1_M1_1_RUNBOOK.md). Die Kästchen unten werden erst nach dem Lauf auf dem Moto G84 abgehakt.

- [ ] PRoot und Loader mit NDK r28+ für arm64 bauen. (Für den Smoke-Test laufen vorerst die gepinnten Termux-Binaries; M1.2 ersetzt sie durch den eigenen Build.)
- [ ] `libproot.so`, `libproot-loader.so`, `libtalloc.so` in `jniLibs/arm64-v8a/` legen.
- [ ] `extractNativeLibs=true` setzen.
- [ ] Mini-Debian-Rootfs als tar mit einem temporären symlink-erhaltenden Extractor nach `filesDir` entpacken; kein normales ZIP.
- [ ] `libproot.so --version` aus `nativeLibraryDir` starten.
- [ ] `PROOT_LOADER` auf `nativeLibraryDir/libproot-loader.so` setzen.
- [ ] `/bin/echo ok` im Guest ausführen.
- [ ] `readlink /bin/sh` und `/bin/bash -c 'echo ok'` ausführen.
- [ ] `java -version` im Guest ausführen.
- [ ] `getconf PAGE_SIZE` und `uname -m` dokumentieren.
- [ ] Auf dem Moto G84 (Android 15) mit `targetSdk 36` testen.
- [ ] Pflicht: Build mit aktivem Schalter. Optional: Gegenprobe mit Standardlimit (Schalter aus, Neustart); Signal 9 dokumentieren.
- [ ] 16-KB-ELF-Ausrichtung statisch prüfen; in Phase 1 keinen 16-KB-Lauf erzwingen.
- [ ] Abbruch mit laufendem Guest-Prozess; keine Zombies.
- [ ] Ergebnis und Logs dokumentieren.
- [ ] Alle 13 Akzeptanzkriterien aus [PHASE1_M1_0_DECISIONS.md](PHASE1_M1_0_DECISIONS.md), Abschnitt 10, einzeln abhaken.

**Go/No-Go:** Wenn Guest-Exec unter `targetSdk 36` nicht funktioniert, wird Phase 1 hier gestoppt. Fallbacks:

1. `targetSdk 28` nur für einen separaten Sideload-Build prüfen.
2. Custom-Linker-/Loader-Ansatz prüfen.
3. PRoot verwerfen und Architektur neu entscheiden.

**Exit:** PRoot startet zuverlässig ein Guest-Kommando.

### M1.2 — Toolchain-Pack (3–5 Tage)

- [ ] Debian-Rootfs als `tar.zst` mit JDK 17, Gradle 8.14.3, SDK 35/36 und statischen Build-Tools bestücken.
- [ ] `aapt2` und `zipalign` aus `android-sdk-tools` 35.0.2 als statische aarch64-Binaries verifizieren und mit AGP 8.13.2/compileSdk 36 echt linken; bei Fehlschlag Fallback aus Abschnitt 4 der M1.0-Entscheidungen.
- [ ] `android.jar` für API 35 und 36 einspielen.
- [ ] `package.xml` für Build-Tools schreiben.
- [ ] `gradle --version`, `java -version`, `aapt2 version`, `zipalign` im Guest testen.
- [ ] `android.aapt2FromMavenOverride` setzen.
- [ ] CA-Zertifikate und Java-Truststore im Rootfs prüfen.
- [ ] Debug-Keystore (`~/.android/debug.keystore`) erzeugen und `apksigner verify` testen.
- [ ] Rootfs-Extractor mit Symlink-, Mode- und Hardlink-Erhalt implementieren.
- [ ] Installationsroutine mit Größenprüfung, Zip-Slip- und Symlink-Traversal-Schutz implementieren.
- [ ] 16-KB-ELF-Prüfung für Rootfs, JDK, aapt2, zipalign, PRoot und Loader ausführen.
- [ ] Pack als getrennte Assets mit `manifest.json` und SHA-256 erzeugen.
- [ ] Pack-Größe, entpackte Größe und Downloadzeit messen.

**Exit:** Ein Guest-Prozess kann Gradle und aapt2 ohne x86_64-Fehler starten.

### M1.3 — Hello-World-Build (2–3 Tage)

- [ ] `hello-local-build` in den Workspace legen.
- [ ] PRoot-Build mit `:app:assembleDebug` ausführen.
- [ ] APK-Ausgabe dynamisch unter `app/build/outputs/apk/debug/` ermitteln; mehrere Varianten erkennen.
- [ ] APK mit `apksigner verify` prüfen.
- [ ] APK auf einem Testgerät installieren.
- [ ] Build-Log auf Fehler und Warnungen prüfen.
- [ ] Dauer, RAM und Speicherverbrauch messen.

**Exit:** Ein echtes Debug-APK entsteht auf dem Gerät und lässt sich installieren.

### M1.4 — Mehrprojekt-Build (3–5 Tage)

- [ ] KiloCoder (`android/`) als zweites Projekt in den Build-Workspace kopieren.
- [ ] `local.properties` und Gradle-Properties setzen.
- [ ] `:app:assembleDebug` für KiloCoder bauen.
- [ ] APK-Inhalt, Paketname und Version prüfen.
- [ ] Kein Installationsversuch über die installierte App wegen Signaturkonflikt.
- [ ] Drittes Fixture `generic-sdk35` (AGP 8.7.3, compileSdk 35, weiterhin `:app`) auswählen und bauen; KiloCoder ist nicht die Grenze.
- [ ] Zwei aufeinanderfolgende Builds messen (kalt/warm).
- [ ] Speicherspitze und Gradle-Daemon-Verhalten dokumentieren.

**Exit:** Drei verschiedene Android-Projekte bauen auf dem Gerät; KiloCoder ist ein Fixture, nicht der einzige Projekttyp.

### M1.5 — App-Integration (5–7 Tage)

- [ ] `BuildJobEntity` und Migration 3 → 4.
- [ ] `BuildRepository` mit Start, Status, Log, Abbruch und APK-Pfad.
- [ ] `BuildService` als Foreground Service `dataSync`.
- [ ] Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, Service-Deklaration mit `foregroundServiceType="dataSync"`.
- [ ] `NotificationChannel`, `startForeground()`, `stopForeground()`, `START_NOT_STICKY`, `onTimeout()`.
- [ ] Notification mit Fortschritt, Abbruch und Rücksprung in die App.
- [ ] `PARTIAL_WAKE_LOCK` für die Build-Dauer.
- [ ] `POST_NOTIFICATIONS` zur Laufzeit anfragen; Verhalten bei Ablehnung testen.
- [ ] `ProotEngine` mit Toolchain-/Workspace-Pfaden.
- [ ] `ToolchainRepository` mit Download, SHA-Prüfung, tar-Entpacken, Update.
- [ ] `ProjectCopier` für binärsichere Projektkopie; nicht `WorkspaceRepository.snapshot()`.
- [ ] Projektauswahl mit Validierung (`settings.gradle*`, Wrapper, Application-Modul `:app`).
- [ ] `BuildScreen` und `BuildViewModel`.
- [ ] Fünfter Tab **Build** in `AppRoot`.
- [ ] `FileProvider`-Pfad für `cacheDir/local-build/`.
- [ ] Deutsche und englische Strings.
- [ ] Fehlerzustände: kein Speicher, Downloadfehler, SHA-Fehler, Gradle-Fehler, Abbruch, Prozess-Tod.

**Exit:** Der Nutzer kann ohne PC und ohne zweite App ein Debug-APK für ein ausgewähltes Android-Projekt bauen und installieren.

### M1.6 — Härtung und Abnahme (3–5 Tage)

- [ ] 16-KB-Ausrichtung von APK **und** Guest-Toolchain statisch prüfen; echter 16-KB-Lauf erst Phase 2, falls Testumgebung verfügbar.
- [ ] Rootfs-Symlinks/Modes, Projektkopie mit Binärdateien und aapt2/zipalign testen.
- [ ] Bildschirm aus, App im Hintergrund, Notification sichtbar.
- [ ] Notification-Permission verweigert; FGS-Status und Fehlermeldung prüfen.
- [ ] FGS-`onTimeout` und Service-Kill simulieren; Zustand wird als fehlgeschlagen markiert.
- [ ] Abbruch mitten im Build; keine Zombie-Prozesse.
- [ ] Prozess-Tod simulieren; Zustand wird als fehlgeschlagen markiert.
- [ ] Speicher voll; klare Fehlermeldung statt Crash.
- [ ] Netzwerk weg, CA-Fehler und Thermal-Throttling testen.
- [ ] Toolchain-Update und Rollback.
- [ ] Build-Log-Größenlimit und Redaktion.
- [ ] Keine Secrets im Log, keine Tokens im Rootfs.
- [ ] Unit-Tests für Command-Builder, Manifest, Zustandsmaschine, Log-Parser.
- [ ] `android/README.md` um den lokalen Build ergänzen.
- [ ] Ergebnisbericht mit Messwerten.

**Exit:** Phase 1 ist abgeschlossen; Phase 2 kann NDK/Agent-Integration planen.

## 6. Zeitplan

Realistisch für eine Person:

| Abschnitt | Dauer |
|---|---|
| M1.0 | 1–2 Tage |
| M1.1 | 2–3 Tage |
| M1.2 | 3–5 Tage |
| M1.3 | 2–3 Tage |
| M1.4 | 3–5 Tage |
| M1.5 | 5–7 Tage |
| M1.6 | 3–5 Tage |
| **Gesamt** | **19–30 Arbeitstage** |

Der kritische Pfad ist M1.1. Wenn der PRoot-Smoke-Test scheitert, werden M1.2–M1.6 nicht begonnen.

## 7. Akzeptanzkriterien

Phase 1 ist nur erfolgreich, wenn alle folgenden Punkte auf einem echten Gerät gelten:

1. `targetSdk 36` bleibt erhalten.
2. PRoot startet aus `nativeLibraryDir`, nicht aus `filesDir`.
3. Ein Guest-`bash` und `java -version` laufen.
4. Gradle und aapt2 laufen ohne x86_64-Fehler.
5. Ein Hello-World-APK wird auf dem Gerät gebaut und installiert.
6. Drei verschiedene Android-Projekte bauen auf dem Gerät; KiloCoder ist nicht die einzige Projektgrenze.
7. Der Build überlebt Bildschirm-aus für mindestens 10 Minuten.
8. Abbruch beendet PRoot und den Prozessbaum.
9. Die App zeigt bei Fehlern eine konkrete Ursache, keinen generischen Crash.
10. Es werden keine API-Keys, GitHub-Tokens oder Keystores in den Build gemountet.
11. Der Build funktioniert auf dem Moto G84 (Android 15). Android 11/13/16 sind spätere Kompatibilitätstests.
12. Die 16-KB-Ausrichtung ist für APK und Guest-Toolchain statisch geprüft. Ein echter 16-KB-Lauf ist Phase 2 und keine Phase-1-Bedingung.
13. Ein Kaltbuild bleibt unter 60 Minuten; Zielkorridor ist 20–40 Minuten.
14. Die Rootfs-/Projektkopie erhält Symlinks, Modi und Binärdateien.
15. FGS-Permissions, Notification-Channel und `onTimeout` sind vorhanden; Notification-Ablehnung ist sauber behandelt.
16. APK-Ausgabe wird dynamisch ermittelt; Flavors/Splits führen nicht zu einem falschen Pfad.

## 8. Messwerte, die Phase 1 liefern muss

- Downloadgröße des Toolchain-Packs
- entpackte Größe
- benötigter freier Speicher
- Kaltstart-Buildzeit
- Warmbuild-Zeit
- Spitzen-RAM
- APK-Größe
- Anzahl der Gradle-Fehler pro Testprojekt
- Verhalten auf dem 12-GB-Gerät; 4/6/8 GB sind spätere Kompatibilitätstests
- Verhalten auf 16-KB-Seiten-Geräten (nur falls verfügbar; sonst statische ELF-Prüfung)

Ohne diese Zahlen wird Phase 2 nicht geplant.

## 9. Risiken

| Risiko | Wahrscheinlichkeit | Auswirkung | Gegenmaßnahme |
|---|---|---|---|
| PRoot-Guest-Exec scheitert unter targetSdk 36 | Mittel | Blockiert Phase 1 | M1.1 als Go/No-Go; targetSdk-28-Fallback nur für Sideload |
| Debian-Rootfs zu groß | Mittel | Speicherprobleme | Bionic später prüfen; Rootfs minimieren; Caches begrenzen |
| Gradle zu langsam unter PRoot | Hoch | 30–60 Min. Builds | Erster Test `--no-daemon --max-workers=2`; warmen Daemon erst nach Messung prüfen |
| Rootfs-Extraktion verliert Symlinks/Modi | Hoch | Guest startet nicht | `tar.zst`; symlink-/mode-erhaltender Extractor; Tests |
| Projektkopie verliert Binärdateien | Hoch | Wrapper/Assets fehlen | `ProjectCopier` raw copy; Snapshot verboten |
| Projekt außerhalb Toolchain-Korridor | Hoch | Build bricht ab | Matrix/Validierung; drittes Fixture |
| aapt2/zipalign Bionic/glibc-Mismatch | Mittel | aapt2 startet nicht | nur statische `android-sdk-tools` |
| 16-KB-Guest-Binaries | Mittel | Guest startet auf 16-KB-Gerät nicht | Guest-ELF separat prüfen; Phase 2 |
| FGS-Notification verweigert | Mittel | Kein sichtbarer Fortschritt | Runtime-Permission; Task-Manager-Status |
| Thermal-Throttling | Mittel | 60-Minuten-Stop | Temperatur/Netzteil-Hinweis; Messung |
| Netzwerk/CA fehlt | Mittel | Gradle lädt nicht | CA/DNS/HTTPS-Test |
| aapt2-Version inkompatibel | Mittel | Build bricht ab | Mehrere aarch64-Builds testen; Override dokumentieren |
| 16-KB-Ausrichtung fehlt | Mittel | Crash auf 16-KB-Geräten | NDK r28+; ELF-Prüfung im Toolchain-Manifest |
| Phantom Process Killer beendet Gradle | Hoch | Build stirbt mit Signal 9 | Entwickleroption „Disable child process restrictions“; `--max-workers=2`; Signal-9-Erkennung in der UI |
| FGS wird vom System beendet | Niedrig | Build schlägt fehl | dataSync, WakeLock, Zustand persistieren, klare Fehlermeldung |
| Signaturkonflikt beim Selbstbuild | Sicher | Selbstbuild nicht installierbar | Nur APK prüfen; nicht über installierte App installieren |
| PRoot ist keine Sandbox | Sicher | Build-Code kann App-Daten lesen | Nur vertrauenswürdige Projekte; keine Secrets mounten |
| Lizenzproblem PRoot GPLv2 | Mittel | Keine Veröffentlichung | Private Nutzung okay; Lizenzprüfung vor Distribution |
| Toolchain-Download bricht ab | Mittel | Kein Build | Resume, SHA-Prüfung, temporäre Dateien, Wiederholung |

## 10. Sicherheitsgrenzen

PRoot ist **keine** Sicherheits-Sandbox. Der Build läuft unter derselben Android-UID wie die App. Ein bösartiges Gradle-Plugin kann:

- den Workspace lesen,
- Netzwerkzugriff nutzen,
- Dateien der App lesen, soweit sie ihm bekannt sind,
- den Build-Prozess manipulieren.

Deshalb gilt für Phase 1:

- Nur der app-private Workspace wird gebaut.
- Kein GitHub-Token, kein API-Key und kein Keystore wird in das Rootfs oder den Workspace kopiert.
- Der Build erhält nur das Projekt, das SDK und einen separaten Gradle-Home.
- Der Download des Toolchain-Packs ist HTTPS- und SHA-256-gepinnt.
- Build-Logs werden nicht ungeprüft an den Agenten oder an GitHub gesendet.
- Der Agent bekommt in Phase 1 kein `build_apk`-Tool.
- Das Gerät ist nicht gerootet. PRoot bietet nur einen Fake-Root im Guest; die App bleibt eine normale `untrusted_app` ohne Systemrechte.

## 11. Lizenz

- PRoot: GPLv2.
- Debian-Pakete: überwiegend GPL, LGPL, Apache, BSD; Notice-Dateien nötig.
- OpenJDK: GPLv2 mit Classpath Exception.
- Gradle: Apache-2.0.
- Android SDK/Build-Tools: Android SDK License, Apache-2.0-Komponenten.
- aapt2: Apache-2.0.
- KiloCoder: derzeit MIT (README/Footer).

Private Sideload-Nutzung ist unkritisch. Eine öffentliche Distribution oder F-Droid-Veröffentlichung braucht vorher eine Lizenzprüfung, insbesondere wegen GPLv2-only PRoot und der Kombination mit GPLv3-Code. Das ist **kein Phase-1-Blocker**, aber ein **Release-Gate**.

## 12. Fallback-Entscheidung

Wenn M1.1 scheitert:

### Fallback A: targetSdk 28

- Separat signierter Sideload-Build.
- Kein Play-Store, kein F-Droid-Anspruch.
- Verhaltensänderungen und moderne Android-Features gehen verloren.
- Nur akzeptabel, wenn der Nutzer ausdrücklich einen reinen Sideload-Build will.

### Fallback B: Custom Loader

- Eigenes Exec-/Loader-Shim, das Guest-Binaries über `/system/bin/linker64` startet.
- Deutlich mehr Implementierungsaufwand.
- Muss von PRoot unabhängig getestet werden.

### Fallback C: Externe Termux-App

- Widerspricht der Anforderung „keine andere App“.
- Wird nicht als Phase-1-Lösung verfolgt.

### Fallback D: Cloud-only

- Bestehender GitHub-Actions-Pfad.
- Widerspricht dem Ziel „lokal auf dem Handy“.

## 13. Offene Punkte vor dem Start

Keine offenen Architekturentscheidungen. Debug-only und Mehrprojekt-Unterstützung sind bestätigt; Hosting, 16-KB-Strategie und Prozesskiller sind entschieden. Offene Umsetzungsaufgaben stehen in M1.0; die P0-Korrekturen sind in den Meilensteinen verankert.

16-KB ist entschieden: Phase 1 prüft nur statisch; ein echter 16-KB-Lauf folgt in Phase 2, wenn Emulator, Pixel/Leihgerät oder Cloud-Gerät verfügbar ist.

Hosting ist entschieden: **GitHub Releases** mit komponentenweisen Assets; R2 bleibt Fallback.

## 14. Quellen

- Android 10 behavior changes: https://developer.android.com/about/versions/10/behavior-changes-10
- AOSP SELinux `app_neverallows.te`: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te
- AOSP SELinux `app.te`: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app.te
- Termux W^X issue: https://github.com/termux/termux-app/issues/1072
- Termux native-library execution: https://github.com/termux/termux-app/issues/2155
- Termux proot: https://github.com/termux/proot
- Termux proot-distro: https://github.com/termux/proot-distro
- Termux 16-KB rebuild: https://github.com/termux/termux-packages/issues/21688
- On-device Gradle build log: https://github.com/sam1am/termux_crt/blob/main/BUILDING.md
- Termux SDK 36 aapt2: https://github.com/termux/termux-packages/pull/30323
- aarch64 SDK tools: https://github.com/lzhiyong/android-sdk-tools
- AGP 8.13 release notes: https://developer.android.com/build/releases/agp-8-13-0-release-notes
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Foreground service timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout
- Android 15 behavior changes: https://developer.android.com/about/versions/15/behavior-changes-15
- 16-KB page sizes: https://developer.android.com/guide/practices/page-sizes
- 16-KB developer option: https://source.android.com/docs/core/architecture/16kb-page-size/16kb-developer-option
- GitHub release asset limit: https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases
- Cloudflare R2 pricing: https://developers.cloudflare.com/r2/pricing/
- Android phantom process killer: https://github.com/sabamdarif/termux-desktop/blob/main/docs/disable-phantom-process-killing.md
- Motorola developer options: https://en-us.support.motorola.com/app/answers/detail/a_id/160067/~/developer-options

## 15. Nächster Schritt

Vor jeder Implementierung:

1. Diesen Plan freigeben.
2. M1.0 starten.
3. M1.1 auf dem Zielgerät ausführen.
4. Erst nach dem Go/No-Go mit M1.2 fortfahren.

Der Plan ändert keine App-Funktion. Er legt nur fest, was Phase 1 beweisen muss und wann abgebrochen wird.
