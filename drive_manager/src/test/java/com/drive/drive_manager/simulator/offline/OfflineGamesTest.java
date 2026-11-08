package com.drive.drive_manager.simulator.offline;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.DriveCard;
import com.drive.drive_manager.repository.CardRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import com.drive.drive_manager.repository.DriveCardRepository;
import com.drive.drive_manager.service.ActivityLogService;
import com.drive.drive_manager.service.CollectionService;
import com.drive.drive_manager.service.MissionRules;
import com.drive.drive_manager.service.MissionService;
import com.drive.drive_manager.service.ProgressionRules;
import com.drive.drive_manager.service.ProgressionService;
import com.drive.drive_manager.simulator.bot.BotRooms;
import com.drive.drive_manager.simulator.mission.MissionEvents;
import com.drive.drive_manager.simulator.mission.MissionHooks;
import com.drive.drive_manager.simulator.replay.MatchHistory;
import com.drive.drive_manager.simulator.replay.MatchRecorder;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.RoomOptions;
import com.drive.drive_manager.simulator.room.RoomService;
import com.eras.engine.local.LocalMatchRoom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Offline games against the bot, recorded as the site's engine worker records them (a real LocalMatch game), are played
 * again on the server and credited once, exactly as the server counts its own games against the bot (missions and
 * progression included); a tampered result or action list, an illegal deck or one not owned, and a game too long to
 * check are refused, and credit nothing; a player over the day's replays is told to send it later.
 */
class OfflineGamesTest {

    private static final String ALICE = "alice";
    private static final Instant NOW = Instant.parse("2026-11-08T15:00:00Z");
    private static final Map<String, JsonNode> STARTERS = OfflineDecks.readStarters();
    /** One recorded game of Alice's (ST1 against the easy bot), shared by the tests: recording it takes seconds. */
    private static OfflineRecorder.Recorded recorded;

    private final Store store = new Store();
    private DeckListRepository deckLists;
    private DriveCardRepository driveCards;
    private CardRepository cards;
    private CollectionService collections;
    private MissionService missions;
    private ProgressionService progression;
    private ActivityLogService activity;
    private MatchHistory history;
    private OfflineGames games;

    @BeforeEach
    void setUp() {
        deckLists = mock(DeckListRepository.class);
        driveCards = mock(DriveCardRepository.class);
        cards = mock(CardRepository.class);
        collections = mock(CollectionService.class);
        missions = mock(MissionService.class);
        progression = mock(ProgressionService.class);
        activity = mock(ActivityLogService.class);
        history = mock(MatchHistory.class);
        when(history.saveOffline(any(), anyInt(), anyString(), any())).thenReturn("match-1");
        // The catalog has the starter decks' cards (drive cards "d-<metadata id>") and Alice owns them all
        List<Card> metas = starterCards();
        for (String edition : List.of("ST1", "ST2", "ST3", "ST4")) {
            when(driveCards.findByEdition(edition)).thenReturn(drives(metas.stream().filter(m -> edition.equals(m.getEdition())).toList()));
        }
        Map<String, Map<String, Integer>> owned = new HashMap<>();
        metas.forEach(m -> owned.put("d-" + m.getId(), Map.of("normal", 3)));
        when(collections.of(ALICE)).thenReturn(new CollectionService.View(owned, Map.of()));
        games = service(rules(40, 120_000));
    }

    private OfflineGames service(OfflineGames.Rules rules) {
        return service(rules, store);
    }

    private OfflineGames service(OfflineGames.Rules rules, OfflineGameStore verdicts) {
        OfflineDecks decks = new OfflineDecks(deckLists, driveCards, cards, collections, "https://images.invalid", STARTERS);
        MissionHooks hooks = new MissionHooks(missions, progression, collections, activity);
        return new OfflineGames(decks, verdicts, hooks, history, rules, ZoneId.of("America/Santiago"), () -> NOW);
    }

    private static OfflineGames.Rules rules(int dailyReplays, long budgetMs) {
        OfflineGames.Rules d = OfflineGames.Rules.defaults();
        return new OfflineGames.Rules(dailyReplays, d.windowDays(), d.maxActions(), d.maxBotMoves(),
                d.maxActionChars(), budgetMs, 1, 1_000, OfflineRecorder.TICK_NANOS);
    }

    /** Alice's game with the starter deck ST1 against the easy bot, as the site records it (the starter's own card ids). */
    private static synchronized OfflineRecorder.Recorded st1Game() {
        if (recorded == null) {
            ObjectNode decks = OfflineRecorder.JSON.createObjectNode();
            decks.set("deck-st1", STARTERS.get("deck-st1"));
            recorded = OfflineRecorder.play(ALICE, 11, "easy", decks, "deck-st1", starterIds("deck-st1"), NOW.minusSeconds(3_600).toEpochMilli());
        }
        return recorded;
    }

    /** A starter deck's card ids as the site lists them without the catalog: each card's metadata id, ×3 (the Iniciador ×1). */
    private static List<String> starterIds(String deckId) {
        List<String> ids = new ArrayList<>();
        for (JsonNode meta : STARTERS.get(deckId).get("metadata")) {
            int copies = meta.path("starter").asBoolean(false) ? 1 : 3;
            for (int i = 0; i < copies; i++) ids.add(meta.get("id").asString());
        }
        return ids;
    }

    @Test
    void theRecordedGameEndsAndClaimsItsResult() {
        OfflineGame game = st1Game().game();
        assertThat(st1Game().match().status()).isEqualTo("POST_GAME");
        assertThat(game.result().gameNumber()).isEqualTo(1);
        assertThat(game.result().winnerId()).isIn(ALICE, BotRooms.BOT);
        assertThat(game.actions().size()).isGreaterThan(10);
        assertThat(game.actions().stream().mapToInt(OfflineGame.Step::botMoves).sum()).isGreaterThan(10);
    }

    @Test
    void anHonestGameIsAcceptedOnceAndCreditedAsTheServersOwnGameAgainstTheBot() {
        OfflineGame game = st1Game().game();

        OfflineGames.Outcome first = games.submit(ALICE, game);

        assertThat(first.status()).as(first.toString()).isEqualTo(OfflineGames.ACCEPTED);
        assertThat(first.credit().won()).isEqualTo(ALICE.equals(game.result().winnerId()));
        assertThat(first.credit().turns()).isEqualTo(game.result().turns());
        assertThat(first.credit().matchId()).isEqualTo("match-1");
        // The server's path for a game that ends: the activity log (the game's end, flagged offline), the missions and
        // the progression as they count a game against the bot on the server (MissionHooks.count), never ranked
        verify(activity).record(eq(ALICE), eq(ActivityLogService.ONLINE_GAME_END), argThat(d -> Boolean.TRUE.equals(d.get("offline"))
                && game.id().equals(d.get("offlineId")) && d.get("result").equals(ALICE.equals(game.result().winnerId()) ? "won" : "lost")));
        List<MissionEvents.Progress> progress = first.credit().progress();
        assertThat(progress).allMatch(p -> ALICE.equals(p.userId()));
        assertThat(progress).contains(new MissionEvents.Progress(ALICE, MissionRules.GAME_PLAYED, 1));
        assertThat(progress.contains(new MissionEvents.Progress(ALICE, MissionRules.GAME_WON, 1))).isEqualTo(ALICE.equals(game.result().winnerId()));
        assertThat(progress).anyMatch(p -> MissionRules.SUMMON.equals(p.counts()));
        // Each step of progress is recorded for the missions and the progression, as the server's own bot game records it
        Map<MissionEvents.Progress, Long> times = progress.stream().collect(java.util.stream.Collectors.groupingBy(p -> p, java.util.stream.Collectors.counting()));
        times.forEach((p, n) -> {
            verify(missions, times(n.intValue())).record(ALICE, p.counts(), p.amount());
            verify(progression, times(n.intValue())).record(ALICE, p.counts(), p.amount());
        });
        // A game against the bot: the welcome step that asks for a game against a player leaves it out
        verify(progression).record(ALICE, ProgressionRules.BOT_GAME_PLAYED, 1);
        verify(missions, never()).record(anyString(), eq(ProgressionRules.BOT_GAME_PLAYED), anyInt());
        // The bot has no account
        verify(missions, never()).record(eq(BotRooms.BOT), anyString(), anyInt());
        verify(progression, never()).record(eq(BotRooms.BOT), anyString(), anyInt());
        // The same the server counts, with the same rules, for its own room against the bot that ends this way
        assertThat(progress).containsAll(serverBotGameProgress());
        verify(history).saveOffline(argThat((MatchRecorder.Match m) -> !m.setup().ranked() && m.setup().bot()
                && java.util.Objects.equals(m.winnerId(), game.result().winnerId()) && m.turns() == game.result().turns()),
                eq(game.actions().size()), eq(game.id()), eq("test-build"));
        assertThat(store.verdicts).hasSize(1);
        assertThat(store.verdicts.values().iterator().next().matchId()).isEqualTo("match-1");

        // The same game again: counted once
        OfflineGames.Outcome again = games.submit(ALICE, game);
        assertThat(again.status()).isEqualTo(OfflineGames.DUPLICATE);
        // ...and under another id (the same seed and game of the room)
        OfflineGames.Outcome renamed = games.submit(ALICE, withId(game, "00000000-0000-0000-0000-00000000beef"));
        assertThat(renamed.status()).isEqualTo(OfflineGames.DUPLICATE);
        verify(history, times(1)).saveOffline(any(), anyInt(), anyString(), any());
        // One end of the game for each seat, the bot's too, as the server logs its own games against the bot
        verify(activity, times(1)).record(eq(ALICE), eq(ActivityLogService.ONLINE_GAME_END), any());
        verify(activity, times(1)).record(eq(BotRooms.BOT), eq(ActivityLogService.ONLINE_GAME_END), any());
        verify(missions, times(1)).record(ALICE, MissionRules.GAME_PLAYED, 1);
    }

    /**
     * What the server counts for the missions of a room against the bot that ends with the recorded game's result after
     * its turns (MissionEvents, as RoomService counts every action of it): the game played and, for its winner, won.
     */
    private static List<MissionEvents.Progress> serverBotGameProgress() {
        RoomService service = new RoomService();
        GameRoom room = service.createRoom(ALICE, RoomOptions.BOT);
        service.applyAction(room.getRoomId(), ALICE, com.drive.drive_manager.simulator.bot.BotPlayer.action("JOIN_ROOM", Map.of("username", "Alice")), false);
        for (int i = 0; i < 2; i++) {
            com.drive.drive_manager.simulator.engine.GameEvent turn = new com.drive.drive_manager.simulator.engine.GameEvent();
            turn.setSeq(i + 1);
            turn.setType(com.drive.drive_manager.simulator.engine.GameEvent.TURN_START);
            room.getTimeline().add(turn);
        }
        room.setStatus(GameRoom.RoomStatus.POST_GAME);
        room.setWinnerId(st1Game().game().result().winnerId());
        List<MissionEvents.Progress> out = MissionEvents.of(room, GameRoom.RoomStatus.IN_PROGRESS, 2);
        assertThat(out).noneMatch(p -> BotRooms.BOT.equals(p.userId()));
        return out.stream().filter(p -> !ProgressionRules.BOT_GAME_PLAYED.equals(p.counts())).toList();
    }

    @Test
    void aTamperedResultIsRejectedAndCreditsNothing() {
        OfflineGame game = st1Game().game();
        OfflineGame.Claim c = game.result();
        String otherWinner = BotRooms.BOT.equals(c.winnerId()) ? ALICE : BotRooms.BOT;
        OfflineGame forged = withClaim(game, new OfflineGame.Claim(c.gameNumber(), otherWinner, c.endReason(), c.turns(), c.digest()));

        OfflineGames.Outcome outcome = games.submit(ALICE, forged);

        assertThat(outcome.status()).isEqualTo(OfflineGames.REJECTED);
        assertThat(outcome.reason()).isEqualTo("mismatch");
        nothingCredited();
        // A forged digest too
        OfflineGames.Outcome digest = games.submit(ALICE, withId(withClaim(game, new OfflineGame.Claim(c.gameNumber(), c.winnerId(),
                c.endReason(), c.turns(), "0000000000000000")), "00000000-0000-0000-0000-0000000d1e57"));
        assertThat(digest.reason()).isEqualTo("mismatch");
        nothingCredited();
        // The verdict is kept: the same game sent again is not played again
        assertThat(games.submit(ALICE, forged).reason()).isEqualTo("mismatch");
    }

    @Test
    void aTamperedActionListIsRejected() {
        OfflineGame game = st1Game().game();
        // One of the player's moves taken out of the middle of the game: the replay is another game
        List<OfflineGame.Step> steps = new ArrayList<>(game.actions());
        steps.remove(steps.size() / 2);
        OfflineGames.Outcome dropped = games.submit(ALICE, withSteps(game, steps));
        assertThat(dropped.status()).isEqualTo(OfflineGames.REJECTED);
        assertThat(dropped.reason()).isEqualTo("mismatch");

        // The bot made to wait (one move fewer after an action): the bot moves at once after the next action instead
        List<OfflineGame.Step> lazier = new ArrayList<>(game.actions());
        int at = 0;
        while (lazier.get(at).botMoves() == 0 || at < 3) at++;
        lazier.set(at, new OfflineGame.Step(lazier.get(at).body(), lazier.get(at).botMoves() - 1));
        OfflineGames.Outcome reordered = games.submit(ALICE, withId(withSteps(game, lazier), "00000000-0000-0000-0000-00000000a11e"));
        assertThat(reordered.status()).isEqualTo(OfflineGames.REJECTED);
        nothingCredited();
    }

    @Test
    void aStarterDeckWithOtherCardsOrNotOwnedIsRejected() {
        OfflineGame game = st1Game().game();
        // A starter deck with other cards
        List<String> stronger = new ArrayList<>(starterIds("deck-st1"));
        stronger.set(0, starterIds("deck-st2").get(0));
        OfflineGames.Outcome altered = games.submit(ALICE, withId(withCards(game, "deck-st1", stronger), "00000000-0000-0000-0000-0000000a17e2"));
        assertThat(altered.status()).isEqualTo(OfflineGames.REJECTED);
        assertThat(altered.reason()).isEqualTo("deck");
        // The server has no starter decks of its own: online every deck needs its copies in the collection, so here too
        Map<String, Map<String, Integer>> fewer = new HashMap<>(collections.of(ALICE).cards());
        fewer.keySet().removeIf(id -> id.equals("d-" + starterIds("deck-st1").get(0)));
        when(collections.of(ALICE)).thenReturn(new CollectionService.View(fewer, Map.of()));
        OfflineGames.Outcome notOwned = games.submit(ALICE, withId(game, "00000000-0000-0000-0000-0000000a17e3"));
        assertThat(notOwned.reason()).isEqualTo("deck");
        assertThat(notOwned.message()).contains("colección");
        nothingCredited();
    }

    @Test
    void anIllegalDeckIsRejectedBeforePlayingItAgain() {
        OfflineGame game = withDeck(st1Game().game(), "deck-st1", "deck-x");
        List<String> fileIds = starterIds("deck-st1").stream().map(id -> "d-" + id).toList();
        catalogHas(starterCards());
        when(collections.missing(eq(ALICE), anyList())).thenReturn(0);

        // A fifth copy of a card
        List<String> five = new ArrayList<>(fileIds);
        five.add(0, fileIds.get(0));
        five.add(0, fileIds.get(0));
        OfflineGames.Outcome copies = games.submit(ALICE, withId(withCards(game, "deck-x", five), "00000000-0000-0000-0000-00000000c001"));
        assertThat(copies.reason()).isEqualTo("deck");
        assertThat(copies.message()).contains("más de 4 copias");
        // More than the deck builder's 100 cards
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 101; i++) big.add("d-big-" + i);
        OfflineGames.Outcome size = games.submit(ALICE, withId(withCards(game, "deck-x", big), "00000000-0000-0000-0000-00000000c002"));
        assertThat(size.reason()).isEqualTo("deck");
        assertThat(size.message()).contains("100 cartas");
        // No Iniciador to start with
        List<String> iniciadores = starterCards().stream().filter(m -> Boolean.TRUE.equals(m.getStarter())).map(m -> "d-" + m.getId()).toList();
        List<String> noStarter = fileIds.stream().filter(id -> !iniciadores.contains(id)).toList();
        assertThat(noStarter).hasSizeLessThan(fileIds.size());
        OfflineGames.Outcome starterless = games.submit(ALICE, withId(withCards(game, "deck-x", noStarter), "00000000-0000-0000-0000-00000000c003"));
        assertThat(starterless.reason()).isEqualTo("deck");
        assertThat(starterless.message()).contains("Iniciador");
        // A card the server does not know (never the site's metadata)
        List<String> unknown = new ArrayList<>(fileIds);
        unknown.set(unknown.size() - 1, "d-made-up");
        OfflineGames.Outcome made = games.submit(ALICE, withId(withCards(game, "deck-x", unknown), "00000000-0000-0000-0000-00000000c004"));
        assertThat(made.reason()).isEqualTo("deck");
        nothingCredited();
    }

    @Test
    void thePlayersDeckCountsWhenLegalAndOwnedEvenIfEditedOrDeletedSince() {
        // Alice's own deck: ST1's cards under their drive ids, saved on the server
        List<String> fileIds = starterIds("deck-st1").stream().map(id -> "d-" + id).toList();
        DeckList mine = new DeckList("deck-alice", "Mi ST1", fileIds, null, null, ALICE, "Alice", true);
        when(deckLists.findById("deck-alice")).thenReturn(Optional.of(mine));
        catalogHas(starterCards());
        when(collections.missing(eq(ALICE), anyList())).thenReturn(0);
        // The site plays it with the deck as the server makes it (the same metadata)
        OfflineDecks.Made made = new OfflineDecks(deckLists, driveCards, cards, collections, "https://images.invalid", STARTERS)
                .make(ALICE, "deck-alice", fileIds, false);
        assertThat(made.refusal()).isNull();
        assertThat(made.spec().path("deckName").asString()).isEqualTo("Mi ST1");
        ObjectNode decks = OfflineRecorder.JSON.createObjectNode();
        decks.set("deck-alice", made.spec());
        OfflineGame game = OfflineRecorder.play(ALICE, 12, "easy", decks, "deck-alice", fileIds, NOW.minusSeconds(600).toEpochMilli()).game();

        // Without every copy in her collection the server would not let her play it against the bot: nor offline
        when(collections.missing(eq(ALICE), anyList())).thenReturn(3);
        OfflineGames.Outcome missing = games.submit(ALICE, withId(game, "00000000-0000-0000-0000-0000000c011e"));
        assertThat(missing.status()).isEqualTo(OfflineGames.REJECTED);
        assertThat(missing.reason()).isEqualTo("deck");
        assertThat(missing.message()).contains("colección");
        nothingCredited();

        when(collections.missing(eq(ALICE), anyList())).thenReturn(0);
        // Edited since: the game was played with the deck it recorded, legal and hers
        List<String> edited = new ArrayList<>(fileIds);
        java.util.Collections.swap(edited, 0, edited.size() - 1);
        mine.setCards(edited);
        OfflineGames.Outcome changed = games.submit(ALICE, withId(game, "00000000-0000-0000-0000-0000000c4a96"));
        assertThat(changed.status()).as(changed.toString()).isEqualTo(OfflineGames.ACCEPTED);

        // Deleted since (another day's verdicts: the same game is new there)
        when(deckLists.findById("deck-alice")).thenReturn(Optional.empty());
        OfflineGames.Outcome deleted = service(rules(40, 120_000), new Store()).submit(ALICE, game);
        assertThat(deleted.status()).as(deleted.toString()).isEqualTo(OfflineGames.ACCEPTED);
        verify(progression, times(2)).record(ALICE, MissionRules.GAME_PLAYED, 1);
    }

    @Test
    void overTheDaysReplaysTheGameIsKeptForLaterNeverLost() {
        OfflineGame game = st1Game().game();
        for (int i = 0; i < 2; i++) {
            store.insert(new OfflineGameStore.Verdict(ALICE, "earlier-" + i, OfflineGames.ACCEPTED, null, null, "9" + i, 1, ALICE, 10, "d",
                    "b", NOW.minusSeconds(60), NOW.minusSeconds(30), NOW.minusSeconds(10), null));
        }
        OfflineGames guarded = service(rules(2, 120_000));

        OfflineGames.Outcome outcome = guarded.submit(ALICE, game);

        assertThat(outcome.status()).isEqualTo(OfflineGames.RETRY);
        assertThat(outcome.reason()).isEqualTo("daily-replays");
        nothingCredited();
        // Not decided: nothing kept, so it is played again when it comes back
        assertThat(store.find(ALICE, game.id())).isNull();
        // There is no cap on the games accepted: with replays left, the third game of the day counts
        assertThat(service(rules(3, 120_000)).submit(ALICE, game).status()).isEqualTo(OfflineGames.ACCEPTED);
    }

    @Test
    void aGameTooOldOrInTheFutureOrOutOfShapeIsRejected() {
        OfflineGame game = st1Game().game();
        long old = NOW.minusSeconds(31L * 86_400).toEpochMilli();
        assertThat(games.submit(ALICE, withTimes(game, old, old + 1000)).reason()).isEqualTo("window");
        long future = NOW.plusSeconds(3_600).toEpochMilli();
        assertThat(games.submit(ALICE, withTimes(game, future, future + 1000)).reason()).isEqualTo("window");
        assertThat(games.submit(ALICE, withId(game, "x")).reason()).isEqualTo("invalid");
        List<OfflineGame.Step> many = new ArrayList<>();
        for (int i = 0; i < 5001; i++) many.add(new OfflineGame.Step("{}", 0));
        assertThat(games.submit(ALICE, withSteps(game, many)).reason()).isEqualTo("invalid");
        List<OfflineGame.Step> bot = new ArrayList<>(game.actions());
        bot.set(0, new OfflineGame.Step(bot.get(0).body(), 20_001));
        assertThat(games.submit(ALICE, withSteps(game, bot)).reason()).isEqualTo("invalid");
        nothingCredited();
        assertThat(store.verdicts).isEmpty();
    }

    @Test
    void aReplayOverItsBudgetStopsAndIsRejected() {
        OfflineGames hurried = service(rules(20, 1));
        OfflineGames.Outcome outcome = hurried.submit(ALICE, st1Game().game());
        assertThat(outcome.status()).isEqualTo(OfflineGames.REJECTED);
        assertThat(outcome.reason()).isEqualTo("budget");
        nothingCredited();
    }

    @Test
    void theChosenDecksAreReadFromTheActions() {
        OfflineGame game = new OfflineGame("00000000-0000-0000-0000-000000000001", null, "1", null, 0L, 0L, Map.of(), List.of(
                new OfflineGame.Step(OfflineRecorder.body("SET_DECK", Map.of("deckId", "a")), 0),
                new OfflineGame.Step(OfflineRecorder.body("SET_DECK", Map.of("deckId", "b", "actAs", "bot")), 0),
                new OfflineGame.Step("not json", 0),
                new OfflineGame.Step(OfflineRecorder.body("SET_DECK", Map.of("deckId", 7)), 0)), null);
        assertThat(OfflineGames.chosenDecks(game)).containsExactly(Map.entry("a", false), Map.entry("b", true));
    }

    private void nothingCredited() {
        verify(activity, never()).record(anyString(), anyString(), any());
        verify(missions, never()).record(anyString(), anyString(), anyInt());
        verify(progression, never()).record(anyString(), anyString(), anyInt());
        verify(history, never()).saveOffline(any(), anyInt(), anyString(), any());
        assertThat(store.verdicts.values()).noneMatch(v -> OfflineGames.ACCEPTED.equals(v.status()) && !v.gameId().startsWith("earlier-"));
    }

    /** The starter decks' cards (ST1–ST4), as the server's card metadata. */
    private static List<Card> starterCards() {
        List<Card> out = new ArrayList<>();
        for (String deck : List.of("st1", "st2", "st3", "st4")) {
            try (InputStream in = OfflineGamesTest.class.getResourceAsStream("/simulator/" + deck + "-cards.json")) {
                out.addAll(OfflineRecorder.JSON.readValue(in, new TypeReference<List<Card>>() {}));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return out;
    }

    /** Each card's drive card, "d-<metadata id>", of its name and edition. */
    private static List<DriveCard> drives(List<Card> metas) {
        return metas.stream().map(m -> {
            DriveCard d = new DriveCard();
            d.setId("d-" + m.getId());
            d.setName(m.getCardName());
            d.setEdition(m.getEdition());
            return d;
        }).toList();
    }

    /** The server's catalog: the drive cards asked for that it has, and the metadata of their editions. */
    private void catalogHas(List<Card> metas) {
        List<DriveCard> all = drives(metas);
        when(driveCards.findAllById(anyList())).thenAnswer(inv -> {
            List<?> ids = inv.getArgument(0);
            return all.stream().filter(d -> ids.contains(d.getId())).toList();
        });
        when(cards.findByEditionIn(anyList())).thenAnswer(inv -> {
            List<?> editions = inv.getArgument(0);
            return metas.stream().filter(m -> editions.contains(m.getEdition())).toList();
        });
    }

    private static OfflineGame withId(OfflineGame g, String id) {
        return new OfflineGame(id, g.engineBuild(), g.seed(), g.botLevel(), g.startedAt(), g.endedAt(), g.decks(), g.actions(), g.result());
    }

    private static OfflineGame withClaim(OfflineGame g, OfflineGame.Claim claim) {
        return new OfflineGame(g.id(), g.engineBuild(), g.seed(), g.botLevel(), g.startedAt(), g.endedAt(), g.decks(), g.actions(), claim);
    }

    private static OfflineGame withSteps(OfflineGame g, List<OfflineGame.Step> steps) {
        return new OfflineGame(g.id(), g.engineBuild(), g.seed(), g.botLevel(), g.startedAt(), g.endedAt(), g.decks(), steps, g.result());
    }

    private static OfflineGame withTimes(OfflineGame g, long started, long ended) {
        return new OfflineGame(g.id(), g.engineBuild(), g.seed(), g.botLevel(), started, ended, g.decks(), g.actions(), g.result());
    }

    private static OfflineGame withCards(OfflineGame g, String deckId, List<String> cards) {
        Map<String, List<String>> decks = new HashMap<>(g.decks());
        decks.put(deckId, cards);
        return new OfflineGame(g.id(), g.engineBuild(), g.seed(), g.botLevel(), g.startedAt(), g.endedAt(), decks, g.actions(), g.result());
    }

    /** The same game, its deck chosen under another id. */
    private static OfflineGame withDeck(OfflineGame g, String from, String to) {
        List<OfflineGame.Step> steps = g.actions().stream().map(s -> new OfflineGame.Step(s.body().replace("\"" + from + "\"", "\"" + to + "\""), s.botMoves())).toList();
        Map<String, List<String>> decks = new HashMap<>();
        decks.put(to, g.decks().get(from));
        return new OfflineGame(g.id(), g.engineBuild(), g.seed(), g.botLevel(), g.startedAt(), g.endedAt(), decks, steps, g.result());
    }

    /** The verdicts in memory, as Mongo keeps them (one per player and id; one accepted per player, seed and game). */
    static final class Store implements OfflineGameStore {
        final Map<String, Verdict> verdicts = new java.util.LinkedHashMap<>();

        @Override
        public Verdict find(String userId, String gameId) {
            return verdicts.get(userId + ":" + gameId);
        }

        @Override
        public boolean acceptedGame(String userId, String seed, int gameNumber) {
            return verdicts.values().stream().anyMatch(v -> v.userId().equals(userId) && v.seed().equals(seed)
                    && v.gameNumber() == gameNumber && OfflineGames.ACCEPTED.equals(v.status()));
        }

        @Override
        public long replayed(String userId, Instant since) {
            return verdicts.values().stream().filter(v -> v.userId().equals(userId) && !v.at().isBefore(since)).count();
        }

        @Override
        public boolean insert(Verdict v) {
            String key = v.userId() + ":" + v.gameId();
            if (verdicts.containsKey(key)) return false;
            if (OfflineGames.ACCEPTED.equals(v.status()) && acceptedGame(v.userId(), v.seed(), v.gameNumber())) return false;
            verdicts.put(key, v);
            return true;
        }

        @Override
        public void matchKept(String userId, String gameId, String matchId) {
            Verdict v = verdicts.get(userId + ":" + gameId);
            verdicts.put(userId + ":" + gameId, new Verdict(v.userId(), v.gameId(), v.status(), v.reason(), v.message(), v.seed(),
                    v.gameNumber(), v.winnerId(), v.turns(), v.digest(), v.engineBuild(), v.startedAt(), v.endedAt(), v.at(), matchId));
        }
    }
}
