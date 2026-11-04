# M1.2 · PRoot mit NDK r28

Seit M1.2 baut der Android-Workflow PRoot selbst. `build-proot.sh` läuft vor
Gradle, ersetzt die vier Debug-Bibliotheken in
`app/src/debug/jniLibs/arm64-v8a/` und bricht ab, wenn eine davon nicht
16-KB-ausgerichtet ist. Der M1.1-Smoke-Test bestand mit diesem Build am
2026-11-03 auf dem Moto G84 12 von 12 Prüfungen; auf dem Gerät meldeten alle
vier Bibliotheken `align=0x4000`.

## Warum

M1.1 hat mit gepinnten Termux-Paketen bewiesen, dass Guest-Exec unter
`targetSdk 36` funktioniert. Diese Pakete sind fertige Fremd-Binaries ohne
Build aus dem Repo. M1.2 ersetzt sie durch einen reproduzierbaren Build aus
Quellcode, damit 16-KB-Ausrichtung, Loader und Abhängigkeiten unter eigener
Kontrolle stehen.

## Quellen (gepinnt)

| Komponente | Version | Quelle | SHA-256 |
|---|---|---|---|
| PRoot | 5.1.107.102 | `termux/proot`, Tag `v5.1.107.102` (zip archive) | `30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852` |
| talloc | 2.5.0 | `samba.org/ftp/talloc` | `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007` |
| libandroid-shmem | 0.7 | `termux/libandroid-shmem`, Tag `v0.7` | `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867` |

NDK: `28.2.13676358` (r28c), Ziel `aarch64-linux-android26`. Die App hat
`minSdk 26`; ein niedrigeres API-Level würde die Bibliotheken nur künstlich
einschränken.

## Ablauf

1. Toolchain aus `ANDROID_NDK_HOME` verwenden.
2. talloc mit `--cross-compile` und `cross-answers.txt` bauen; die Datei ist
   aus dem Termux-`libtalloc`-Paket für dieselbe Version übernommen.
3. `libandroid-shmem.so` mit Version-Skript bauen und nach
   `shmem-prefix/usr` installieren, inklusive `sys/shm.h`. Bionic definiert
   `_PATH_TMP` nicht, deshalb wird es als `/tmp/` übergeben.
4. PRoot mit `PROOT_WITH_LIBANDROID_SHMEM=true`, `-ltalloc` und
   `-landroid-shmem` bauen. `-I` auf das shmem-Prefix stellt sicher, dass
   `<sys/shm.h>` die Emulation und nicht den Bionic-Stub trifft. `OBJCOPY`
   und `OBJDUMP` zeigen auf die LLVM-Tools des NDK, damit der Loader als
   `loader-wrapped.o` eingebettet werden kann.
5. Loader separat mit `LOADER_LDFLAGS` und derselben Seitengröße linken.
6. Nach `jniLibs` kopieren und prüfen: ELF64/AArch64, jedes
   `PT_LOAD`-Alignment ≥ `0x4000`, Interpreter `/system/bin/linker64`,
   `DT_NEEDED` `libtalloc.so.2` und `libandroid-shmem.so`, SONAME von talloc.
7. Bericht mit Größen und SHA-256 nach `app/build/proot-ndk/report.txt`
   schreiben.

Die vier Ausgabedateien behalten die Namen aus M1.1. `ProotEngine` und der
Smoke-Test brauchen keine Änderung.

## Toolchain-Pack

Den Phase-1-Pack baut `.github/workflows/android-toolchain.yml`, nie ein
lokaler Rechner. Der Workflow läuft bei Änderungen unter
`android/tools/m1_2/toolchain/**` und per `workflow_dispatch`.
`toolchain/build-pack.sh` lädt die gepinnten Quellen, startet das arm64-Rootfs
mit qemu-user und ruft `toolchain/build-in-chroot.sh` auf; `check-elf.py` prüft
die ELF-Ausrichtung, `write-manifest.py` schreibt den Bericht. Das Artefakt
`android-toolchain-pack` enthält `rootfs.tar.zst`, `manifest.json`,
`SHA256SUMS`, die ELF-Berichte, `toolchain-report.txt` und die Protokolle von
aapt2, zipalign und apksigner.

| Komponente | Pin |
|---|---|
| Basis | Debian 12 bookworm arm64, proot-distro v4.7.0, SHA-256 `4baa3228…b8df8` |
| JDK | Debian `openjdk-17-jdk-headless`, nach `apt-get full-upgrade` |
| Gradle | 8.14.3, SHA-256 `bd711022…f3531` |
| Android Platform | 35 (`platform-35_r02.zip`) und 36 (`platform-36_r02.zip`) |
| Build-Tools | 35.0.0, `aapt2`/`zipalign` statisch aus `android-sdk-tools` 35.0.2 |
| Java-Tools | `apksigner`/`d8` aus Build-Tools 35.0.0 |

Im Gast liegen die Platform-JARs unter `/opt/android-sdk/platforms/android-35`
und `android-36`, die Tools unter `/opt/android-sdk/build-tools/35.0.0`,
Gradle unter `/opt/gradle/gradle-8.14.3`. `/etc/profile.d/kilocoder-toolchain.sh`
setzt `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` und `PATH`;
`/root/.gradle/gradle.properties` setzt `android.aapt2FromMavenOverride`.
Der Debug-Keystore liegt unter `/root/.android/debug.keystore`.

Der Gast-Test linkt mit `aapt2` eine minimale Ressource gegen `android.jar` 36,
aligniert mit `zipalign`, signiert mit dem Debug-Key und prüft mit
`apksigner verify`. Dazu kommen `java -version`, `gradle --version`, der
CA-Bundle und der Java-Truststore.

**16-KB-Befund:** Die statischen `aapt2` und `zipalign` aus 35.0.2 haben
`PT_LOAD`-Alignment `0x1000` (4 KB), nicht `0x4000`. Sie werden als
Guest-Executables unter PRoot gestartet und nicht vom Android-Linker geladen;
der harte Gate gilt deshalb für alle Shared Libraries und die vier
PRoot-Bibliotheken. Die beiden Tools stehen im Manifest als dokumentierte
Ausnahme. Wenn die Abnahme 16 KB auch für sie verlangt, ist der Fallback ein
eigener 16-KB-Build aus AOSP/ReVanced (aapt2) und ein eigener zipalign-Build.

## Grenzen

- Der PRoot-Build läuft ausschließlich in `.github/workflows/android.yml`, der
  Toolchain-Pack in `.github/workflows/android-toolchain.yml`; lokal sind beide
  laut `android/AGENTS.md` nicht erlaubt.
- Die eingecheckten `.so`-Dateien bleiben die M1.1-Termux-Kopien als Referenz.
  CI überschreibt sie im Runner vor `:app:assembleDebug`; das ausgelieferte
  APK enthält damit den NDK-Build.
- Ein echter 16-KB-Lauf auf Hardware bleibt Phase 2. Geprüft wird statisch,
  wie in Abschnitt 3.7 des Plans festgelegt.
- PRoot wird mit `-Wno-error=implicit-function-declaration` gebaut. NDK r28
  macht fehlende Deklarationen zum Fehler; die älteren PRoot-Quellen lassen
  einzelne `<string.h>`-Includes aus. Die Warnungen bleiben im Log sichtbar.
- `_PATH_TMP` zeigt auf `/tmp/`, weil Bionic den Pfad nicht definiert. Er wird
  nur für benannte SysV-Schlüssel (nicht `IPC_PRIVATE`) benutzt, die Phase 1
  nicht braucht. Ein umgebungsbasierter Pfad gehört zu M1.5, sobald die App
  den Temp-Pfad vorgibt.
- Bericht und Bibliotheken liegen als Artefakt `proot-ndk-r28` am Lauf.

## Lizenzen

PRoot GPL-2.0-only, talloc LGPL-3.0-or-later, libandroid-shmem BSD-3-Clause.
Quellen und Build-Skript bleiben offen; die Lizenzpflichten aus Abschnitt 11
des Phase-1-Plans gelten weiterhin, besonders für eine Veröffentlichung.
