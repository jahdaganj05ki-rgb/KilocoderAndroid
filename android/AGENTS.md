# KiloCoder Android — Projektregeln

Gilt für alles unter `android/`. Ergänzt die Regeln der Repository-Wurzel.

## Builds laufen ausschließlich auf GitHub Actions

Diese Android-App wird **nie lokal gebaut**. Verboten sind insbesondere:

- `gradle`/`./gradlew`-Läufe, `assembleDebug`, `testDebugUnitTest`, Lint oder
  irgendein anderer Android-Toolchain-Lauf in einer lokalen Umgebung,
- lokale Installation von JDK, Android SDK, Build-Tools, Gradle oder NDK zum
  Bauen dieser App,
- lokale `aapt2`-/`apksigner`-/`zipalign`-Prüfungen an einem gerade lokal
  erzeugten APK.

Jeder Build — Debug-APK, Unit-Tests, Lint — läuft über
`.github/workflows/android.yml` auf GitHub Actions. APKs kommen ausschließlich
aus dem Artefakt des Workflow-Laufs (`kilocoder-debug-apk`), nicht aus einem
lokalen `app/build/`-Verzeichnis.

Ist CI technisch nicht verfügbar oder deckt der Workflow etwas nicht ab: Arbeit
stoppen und fragen. Nicht stillschweigend auf einen lokalen Build ausweichen.
Commits, Pushes und Workflow-Starts nur mit ausdrücklicher Freigabe.

## Der CI-Build

`.github/workflows/android.yml` läuft bei Push und Pull Request mit Änderungen
unter `android/**` sowie manuell per `workflow_dispatch`. Er führt
`:app:testDebugUnitTest` und `:app:assembleDebug` aus und lädt das APK als
Artefakt `kilocoder-debug-apk` hoch. Ein Workflow-Lauf ersetzt jeden lokalen
Build; Fehlschläge werden im Workflow-Log diagnostiziert, nicht durch einen
lokalen Gegenlauf.

## Was lokal erlaubt ist

- Quellcode lesen, suchen, schreiben, diffen.
- Statische Prüfungen ohne Android-Build: `git diff --check`, Markdown-Tabellen
  (`bun script/check-md-table-padding.ts`), XML-/TOML-Syntax, `readelf` auf
  bereits eingecheckten Binaries, Python- und Shell-Prüfskripte.
- Analyse und Planung, auch mit Blick in den Workflow.

Ein Kompilierlauf gegen eine lokal geladene `android.jar` zählt bereits als
Build und ist damit ebenfalls nicht erlaubt.

## Abgrenzung zu Phase 1

Die Regel betrifft das Bauen von **KiloCoder selbst**. Die geplante
Phase-1-Funktion — beliebige Android-Projekte auf dem Telefon bauen — ist davon
unberührt und bleibt Ziel der Roadmap.

## Artefakte

Keine Build-Ausgaben einchecken (`build/`, `*.apk`, `local.properties`).
Der Debug-Smoke-Test aus M1.1 (`app/src/debug/`) wird ebenfalls nur über CI
gebaut; die Debug-Binaries liegen im Repo, das APK nicht.

## Branch-Regel

- **Nie direkt auf `main` pushen.** Jede Änderung läuft auf einem eigenen Branch
  nach dem Muster `android/<thema>`, zum Beispiel `android/m1.1-proot-smoke`.
- Push immer mit explizitem Zielbranch (`cloud_git push` mit `branch=<name>`),
  nie mit dem Default.
- `main` bleibt sauber und bekommt Änderungen nur über einen bewussten Merge.
- Der M1.1-Stand liegt auf `android/m1.1-proot-smoke`.
