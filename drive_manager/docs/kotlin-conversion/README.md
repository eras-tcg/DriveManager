# Converting the engine to Kotlin (phase 2)

The engine's core (packages `engine`, `bot`, `actions`, and the files `CoreBoundaryTest` lists in `room`, `rank`,
`replay`) moves to Kotlin **file by file inside drive_manager**: Kotlin and Java compile together
(`kotlin-maven-plugin` first, then `javac`; Kotlin's Lombok plugin lets Kotlin see what Lombok generates on the Java
classes not converted yet). Once everything is Kotlin and free of JVM-only APIs it moves to `eras-engine`
(commonMain). **Nothing may change for the players**: the same rules, the same JSON, the same replays.

> **Done (step B, 2026-11-08):** the core now lives in `eras-engine/src/commonMain/kotlin` and drive_manager uses it as
> a library (`com.eras:eras-engine-jvm`); drive_manager has no Kotlin sources and no `kotlin-maven-plugin` any more.
> The batch notes below describe how the conversion was done; see "Step B" at the end for where things are now.
>
> **Done (step C, 2026-11-08):** the card files live in `eras-engine/cards` and are read by the engine's common reader
> (kotlinx.serialization) on the server and in JavaScript, from one generated card bundle; see "Step C".
>
> **Phase 3 (2026-11-08):** a game against the bot runs in the browser as the server runs it (`com.eras.engine.local.LocalMatch`,
> byte-identical messages checked by `room/LocalMatchParityTest`); see eras-engine's README, "Phase 3". In the core, the
> clocks are read through `platform/Clocks` and the maps whose order reaches the messages are `Platform.hashMap()`.
>
> **Fixed after the migration (2026-11-08):** `room/ShallowCopy` (StateMask's copies, which replaced Spring's
> `BeanUtils.copyProperties`) had kept BeanUtils' quirk of copying a card's level through `getLevel()`, which already
> adds the continuous level, besides copying the continuous level itself: the rival saw a face-up card with face-down
> materials or resources with its continuous level bonus counted twice. The copy now takes the printed level (the
> field); `ShallowCopyTest` checks against BeanUtils with that field corrected and `StateMaskTest` plays the case
> (Xiromorfo with a copy in the Tributos and a face-down material). No golden changed: no seeded game has a continuous level.
>
> **One copy of the room logic (2026-11-08):** the room session's rules that `LocalMatch` had ported from the server's Java
> (joining, leaving, kicking, closing, the deck choice, the bot's seat, settings and moves, who is sent an update) are
> common code in eras-engine (`room/RoomSeats`, `room/DeckChoice`, `bot/RoomBots`, `StateMask.recipients`,
> `RoomEngine.blamed`), used by both. `RoomMembership`, `RoomDecks`, `BotRooms`, `RoomService` and `RoomMessages` keep
> only Spring, Mongo, the registry and locks, scheduling, STOMP, sessions and sweeps, missions, history and ranks; see
> eras-engine's README, "The room session".

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
   - **A record's compact constructor that fills defaults** (`Selector`: `filter`, `self`, `mode`): only a `data class`
     can be `@JvmRecord`, and a Kotlin record cannot change what its constructor stores. Make those components
     `private val` and write the record's accessor as a function that gives the default (`fun mode(): Mode = mode ?:
     Mode.CHOOSE`): it is still the component's accessor, which Java callers and Jackson read. Write `equals`,
     `hashCode` (`31 * h + hash(c)` from 0, as a record's) and `toString` (`Name[a=1, b=2]`) over the values as read.
   - **A sealed family** (`Action`): `sealed interface` with `@JvmRecord data class` members. Kotlin writes the
     `PermittedSubclasses` attribute (jvmTarget 21) **sorted by name**, not in declaration order: the same classes and
     simple names, so `EffectJson` (which names them by simple name) reads and writes the same JSON, but anything that
     depends on their order must sort them. Check with `javap -v -cp target/classes '<Family>'`.
   - **A compact constructor that also copies** (`CardFilter`, `CardDefinition`: `x == null ? List.of() : List.copyOf(x)`):
     the accessor gives what Java stored, `if (x == null) java.util.List.of() else java.util.List.copyOf(x)` (unmodifiable, as
     Java's), and the class's own code reads a getter-only `private val xList get() = x ?: java.util.List.of()` so a hot path
     (`matches`) does not copy (a record can have no field outside its components).
   - **A sealed family moves in one step, wherever its members are written.** Kotlin refuses to extend a Java sealed
     interface ("extending Java sealed classes is prohibited"), and javac refuses a Java member of a Kotlin one (Kotlin's
     `PermittedSubclasses` lists only its own classes). So `Reaction` goes together with `CostModifiers.CostModifier` and
     the records nested in `CardEffects` (`Replacement`, `AllyRemovalSummon`, `AllyGuard`, `DamageReplacement`), and
     `CardEffects` waits for it.
   - **A record without components** (`Amount.LastCount()`, 9 in `Amount`, 27 in `EffectStep`, 2 in `Condition`, 3 in
     `Reaction`) has no Kotlin record: `@JvmRecord` needs a `data class`, and a `data class` needs a parameter. A plain
     class or a `data object` is not a record, so `EffectJson` (which registers only records) would stop reading and
     writing it; a dummy component would show in `getRecordComponents` (`LanguageCatalogExportTest`'s catalog) and be
     accepted in the JSON. Decided: they become `data object`s (below, "Records without fields"); `Amount` and `Reaction`
     moved that way (batch 4), `Condition` (batch 5), `EffectStep` (batch 6).
   - Generics Java implements or overrides: Kotlin adds `? extends` to a parameter `List<Open>`/`Map<K, Any?>` whose
     element type is open (an interface, `Any`), so a Java class implementing a Kotlin interface stops overriding it.
     Write `Map<String, @JvmSuppressWildcards Any?>`, `List<@JvmSuppressWildcards Action>`.
   - **Only static members** (`private Name() {}`) → `object Name` with `@JvmStatic` on every function and `@JvmField`
     or `const val` on every constant, so Java keeps calling `Name.f()`. Static and instance members together →
     `companion object` with the same annotations.
   - **enum** → `enum class` with the same constants and members (`@JvmStatic` on its static functions).
   - **Kotlin cannot star-import an `object`** ("Cannot import on demand from object"): once a class of statics is an
     `object` (`Chain`), the Kotlin files that had `import ...Chain.*` import each member they use by name
     (`import ...Chain.advance`). Java's `import static ...Chain.*` keeps working through `@JvmStatic`.
   - **Kotlin callers of a record that becomes Kotlin** switch from the accessor calls they used on the Java record
     (`use.modifier().payment().kind()`) to properties (`use.modifier.payment!!.kind`); `fix-accessors.py` fixes only
     Java. A record's other methods (`damage()`, `needsChoice()`, `instead()`, `reduceNow()`) stay `fun`s: a
     `val x get() = …` would add a `getX()` that Jackson writes as one more property. `fix-kotlin-calls.py` (below) drops
     the `()` at each "cannot be invoked as a function" the compiler reports; then add `!!` where Java dereferenced a
     component that is now nullable (`script.cost!!`, `for (step in script.cost!!)`), never on a null check
     (`script.condition != null && !script.condition.test(...)` smart-casts).
   - **Static methods of an interface** (`Condition.any(...)`, `Condition.yourTurn()`): a `companion object` inside the
     sealed interface, every function `@JvmStatic` (Java keeps `Condition.any(...)`), the private ones `private fun`s
     there (nested records reach them). The companion is no member of the family (it does not implement it). A Kotlin
     file that star-imported the Java interface (`import ...Condition.*`) still gets its nested classes but not the
     companion's functions: write `Condition.not(...)`.
   - **A nested class named like a Kotlin built-in** (`Condition.Any`) shadows `kotlin.Any` inside the interface and in
     files that star-import it: write `kotlin.Any` there.
   - **Java method references evaluate their receiver at once**: `d.filter()::matches` throws on a null filter even
     over an empty list. Write `val f = d.filter()!!` before the lambda (`count { f.matches(it) }`).
   - A Lombok `boolean` on a Java class not converted yet (`Foo.lastDone`) is `isLastDone` from Kotlin; once the class is
     Kotlin (`@get:JvmName("isLastDone") var lastDone`) its Kotlin callers write `lastDone` (Java keeps `isLastDone()`).
   - **Static initialization order**: an `object`'s properties and `init` blocks run in `<clinit>` in textual order, as
     Java's static fields and blocks did. Keep the `init` that loads (`CardEffects`: `CardFiles.load().forEach(::install)`)
     after every property, and in a cycle between two objects (`CostModifiers.init` calls `CardEffects.load()`, whose
     `install` calls `CostModifiers.register`) declare each registry before the `init` that wakes the other, as in Java.
   - Java's `String.split(",")` exactly: `java.util.regex.Pattern.compile(",").split(s)` (a literal `split("|")` with
     Java's limit `-1` is Kotlin's `split("|")`).
   - Java static imports: Kotlin star-imports a Java class's statics (`import ...EffectEngine.*`), and Java's
     `import static ...Answers.*` keeps working on an `object` whose functions are `@JvmStatic`. Qualify a call whose name
     is also a local (`Answers.selfDamage` next to `val selfDamage`) or a Kotlin scope function (`Resolution.apply`).
     Kotlin's `List`/`Map` have no `of`: write `java.util.List.of(...)`, and `java.util.Map.of<String, Any?>(...)` where
     Java takes a `Map<String, Object>` (Kotlin will not pass a `Map<String, String>` there).
   - A pattern `switch` over a sealed family → `when (step) { is X -> ... else -> ... }` with `step` smart-cast; a `break`
     out of a case becomes an `if` around the rest of that branch.
   - Visibility: Java package-private → `internal` (same module) or `public` when Java in another package uses it.
     An `internal` **function** or property accessor gets a mangled JVM name (`toRoom$drive_manager`), so make one
     that Java calls `public` (`internal` is fine for `const val`, `@JvmField`, classes and annotations).
   - The Kotlin plugin compiles with `javaParameters` (as javac's `-parameters`, Spring Boot's default): Jackson reads
     the constructors' parameter names in Kotlin classes as it did in Java ones.
3. Behaviour stays identical: the same collection types (`ArrayList`, `HashMap`, `LinkedHashMap`, `HashSet`,
   `LinkedHashSet` — Kotlin's names for them) and iteration orders, the same integer arithmetic, the same messages and
   exceptions, the same random draws (shuffles go through `GameRandom.shuffle`). Streams become collection operations
   with the same results; `Optional` may stay in a signature Java still calls (it goes away when the callers are Kotlin).
   Kotlin's look-alikes that differ from Java's: `split(",")` keeps the empty strings at the end (Java's drops them,
   unless nothing matched), `trim()` and `isBlank()` also take Unicode spaces (Java's: chars `<= ' '`, and
   `Character.isWhitespace`; write `trim { it <= ' ' }`, `all { Character.isWhitespace(it) }`), and `when (s)` on a
   null string takes `else` where Java's `switch` throws (`when (s!!)`). Chained stream filters run per element, one
   after the other: write one `filter { a(it) && b(it) }` so the context is asked in the same order. Keep
   `java.util.Map.of`/`ofEntries`/`List.of` where Java had them (`Actions`' handlers, an event's data).
4. Nullability: a reference Java may pass or leave null is `T?`. When unsure, nullable. No `!!` on what Java gives,
   but where Java dereferenced it anyway (`action.cards().min()`): there `!!` throws where Java threw.
5. Comments move along (Javadoc → KDoc), same wording.

## Checking

```bash
mvn -q clean test-compile 2>&1 | python3 docs/kotlin-conversion/fix-accessors.py name1,name2,...   # record accessors
mvn -q clean test-compile 2>&1 | python3 docs/kotlin-conversion/fix-kotlin-calls.py                  # x() -> x in Kotlin callers
mvn -q clean test-compile 2>&1 | python3 docs/kotlin-conversion/fix-nullable-receivers.py           # !! where Java dereferenced
mvn -q clean test-compile                                                                          # until it compiles
mvn -q test -Dtest='com.drive.drive_manager.simulator.**.*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

`clean` matters: without it Maven may skip Java that did not change and miss what a conversion broke. The simulator
tests must stay green with the same counts per class: `SimulatorJsonTest` sums the JSON of rooms, updates, each
player's view and the replay fingerprints, `RoomCopyTest` and `ShallowCopyTest` list the copied fields (a Kotlin
property keeps its field name), `CoreBoundaryTest` keeps Spring, Jackson and reflection out of the core.
`BotPlayTest.aCopyOfTheRoomPlaysApartFromTheRoom` fails about once in a hundred runs, also before the conversion.

## Records without fields → `data object`

A record without components (`record LastCount() implements Amount {}`) becomes a `data object LastCount : Amount`.
`EffectJson` registers a sealed family's records **and** its Kotlin objects (a final class whose static `INSTANCE` is
itself), and writes an object as its type alone (`{"type":"LastCount"}`), exactly as the empty record was written. A
`data object` equals any instance of its type, as the empty record did; Java callers that wrote `new LastCount()` write
`LastCount.INSTANCE`. In `kotlinx.serialization` (eras-engine) the same `data object` is the standard form.

A `data object`'s `toString` is `LastCount` (the record's was `LastCount[]`) and its `hashCode` differs; nothing reads
them. To check a change in a family `EffectJson` reads, dump `EffectJson.write` of every `CardEffects.definitions()`
(and `ruleEffects()`) with a throwaway test, in this tree and in a `git worktree` of `HEAD`, and `cmp` the two: for
batch 4 they were byte-identical (358 cards, each reading back equal). Batch 5 (`Condition`, `EffectScript`) and batch 6 (`EffectStep`): byte-identical too.

**`EffectStep` took them in batch 6.** `ResolutionTest.everyStepHasItsFamily` treats a Kotlin object as a leaf member (as
`EffectJson.collect` and `LanguageCatalogExportTest.collect` do) and still asks `Resolution.hasFamily` of it, so its 27 empty
records are `data object`s now. Java's pattern switches over the family (`case IfPossible ignored ->`, `instanceof
IfPossible`) work on an object unchanged; only a record pattern (`case IfPossible() ->`) or `new IfPossible()` would need
`IfPossible.INSTANCE`. The cards' JSON stayed byte-identical (358 cards).

## Lessons from batch 6 (`EffectEngine`, `Compose`, `EffectStep`, `GameEvent`…)

- **A class of statics that Kotlin files star-imported** (`import ...EffectEngine.*`, `Compose.*`): once it is an `object`,
  run `python3 docs/kotlin-conversion/fix-star-imports.py EffectEngine,Compose <the .kt files>`: it writes one import per
  public member each file uses.
- **Helpers that Kotlin callers fed with platform values** (`name(find(...))`, `keywords(card)`, `frontLine(p.field)`):
  Java's parameters took nulls and threw only where they dereferenced them. Make the Kotlin parameter nullable and put
  `!!` at that dereference (`fun name(card: CardSlot?) = if (card!!.cardName != null) …`): callers compile unchanged and
  the NPE comes at the same moment. Return types stay honest (`find(...): CardSlot?`); then
  `mvn -q clean test-compile 2>&1 | python3 docs/kotlin-conversion/fix-nullable-receivers.py` adds `!!` at each
  "Only safe (?.) or non-null asserted" the compiler reports. Review its diff: only where Java dereferenced anyway.
- **A stream `.toList()` of a nullable property** (`ids(cards)`: `List<String>` for Java, its elements `instanceId: String?`):
  `cards.map { it.instanceId } as List<String>` with `@Suppress("UNCHECKED_CAST")` keeps the signature and the nulls
  (`it.instanceId!!` would throw where Java did not). The same for a `LinkedHashMap` keyed by a nullable id returned as
  `Map<String, …>` (`LegalActions`).
- **Java's `Set.of(...)`/`List.of(...)` throw on `contains(null)`**: where Java asked one with a possibly null value
  (`EFFECT_MOMENTS.contains(event.getType())`), write `contains(event.type!!)` in the same short-circuit position.
- **A Lombok `@Data` property that Kotlin read through a smart cast** (`event.data != null && event.data["cause"]`): once
  the class is Kotlin, `data` is a mutable property and does not smart-cast; write `event.data!!["cause"]` after the check.
  Type a `Map<String, Object>` property as `MutableMap<String, Any?>?`: Java keeps `Map<String, Object>` on the getter and
  the setter, and Kotlin callers that `put` still compile.
- **A shuffle's `Random` that Java dereferenced only inside the loop** (`GameRandom.shuffle`): `random: Random?` and
  `random!!.nextInt(i)` there, so a list of 0 or 1 cards still shuffles without one, as before.

## Lessons from batch 7 (`PendingEffect`, `Prompt`, `Removals`, `Resolution`, `EngineContext`…)

- **Kotlin callers of a Lombok class that becomes Kotlin** used Lombok's boolean names (`effect.isConfirmed = true`):
  the compiler reports "Unresolved reference 'isX' on receiver of type 'Foo'" at each; rename them to the property
  (`effect.confirmed`). Only those lines: other `isX` in the same files belong to other classes.
- **Lists of ids that Kotlin fills with nullable ids** (`effect.targetIds.add(card.instanceId)`, `lastTributed`,
  `Prompt.validIds`, the values of `saved`, `passed`): type the element nullable, `MutableList<String?>` (Java still sees
  `List<String>`, and `ArrayList(ids(cards))` infers `ArrayList<String?>` from the expected type). That also matches
  `EffectContext.saved(): List<String?>`. A `java.util.List.of(x)` already throws on a null `x`, so `!!` inside one is
  free. Lists Kotlin assigns from `map { }` (`Prompt.effects`, `requiredIds`) are read-only `List<T>?`.
- **A Kotlin `MutableSet<String>` (an older conversion, `GameRoom.removalDecided`) that Java fed nullable ids**: read it
  through a private `as MutableSet<String?>` view (`@Suppress("UNCHECKED_CAST")`) rather than `!!` on the id.
- **`Map.merge(key, value, f)` throws on a null value** (Java's too): `merge(e.sourceInstanceId, e.label!!)` in the same
  place; a null key is allowed, so the map is `MutableMap<String?, String>` returned `as MutableMap<String, String>`.
- **A class that implements a Kotlin interface** (`EngineContext : EffectContext`): override with the interface's own
  Kotlin types and keep its `@JvmSuppressWildcards` on the parameter (`data: Map<String, @JvmSuppressWildcards Any?>?`).
  Java's unqualified `strength(card)` inside it was the class's own override, not `EffectEngine.strength`: qualify the
  helpers (`EffectEngine.find`) instead of importing them, and write `this.amount(x)` where a parameter has the name.
- **A Java `switch` on a `String`/enum throws on null**: `when (zone!!)`, `when (until!!)`, `when (FAMILY[step.javaClass]!!)`.
  A switch *statement* that leaves cases out (`answerRemoval` on `ReplacementCost`) gets `else -> {}`.
- **`fix-star-imports.py` matches names textually, comments included**: after it, drop imports the file does not call
  (`Resolution.apply` came from the word "apply" in a comment); a member named like a Kotlin scope function (`apply`,
  `run`) is called qualified (`Resolution.apply(...)`, `Resolution.run(...)`).
- **Java's `toLowerCase()` uses the default locale**: `lowercase(Locale.getDefault())` (Kotlin's `lowercase()` is ROOT);
  `toLowerCase(Locale.ROOT)` is `lowercase(Locale.ROOT)`. `replaceAll(regex, "")` is `replace(Regex(regex), "")` (keep the
  `Regex` in a `private val`).

## Lessons from batch 8 (`Summons`, `SpecialRequirements`, `SpecialSummons`, `Rituals`, `StepHelpers`…)

With it the engine is Kotlin but for `EffectJson`, `CardFiles` and `CardFilesCheck` (Jackson and Spring, server-only).

- **A record nested in a class of statics** (`Rituals.Requirement`, `SpecialRequirements.SpecialRequirement`): a
  `@JvmRecord data class` inside the `object`, with its extra constructors as secondary ones. Java (tests included) keeps
  `Rituals.Requirement`, `req.fits(...)`, `req.materialZones()`; Kotlin callers move to properties (`fix-kotlin-calls.py`)
  and, as `specialRequirement(...)` now says `SpecialRequirement?`, `fix-nullable-receivers.py` adds a `!!` at every use:
  keep only the first in each block (the rest smart-cast).
- **Java tests call package-private statics** (`StepHelpers.enemyTargets`, `TurnRules.offerAdvance`, `Rituals.requirement`):
  every function of these objects is public (`internal` would be mangled).
- **Write the new files with Java's star imports and let `fix-star-imports.py` replace them** (pass every object at once).
  It also matches locals and parameters named like a member (`possible`, `pending`, `answer`, `picked`, `prompt`, `zones`,
  `covers`): drop those imports too. Do not use `run { }` in a file that imports `Resolution.*`: call a private function.
- **A parameter named like a member function** (`declaredFrom(..., zones: String?)` calling `zones(player, zones)`): Kotlin
  tries to invoke the parameter; qualify the call (`StepHelpers.zones(player, zones)`).
- **A Java method reference in a stream chain** (`list.stream().filter(special.filter()::matches)`) reads its receiver when
  the pipeline is built: after the source list, before any element. Write `val zones = specialZones(...)`, then
  `val filter = special.filter!!`, then one `zones.filter { ... && filter.matches(c) && ... }`.
- **Java's `list.remove(card)` with a possibly null `card`, dereferenced right after**: `list.remove(card!!)` (removing
  null did nothing; the NPE comes before anything changes). Where Java **added** a possibly null value to a list
  (`tributeZone.add(summoner)`), add through a view: `(player.tributeZone as MutableList<CardSlot?>).add(summoner)`.
- **A Kotlin caller that stores the result in a `MutableList` map** (`GameStateUpdate`: `specialSummonable`, `unplayable`):
  return `MutableList<String>` (Java still sees `List<String>`).
- **Java's `x.split(",")[0]`**: `Pattern.compile(",").split(x)[0]` (Java's split of `","` is empty and throws there).

## Lessons from batch 9 (`actions`: `ActionDispatcher`, `CardActions`, `PreRoomActions`, `ZoneActions`…)

- **A `switch` over an enum with `case null`** → `when (type) { null -> ...; ActionType.X -> ... }` with no `else`: Kotlin
  checks it exhaustive over the nullable enum, as javac did. Keep `case A, B ->` as `ActionType.A, ActionType.B ->`.
- **Package-private classes of statics** (`CardActions`, `RulesActions`, `ZoneActions`) become public `object`s with
  public `@JvmStatic` functions: `internal` would mangle the names Java sees, and an `object` cannot be package-private.
- **`id.equals(c.getInstanceId())` inside a stream lambda** throws on a null `id` only when the list has an element:
  `list.firstOrNull { c -> id!! == c.instanceId }` keeps the `!!` inside the lambda, so an empty zone still returns
  "not found" as before. Where Java dereferenced outside a lambda (`userId.equals(room.getHostId())`), `userId!! != ...`.
- **A Kotlin `MutableMap<String, String>` on the room** (`starterSelections`, `postGameChoices`) that Java filled with a
  possibly null key or value: write and query it through a private `as MutableMap<String?, String?>` view (the same as
  the `MutableSet<String?>` view of batch 7, used again for `mulliganReady`).
- **`stream().filter(a).forEach(b)` runs `a` and `b` per element**: when `b` could change what `a` reads, write a loop
  (`for (c in ArrayList(p.field)) { if (!c.returnAtTurnEnd) continue; ... }`) instead of `filter { }.forEach { }`.
- `Boolean.TRUE.equals(payload.get("x"))` is `payload["x"] == true`; a Java labelled `break outer` is `outer@ for (...)`
  (label on the same line) and `break@outer`.

## Lessons from batch 10 (`room`: `RoomCopy`, `RoomEngine`, `RoomHost`, `StateMask`…; `bot`: `Sandbox`, `CardWorth`, `HandRank`…)

- **`DoubleStream.average()`/`sum()` sum with compensation (Kahan)**; Kotlin's `average()`/`sum()` add plainly and can differ in
  the last bits, which moves a bot's choice. Keep the stream (`pool.stream().mapToDouble { … }.average().orElse(0.0)`,
  `LifeHits`) until a helper replicates the JDK's summation. `IntStream.max()`, `Stream.max(comparator)` are exact:
  `maxWith(Comparator.comparingDouble { … })` keeps the first of equal elements, as Java's did.
- **Java's `c != best` on a class with `equals`** (a `CardSlot`) is identity: `!==` in Kotlin (`!=` would call `equals`).
- **A Java interface with a static factory and a nested implementation** (`RoomHost.gameOnly()`, `GameOnly`): a Kotlin
  `interface` with `companion object { @JvmStatic fun gameOnly() }` (a static method on the interface for Java) and a nested
  `object GameOnly : RoomHost`. Keep the methods' return types non-null where Kotlin callers return them (`ActionDispatcher`);
  Java implementations (`RoomService`) still override them.
- **Package-private Java classes of statics in `bot`** become `internal object`s with plain (public) `@JvmStatic` functions:
  Java sees a public class and unmangled names. Kotlin reaches the Java package's package-private classes, fields
  (`BotSettings.vStr`) and `protected static` methods (`BotPlayer.value`) from the same package.
- **`getOrDefault(id, …)` with a possibly null id on a map typed with non-null keys** (`LegalActions.of`, filled with nullable
  ids): read it through a `Map<String?, V>` view, so a null key answers as the Java map did.
- **An explicit copy with nullable helpers** (`RoomCopy.list/set/map/cards`): the helpers take and return nullable
  collections, as Java's; assign them with `!!` to the non-null properties (the Kotlin setter would have thrown there), and
  build lists of copied elements as `ArrayList<CardSlot?>` returned `as MutableList<CardSlot>`. `RoomCopyTest` and
  `SimulatorJsonTest` stayed identical.
- `Stream.toList()` into a property (`StateMask`'s masked zones, timeline, history) became `mapTo(ArrayList(n)) { … }`: the
  same elements and JSON, but a mutable list (nothing writes to a view).

## Lessons from batch 11 (`bot`: `LookaheadBot`, `SequenceSearch`, `LethalSearch`, `TableScore`, `ValueModel`, `Usefulness`…)

The seeded bot games (`BotPlayTest`, `BotThinksApartTest`, `BotEdgeTest`) and `ValueModelTest` stayed identical with no
special care beyond the rules above: `Math.*` kept as is, `Double.toInt()`/`toLong()` for Java's `(int)`/`(long)` casts (the
same saturating conversion), `sortWith` for `List.sort` (the same stable TimSort), `removeIf` kept (not Kotlin's `removeAll`).

- **A local named like a function of the same object** (`var wins` beside `wins(...)`, `val cuts` beside `cuts(...)`): Kotlin
  tries to invoke the local ("cannot be invoked as a function"); qualify the call (`LethalSearch.wins(...)`). A call inside the
  local's own initializer (`val best = best(...)`, `val choices = choices(prompt)`) still reaches the function.
- **`==` on two lists in Java** (`zone == p.getDeck()`) is identity: `===` (two empty `ArrayList`s are `equals`).
- **A Kotlin class extending a Java class** (`LookaheadBot : HeuristicBot`): its instance members call the Java superclasses'
  statics unqualified (`action(...)`, `choices(prompt)`, `tributesFromHand(...)`), its `companion object` does not: write
  `BotPlayer.action(...)`, `HeuristicBot.summonX(...)`. An `override` of a `protected` Java method stays `protected`, which Java
  tests of the same package still call (`new LookaheadBot(rule).answer(...)`).
- **A record with array components** (`ValueModel.Weights`, `Sample`): `@JvmRecord data class` with `DoubleArray?`/`Array<DoubleArray>?`;
  `equals`/`hashCode` stay by identity on the arrays, as the record's, and Jackson writes and reads it unchanged. A record's static
  constant (`Training.DEFAULT`) goes in a `companion object` as `@JvmField`. A private static factory that Java calls
  (`ValueModel.of`) becomes a companion `@JvmStatic` function; the companion reaches the class's private constructor and members.
- **A Java functional parameter of a package-private Java class** (`MoveTrials(…, ToDoubleBiFunction<GameRoom, String>, …)`):
  pass `ToDoubleBiFunction { r: GameRoom, p: String? -> score(r, p) }`.
- **try-with-resources** → `stream.use { input -> if (input == null) return m; … }` (inline: `return` leaves the function; a null
  resource is not closed, as in Java). `String.split("\\R")` → `Pattern.compile("\\R").split(s)`.
- **A `for` whose condition reads the room** (`for (step = 0; step < MAX && copy.getStatus() == …; step++)`) →
  `for (step in 0 until MAX) { if (!(cond)) break; … }`, or a `while` with `step++` at the end of the body when nothing `continue`s.

## Lessons from batch 12 (`bot`: `BotPlayer`, `HeuristicBot`, `MoveTrials`, `CombatSearch`, `BotSettings`)

With it the bot is Kotlin but for `BotRooms` and `ValueModelFiles` (the server's scheduler and Jackson). The seeded bot games
stayed identical.

- **A Java base class with `protected static` helpers** (`BotPlayer.choices/own/onField`, `HeuristicBot.summonX`): companion
  `@JvmStatic protected fun`. Java sees `protected static` (so Java of the same package still calls them), Kotlin subclasses
  reach them unqualified from instance code and qualified from their own companion (`HeuristicBot.summonX` in `LookaheadBot`'s).
  A `protected static` that a Kotlin **non-subclass** of the package called through Java's package access (`BotPlayer.value` from
  `StrengthCuts`, `HandRank`…) must become public: Kotlin's `protected` has no package access.
- **Package-private instance methods a Java class calls** (`claimWake`, `releaseWake`, `pause` from `BotRooms`) → public `open fun`
  (`internal` would mangle them). Keep `open` on every method Java could override (Java tests subclass `BotPlayer`: `SlowBot.next`).
- **Package-private `final` fields read on every call** (`BotSettings.life`, `vStr`…) → `@JvmField internal val x: Double = values["x"]!!`
  initialized from the constructor **parameter** (as Java read it, not the copied map). With `@JvmField` there is no getter, so a
  property `life` sits next to a function `life()` without clashing.
- **A Java `List<String>` answer built from nullable ids** (`Prompt.validIds: MutableList<String?>`): compute with `List<String?>` in the
  helpers and cast once where the override returns (`return chosen as List<String>?`, `@Suppress("UNCHECKED_CAST")`).
- **`Map.of(me, botA, rival, botB)` and `sides.get(seat)`**: Java's `Map.of` throws on a null key, at build and at `get`. Keep
  `java.util.Map.of<String?, BotPlayer>(...)` and `sides[seat]`, so a missing seat throws where it threw.
- **A private record compared by `List.remove`** (`CombatSearch.Unit` in `prey.remove(best)`): a `data class` keeps the value equality
  (doubles by `Double.compare`, as a record's) and so which equal element goes. A nested class named `Unit` shadows `kotlin.Unit` in the
  enclosing object: fine while nothing there writes `: Unit`.
- **A local that shadows a function of the enclosing object, from a nested class** (`var best` in `Fight.attack` next to
  `CombatSearch.best(...)`): qualify with the object's name; a member function of the same class (`counter` next to the property
  `counter`) needs nothing.
- Java bit masks: `a & b & (1 << i)` → `(a and b and (1 shl i))`, `x &= ~m` → `x = x and m.inv()`, `(long) a << 10` →
  `(a.toLong() shl 10)`; parenthesize (Kotlin's infix `shl` binds weaker than `+`). `++nodes[0] > MAX` works as is.
  `DoubleStream.sum()` (`cheapestGroup`) stays a stream, as batch 10 says.

## Step A: JVM APIs removed

Before the core moves to eras-engine (`commonMain`), every Kotlin file under `simulator/{engine,actions,bot,room}` plus
`rank/RankRules.kt` and `replay/MatchSetup.kt` uses only common Kotlin, except for the seam in `simulator/platform`
(`Platform.kt`), which step B turns into `expect`/`actual`. Nothing moved and nothing changed behaviour: the golden hashes,
seeded bot games and per-class test counts are those of before (plus the helpers' own tests). Checked by copying those files into
a scratch Kotlin Multiplatform project (`jvm()` + `js(IR)`, Kotlin 2.4.21) with a stub `platform` package: they compile for both,
leaving only the references to Java classes of the server (`CardFiles`, `ValueModelFiles`, `RoomService`, `dto.DeckList` in
`CardEffects`, `BotCatalog`, `RoomGuard`, `RoomSetup`) for step B to cut.

| Java / JVM | Now | Why it is the same |
|---|---|---|
| `java.util.Random` | `GameRandom` (engine) | Port of the 48-bit LCG, `nextGaussian` with a port of `StrictMath.log`: bit-identical draws (`GameRandomTest`) |
| `Pattern`, `String.split`, `Character.isWhitespace`, `String.format("%.nf")` | `Regex`, `JavaText.split/isWhitespace/isBlank/fixed` | Java's split rules (trailing empties, zero-width first match), its whitespace set, its decimal rounding (`JavaTextTest`) |
| `List.of`, `List.copyOf`, `Set.of`, `Set.copyOf`/`toUnmodifiableSet`, `Map.of`, `Map.ofEntries`, `Collections.unmodifiableMap` | `frozenListOf`, `frozenListCopy`, `frozenSetOf`, `frozenSetCopy`, `frozenMapOf`, `frozenMapOfEntries`, `unmodifiableMapOf` (engine/Frozen.kt) | Same refusals (null → NPE, also on `contains`/`get(null)`; repeats → IAE; changes → UOE), equality, hash and `toString` (`FrozenTest`). Only `Set.of`/`Map.of`'s per-JVM pseudo-random order became the given order: nothing observable read it (it changed from run to run; the golden JSON sorts keys) |
| `Map.getOrDefault`, `Map.merge`, `List.replaceAll` | `valueOr`, `mergeWith`, `replaceEach` (engine/JavaCollections.kt) | Java's semantics written out (a key with a null value is kept by `valueOr`; `merge` removes on a null result) |
| `computeIfAbsent` (plain maps), `removeIf`, `Map.forEach { k, v -> }` | `getOrPut`, `removeAll { }`, `for ((k, v) in map)` | The values computed are never null; the predicates do not read the collection |
| `Math.round(double)`, `DoubleStream.sum()`/`average()` | `JavaMath.round`, `JavaMath.sum`/`average` | `roundToLong` throws on NaN and JS rounds differently; the stream sums with Kahan compensation (`JavaMathTest`) |
| `Math.min/max/abs/pow/exp/log/sqrt/floor` | `kotlin.math` | The JVM's `kotlin.math` calls `java.lang.Math` |
| `Comparator.comparingInt/comparingDouble`, `thenComparing`, `Comparator.reverseOrder()` | `comparingInt`/`comparingDouble` (engine/JavaMath.kt), `then`, `reverseOrder()` | `Int`/`Double.compareTo` on primitives is `Integer.compare`/`Double.compare` |
| `Optional<T>` (`CardEffects.byId`, `Zone.parse`, `ActionType.parse`, `PromptPurpose.parse`) | `T?` | `map(f).orElse(x)` → `?.f() ?: x` (a null from `f` falls to `x`, as `Optional.map`); `findFirst()` on a null element threw NPE: `!!` keeps it (`MatchSetup.username`) |
| `Stream` (`CardLocator.cards`, `EffectEngine.cardsInRoom`), `stream().filter().toList()` | `Sequence`, `filter` (`frozenListCopy` where `Stream.toList()`'s unmodifiable list was kept) | Same lazy order |
| `Predicate`, `Supplier`, `Consumer`, `ToDoubleBiFunction` | Kotlin function types; `fun interface` `TableScoring`, `LookaheadBot.Watcher`, `ValueModel.TrainingLog` for the ones Java passes lambdas to | Java lambdas still fit |
| `Class<*>` keys (`Resolution` families, `Actions` handlers) | `KClass<*>` (`step::class`) | `KClass` equality is the `Class`'s |
| `TreeSet`/`TreeMap` built and returned (`CardEffects.definitions`, `ruleEffects`), `TreeMap.toString` (`BotPlayer` keys) | collect, then `sorted()` into a `LinkedHashMap` / `joinToString(", ", "{", "}")` | `String.compareTo` is the TreeMap's order; same iteration and text |
| `EnumSet` (`ActionType` flags, `BotSeat.SETUP`) | `setOf` | Only `contains` is used |
| `Arrays.sort(double[])`, `Collections.reverse`, `array.clone()` | `DoubleArray.sort()`, `reverse()`, `copyOf()` | Same results (`clone` does not exist off the JVM) |
| `@Volatile` (kotlin.jvm) | `@kotlin.concurrent.Volatile` | The JVM's is a typealias of it |

What stays in `platform/Platform.kt` (the JVM `actual`s of step B), and why:

- `Platform.randomSeed()` (`new Random().nextLong()`), `resourceText` (class-path resources: bot models and settings).
- Clocks: `currentTimeMillis()` (log and event times), `nanoTime()` (the bot's time budgets).
- `now()`/`Instant`, `seconds()`/`Duration` (typealiases of `java.time`): `GameRoom.createdAt/emptySince` and
  `SimulatorLimits.EMPTY_ROOM_TTL`, read by the server's Java (`RoomRegistry`, `RoomMembership`), so their Java types stay.
- `randomHexId()` (`UUID.randomUUID()` without dashes): room ids and editor dummy cards.
- Threads: `LocalValue<T>` (`ThreadLocal`: the bot's per-thread sandboxes, `EffectEngine`'s looker/summoning context,
  `MoveTrials.AUDIT`), `concurrentMap()` + `computeIfAbsentAtomic` (`ConcurrentHashMap.computeIfAbsent`: the bot's per-card
  caches and `Usefulness.BY_CARD`, shared by bot threads; Kotlin's `getOrPut` is not atomic), `withLock` (`synchronized`),
  `AtomicCounter` (`AtomicLong`, prints as its value), `AtomicFlag` (`AtomicBoolean`: `BotPlayer`'s wake claim).
- `identityMap()` (`IdentityHashMap`: `RoomCopy`'s copied-object map, `MoveTrials.top`'s scores per move object; `GameAction`
  has value equality, so identity matters), `emptySortedSetLike`/`emptySortedMapLike` (`RoomCopy` keeps a sorted
  collection sorted).
- `Transient` (typealias of `kotlin.jvm.Transient`): `GameRoom`'s headless/searchHope fields stay `transient` for Jackson.

Notes for step B: `@JvmRecord`, `@JvmStatic`, `@JvmField`, `@JvmName`, `@JvmOverloads` and `@JvmSuppressWildcards` compile in
common code when imported from `kotlin.jvm` (the scratch project injects those imports). `String.hashCode()` (the seed of
`BlockSearch`) is Java's on every Kotlin target. `Double.toString()` and string templates of doubles differ on JS
(`1.0` prints `1`): any text the core builds from a `Double` (debug traces, keys) is worth a look before the web must match the
JVM's output.

## Step B: the core in eras-engine

The core is common Kotlin in **`eras-engine/src/commonMain/kotlin`**, with the **same packages**
(`com.drive.drive_manager.simulator.{engine,actions,bot,room,platform}`, `rank/RankRules.kt`, `replay/MatchSetup.kt`), so
the server's Java, the `@JacksonMixin` targets, the class names Mongo keeps and the JSON did not change. drive_manager
depends on its JVM artifact (`com.eras:eras-engine-jvm:0.1.0-SNAPSHOT`, group/version in `eras-engine/gradle.properties`)
in its main build. Behaviour is the one of step A: 652 simulator tests (0 failures, 18 skipped) with the same per-class
counts, the golden hashes unchanged.

What stays in drive_manager (Java): the card files' readers (`CardFiles`, `EffectJson`, `CardFilesCheck`: Jackson; step C
replaced them with kotlinx.serialization in common, see "Step C"), `ValueModelFiles`, `BotRooms`, the rooms' service and messages
(`RoomService`, `RoomMessages`, `RoomDecks`, …), controllers, missions, ranks' and replays' server parts, `json/SimulatorJson`.
(Since then the rules inside `BotRooms`, `RoomMembership`, `RoomDecks` and `RoomMessages` moved to common code too: see "One
copy of the room logic" at the top.)
The resources (`cards/`, `bot/`) stay in drive_manager's class path too.

### The seams (the core names no server class)

| Core (eras-engine) | Server (drive_manager) | Replaces |
|---|---|---|
| `engine/CardSource` (`fun interface`: `cards()`), `CardSources` (`install`, `cards()`) | `engine/ServerCardSource` → `CardFiles.load()` | `CardEffects` calling `CardFiles.load()` |
| `bot/BotResourceSource` (`text(path, failure)`, `valueModel(path)`), `BotResourceSources` (`install`, `current()`, `NONE`) | `bot/ServerBotResourceSource`: class-path text, `ValueModelFiles.resource` | `BotCatalog` calling `ValueModelFiles` and `Platform.resourceText` |
| `RoomGuard.usableDeck(privateDeck, deckOwner, room, userId)` | `RoomDecks` passes `DeckList.isPrivateDeck()`, `getUserId()` | `usableDeck(dto.DeckList, …)` |
| `RoomEngine.EDITOR_RIVAL` | `RoomService.EDITOR_RIVAL` (an alias already) | `RoomService.EDITOR_RIVAL` in `RoomSetup`/`RoomGuard` |

The server offers its two sources as `java.util.ServiceLoader` services
(`src/main/resources/META-INF/services/com.drive.drive_manager.simulator.{engine.CardSource,bot.BotResourceSource}`), found by
`Platform.discover` the first time the core needs them (when `CardEffects` / `BotCatalog` initialise), so every test and
the server get them with no start-up code, exactly when they were read before. An `install(...)` before that first use
wins over discovery (JavaScript, which has no service loader, and step C will install theirs that way). Without any
source the core has no programmed cards and the bot plays with its defaults.

### `platform/Platform.kt`: expect/actual

`commonMain` declares `expect object Platform` (`randomSeed`, `discover`, `currentTimeMillis`, `nanoTime`, `now`,
`seconds`, `randomHexId`, `concurrentMap`, `identityMap`, `emptySortedSetLike`, `emptySortedMapLike`),
`computeIfAbsentAtomic`, `withLock` (`expect inline`), `expect class Instant`, `Duration`, `LocalValue<T>`,
`AtomicCounter`, `AtomicFlag` and `expect annotation class Transient` (a typealias to the `@OptionalExpectation`
`kotlin.jvm.Transient` is not allowed in common code).

- `jvmMain` (`Platform.jvm.kt`): exactly the JVM implementation of step A (`java.time.Instant`/`Duration` and
  `kotlin.jvm.Transient` as `actual typealias`, `ServiceLoader`, `ThreadLocal`, `ConcurrentHashMap.computeIfAbsent`,
  `IdentityHashMap`, `TreeSet`/`TreeMap` copies, `synchronized`, `AtomicLong`, `AtomicBoolean`, `UUID`).
- `jsMain` (`Platform.js.kt`): one thread. `Date.now()`, `performance.now()` (falls back to `Date.now()`),
  `kotlin.random.Random.Default` (Math.random) for seeds and version-4-UUID-like hex ids, `HashMap` for the concurrent
  map, an identity map on a JavaScript `Map`, no sorted copies (null), no-op `withLock`, plain holder/counter/flag, an
  `Instant` of epoch millis and a `Duration` of seconds, no service discovery.
- `PlatformTest` (commonTest) runs on the JVM and on Node.

Class-path resources are no longer read by the core (`resourceText` went to `ServerBotResourceSource`).

### Other changes

- One `GameRandom`: the core's (`com.drive.drive_manager.simulator.engine.GameRandom`, with `nextGaussian`). eras-engine's
  phase-0 `com.eras.engine.GameRandom`/`GameShuffle` is gone; `EngineApi`, `GameRandomTest`, `JavaRandomParityTest` and
  `KotlinEngineInteropTest` use the core's (`nextInt(bound)`, `GameRandom.shuffle`).
- eras-engine's JVM build keeps parameter names (`javaParameters`, as the kotlin-maven-plugin had them): Jackson puts a
  constructor's parameters first, in their order (`CardSlot`'s JSON). `-Xexpect-actual-classes` silences the Beta warning.
- `kotlin.jvm` annotations (`JvmStatic`, `JvmField`, `JvmName`, `JvmOverloads`, `JvmSuppressWildcards`, `JvmRecord`) are
  imported explicitly in each moved file.
- drive_manager's pom: the engine dependency excludes `kotlinx-serialization-json` (only the web facade `com.eras.engine`
  uses it; on the server's class path Spring could pick it as another JSON converter). The `kmp-engine` profile adds it
  back for `KotlinEngineInteropTest`.
- `CoreBoundaryTest` reads the core's Kotlin from `../eras-engine/src/commonMain/kotlin`, same rules (the two new
  server sources are listed with the server's files). `EngineShapeTest` only ever scanned drive_manager's `.java` files;
  it is unchanged.
- All the tests stayed in `drive_manager/src/test/java` and run against the engine's jar.

### Building locally

```bash
cd eras-engine && ./gradlew build publishToMavenLocal   # core on the JVM and JS, tests on both, jar into ~/.m2
cd ../drive_manager && mvn test                         # uses com.eras:eras-engine-jvm from ~/.m2
mvn -Pkmp-engine test -Dtest='KotlinEngineInteropTest'  # the engine against java.util.Random and Jackson
```

After changing the core, publish it again before building drive_manager (Maven takes the jar from `~/.m2`, not the
sources). In IntelliJ, open both builds (Gradle and Maven); the server's Maven project sees the engine as a jar.

### Docker and CI

- `drive_manager/Dockerfile` builds from the **repository root** (`docker build -f drive_manager/Dockerfile .`): stage
  `engine` (eclipse-temurin:21-jdk, the Gradle wrapper, `./gradlew publishToMavenLocal`), stage `builder` (Maven, with
  `com/eras` copied into its `~/.m2`, then `dependency:go-offline` and `package`), then the JRE image as before.
  `.dockerignore` at the root keeps `.git`, build outputs (`build`, `target`, `.gradle`, `.kotlin`), `node_modules` and
  `.env` files out of the context.
- `docker-compose.yml` (root: `context: .`; drive_manager's: `context: ..`) both with `dockerfile: drive_manager/Dockerfile`.
- `.github/workflows/ci-cd.yml`: the `test` job sets up Gradle and runs `./gradlew publishToMavenLocal` in `eras-engine`
  before `mvn verify`; the `build` job's `docker/build-push-action` uses `context: .` and `file: drive_manager/Dockerfile`;
  the `engine` job is unchanged (engine tests on the JVM and Node, publish, interop test).

## Step C: the card files read in common code

The programmed cards' data and their reader moved to eras-engine, so the browser loads exactly the cards the server runs.
Nothing changed for the players: 358 cards read into the same definitions (checked below), the golden hashes and the seeded
bot games unchanged, the simulator's per-class test counts those of step B plus the new tests.

### Where the card data lives

- **`eras-engine/cards/<edition>/<card>.json`**: the one copy of the card files (moved from
  `drive_manager/src/main/resources/cards`, unchanged, history kept by `git mv`).
- eras-engine's build makes from them (`build.gradle.kts`):
  - task **`cardBundle`** → `build/generated/cardBundle/card-bundle.json`: every file's text in one JSON document, in the
    order of their paths: `{"format": "eras-card-bundle/1", "files": [{"path": "E1/a-la-carga.json", "text": "…"}, …]}`
    (~900 KB, the files' text kept byte for byte so each is read exactly as before);
  - task `cardResources` → the JVM jar's resources: the files at `cards/**` (where Spring's `classpath*:cards/**/*.json`
    finds them: `MatchHistory.rulesVersion`, `KotlinEngineInteropTest`, the parity tests) and the bundle at
    `eras/card-bundle.json` (`CardBundle.RESOURCE`).
- drive_manager has no card files of its own any more: they come with the engine's jar (and so into the Docker image,
  whose engine stage copies `eras-engine/cards`).

### The reader (`eras-engine/src/commonMain/kotlin/.../simulator/engine/cards`)

| File | What |
|---|---|
| `CardReader.kt` | `CardReader.read(json): CardDefinition`: parses with kotlinx.serialization (`Json.parseToJsonElement`, not lenient) and builds the definition from the tree; errors are `IllegalArgumentException("Efectos inválidos: <path>: <reason>")`, as `EffectJson.read`'s. `DuplicateKeys`: Jackson's `STRICT_DUPLICATE_DETECTION` (kotlinx's tree keeps the last value), and its refusal of raw control characters in strings. |
| `JsonFields.kt` | How each value is read, as Jackson read it with `EffectJson`'s settings: absent/null `boolean`/`int` → false/0; absent/null list or set → empty `ArrayList`/`HashSet` (Jackson's `Nulls.AS_EMPTY` also applies to an absent creator property); any other absent/null value → null (so `sourceZone` and `limit` are null when absent, not "field"/`NONE`: what Jackson gave); an unknown field → error; enums by exact name. |
| `CardModelReader.kt` | **Generated**: one function per class of the card model (102 records, 41 Kotlin `data object`s, 16 enums, 6 typed families), calling each record's constructor with named arguments. |
| `CardBundle.kt` | `CardBundle.files(text)`, `CardBundle.read(text)` (every card, with the old `CardFiles` checks: "<file name>: Efectos inválidos: …", "Carta en dos archivos: …", "Efecto repetido: …"), and **`BundleCardSource(text)`**: the core's `CardSource` for a bundle's text. |

Typed values follow `EffectJson`: the registered type ids are the simple names of the members of `EffectStep`, `Action`,
`Condition`, `Amount` and `Reaction` (their records and data objects, through the sealed hierarchy); a value is typed when its
declared class is one of those or `EffectStep.NeedsTarget`, or a subtype of one (so a `Replacement` property carries
`"type": "Replacement"` too), and its id must name a registered subtype of the declared type. An id can name members of two
families (`Amount.Gems` and `Action.Gems`): the declared type decides, as in Jackson. A data object is its type alone and reads as
the object itself (Jackson made a new instance, equal to it).

**The generator** (`src/jvmTest/.../engine/cards/CardModelReaderGenerator.kt`, kotlin-reflect on the JVM classes) writes
`CardModelReader.kt` from the model; `CardModelReaderGeneratorTest` fails while the checked-in file differs from what the model
gives. After changing a card model class: `./gradlew jvmTest -PregenerateCardReader`, then commit the file.

**Stricter than Jackson, on purpose**: no coercions (a text `"3"` or `2.7` for an int, `"true"` or `1` for a boolean, a
number for a text, an enum's index) and no null inside a list (Jackson kept it and the definition broke later, with an NPE).
No card file uses any of them: each file is, as a tree, exactly the JSON `EffectJson` writes for it
(`CardReaderParityTest.everyCardFileIsCanonical`), and the differences are listed in `refusesJacksonsCoercions`.

### The server

- `CardFiles.load()` (Java, still the server's loader) = `CardBundle.read(<eras/card-bundle.json from the class path>)`;
  `ServerCardSource` (the `CardSource` service) calls it, as before. `CardFilesCheck` (the start-up check that logs the count
  and fails the start on a broken card) is unchanged.
- `EffectJson` moved to `src/test/java` (same package): the server's main code no longer reads or writes card JSON with
  Jackson. It stays as the parity tests' oracle and for the tests that write definitions as JSON (`EffectJsonTest`,
  `DefinitionsExportTest`, `LanguageCatalogExportTest`). `JacksonCardFiles` (test) is the old `CardFiles.load()`: class-path
  scanning plus `EffectJson.read`.
- `kotlinx-serialization-json` is now on the server's class path (the engine needs it at run time; the pom's exclusion and the
  `kmp-engine` profile's own copy are gone), at the engine's version: the pom sets `kotlin-serialization.version` to 1.12.0
  (Spring Boot's dependency management would otherwise downgrade it to 1.9.0). Spring still uses Jackson for the server's types, checked by starting the
  application against a local MongoDB: the STOMP converters are String, bytes and Jackson (Boot's configuration replaces
  Spring's defaults, which would otherwise put kotlinx's message converter, one that takes every type, first); among the HTTP
  converters Spring adds `KotlinSerializationJsonHttpMessageConverter` before Jackson's, but it refuses everything that is not
  `@Serializable` (maps, lists, `GameRoom`…: Jackson's, as before), and no response of the server is.
- `CoreBoundaryTest`: `engine/EffectJson.java` left the list of server files of the core's packages.

### Parity (`drive_manager/.../engine/CardReaderParityTest`)

- `theBundleHoldsTheCardFilesOfTheClassPath`: 358 files, the bundle's paths and texts equal to the class path's files, in
  Spring's scanning order (the order the server loaded them in).
- `everyCardReadsAsJacksonReadIt`: for each of the 358 files, `CardReader.read` and `EffectJson.read` give definitions whose
  JSON is **byte-identical** with both of the server's mappers (`EffectJson.write`/`pretty` and `SimulatorJson`'s, the rooms'
  JSON), that are `equals` both ways with equal `hashCode` and `toString`, and whose **object graphs** match field by field
  (the same record classes, list and set classes — `ArrayList`, `HashSet` — element order, enum constants, data objects).
- `theServerLoadsWhatJacksonLoaded`: `CardFiles.load()` / `ServerCardSource` equal the old loader's list.
- `refusesWhatJacksonRefused`: 33 broken inputs (unknown step/condition/field, a type of another family, a missing or null
  type, a repeated key — also written with an escape —, wrong kinds, an unknown enum name, int overflow, trailing or missing
  text, comments, unquoted keys, a non-object root…): both refuse; the common reader with "Efectos inválidos: …".
- `theLoaderRefusesWhatItRefused`: a card in two files, an effect id twice (in two cards, or as own and inherited), a broken
  file: the same failures, with the same messages (for a broken file, the same "<file>: Efectos inválidos: " and then each
  reader's own reason).
- eras-engine's `CardReaderTest` (common, JVM and Node) covers the reader's values and refusals; `CardBundleTest` reads the
  build's bundle on both platforms.

### JavaScript: the same game as the JVM

- **Installing the cards**: `CardSources.install(BundleCardSource(bundleText))` before the engine is first used (Kotlin), or
  `EngineApi.installCards(bundleText)` from the site (exported; reads every card at once and returns how many). On the JVM the
  server keeps its service (`ServerCardSource`); `BundleCardSource` works there too (eras-engine's own JVM tests use it). The
  site will fetch `card-bundle.json` (phase 4); the tests read it from the build (`ERAS_CARD_BUNDLE`).
- **`SeededBotGameTest`** (commonTest: runs on the JVM and on Node): installs the bundle and plays whole games between two
  bots through the core alone (`RoomEngine` with `RoomHost.gameOnly()`): the decks of drive_manager's `BotMatch.seat` from the
  same starter decks' metadata (`drive_manager/src/test/resources/simulator/st*-cards.json`, `ERAS_DECKS`; `CardMeta.apply`
  ported to the test), started like `MatchReplay.play` (`PreRoomActions.startFromPreRoom(room, seed)`), then the loop of
  `SimulatorJsonTest` (one seeded `HeuristicBot` moving for the first seat that has a move). `GameFingerprint` (common, no
  Jackson) sums up the final state (room counters, every card of every zone with its fields; doubles by their bits), the
  history's lines and the actions (FNV-1a 64). Four games (st1–st2 seed 11, st2–st3 3, st1–st3 2 — with refused answers on the
  way —, st4–st1 1), 173–198 actions each, all to the end: **the same fingerprints on the JVM and on Node**, asserted against
  the same constants on both. (The bots play with their defaults there: no bot resources are installed in eras-engine.)
- Node tests (`jsNodeTest`) run in `./gradlew build`, as before; the browser's (Karma) stay disabled.

What JavaScript did differently, fixed in the core without changing the JVM's behaviour:

| Found | Fix |
|---|---|
| `Removals.PROTECCION = Regex("(?i)protección")`: JavaScript has no inline flags (`SyntaxError: Invalid group` the first time a "Protección" keyword was checked) | `[pP][rR][oO][tT][eE][cC][cC][iI]ó[nN]`: exactly Java's `(?i)`, which without `UNICODE_CASE` folds ASCII letters only (`RegexOption.IGNORE_CASE` would have added `UNICODE_CASE` on the JVM) |
| Java's and JavaScript's regular expressions mean different things by `\s` (JavaScript adds the Unicode spaces, e.g. a no-break space), `.` (JavaScript lets `\u0085` through) and `$` (Java also matches before a final line terminator). Not seen in the games (the texts have none of those characters), but card metadata (requirements, keywords) is free text | Every pattern of the core is built with **`Platform.regex(pattern)`**: on the JVM `Regex(pattern)` (unchanged); on JavaScript `Regex(JavaRegex.toJavaScript(pattern))`, which writes Java's meaning out (`[ \t\n\u000B\f\r]`, `[^\n\r\u0085\u2028\u2029]`, a look-ahead for `$`) and refuses inline flags and `\Q…\E` (`JavaRegexTest`, both platforms) |

Checked and the same on both: `Double.toString` does not reach the state or the history of these games (the fingerprint
writes doubles by their bits anyway), `String.hashCode` is Java's on every target, `Long` arithmetic is exact on JavaScript.
Still worth knowing: Kotlin/JS's `HashMap`/`HashSet` iterate in insertion order, the JVM's in hash order; the core iterates
some (`usedThisTurn`, the bot's caches…). No game showed a difference; a future one would show as a fingerprint mismatch in
`SeededBotGameTest`.

### Building

```bash
cd eras-engine && ./gradlew build publishToMavenLocal   # bundle, reader, tests on the JVM and Node, jar (with the cards) into ~/.m2
cd ../drive_manager && mvn test                         # the server reads the cards from the engine's jar
```

After editing a card file, publish the engine again (the server takes the cards from the jar in `~/.m2`). The Docker image
(`docker build -f drive_manager/Dockerfile .`) copies `eras-engine/cards` into its engine stage; built and started against a
local MongoDB, it logs "Cartas programadas cargadas: 358".
