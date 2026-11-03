# M1.1 · PRoot-Binaries

Die Debug-Variante bringt vier aarch64-Binaries mit. Sie liegen in
`app/src/debug/jniLibs/arm64-v8a/`, damit sie **nur** im Debug-APK landen.
Ein Release-Build enthält sie nicht.

## Woher

Es sind die aktuellen Termux-Pakete für `aarch64` (Bionic, mit NDK gebaut).
Für den einmaligen M1.1-Smoke-Test sind sie der schnellste Weg zu echten
PRoot-Binaries; M1.2 baut PRoot reproduzierbar aus dem Quellcode neu (siehe
Plan, Abschnitt 3.2 und M1.2).

| Datei im APK | Quelle | Paket-SHA-256 |
|---|---|---|
| `libproot.so` | `proot_5.1.107.102_aarch64.deb` → `bin/proot` | `10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030` |
| `libproot-loader.so` | dasselbe Paket → `libexec/proot/loader` | siehe oben |
| `libtalloc.so` | `libtalloc_2.5.0_aarch64.deb` → `lib/libtalloc.so.2.5.0` | `556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7` |
| `libandroid-shmem.so` | `libandroid-shmem_0.7_aarch64.deb` → `lib/libandroid-shmem.so` | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` |

Bezugsquelle: `https://packages.termux.dev/apt/termux-main/pool/main/...`
(Paketliste `Packages.xz` ist auf dem Server derzeit nicht abrufbar, die
Pool-Verzeichnisse schon).

## SHA-256 der eingebundenen Dateien

| Datei | SHA-256 |
|---|---|
| `libproot.so` | `3621937c1c5a98d0d39ca599af573a6bc15128690606d535e8b05da00cec4f41` |
| `libproot-loader.so` | `1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5` |
| `libtalloc.so` | `742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309` |
| `libandroid-shmem.so` | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` |

Geprüft mit `readelf`:

- alle vier: ELF64, AArch64, little endian
- alle vier: `PT_LOAD`-Alignment `0x4000` (16 KB)
- `libproot.so`: PIE (`ET_DYN`), Interpreter `/system/bin/linker64`,
  `DT_NEEDED` = `libtalloc.so.2`, `libandroid-shmem.so`, `libc.so`
- `libproot-loader.so`: statisch, keine `DT_NEEDED`
- `libtalloc.so`: SONAME `libtalloc.so.2`
- `libandroid-shmem.so`: SONAME `libandroid-shmem.so`, braucht `liblog.so`, `libc.so`

## Zwei Besonderheiten

1. **`libtalloc.so` heißt im APK ohne Versionssuffix.** Android packt aus
   `jniLibs` nur Dateien, die auf `.so` enden, also kann `libtalloc.so.2`
   nicht direkt mitgeliefert werden. `ProotEngine` setzt deshalb
   `LD_PRELOAD=nativeLibraryDir/libtalloc.so`; die Bibliothek registriert ihre
   SONAME `libtalloc.so.2`, womit das `DT_NEEDED` von `proot` erfüllt ist.
   Falls der Start mit „library libtalloc.so.2 not found“ scheitert, ist das
   der erste Punkt zum Nachsehen.
2. **`libandroid-shmem.so`** braucht PRoot für `--sysvipc`. Ohne sie startet
   PRoot, aber System-V-IPC im Guest fehlt.

## Lizenz

PRoot: GPL-2.0-only. Termux-Pakete: überwiegend GPL/LGPL/BSD, siehe
`/data/data/com.termux/files/usr/share/doc/*/copyright` in den Paketen.
Private Sideload-Nutzung ist unkritisch. Vor einer Veröffentlichung müssen
Quellen und Build-Skript beiliegen (Plan, Abschnitt 11).

## Neu bauen statt Pakete kopieren

M1.2 ersetzt diese Dateien durch einen eigenen NDK-r28-Build aus
`https://github.com/termux/proot` mit `libtalloc` und `-Wl,-z,max-page-size=16384`.
Die vier Dateinamen und Pfade bleiben gleich, der Smoke-Test ändert sich nicht.
