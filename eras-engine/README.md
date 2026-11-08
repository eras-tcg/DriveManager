# eras-engine

The game engine in Kotlin Multiplatform: one code, built for the JVM (DriveManager) and for JavaScript (eras-web,
to play against the bot offline). Plan: "ERAS: plan del motor en Kotlin Multiplatform".

**Status: phase 2, step C.** The game's core (rules, effects, bot, rooms' state) is here, in `src/commonMain/kotlin`,
under its original packages `com.drive.drive_manager.simulator.*`; DriveManager depends on the JVM artifact. The
platform seam (`simulator/platform`) is `expect`/`actual` (`jvmMain`, `jsMain`). The card files live here too and are read
in common code (kotlinx.serialization), on the server and in JavaScript. Details: `drive_manager/docs/kotlin-conversion/README.md`,
"Step B" and "Step C".

## The cards

- **`cards/<edition>/<card>.json`**: the programmed cards (358), the one copy of their data.
- The build makes the **card bundle** from them (task `cardBundle`): `build/generated/cardBundle/card-bundle.json`, every
  file's text in one JSON document (`{"format": "eras-card-bundle/1", "files": [{"path": "E1/a-la-carga.json", "text": "…"}, …]}`).
  The JVM jar carries the files (`cards/**`) and the bundle (`eras/card-bundle.json`); the site will fetch the bundle.
- The reader: `simulator/engine/cards` (`CardReader.read(json)`, `CardBundle.read(bundle)`), the same definitions the server's
  Jackson reader built (checked on every card by DriveManager's `CardReaderParityTest`). `CardModelReader.kt` is generated
  from the model's classes: after changing one, `./gradlew jvmTest -PregenerateCardReader`.
- Installing them as the core's cards, before the engine is first used:
  - Kotlin (JVM or JS): `CardSources.install(BundleCardSource(bundleText))`;
  - the site: `EngineApi.installCards(bundleText)` (returns how many cards);
  - DriveManager: its `ServerCardSource` service reads `eras/card-bundle.json` from this jar (found with no start-up code).
- The tests (JVM and Node) read the bundle from the build and play seeded bot games with it (`SeededBotGameTest`: the same
  fingerprints on both); they read the starter decks from `../drive_manager/src/test/resources/simulator`.

## Build

```bash
./gradlew build                              # card bundle, tests on the JVM (jvmTest) and on Node (jsNodeTest)
./gradlew publishToMavenLocal                # com.eras:eras-engine-jvm, for DriveManager
./gradlew jsBrowserProductionLibraryDistribution   # npm package @eras/engine in build/dist/js/productionLibrary
```

From DriveManager's Java (after `publishToMavenLocal`):

```bash
cd ../drive_manager
mvn -Pkmp-engine test -Dtest='KotlinEngineInteropTest' -Dsurefire.failIfNoSpecifiedTests=false
```

DriveManager's main build depends on the JVM artifact, so publish it before building the server. The `kmp-engine`
profile adds `src/test-kmp/java` (and kotlinx.serialization) to the tests. CI runs both steps in the `engine` job, and
builds the engine before the server's tests and image.

## Phase 0 results (2026-11-07)

| Check | Result |
|---|---|
| Common tests on the JVM and on Node | 5 + 5, green |
| `GameRandom` against `java.util.Random` (105 seeds × 1,000 draws, every bound kind) and `Collections.shuffle` | Identical |
| From DriveManager's Java: the game's shuffle and draws, and the 281 card files read with kotlinx.serialization against Jackson | 4 tests, green |
| In Chromium, bundled with esbuild (as Angular does) and type-checked with `tsc` | Seed 42 gives the same 40-card order as the JVM |
| Bundle size (engine API, Kotlin stdlib, kotlinx.serialization JSON) | 215 KB minified, 65 KB gzipped |
| Speed in the browser | ~36,000 shuffles of 40 cards per second |

## Rules learned

- **No `Long` across the JavaScript boundary.** Kotlin exports a 64-bit `Long` only as a `BigInt`. Everything the
  site calls goes through `EngineApi` with plain values (seeds as decimal strings, later JSON); `GameRandom` and
  the rest stay internal.
- **ES modules** (`useEsModules()`): Angular's build bundles and optimizes them; CommonJS would warn and bail out.
- **Java's regular expressions** are not JavaScript's: the core builds every pattern with `Platform.regex` (on JavaScript,
  `JavaRegex.toJavaScript` gives `\s`, `.` and `$` Java's meaning and refuses inline flags such as `(?i)`).
- **No browser test runner.** The JavaScript tests run on Node (`jsNodeTest`); Karma is not needed, and it is
  fetched from GitHub, which some networks block.
- **Java names**: top-level functions live in `<File>Kt` (`CardFileKt.readCardOutline`) unless `@file:kotlin.jvm.JvmName` names it;
  objects are reached as `EngineApi.INSTANCE`; Kotlin properties are `getX()` / `isX()` from Java.
- **Docker (phase 2, done in step B)**: `drive_manager`'s image builds from the repository root:
  a Gradle stage (`./gradlew publishToMavenLocal` in `eras-engine`) and then the Maven stage with that local
  repository; the workflow's build context changes from `./drive_manager` to `.`.
- Maven Central may answer 429 (rate limit) in some sandboxes; a local Gradle init script with Google's mirror
  (`https://maven-central.storage-download.googleapis.com/maven2`) gets around it. Not needed on GitHub Actions.
