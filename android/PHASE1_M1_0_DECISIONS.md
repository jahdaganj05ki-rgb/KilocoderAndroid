# Phase 1 / M1.0 — Entscheidungen, Mindestanforderungen und Fixtures

Status: entschieden, Fixtures angelegt
Datum: 2026-11-03
Gehört zu: [PHASE1_LOCAL_BUILD_PLAN.md](PHASE1_LOCAL_BUILD_PLAN.md), Meilenstein M1.0
Gerät: Motorola Moto G84 5G, Android 15 (API 35), 12 GB RAM (+4 GB Boost), 256 GB, ≥ 100 GB frei, kein Root

Dieses Dokument schließt M1.0 ab. Es legt die offenen Punkte fest und korrigiert
zwei Annahmen des Plans, die sich bei der Prüfung als falsch erwiesen haben
(aapt2-Version und Wrapper-Anforderung).

## 1. Debian-Rootfs: Version und Quelle

**Entscheidung: Debian 12 „bookworm“, arm64 (aarch64).**

Warum nicht Debian 13 „trixie“:

- Debian 13 enthält nur noch OpenJDK 21 und 25. `openjdk-17-jdk-headless` ist in
  trixie **nicht** mehr verfügbar (`apt` endet mit „Unable to locate package“).
- Der Toolchain-Korridor von Phase 1 ist auf **JDK 17** festgelegt: AGP 8.7.3
  verlangt JDK 17, und Gradle 8.14.3 läuft damit am besten getestet.
- Debian 12 liefert `openjdk-17-jdk-headless` als Paket und bleibt bis 2028
  unterstützt.

Gepinnte Quelle (Stand 2026-11-03 geprüft):

| Feld | Wert |
|---|---|
| Datei | `debian-bookworm-aarch64-pd-v4.7.0.tar.xz` |
| URL | `https://github.com/termux/proot-distro/releases/download/v4.7.0/debian-bookworm-aarch64-pd-v4.7.0.tar.xz` |
| SHA-256 | `4baa32280cc70b67e2c650777c1d974349f0cdf23afaabc305ad3bc6182b8df8` |
| Herkunft | Termux `proot-distro`, Plugin `debian.sh` (bookworm-Zweig), dort mit demselben SHA-256 gepinnt |
| Lizenz | Debian-Pakete (GPL/LGPL/BSD/Apache), siehe Abschnitt 9 |

Warum diese Quelle und nicht ein Vanilla-Docker-Rootfs:

- Es ist der in `proot-distro` und im dokumentierten On-Device-Gradle-Build
  (`sam1am/termux_crt`) erprobte PRoot-Pfad.
- GitHub Releases liefert HTTPS, Range-Requests und unveränderliche Assets —
  dasselbe Hosting wie für das fertige Toolchain-Pack.
- Die Datei ist ein reines Rootfs ohne Kernel und ohne Gerätenodes; PRoot bindet
  `/dev`, `/proc` und `/sys` vom Gerät ein.

Hinweis: `debuerreotype/docker-debian-artifacts` führt auf dem arm64-Zweig nur
noch `rootfs.tar.xz.sha256` und die OCI-Layout-Blobs, nicht mehr das fertige
Rootfs-Tarball. Es ist damit keine direkte Downloadquelle mehr.

Das Tarball ist von 2023. M1.2 führt deshalb im Gast
`apt-get update && apt-get full-upgrade` aus, installiert die Pakete und
dokumentiert die resultierenden Versionen im `manifest.json`.

### 1.1 Auslieferungsformat `tar.zst`

- Quelle: `.tar.xz` (nur zum Bauen des Packs).
- Auslieferung: **ein** `rootfs.tar.zst`-Stream, erzeugt mit `zstd -19 --long`.
- Kein ZIP: ZIP verliert Symlinks, harte Links und Unix-Modi, die Debian braucht
  (`/bin/sh -> dash`, `/usr/bin/java`, `/etc/alternatives/*`).
- Der Pack-Bauer prüft vor dem Hochladen: Symlink-Anzahl, harte Links,
  ausführbare Bits, `sha256sum`, entpackte Größe.

**Extraktor (M1.2) muss:**

- Symlinks, harte Links, Datei- und Verzeichnismodi erhalten;
- nur relative Pfade innerhalb des Zielverzeichnisses akzeptieren
  (keine absoluten Pfade, kein `..`, kein Symlink-Traversal nach außen);
- Gerätenodes und FIFOs überspringen (PRoot liefert `/dev` per Bind);
- Besitzer/Gruppen ignorieren (PRoot arbeitet als Fake-Root), Modi aber setzen;
- bei Abbruch das temporäre Verzeichnis löschen und erst nach vollständiger
  Prüfung atomar nach `toolchains/<version>/rootfs` verschieben;
- mindestens `3 × Packgröße` freien Speicher verlangen und vorher prüfen.

**zstd-Decoder in der App:** Android/Java bringt keinen zstd-Decoder mit.
Entscheidung: `com.github.luben:zstd-jni` (BSD-2-Clause) mit den arm64-`.so` aus
dem AAR. Diese `.so` werden wie alle Native Libraries statisch auf 16-KB-Load-
Alignment geprüft (M1.2, M1.6). Fallback, falls zstd-jni zu groß ist: Rootfs als
`.tar.gz` ausliefern (langsamer, aber `GZIPInputStream` reicht) — erst nach
Messung.

## 2. Mindestanforderungen

| Anforderung | Minimum | Testgerät |
|---|---|---|
| ABI | `arm64-v8a` (64-bit), kein 32-bit | arm64 |
| Android | 11 (API 30) | 15 (API 35) |
| `targetSdk` der App | 36 | 36 |
| RAM | 8 GB empfohlen, 6 GB hartes Minimum | 12 GB + 4 GB Boost |
| Freier Speicher (Installation) | 10 GB | ≥ 100 GB |
| Freier Speicher (während eines Builds) | zusätzlich 4 GB | vorhanden |
| Root | nicht nötig, nicht unterstützt | kein Root |
| Entwickleroption „Beschränkungen für Unterprozesse deaktivieren“ | auf Android 12+ **an** | an, nach Neustart persistent |
| „Hintergrundprozesslimit“ | „Standardlimit“ | Standardlimit |
| Benachrichtigungen | erlaubt (FGS-Sichtbarkeit) | wird bei Installation abgefragt |
| Akku-Optimierung | App nicht eingeschränkt | beim Test prüfen |

Begründung der Grenzen:

- 8 GB: Gradle + Kotlin-Compiler + d8 brauchen zusammen etwa 1,5–2,5 GB Heap und
  dazu Datei-Cache. Unter 6 GB bricht der Build regelmäßig mit `OutOfMemoryError`
  oder durch den Prozesskiller ab.
- 10 GB Installation: Rootfs (~400 MB gepackt, ~1,2 GB entpackt), JDK (~300 MB),
  Gradle (~150 MB), SDK 35/36 + Build-Tools (~500 MB), Caches.
- Android 11 als Untergrenze: `nativeLibraryDir`-Exec und PRoot funktionieren
  darunter zwar auch, aber `dataSync`-FGS-Typen und die Prozesskiller-Regeln sind
  erst ab 12/14 sauber dokumentiert. Getestet wird ausschließlich Android 15.

## 3. Toolchain-Korridor (final)

| Dimension | Festlegung |
|---|---|
| Gradle | 8.14.3 (System-Gradle aus dem Pack, Wrapper wird nicht ausgeführt) |
| JDK | 17 (Debian `openjdk-17-jdk-headless`) |
| AGP | 8.7.3 bis 8.13.2; Tests mit 8.7.3 und 8.13.2 |
| Gradle-Anforderung der AGP-Versionen | 8.7.3 → Gradle ≥ 8.9; 8.13.2 → Gradle ≥ 8.13; 8.14.3 erfüllt beides |
| compileSdk | 35 und 36 (beide `android.jar` im Pack) |
| build-tools | 35.0.0 (einziger Satz im Pack, siehe Abschnitt 4) |
| Modul | Application-Modul `:app` |
| Variante | `:app:assembleDebug`, keine Release-, Flavor- oder Split-Sonderfälle |
| NDK/Flutter/React Native | nicht unterstützt |
| Private Maven-Repos | nicht unterstützt (nur `google()`, `mavenCentral()`, `gradlePluginPortal()`) |

Der Kotlin-Compiler wird von Gradle aus Maven geladen. Der **erste** Build eines
Projekts braucht deshalb Netz; das ist kein Toolchain-Fehler, sondern normal.

## 4. Korrektur: aapt2/zipalign sind 35.0.2, nicht 36.0.0

Der Plan nannte `android-sdk-tools` **36.0.0**. Das ist falsch. Geprüft am
2026-11-03 über die GitHub-API:

- Tags des Repos: `35.0.2`, `34.0.3`, `33.0.3`
- `releases/tags/36.0.0` → HTTP 404
- `releases/latest` → `35.0.2`

**Neue Festlegung:**

| Feld | Wert |
|---|---|
| Release | `lzhiyong/android-sdk-tools` `35.0.2` |
| Asset | `android-sdk-tools-static-aarch64.zip` |
| Größe | 14.401.170 Bytes |
| Veröffentlicht | 2024-11-22 |
| Lizenz | Apache-2.0 (`LICENSE.txt` im Repo geprüft) |
| Inhalt für Phase 1 | statisches `aapt2`, `zipalign` (aarch64) |

Konsequenzen:

- Der Pack enthält **build-tools 35.0.0** als passenden Satz; die Fixtures setzen
  `buildToolsVersion = "35.0.0"` explizit, damit AGP nicht 36.0.0 anfordert.
- `aapt2` aus 35.0.2 wird über `android.aapt2FromMavenOverride` erzwungen.
- **Risiko:** AGP 8.13.2 mit `compileSdk 36` könnte Ressourcen-Features nutzen,
  die aapt2 35.0.2 nicht kennt. M1.2 prüft das mit einem echten Link, nicht nur
  mit `aapt2 version`.
- Fallback-Reihenfolge, falls aapt2 35.0.2 mit AGP 8.13.2 scheitert:
  1. den ganzen Korridor auf AGP 8.7.3 + compileSdk 35 ziehen;
  2. aapt2 36 aus AOSP für arm64 selbst bauen (aufwendig, aber machbar);
  3. `compileSdk 36` aus dem Korridor streichen und nur 35 anbieten.
- Die Version im `manifest.json` des Packs wird auf `35.0.2` gepinnt, damit ein
  späteres 36er-Release bewusst übernommen werden muss.

## 5. Testfixtures

| Fixture | Pfad | AGP | compileSdk | targetSdk | Rolle |
|---|---|---|---|---|---|
| `hello-local-build` | `android/test-fixtures/hello-local-build` | 8.13.2 | 36 | 36 | erster Build, obere Korridorkante |
| `generic-sdk35` | `android/test-fixtures/generic-sdk35` | 8.7.3 | 35 | 35 | untere Kante, „nicht nur KiloCoder“ |
| KiloCoder | `android/` | 8.13.2 | 36 | 36 | reales Projekt mit Compose, Room/KSP, OkHttp |

Details und Begründung: [test-fixtures/README.md](test-fixtures/README.md).

Die beiden neuen Fixtures haben bewusst **keine** AndroidX- oder
Fremd-Dependencies. Sie isolieren die Toolchain (aapt2, Kotlin, d8, zipalign,
Debug-Signing) von der Maven-Auflösung. KiloCoder deckt die Auflösung ab.

### 5.1 Wrapper-Regel (Korrektur)

Der Plan verlangte „Gradle Wrapper“ als Projektkriterium. KiloCoder hat aber
**keinen** Wrapper: weder `gradlew` noch `gradle/wrapper/*` (geprüft am
2026-11-03). Ein Wrapper-Jar lässt sich hier nicht erzeugen, und Phase 1 führt
den Wrapper ohnehin nie aus.

**Festlegung:**

- Pflicht ist nur `gradle/wrapper/gradle-wrapper.properties` mit einer lesbaren
  `distributionUrl` (Gradle 8.x). Daraus liest der Validator die erwartete
  Gradle-Version.
- `gradlew` und `gradle-wrapper.jar` sind **optional**. Fehlen sie, wird das
  Projekt trotzdem akzeptiert; ausgeführt wird immer das System-Gradle 8.14.3.
- Ein Projekt ohne Wrapper-Metadaten wird akzeptiert, wenn `settings.gradle*`
  und `:app` vorhanden sind — dann ohne Gradle-Versionsprüfung, mit Hinweis im
  Build-Log.
- KiloCoder einen echten Wrapper zu geben ist eine spätere, freiwillige
  Repo-Verbesserung (`gradle wrapper --gradle-version 8.14.3`), kein M1-Blocker.

## 6. Projektauswahl-Kriterien für beliebige Android-Projekte

Ein Projekt wird nur gebaut, wenn **alle** Punkte zutreffen. Die Prüfung ist
statisch und „best effort“; ein trotzdem scheiternder Gradle-Lauf bleibt ein
normaler Fehlerfall mit Log.

1. Verzeichnis enthält `settings.gradle` oder `settings.gradle.kts`.
2. Es gibt ein Application-Modul `app` (`include(":app")` bzw. `:app` mit
   `com.android.application`).
3. AGP-Version liegt zwischen 8.7.3 und 8.13.2 — gelesen aus
   `plugins { id("com.android.application") version … }`, aus
   `pluginManagement` oder aus `gradle/libs.versions.toml`.
4. `compileSdk` ist 35 oder 36.
5. Kein `externalNativeBuild`, kein `ndk { }`, kein `com.android.library` als
   einziges Modul.
6. Keine Flutter-/React-Native-Marker (`pubspec.yaml`, `react-native`-Dependency).
7. Keine privaten Maven-Repos (`maven { url = … }` außer `google()`,
   `mavenCentral()`, `gradlePluginPortal()`).
8. Build-Kommando ist immer `:app:assembleDebug`; keine Flavors, Splits oder
   Release-Varianten.

Ablehnung erfolgt mit konkreter deutscher Meldung („AGP 8.5.2 wird nicht
unterstützt; Phase 1 unterstützt 8.7.3–8.13.2“), nicht mit einem rohen
Gradle-Stacktrace.

## 7. Projektkopie (`ProjectCopier`)

- Rohkopie des ausgewählten Projektverzeichnisses, binärsafe (Streams, keine
  Text-Dekodierung).
- **Verboten:** `WorkspaceRepository.snapshot()` (text-only, 500 Dateien/5 MB,
  überspringt Binärdateien).
- Ausgeschlossen: `.git`, `.gradle`, `build`, `.idea`, `.kotlin`, `node_modules`,
  `local.properties`, `*.iml`, `captures`, `.cxx`, `.externalNativeBuild`.
- Beim Kopieren von KiloCoder zusätzlich `test-fixtures/` ausschließen, damit die
  Fixtures nicht im Buildlanden.
- Keine Secrets: `.env`, `.env.*`, `*.jks`, `*.keystore` werden übersprungen.
  Debug-Builds brauchen kein Signing-Material; der Debug-Keystore wird im Gast
  erzeugt.
- Grenzen: 2 GB und 50.000 Dateien; darüber klare Fehlermeldung.
- Symlinks werden nur kopiert, wenn ihr Ziel innerhalb des Projekts liegt;
  sonst Abbruch mit Pfadangabe.
- `local.properties` wird **neu geschrieben** (`sdk.dir=/opt/android-sdk`).
  Die `gradle.properties` des Projekts bleibt unverändert; `--no-daemon`,
  `--max-workers=2` und `parallel=false` kommen als Kommandozeilenflags, und
  `android.aapt2FromMavenOverride` steht nur im job-eigenen
  `GRADLE_USER_HOME/gradle.properties`.

## 8. Foreground-Service: Manifest- und Laufzeitaufgaben (M1.5)

Manifest:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<service
    android:name=".build.BuildService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

Laufzeit:

- `startForeground()` aus der sichtbaren Build-Activity (Android 15 verbietet
  FGS-Starts aus dem Hintergrund).
- `NotificationChannel` (niedrige Wichtigkeit, Fortschritt + Abbrechen + Rücksprung).
- `POST_NOTIFICATIONS` zur Laufzeit anfragen; bei Ablehnung läuft der FGS weiter,
  der Status ist dann nur in der App/Task-Manager sichtbar.
- `PARTIAL_WAKE_LOCK` für die Build-Dauer, im `finally` freigeben.
- `onTimeout()` implementieren und den Build sauber stoppen (Android 15 begrenzt
  `dataSync` auf 6 h/24 h; die App stoppt zusätzlich nach 60 min).
- `START_NOT_STICKY`, `stopForeground(STOP_FOREGROUND_REMOVE)`, `onDestroy()`,
  `onTaskRemoved()`: Prozessbaum beenden, Zustand als `FAILED`/`CANCELLED`
  persistieren, keine Zombies.

## 9. Lizenzstand

| Komponente | Lizenz | Status |
|---|---|---|
| PRoot (Termux-Fork) | GPL-2.0-only | Binaries werden mitgeliefert → Quellen/Angebot nötig bei Distribution |
| Debian-12-Pakete | GPL, LGPL, BSD, Apache (gemischt) | Notice-Dateien je Paket sammeln |
| OpenJDK 17 (Debian) | GPL-2.0 mit Classpath Exception | unkritisch |
| Gradle 8.14.3 | Apache-2.0 | unkritisch |
| AGP 8.13.2 / 8.7.3 | Apache-2.0 | unkritisch |
| aapt2/zipalign aus `android-sdk-tools` 35.0.2 | Apache-2.0 (Repo-Lizenz geprüft) | unkritisch |
| Android SDK Platform 35/36 | Android SDK License | Notice beilegen |
| zstd-jni | BSD-2-Clause | im Pack vermerken |
| KiloCoder | MIT | unverändert |

Private Sideload-Nutzung ist unkritisch. **Release-Gate:** vor öffentlicher
Distribution oder F-Droid müssen PRoot-Quellen (Commit + Build-Skript) und die
Debian-Notices beiliegen. GPLv2-only PRoot und MIT-App bleiben getrennte
Programme, solange PRoot unverändert als eigenes Binary ausgeliefert wird.

## 10. Akzeptanzkriterien für M1.1 (Go/No-Go)

Alle Punkte werden auf dem Moto G84 mit `targetSdk 36` und aktivem Schalter
„Beschränkungen für Unterprozesse deaktivieren“ geprüft. Jeder Punkt ist
bestanden oder nicht; es gibt kein „teilweise“.

| # | Kriterium | Bestanden wenn |
|---|---|---|
| 1 | Installation | Debug-APK installiert und startet auf Android 15 |
| 2 | PRoot-Start | `nativeLibraryDir/libproot.so --version` endet mit Exit 0 und gibt eine Version aus |
| 3 | Loader | `PROOT_LOADER` zeigt auf `nativeLibraryDir/libproot-loader.so`; Start ohne Loader-Fehler |
| 4 | Guest-Exec | `-r rootfs -0 /bin/echo ok` gibt exakt `ok` aus |
| 5 | Shell-Symlink | `readlink /bin/sh` liefert `dash`; `/bin/bash -c 'echo ok'` liefert `ok` |
| 6 | Java | `java -version` meldet `17.0.x` und Exit 0 |
| 7 | Architektur | `uname -m` → `aarch64`; `getconf PAGE_SIZE` wird dokumentiert (G84 erwartet 4096) |
| 8 | Rootfs-Integrität | Symlink `/bin/sh`, Exec-Bit auf `/usr/bin/java`, ein harter Link und ein Verzeichnismodus stimmen nach der Extraktion |
| 9 | Exit-Code | `false` im Guest wird als Fehler (≠ 0) an die App gemeldet |
| 10 | Abbruch | Abbruch während `sleep 60` beendet PRoot und alle Guest-Prozesse; `ps` zeigt keine Reste; der nächste Lauf funktioniert |
| 11 | Prozesskiller | Ein 10-Minuten-Lauf mit mehreren Kindprozessen überlebt ohne Signal 9 |
| 12 | 16-KB statisch | `libproot.so`, `libproot-loader.so`, `libtalloc.so` haben LOAD-Alignment `2**14` |
| 13 | Logs | Jeder Schritt schreibt Exit-Code und die letzten Zeilen in `build.log`; keine Tokens, keine Pfade außerhalb `filesDir` |

**No-Go:** Wenn 2, 3, 4, 5 oder 6 scheitert, wird Phase 1 an dieser Stelle
gestoppt und die Fallback-Entscheidung aus Plan-Abschnitt 12 getroffen. Ein
Fehlschlag von 11 allein ist kein No-Go, sondern ein Konfigurationsbefund
(Entwickleroption prüfen, Worker reduzieren).

Optionale Gegenprobe (nicht Go/No-Go): Schalter aus, Neustart, gleicher Lauf →
Signal 9 dokumentieren.

## 11. Offene Punkte und Grenzen

- **Kein Gerätezugriff in dieser Umgebung.** M1.1 ist vorbereitet, aber nicht
  ausgeführt. Ohne Moto G84/ADB gibt es kein Go/No-Go.
- **KiloCoder hat keinen Gradle Wrapper.** Kein Blocker (Abschnitt 5.1), aber
  eine Lücke im Repo.
- **aapt2 35.0.2 gegen AGP 8.13.2/compileSdk 36** ist plausibel, aber ungetestet.
  M1.2 muss das mit einem echten Link prüfen.
- **Rootfs-Basis ist von 2023.** M1.2 aktualisiert im Gast und pinnt die
  resultierenden Paketversionen im Manifest.
- **PRoot-Binaries existieren noch nicht.** M1.1 baut sie mit NDK r28+; ohne
  NDK-Build gibt es keinen Smoke-Test.
