# eras-engine

The game engine in Kotlin Multiplatform: one code, built for the JVM (DriveManager) and for JavaScript (eras-web,
to play against the bot offline). Plan: "ERAS: plan del motor en Kotlin Multiplatform".

**Status: phase 3.** The game's core (rules, effects, bot, rooms' state) is here, in `src/commonMain/kotlin`,
under its original packages `com.drive.drive_manager.simulator.*`; DriveManager depends on the JVM artifact. The
platform seam (`simulator/platform`) is `expect`/`actual` (`jvmMain`, `jsMain`). The card files live here too and are read
in common code (kotlinx.serialization), on the server and in JavaScript. A game against the bot runs in the browser exactly
as the server runs it (`com.eras.engine.local.LocalMatch`, see "Phase 3"), shipped as the npm package `@eras/engine`.
Details of phase 2: `drive_manager/docs/kotlin-conversion/README.md`, "Step B" and "Step C".

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
./gradlew jsSmoke                            # a whole game with that package on Node (js-smoke/smoke.mjs)
```

From DriveManager's Java (after `publishToMavenLocal`):

```bash
cd ../drive_manager
mvn -Pkmp-engine test -Dtest='KotlinEngineInteropTest' -Dsurefire.failIfNoSpecifiedTests=false
```

DriveManager's main build depends on the JVM artifact, so publish it before building the server. The `kmp-engine`
profile adds `src/test-kmp/java` (and kotlinx.serialization) to the tests. CI runs both steps in the `engine` job, and
builds the engine before the server's tests and image.

## Phase 3: a game against the bot in the browser (`LocalMatch`)

`com.eras.engine.local.LocalMatch` (common code, exported to JavaScript) is a room against the bot that the board drives
**exactly as it drives the server**: it takes the bodies the board publishes to `/app/room/{id}/action`
(`{"type": …, "payload": {…}}`) and returns, as JSON texts, the messages the server would push to that player's
`/user/queue/room/{id}`: the same messages, in the same order, byte for byte (field names and order, nulls, numbers,
masking). Phase 4 puts it behind the same store (`game.store.ts`) as a `LocalTransport`, in a Web Worker.

### API (as TypeScript sees it, `eras-engine.d.mts`)

```ts
import { EngineApi, LocalMatch } from '@eras/engine';

const api = EngineApi.getInstance();
api.installCards(bundleText: string): number;          // card-bundle.json, before the first match (once)
api.installBotResources(json: string): number;         // bot-resources.json, before the first match (once)

const match = new LocalMatch(config: string);          // the room, with the bot seated (no message yet)
match.roomId: string;                                  // "local" unless the configuration names another
match.userId: string;                                  // "local-player" unless the configuration names another
match.send(action: string): Array<string>;             // the player's action: what they are sent for it (0 or 1 message)
match.step(): Array<string> | null;                    // one move of the bot: what the player is sent of it; null: nothing to do
match.playBot(): Array<string>;                        // step() until null, all at once
match.botPauseMs(): number;                            // how long the server's bot waits before its next move
match.status(): string;                                // WAITING, MULLIGAN, IN_PROGRESS, POST_GAME, FINISHED…
match.lastError(): string | null;                      // why the last send/step sent nothing (unreadable body, engine failure)
match.result(): string;                                // {"status","gamesEnded","winnerId","endReason","turns","digest"} (JSON)
```

`result()` is what an offline game claims when the site sends it to the server ("Offline games and rewards", below):
`gamesEnded` counts the times the room reached the post-game (a rematch plays another game in the same room), `turns` is
counted as the server's match history counts them, and `digest` is `MatchDigest`: FNV-1a 64 over the room's game without
anything the clock or the device decides (the history's times, icons, playmats, connection, the cards' pictures and file
ids), so the same game gives the same digest on the JVM and in the browser whatever `time` was (`LocalMatchTest` checks
four games' results on both platforms). On the JVM, `LocalMatchRoom.of(match)` gives the match's `GameRoom` (not
exported to JavaScript).

The configuration (JSON; every member optional but `decks`):

| Member | Default | What |
|---|---|---|
| `seed` | `"0"` | The game's chance (the room's `GameRandom`) and the bot's (seed × 31 + 2), as a decimal string (64 bits) |
| `decks` | none | The decks `SET_DECK {"deckId"}` finds, by id, in place of Mongo: `{"<deckId>": {"deckName": …, "cards": [{"card": <the card's metadata as the site's card API gives it>, "copies": n, "cardId": <file id; default the metadata's id>, "imageUrl": …}]}}`, or `{"deckName": …, "metadata": [<metadata>…]}` for a starter deck (each card ×3, the Iniciador ×1). `starter-decks.json` holds ST1–ST4 in this form (`decks`) |
| `botLevel` | `normal` | `easy`, `normal`, `hard` or a named model of `bot/models.txt`; also `SET_BOT_LEVEL` in the pre-room, as online |
| `userId`, `roomId` | `local-player`, `local` | The ids in the messages |
| `avatar`, `playmat` | null | The player's icon and playmat (the server reads them from the profile) |
| `time` | null | Epoch millis written on every history line and event (`ts`); null: the clock's (as the server) |
| `botClock`, `tickNanos` | `ticks`, `1000000` | `ticks`: the bot's time budgets counted in clock readings (each reading one `tickNanos`), so a seed plays the same game on any machine; `system`: real time, as the server's bot (a slower machine thinks less) |

The game itself is the board's to drive, with the actions it sends online: `JOIN_ROOM {"username"}`, `SET_DECK
{"deckId"}` (and `{"deckId", "actAs": "bot"}` for the bot's deck: by default the bot mirrors the player's, as on the
server), `SET_BOT_LEVEL`, `SET_READY`, `CONFIRM_MULLIGAN`, the game's actions, `POST_GAME_CHOICE`, `LEAVE_ROOM`…

### The message contract

- Every message is a `GameStateUpdate` written as the server's Spring Boot Jackson 3 mapper writes it with
  `json/SimulatorJson`'s mix-ins (`UpdateJson`): all its properties every time (nulls included), in Jackson 3's order
  (by name; the parameters of a class's all-property constructor first: `CardSlot`, `GameEvent`, the records), doubles as
  Java writes them (`50.0`), strings escaped as Jackson escapes them. `UpdateJson.PROPERTIES` lists each class's properties;
  drive_manager's `LocalMatchParityTest` checks them against Jackson's own.
- What the player sees of it is `StateMask.forViewer(update, player, manual = false)` (the rival's hand, decks, face-down
  cards and choices are backs, as online); `StateMask.recipients` (which the server's `RoomMessages.send` uses too) decides
  whether they get it at all.
- An action the rules refuse answers with an error update (`roomId`, `error`, `actorId` = the player; every other
  property null/false/0), sent only to the player. An accepted `SET_BOT_DELAY` sends nothing (it shows in the next
  update), a refused one its error. A body the server could not read (not JSON, a payload that is not an object) sends
  nothing; nor does a failure inside the engine (the server would log it): `lastError()` tells which.
- The order is the server's: the player's update first (`send`), then one update per move of the bot (`step`), its
  conceding included (after 1100 refused moves, as `BotRooms`). After the room is closed (`LEAVE_ROOM`, `CLOSE_ROOM`)
  every action answers "La sala ya no existe".

**Placeholders** for what the server makes up and has no meaning offline: the room id (`local`), the player's id
(`local-player`; their name is the `username` of `JOIN_ROOM`, as online), the card instances' ids (`c1`, `c2`… in the
order the decks are loaded; the server's are random UUIDs), the history's times (the clock's, or `time`). What only the
server has is not there: the site editors' manual actions (always `manual = false`), the player's collection (any deck in
`decks` is playable), finishes, missions, the match history, the ranked ladder (`SET_RANKED` works, at a new player's
strength, 1000 MMR, and nothing is recorded), reconnections and the rooms' sweeps.

### In a Web Worker

One thread and nothing running on its own: `send` applies the player's action and returns; the bot moves only when asked.
The worker posts what `send` returns, then calls `step()` after `botPauseMs()` until it returns null (the server's bot
waits that long before each move, so the player follows them), and again after the player's next action:

```ts
// worker.ts
import { EngineApi, LocalMatch } from '@eras/engine';
let match: LocalMatch;
onmessage = async ({ data }) => {
  if (data.kind === 'create') {
    const api = EngineApi.getInstance();
    api.installCards(await (await fetch(data.cardBundleUrl)).text());     // once per worker
    api.installBotResources(await (await fetch(data.botResourcesUrl)).text());
    match = new LocalMatch(JSON.stringify(data.config));
    postMessage({ kind: 'created', roomId: match.roomId, userId: match.userId });
  } else if (data.kind === 'action') {                                   // the body the store publishes
    for (const body of match.send(data.body)) postMessage({ kind: 'message', body });
    pumpBot();
  }
};
let pumping = false;
function pumpBot() {
  if (pumping) return;
  pumping = true;
  setTimeout(function next() {
    const moves = match.step();
    if (moves === null) { pumping = false; return; }
    for (const body of moves) postMessage({ kind: 'message', body });   // what /user/queue/room/{id} would deliver
    setTimeout(next, match.botPauseMs());
  }, match.botPauseMs());
}
```

A move of the bot takes from a few milliseconds to a few seconds of the worker's time: its levels look ahead with a
budget of 400 ms (easy) to 2 s (normal, hard), counted in `ticks` mode in readings of its clock (1 ms each by default).
On Node (one core of this sandbox), a whole game against a passive player: easy 80 ms a move on average (at most 0.3 s),
normal 0.7 s (median 0.25 s, at most 4.4 s); hard thinks longer. The worker stays responsive between moves; a player's action that arrives while
the bot thinks waits for that move (on the server the two would race; here they never do).

### Loading

The npm package carries what a game needs beside the code: `card-bundle.json` (the 358 programmed cards; install it with
`installCards`), `bot-resources.json` (the server's `bot/*.txt`: the levels' tuned settings and the named models, so the
bot plays as the server's and the pre-room offers the same `botChoices`; `installBotResources`) and `starter-decks.json`.
Both installs must come before the first `LocalMatch` (the engine reads them once). They are exported paths of the
package (`@eras/engine/card-bundle.json`…): the site can copy them as assets or fetch them by URL.

| `build/dist/js/productionLibrary` | Size | Gzipped |
|---|---|---|
| The ES modules (`eras-engine.mjs`, Kotlin's stdlib, kotlinx.serialization), as built | 2,412 KB | 394 KB |
| The same bundled and minified by esbuild (as Angular's build does), `EngineApi` and `LocalMatch` | 853 KB | 253 KB |
| `card-bundle.json` | 918 KB | 70 KB |
| `bot-resources.json`, `starter-decks.json` | 6 KB, 80 KB | 1 KB, 7 KB |

### The room session: one copy of the room logic

The rules of a room that are neither the game's actions nor the server's plumbing live once, in common code, and both
the server and `LocalMatch` call them (each through a small interface for what only it knows):

| Common (eras-engine) | What | Asks of its host | Server (drive_manager, Java) | Browser (`LocalRoom`) |
|---|---|---|---|---|
| `room/RoomSeats` | join and reconnect, leave, kick, close, a player taken out (`remove`), the ownership passed on, a ranked game against the bot lost by leaving, their messages | `Keeper`: `dress` (icon, playmat), `closed`, `forgetSessions` | `RoomMembership`: profiles, the registry; keeps sessions dropping, test room, the sweeps (abandons, owner gone) with missions and recording | the match's avatar/playmat; `closed` ends the room |
| `room/DeckChoice` | `SET_DECK` from a deck found by id (guards, unprogrammed cards, the bot's mirrored copy), `REFRESH_CARD_METADATA`, `freshCopy`, `unprogrammedIn`, the walk over every copy of a card | `Shelf`: `find` (name, private, owner, slots), `nextInstanceId`, `refusal` (collection), `withFinishes`, `refresh` | `RoomDecks` (Mongo via `DeckLoader`, missions' collection and finishes, UUIDs) | `LocalDecks`, ids `c1`, `c2`… |
| `bot/RoomBots` | the bot's seat, every bot setting (level, ranked with the copy of the owner's deck, the test room's bots, pace, edge), `paceAtOnce`, `mayMove`, one move of a bot (`move`: refusals, conceding after `MAX_ATTEMPTS`), `concede` | `Roster`: `newBot`, `ownerStrength`, `nextInstanceId`, the test room's `dropBot`/`newSelfBot`/`dropSelfBot` | `BotRooms`: the bot players by room, wake/think on the bots' threads, `playNow` | one seeded bot, a new player's strength |
| `room/StateMask.recipients` | who is sent an update (error: who caused it) | | `RoomMessages` (STOMP, one message per tab) | the one player |
| `room/RoomEngine.blamed` | an error tagged with who caused it | | `RoomService.applyAction` | `LocalRoom.applyAction` |

What stays server-only: Spring, Mongo lookups, the registry and its locks, the bots' scheduling and thinking on copies,
STOMP sending, sessions and sweeps, missions, match history and ranks, replays and the test room's timeline. What stays in
`LocalMatch`/`LocalRoom`: the JSON in and out, the clocks, the closed flag, the match's decks and placeholder ids.

### What makes it the server's game

- **Parity** (drive_manager's `room/LocalMatchParityTest`, JVM): the same seeded games through the server's real path (the
  STOMP controller `GameController`, `RoomService`, `RoomEngine`/`ActionDispatcher`, the bot moved by `BotRooms.playNow`,
  `RoomMessages` to the player's queue, written by Spring Boot's own `JsonMapper`) and through a `LocalMatch`, with the same
  bodies (the player's moves chosen by a seeded heuristic bot): every message is the same text, byte for byte. Five games
  (ST1 mirror against the normal bot, ST2–ST3, ST4–ST1 and ST3–ST4 against the easy bot, a ranked game against the normal
  bot with its copy of the player's deck; then leaving, and acting in the closed room), 957 messages, with refused actions (an unknown type, a level that does not exist, an attack with no
  card, actions out of turn), an unreadable body, the bot's pace and the post-game rematch. To make the server's side
  predictable the test gives it what LocalMatch makes up: the room id, `DeckLoader.instanceIds` (a seam: random UUIDs
  unless a test sets it), the room's random from the seed, the bot's seed, and the clocks below.
- **The same on the JVM and on Node** (`LocalMatchTest`, common): four seeded games (855 messages) give the same digest of
  every message on both. Fixed for it, without changing the JVM's behaviour:
  - JavaScript's `HashMap` iterates in insertion order: the maps whose order reaches the messages (a timeline event's data,
    an effect's saved choices) are `Platform.hashMap()`, `java.util.HashMap` on the JVM and `JavaHashMap` (Java's buckets,
    resizes and copies) on JavaScript;
  - Java's `Double.toString` (`Platform.doubleText`), and the declared type of each number (on JavaScript `0 is Double`);
  - a `}` outside a quantifier must be escaped in JavaScript's unicode regular expressions.
- **The bot's time** (`platform/Clocks`): the core reads its clocks through `Clocks.millis()` / `Clocks.nanos()`, the
  platform's unless the caller sets its own for the thread (`Clocks.using`): LocalMatch uses a fixed history time and a
  bot clock that moves one tick per reading (`Clocks.ticks`), so `LookaheadBot` is deterministic under a seed; the server
  never sets them. The bot's per-thread memory of verdicts (`Usefulness`) is kept per match (`Usefulness.withMemory`).
  `SeededBotGameTest` now also plays two look-ahead games (and three more heuristic ones) with the same fingerprints on
  both platforms.
- **A refused answer is never sent twice** (`BotPlayer`): a driver that asks one bot for both seats (the seeded tests) made
  it forget, at every call, the answers the rules had refused ("Sus niveles deben sumar 2 o menos") and it sent the same one
  forever (ST3–ST4 seeds 2, 3, 5 reached 3,000 actions). The refused answers to the choice that waits are kept now; every
  golden hash and fingerprint stayed the same, and those three games end (`SeededBotGameTest`).

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

## Offline games and rewards (phase 7)

A game of "Modo solo sin conexión" (a `LocalMatch` in the site's worker) that reaches its post-game counts, once the
player connects, **exactly as a casual game against the server's bot counts** (never ranked), and only when the server,
playing it again with this engine on the JVM, gets the same game. The site's side: eras-web README, "Offline games and
rewards".

**What a game against the server's bot grants today** (`RoomService.apply` → `MissionHooks.count`, `MatchRecorder` →
`MatchHistory`): the activity log's start and end of the game (`ONLINE_GAME_START`/`ONLINE_GAME_END`, one per seat, the
bot's too), and the game in the player's match history (`mode: "solo"`, with its log to watch it again). Missions,
progression and gems count **nothing** for a game against the bot (`MissionEvents.of`: "missions are won against
players"; progression counts the same events), and the rank moves only in a ranked game. An offline game accepted by
the server goes through the same path (`MissionHooks.count(room, before, {"offline": true, "offlineId"})`, so if games
against the bot ever count for missions, offline ones will too) and into the history flagged `offline: true` (with
`offlineId`, `engineBuild`; never ranked; `hasLog: false`: the server has the player's actions, not a log of the bot's).

**The record** (`POST /api/simulator/offline-games`, `{"games": [<game>]}`, at most 10 games and 2 MB; drive_manager
`simulator/offline/OfflineGame`):

```json
{"id": "<UUID made by the site>", "engineBuild": "997a7a6326e9", "seed": "8461…", "botLevel": null,
 "startedAt": 1762600000000, "endedAt": 1762600600000,
 "decks": {"deck-st1": ["<card id>", "…one per copy, in the deck's order"]},
 "actions": [{"body": "{\"type\":\"JOIN_ROOM\",\"payload\":{…}}", "botMoves": 0}, {"body": "…", "botMoves": 3}, …],
 "result": {"gameNumber": 1, "winnerId": "bot", "endReason": "lives", "turns": 12, "digest": "07b16f261386427e"}}
```

`actions` holds every body the board sent since the room was made, each with the bot's moves the worker made after it
before the next action (the worker moves the bot between the player's actions, so that order is part of the game);
`result` is `LocalMatch.result()` the moment the room reached its post-game (`gameNumber` = `gamesEnded`: a rematch's
game carries the whole room from its start). Each game gets a verdict: `accepted` (with what it counted: won, turns, the
missions' progress, the history's id), `duplicate`, `rejected` (`reason`, `message`) or `retry` (kept by the site).

**The checks** (`OfflineGames`, in order; limits in `application.properties`, `offline-games.*`):

| Check | Answer |
|---|---|
| Shape: a UUID-like id, a 64-bit seed, 1–5,000 actions of at most 8,192 characters, at most 20,000 bot moves in all | `rejected`, `invalid` (not kept) |
| A verdict for this player and id already given | the same answer again (`duplicate` when accepted) |
| Started within the last 30 days (`window-days`), ended after it started and not in the future (10 minutes of clock skew) | `rejected`, `window` |
| An accepted game of this player with the same seed and game number (the same game under another id) | `duplicate` |
| Accepted games today (the server's zone, `wallet.zone`) under `daily-cap` (20) | `rejected`, `daily-cap` |
| Games played again today under `daily-replays` (40: accepted and refused; bounds a client sending junk) | `retry` |
| Each deck the actions chose (`SET_DECK`, the bot's included) is legal for the player (`OfflineDecks`): a starter deck in the engine's order (`eras/starter-decks.json`, now in the JVM jar; each card the starter's own id or a drive card of the same name and edition), or one of **the player's own saved decks exactly as saved now**, with every copy in their collection for their own seat (what `DeckChoice` demands on the server). The decks are then made from the server's data (its card metadata, never the site's) | `rejected`, `deck` |
| Played again (`OfflineReplay`: `LocalMatch` on the JVM with the record's seed and bot level, the authenticated user's id, the server's decks, the bot's clock in `ticks` of the server's `tickNanos`, never the site's; each body sent, then `step()` exactly `botMoves` times, a missing move ends it) within `replay-budget-ms` (120 s, checked between every action and move), one replay at a time on the server (`concurrent-replays`; a player who waits more than 20 s gets `retry`) | over budget: `rejected`, `budget` |
| The replay is in the post-game with the claimed game number, winner, end, turns and digest | else `rejected`, `mismatch` |

The verdict is kept in Mongo, `offline_games` (`_id` "{userId}:{id}", a unique partial index on userId, seed and
game number over the accepted ones), **before** crediting, so the same game sent twice at once is credited once.

**A tampered game is caught by the replay, not by trusting the claim** (drive_manager `OfflineGamesTest`, games
recorded by a real `LocalMatch` as the worker records them): a claimed winner or digest that is not the replay's →
`mismatch`; an action taken out, or the bot's moves after an action changed → another game → `mismatch`; a deck that is
someone else's, a starter deck with another card, a saved deck edited since or one missing cards of the collection →
`deck`; the same game again (same id, or the same seed under another id) → `duplicate`, credited once; over the day's cap
→ `daily-cap`; a game too old or in the future → `window`; too many actions or bot moves → `invalid`; a replay over its
budget → `budget`. None of them credits anything.

**The engine's build** travels with each game and is kept with the verdict and the history; it is not checked: the
replay is the authority. A game played by another build is accepted when this build plays it the same (the same
digest); when the rules changed in between (a card's metadata or rules, the starter decks, the bot) it is refused as a
`mismatch`. Likewise a saved deck edited between playing and connecting is refused.
