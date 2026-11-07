# eras-engine

The game engine in Kotlin Multiplatform: one code, built for the JVM (DriveManager) and for JavaScript (eras-web,
to play against the bot offline). Plan: "ERAS: plan del motor en Kotlin Multiplatform".

**Status: phase 0** (integration check). The engine itself is still in Java in `drive_manager`; this module holds
the game's chance (`GameRandom`, the numbers of `java.util.Random`) and a card-file reader, to prove the round trip.
Nothing in the server uses it yet.

## Build

```bash
./gradlew build                              # tests on the JVM (jvmTest) and on Node (jsNodeTest)
./gradlew publishToMavenLocal                # com.eras:eras-engine-jvm, for DriveManager
./gradlew jsBrowserProductionLibraryDistribution   # npm package @eras/engine in build/dist/js/productionLibrary
```

From DriveManager's Java (after `publishToMavenLocal`):

```bash
cd ../drive_manager
mvn -Pkmp-engine test -Dtest='KotlinEngineInteropTest' -Dsurefire.failIfNoSpecifiedTests=false
```

The `kmp-engine` profile adds the JVM artifact and `src/test-kmp/java` to the tests; without it the server's
build is unchanged. CI runs both steps in the `engine` job.

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
- **No browser test runner.** The JavaScript tests run on Node (`jsNodeTest`); Karma is not needed, and it is
  fetched from GitHub, which some networks block.
- **Java names**: top-level functions get a class name with `@file:kotlin.jvm.JvmName` (`GameShuffle.shuffleAs`);
  objects are reached as `EngineApi.INSTANCE`; Kotlin properties are `getX()` / `isX()` from Java.
- **Docker (phase 2)**: once `drive_manager` depends on the engine, its image builds from the repository root:
  a Gradle stage (`./gradlew publishToMavenLocal` in `eras-engine`) and then the Maven stage with that local
  repository; the workflow's build context changes from `./drive_manager` to `.`.
- Maven Central may answer 429 (rate limit) in some sandboxes; a local Gradle init script with Google's mirror
  (`https://maven-central.storage-download.googleapis.com/maven2`) gets around it. Not needed on GitHub Actions.
