# Phase 1 test fixtures

Drei Projekte decken den Toolchain-Korridor ab. Keines davon ist die Grenze des
Projektumfangs; KiloCoder ist nur eines von drei Fixtures.

| Fixture | Pfad | AGP | compileSdk | build-tools | Zweck |
|---|---|---|---|---|---|
| `hello-local-build` | `android/test-fixtures/hello-local-build` | 8.13.2 | 36 | 35.0.0 | erster echter Build (M1.3); obere Kante des Korridors |
| `generic-sdk35` | `android/test-fixtures/generic-sdk35` | 8.7.3 | 35 | 35.0.0 | untere Kante; prüft, dass nicht nur KiloCoder baut (M1.4) |
| KiloCoder | `android/` | 8.13.2 | 36 | 35.0.0 | reales Projekt mit Compose, Room/KSP und Netzwerk-Dependencies (M1.4) |

## Eigenschaften

- Beide neuen Fixtures sind bewusst **ohne AndroidX und ohne Fremd-Dependencies**.
  Sie brauchen nur Kotlin-Stdlib, `android.jar` und die Build-Tools. Damit prüft
  M1.3 zuerst die Toolchain (aapt2, Kotlin, d8, zipalign, Debug-Signing) und
  nicht die Maven-Auflösung. KiloCoder deckt die Dependency-Auflösung ab.
- Beide setzen `buildToolsVersion = "35.0.0"`, weil der Toolchain-Pack genau
  dieses Set enthält (siehe `PHASE1_M1_0_DECISIONS.md`, Abschnitt 4).
- `org.gradle.parallel=false` steht in der Fixture-`gradle.properties`. Die
  Build-Regeln `--no-daemon --max-workers=2` kommen zusätzlich von der
  Kommandozeile und werden nicht in die Projektdateien geschrieben.
- Der Gradle Wrapper wird in Phase 1 **nicht ausgeführt**. Die Fixtures liefern
  deshalb nur `gradle/wrapper/gradle-wrapper.properties` mit der erwarteten
  Distribution; `gradlew` und `gradle-wrapper.jar` fehlen absichtlich.
  Der Validator liest die deklarierte Gradle-Version aus dieser Datei.

## Lokal prüfen (ohne Android-SDK)

```sh
find android/test-fixtures -name '*.kts' -o -name '*.xml' -o -name '*.kt'
```

Ein echter Build braucht JDK 17, Android SDK 35/36 und die statischen
aarch64-Build-Tools; das passiert erst in M1.3 auf dem Gerät.
