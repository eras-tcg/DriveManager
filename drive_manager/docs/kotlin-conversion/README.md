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
     A `boolean` field `editor` becomes `@get:JvmName("isEditor") var editor: Boolean = false`: Java keeps
     `isEditor()`/`setEditor()`, Jackson the name `"editor"`, and the JVM field stays `editor` (`var isEditor` would
     rename the field to `isEditor`, which `RoomCopyTest`, `AuditFixesTest` and the Jackson mix-ins read by name).
     Keep the constructors (no-arg, `@AllArgsConstructor` in field order, others) even when no Java code calls them:
     Jackson lists the properties of a constructor's parameters first, in their order, so `@AllArgsConstructor` is
     what gives `CardSlot`'s JSON (and the replay fingerprints, which are not sorted) its order. Without it the
     properties come out alphabetically.
     A hand-written getter (e.g. `CardSlot.getLevel()`) keeps its logic; when the raw value is read too
     (`getPrintedLevel()`), make the property `private var level` and write `getLevel()`/`setLevel()` as functions.
   - **`@Data` also means `equals`/`hashCode`/`toString`**: value equality that `list.remove(card)`, `contains`,
     `indexOf` rely on. Write them out as Lombok made them (all non-transient fields through their getters,
     `PRIME = 59`, `79`/`97` for booleans, `43` for null): run Lombok's delombok on the Java file
     (`java -jar ~/.m2/repository/org/projectlombok/lombok/1.18.42/lombok-1.18.42.jar delombok -c <classpath> -d out File.java`)
     and translate its methods. In `equals`, compare `Double`/`Float` properties as `(x as Any?) != (other.x as Any?)`
     (Kotlin's `==` on them is IEEE: `NaN`, `-0.0`).
   - **record** → `@JvmRecord data class` with `val`s: it is still a `java.lang.Record`, with the accessors `x()`, so
     Java callers, Jackson (it reads records through their canonical constructor: `MatchSetup` from kept games) and
     the test helpers that share records (`ReflectiveRoomCopy`, `RoomCopyTest`) see no change. Make every reference
     component nullable (a missing JSON property comes as null). A plain `data class` (as `FieldGrant`,
     `PendingAscension`, `PendingRemoval`) turns `x()` into `getX()`: then fix the callers with `fix-accessors.py`
     (below), and check the classes' JSON and the reflective copy as well.
   - **Only static members** (`private Name() {}`) → `object Name` with `@JvmStatic` on every function and `@JvmField`
     or `const val` on every constant, so Java keeps calling `Name.f()`. Static and instance members together →
     `companion object` with the same annotations.
   - **enum** → `enum class` with the same constants and members (`@JvmStatic` on its static functions).
   - Visibility: Java package-private → `internal` (same module) or `public` when Java in another package uses it.
     An `internal` **function** or property accessor gets a mangled JVM name (`toRoom$drive_manager`), so make one
     that Java calls `public` (`internal` is fine for `const val`, `@JvmField`, classes and annotations).
   - The Kotlin plugin compiles with `javaParameters` (as javac's `-parameters`, Spring Boot's default): Jackson reads
     the constructors' parameter names in Kotlin classes as it did in Java ones.
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
