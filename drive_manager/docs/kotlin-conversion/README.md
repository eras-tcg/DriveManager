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
