# Converting the engine to Kotlin (phase 2)

The engine's core (packages `engine`, `bot`, `actions`, and the files `CoreBoundaryTest` lists in `room`, `rank`,
`replay`) moves to Kotlin **file by file inside drive_manager**: Kotlin and Java compile together
(`kotlin-maven-plugin` first, then `javac`; Kotlin's Lombok plugin lets Kotlin see what Lombok generates on the Java
classes not converted yet). Once everything is Kotlin and free of JVM-only APIs it moves to `eras-engine`
(commonMain). **Nothing may change for the players**: the same rules, the same JSON, the same replays.

## Converting a file

1. Write `src/main/kotlin/<same package path>/<Name>.kt` and `git rm` the `.java`.
2. Keep what Java callers and Jackson see the same:
   - **Lombok `@Data` class** → `class` with `var` properties and the same initial values; Java sees `getX()`/`setX()`.
     A `boolean` field `editor` becomes `var isEditor: Boolean` (Java keeps `isEditor()`/`setEditor()`, Jackson keeps
     the name `"editor"`). Keep the constructors Java uses (no-arg, `@AllArgsConstructor` in field order, others).
     A hand-written getter (e.g. `CardSlot.getLevel()`) keeps its logic.
   - **record** → `data class` with `val`s. Java callers then call `getX()` instead of `x()`: compile and run
     `fix-accessors.py` (below). Jackson sees the same property names. A record that Jackson *reads* (e.g.
     `MatchSetup`, kept games) must still be read: check its tests.
   - **Only static members** (`private Name() {}`) → `object Name` with `@JvmStatic` on every function and `@JvmField`
     or `const val` on every constant, so Java keeps calling `Name.f()`. Static and instance members together →
     `companion object` with the same annotations.
   - **enum** → `enum class` with the same constants and members (`@JvmStatic` on its static functions).
   - Visibility: Java package-private → `internal` (same module) or `public` when Java in another package uses it.
3. Behaviour stays identical: the same collection types (`ArrayList`, `HashMap`, `LinkedHashMap`, `HashSet`,
   `LinkedHashSet` — Kotlin's names for them) and iteration orders, the same integer arithmetic, the same messages and
   exceptions, the same random draws (shuffles go through `GameRandom.shuffle`). Streams become collection operations
   with the same results; `Optional` may stay in a signature Java still calls (it goes away when the callers are Kotlin).
4. Nullability: a reference Java may pass or leave null is `T?`. When unsure, nullable. No `!!` on what Java gives.
5. Comments move along (Javadoc → KDoc), same wording.

## Checking

```bash
mvn -q clean test-compile 2>&1 | python3 docs/kotlin-conversion/fix-accessors.py name1,name2,...   # record accessors
mvn -q clean test-compile                                                                          # until it compiles
mvn -q test -Dtest='com.drive.drive_manager.simulator.**.*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

`clean` matters: without it Maven may skip Java that did not change and miss what a conversion broke. The simulator
tests must stay green with the same counts per class: `SimulatorJsonTest` sums the JSON of rooms, updates, each
player's view and the replay fingerprints, `RoomCopyTest` and `ShallowCopyTest` list the copied fields (a Kotlin
property keeps its field name), `CoreBoundaryTest` keeps Spring, Jackson and reflection out of the core.
`BotPlayTest.aCopyOfTheRoomPlaysApartFromTheRoom` fails about once in a hundred runs, also before the conversion.
