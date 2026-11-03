# Phase 1 / M1.1 — Runbook für den PRoot-Smoke-Test

Stand: 2026-11-03, vorbereitet ohne Gerätezugriff
Gehört zu: [PHASE1_LOCAL_BUILD_PLAN.md](PHASE1_LOCAL_BUILD_PLAN.md), M1.1
Akzeptanzkriterien: [PHASE1_M1_0_DECISIONS.md](PHASE1_M1_0_DECISIONS.md), Abschnitt 10

Ziel: Auf dem Moto G84 prüfen, ob PRoot unter `targetSdk 36` ein Guest-Kommando
startet. Das ist das Go/No-Go für Phase 1.

## Was vorbereitet ist

| Teil | Ort |
|---|---|
| Debug-only Activity mit Start/Abbrechen/Bericht | `app/src/debug/java/com/kilocoder/android/m11/M11SmokeActivity.kt` |
| Ablauf und 12 Prüfungen | `.../m11/M11Smoke.kt` |
| PRoot-Start aus `nativeLibraryDir` | `.../m11/ProotEngine.kt` |
| tar.xz-Extraktor mit Symlink-, Hardlink- und Mode-Erhalt | `.../m11/TarXzExtractor.kt` |
| ELF-Prüfung (aarch64, 16-KB-LOAD) auf dem Gerät | `.../m11/ElfCheck.kt` |
| PRoot-Binaries (nur Debug) | `app/src/debug/jniLibs/arm64-v8a/`, Herkunft in [tools/m1_1/BINARIES.md](tools/m1_1/BINARIES.md) |
| zweiter Launcher-Eintrag nur im Debug-Build | `app/src/debug/AndroidManifest.xml` |

Der Test lädt das Rootfs selbst: gepinnte URL, 42.894.344 Bytes,
SHA-256 `4baa3228…b8df8`, danach symlink-erhaltendes Entpacken nach
`filesDir/m11/rootfs`.

## 1. Debug-APK bauen

Der Test existiert **nur im Debug-Build**. Ein Release-APK enthält weder die
Activity noch die PRoot-Binaries. Debug bauen wie bisher (GitHub Actions oder
lokal) und das Debug-APK installieren.

Ein APK aus dem Vorbereitungslauf liegt unter
[artifacts/m11/app-debug.apk](../artifacts/m11/app-debug.apk): 13.510.104 Bytes,
SHA-256 `5b26e2fd98fae15426cf55bf480ed16aa79aff81b0a2f10682c56c279fda7a3d`.
Es entstand **vor** der Regel „Builds nur über GitHub Actions“ und ist mit dem
temporären Debug-Key dieser Umgebung signiert. Ab jetzt ist der CI-Lauf
(`android.yml`, Artefakt `kilocoder-debug-apk`) die Quelle; das vorhandene APK
dient nur dem ersten Gerätetest, solange die Änderungen noch nicht gepusht sind.

Ist bereits ein KiloCoder-Debug-Build mit einem anderen Key installiert,
schlägt die Installation mit `INSTALL_FAILED_UPDATE_INCOMPATIBLE` fehl; dann die
alte App deinstallieren (löscht deren lokale Daten) oder den CI-Build verwenden.

`debugImplementation(libs.xz)` und die vier `.so` aus `src/debug/jniLibs` sind
die einzigen zusätzlichen Abhängigkeiten.

## 2. Installieren und starten

1. Debug-APK installieren. Im App-Drawer erscheinen zwei Einträge:
   **KiloCoder** und **M1.1 PRoot-Test**.
2. **M1.1 PRoot-Test** öffnen.
3. Optional: „JDK im Gast installieren“ ankreuzen. Ohne Haken wird der
   Java-Schritt übersprungen; er dauert 10–20 Minuten und lädt ~200 MB im Guest.
4. **Start** drücken. Bildschirm darf ausgehen, die Activity hält ihn aber wach.

Erwarteter Ablauf (Beispiel):

```text
INFO  Gerät motorola moto g84 5G
INFO  Android 15 (API 35)
INFO  PAGE_SIZE 4096 · frei …
PASS  Preflight: arm64, Libraries, Speicher
INFO  Download 8 MB / 41 MB
PASS  Rootfs heruntergeladen — 42894344 Bytes, SHA-256 stimmt
PASS  Rootfs entpackt — Dateien 8014, Verzeichnisse 1161, Symlinks 1023, …
PASS  PRoot startet aus nativeLibraryDir — proot v5.1.107…
PASS  Guest: /bin/echo ok — ok
PASS  Guest: readlink /bin/sh — dash
PASS  Guest: bash -c echo ok — ok
PASS  Guest: uname -m / PAGE_SIZE — aarch64 · 4096
PASS  Exit-Code wird durchgereicht — 7
PASS  ELF: aarch64 und 16-KB-LOAD — …
PASS  Abbruch beendet PRoot — zweiter Lauf liefert ok
INFO  Ergebnis: GO · 11 bestanden, 0 fehlgeschlagen, 1 übersprungen
```

Mit Java-Haken statt `SKIP`:

```text
PASS  Guest: java -version — openjdk version "17.0.x" …
```

## 3. Auswertung

- **GO**: keine `FAIL`-Zeile. Damit ist die Kernfrage beantwortet; M1.2 darf
  beginnen.
- **NO-GO**: mindestens ein `FAIL` bei PRoot-Start, Guest-Exec, Shell-Symlink
  oder Java. Dann Phase 1 stoppen und die Fallbacks aus Plan-Abschnitt 12
  prüfen (targetSdk 28 nur für Sideload, Custom Loader, PRoot verwerfen).
- Ein `FAIL` nur beim Abbruchtest ist kein No-Go, sondern ein Hinweis auf
  Prozessreste; `--kill-on-exit` und `destroy()` nachziehen.

Der Bericht liegt unter
`/data/data/com.kilocoder.android/files/m11/report.txt` (Debug-Build) und lässt
sich über **Bericht kopieren** in die Zwischenablage holen.

## 4. Bekannte Fehlerbilder

| Meldung | Ursache | Nächster Schritt |
|---|---|---|
| `library "libtalloc.so.2" not found` | Kopie im privaten Verzeichnis fehlt | `ProotEngine` prüfen; `libtalloc.so` muss in `nativeLibraryDir` liegen, die Kopie nach `files/m11/lib/libtalloc.so.2` muss gelingen |
| `Permission denied` beim Start von `libproot.so` | `extractNativeLibs` nicht aktiv | Debug-Manifest und `useLegacyPackaging` prüfen |
| `Preflight: Native Libraries — fehlen: …` | Debug-`jniLibs` nicht im APK | `src/debug/jniLibs/arm64-v8a/` und Build-Variante prüfen |
| Build stirbt mit Signal 9 | Prozesskiller | Entwickleroption „Beschränkungen für Unterprozesse deaktivieren“ |
| `SHA-256 falsch` | Download beschädigt oder URL geändert | Rootfs-Datei löschen, erneut starten; Pin in `M11Smoke` prüfen |
| `Rootfs entpackt`, aber `/bin/sh` fehlt | Tarball-Layout anders als erwartet | Top-Level-Verzeichnis prüfen; `ensureRootfs` verschiebt genau eine Ebene |
| `readlink /bin/sh` liefert nicht `dash` | Extraktor hat Symlinks verloren | Extraktor-Test wiederholen, `TarXzExtractor` prüfen |

## 5. Was hier noch nicht bewiesen ist

- Der Test wurde **nicht auf einem Gerät ausgeführt**. Es gibt in dieser
  Umgebung kein Moto G84 und kein ADB.
- Die PRoot-Binaries sind Termux-Pakete, nicht der eigene NDK-Build aus M1.2.
  Sie sind 16-KB-ausgerichtet, aber nur der Gerätelauf zeigt, ob sie unter
  `targetSdk 36` über `nativeLibraryDir` starten.
- Der Extraktor wurde auf einem Linux-JDK gegen das echte Rootfs-Tarball
  getestet (8014 Dateien, 1023 Symlinks, 211 MB, ~5 s). Auf Android kann
  `java.nio.file` je nach Gerät minimal abweichen; der Test meldet das als
  `FAIL` mit Pfad.
