package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.mission.MissionEvents;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.RoomOptions;
import com.drive.drive_manager.simulator.room.RoomService;
import com.drive.drive_manager.simulator.room.StateMask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.bot.BotMatch.DECKS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bots playing whole games with the starter decks (see {@link BotMatch}): every game must end, by lives or by deck,
 * without the bot getting stuck on a choice; the stronger bots beat the weaker ones. Also the room against the
 * bot: its owner plays, the bot answers by itself. Long runs that keep their results are the arena's ({@link BotArenaRun}).
 */
class BotPlayTest {

    /** Games of each pair of decks (-Dbot.games to play more). */
    private static final int GAMES = Integer.getInteger("bot.games", 2);

    private final RoomService service = new RoomService();

    @Test
    void heuristicBotsFinishEveryGameBetweenTheStarterDecks() {
        int finished = 0;
        int conceded = 0;
        for (String a : DECKS) {
            for (String b : DECKS) {
                for (int game = 0; game < GAMES; game++) {
                    BotMatch.Result r = BotMatch.play(a, b, new HeuristicBot(), new HeuristicBot());
                    assertThat(r.stuckAt()).as(a + " vs " + b).isNull();
                    if (r.reason() != null) finished++;
                    if (r.conceded() != null) conceded++;
                }
            }
        }
        assertThat(finished).as("games that ended").isEqualTo(DECKS.size() * DECKS.size() * GAMES);
        assertThat(conceded).as("games a bot conceded because it found no valid move").isZero();
    }

    @Test
    void passiveBotsFinishTheirGamesToo() {
        BotMatch.Result r = BotMatch.play("st1", "st2", new BotPlayer(), new BotPlayer());
        assertThat(r.reason()).isNotNull();
        assertThat(r.conceded()).isNull();
    }

    @Test
    void theHeuristicBotBeatsThePassiveOne() {
        for (String deck : DECKS) {
            assertThat(BotMatch.play(deck, deck, new HeuristicBot(), new BotPlayer()).winner()).as(deck).isEqualTo("host");
        }
    }

    @Test
    void theLookaheadBotBeatsTheHeuristicOne() {
        int lookahead = 0;
        int heuristic = 0;
        for (String a : DECKS) {
            for (String b : DECKS) {
                // Each bot plays each deck from each seat
                BotMatch.Result first = BotMatch.play(a, b, new LookaheadBot(), new HeuristicBot());
                BotMatch.Result second = BotMatch.play(a, b, new HeuristicBot(), new LookaheadBot());
                assertThat(first.conceded()).isNull();
                assertThat(second.conceded()).isNull();
                if ("host".equals(first.winner())) lookahead++; else if (first.winner() != null) heuristic++;
                if ("guest".equals(second.winner())) lookahead++; else if (second.winner() != null) heuristic++;
            }
        }
        assertThat(lookahead).isGreaterThan(heuristic);
    }

    @Test
    void aCopyOfTheRoomPlaysApartFromTheRoom() {
        GameRoom room = service.createRoom("host");
        String id = room.getRoomId();
        service.applyAction(id, "host", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        service.applyAction(id, "guest", BotPlayer.action("JOIN_ROOM", Map.of("username", "Beto")), false);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("host"), "st1", ids);
        BotMatch.seat(room.getPlayer("guest"), "st2", ids);
        Map<String, BotPlayer> bots = Map.of("host", new HeuristicBot(), "guest", new HeuristicBot());
        for (int i = 0; i < 40 && room.getStatus() != GameRoom.RoomStatus.POST_GAME; i++) {
            for (String seat : List.of("host", "guest")) {
                GameAction action = bots.get(seat).next(room, seat);
                if (action == null) continue;
                bots.get(seat).played(room, action);
                service.applyAction(id, seat, action, false);
                break;
            }
        }
        long seq = room.getEventSeq();
        int hand = room.getPlayer("host").getHand().size();
        Sandbox sandbox = new Sandbox();
        GameRoom copy = sandbox.fork(room, "host");
        assertThat(copy.getPlayer("host")).isNotSameAs(room.getPlayer("host"));
        assertThat(copy.getPlayer("guest").getHand()).hasSameSizeAs(room.getPlayer("guest").getHand());
        // One bot per seat, told what the engine refuses (a fresh one would try a refused move again and again)
        Map<String, BotPlayer> onCopy = Map.of("host", new HeuristicBot(), "guest", new HeuristicBot());
        for (int i = 0; i < 30 && copy.getStatus() == GameRoom.RoomStatus.IN_PROGRESS; i++) {
            String seat = copy.getPrompt() != null ? copy.getPrompt().getPlayerId() : copy.getCurrentTurnUserId();
            GameAction action = onCopy.get(seat).next(copy, seat);
            if (action == null) break;
            GameStateUpdate update = sandbox.act(copy, seat, action);
            if (update != null && update.getError() != null) onCopy.get(seat).refused(action, update.getError());
            else onCopy.get(seat).played(copy, action);
        }
        sandbox.drop(copy);
        assertThat(copy.getEventSeq()).isGreaterThan(seq);
        assertThat(room.getEventSeq()).isEqualTo(seq);
        assertThat(room.getPlayer("host").getHand()).hasSize(hand);
    }

    @Test
    void aRoomAgainstTheBotIsPlayedByItsOwnerWhileTheBotAnswersByItself() {
        GameRoom room = service.createRoom("owner", RoomOptions.BOT);
        String id = room.getRoomId();
        service.applyAction(id, "owner", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        assertThat(room.getPlayers()).containsKeys("owner", BotRooms.BOT);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st1", ids);
        BotMatch.seat(room.getPlayer(BotRooms.BOT), "st3", ids);
        assertThat(service.openRooms()).doesNotContain(room);

        // The bot is ready as soon as it has a deck; the owner starts
        assertThat(service.playBotNow(id)).isEqualTo(1);
        assertThat(room.getPlayer(BotRooms.BOT).isReady()).isTrue();
        service.applyAction(id, "owner", BotPlayer.action("SET_READY", Map.of("ready", true)), false);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.MULLIGAN);

        // The owner plays as the passive bot would; the room's bot moves after each of the owner's actions
        BotPlayer owner = new BotPlayer();
        for (int i = 0; i < BotMatch.MAX_ACTIONS && room.getStatus() != GameRoom.RoomStatus.POST_GAME; i++) {
            service.playBotNow(id);
            if (room.getStatus() == GameRoom.RoomStatus.POST_GAME) break;
            GameAction action = owner.next(room, "owner");
            if (action == null) continue;
            GameStateUpdate update = service.applyAction(id, "owner", action, false);
            if (update != null && update.getError() != null) owner.refused(action, update.getError());
            else owner.played(room, action);
        }
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);
        assertThat(room.getEndReason()).isNotEqualTo("surrender");
    }

    @Test
    void inModoSoloTheBotCanPlayTheRivalsSideWhileTheOwnerSeesEverything() {
        GameRoom room = service.createRoom("owner", RoomOptions.TEST);
        String id = room.getRoomId();
        service.applyAction(id, "owner", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st1", ids);
        BotMatch.seat(room.getPlayer(RoomService.EDITOR_RIVAL), "st3", ids);
        // Off by default: nobody plays the rival but the owner
        assertThat(service.playBotNow(id)).isZero();

        GameStateUpdate on = service.applyAction(id, "owner", BotPlayer.action("SET_EDITOR_BOT", Map.of("on", true)), false);
        assertThat(on.getError()).isNull();
        assertThat(on.isEditorBot()).isTrue();
        assertThat(on.getBotChoices()).isNotEmpty();
        assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_BOT_LEVEL", Map.of("level", BotCatalog.EASY)), false).getError()).isNull();

        // The owner confirms for both sides (the bot waits) and plays its side by hand (here, as the passive bot would)
        assertThat(service.playBotNow(id)).isZero();
        service.applyAction(id, "owner", BotPlayer.action("SET_READY", Map.of("ready", true)), false);
        BotPlayer owner = new BotPlayer();
        int rivalMoves = 0;
        boolean switched = false;
        for (int i = 0; i < BotMatch.MAX_ACTIONS && room.getStatus() != GameRoom.RoomStatus.POST_GAME; i++) {
            if (!switched && RoomService.EDITOR_RIVAL.equals(room.getCurrentTurnUserId()) && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS) {
                // Mid-game the owner switches the bot off (nobody moves for the rival) and on again (it moves at once)
                switched = true;
                assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_EDITOR_BOT", Map.of("on", false)), false).getError()).isNull();
                assertThat(service.playBotNow(id)).isZero();
                // Sent while the board plays the rival's side ("actAs"): still the owner's choice
                assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_EDITOR_BOT", Map.of("on", true, "actAs", RoomService.EDITOR_RIVAL)), false).getError()).isNull();
                assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_BOT_LEVEL", Map.of("level", BotCatalog.EASY)), false).getError()).isNull();
            }
            rivalMoves += service.playBotNow(id);
            if (room.getStatus() == GameRoom.RoomStatus.POST_GAME) break;
            GameAction action = owner.next(room, "owner");
            if (action == null) continue;
            GameStateUpdate update = service.applyAction(id, "owner", action, false);
            if (update != null && update.getError() != null) owner.refused(action, update.getError());
            else owner.played(room, action);
        }
        assertThat(switched).isTrue();
        assertThat(rivalMoves).as("the bot moved for the rival").isGreaterThan(10);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);
        // A test room shows the owner everything: the rival's hand is not hidden
        assertThat(StateMask.forViewer(GameStateUpdate.from(room, "TEST", "owner"), "owner", false).getPlayers().get(RoomService.EDITOR_RIVAL).getHand())
                .allMatch(c -> c.getCardName() != null);
    }

    @Test
    void inModoSoloTwoBotsCanPlayTheWholeGameWhileTheOwnerWatchesAtTheirPace() {
        GameRoom room = service.createRoom("owner", RoomOptions.TEST);
        String id = room.getRoomId();
        service.applyAction(id, "owner", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        int[] ids = {0};
        BotMatch.seat(room.getPlayer("owner"), "st1", ids);
        BotMatch.seat(room.getPlayer(RoomService.EDITOR_RIVAL), "st3", ids);
        assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_EDITOR_BOT", Map.of("on", true)), false).getError()).isNull();
        assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_BOT_LEVEL", Map.of("level", BotCatalog.EASY)), false).getError()).isNull();
        assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_BOT_LEVEL", Map.of("level", BotCatalog.EASY, "side", "self")), false).getError()).isNull();
        // Only a simulator administrator has a bot play their own side
        assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_EDITOR_SELF_BOT", Map.of("on", true)), false).getError()).isNotNull();
        room.getAdminPlayers().add("owner");
        GameStateUpdate self = service.applyAction(id, "owner", BotPlayer.action("SET_EDITOR_SELF_BOT", Map.of("on", true)), false);
        assertThat(self.getError()).isNull();
        assertThat(self.isEditorSelfBot()).isTrue();
        assertThat(self.getSelfBotLevel()).isEqualTo(BotCatalog.EASY);
        GameStateUpdate pace = service.applyAction(id, "owner", BotPlayer.action("SET_BOT_DELAY", Map.of("ms", 1500)), false);
        assertThat(pace.getBotDelayMs()).isEqualTo(1500);
        assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_BOT_DELAY", Map.of("ms", -1)), false).getBotDelayMs()).isNull();

        // The owner only starts; both sides are played by the bots to the end
        service.applyAction(id, "owner", BotPlayer.action("SET_READY", Map.of("ready", true)), false);
        for (int i = 0; i < 50 && room.getStatus() != GameRoom.RoomStatus.POST_GAME; i++) service.playBotNow(id);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);
        assertThat(room.getTimeline().stream().anyMatch(e -> "owner".equals(e.getPlayerId()) && "NORMAL_SUMMON".equals(e.getType()))).isTrue();
    }

    @Test
    void theOwnerChoosesTheBotsDeckAndNobodyElseCanEnter() {
        GameRoom room = service.createRoom("owner", RoomOptions.BOT);
        String id = room.getRoomId();
        service.applyAction(id, "owner", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        assertThat(service.applyAction(id, "other", BotPlayer.action("JOIN_ROOM", Map.of("username", "Beto")), false).getError()).isNotNull();
        assertThat(service.applyAction(id, "owner", BotPlayer.action("KICK_PLAYER", Map.of("userId", BotRooms.BOT)), false).getError())
                .contains("bot");
        // A game against the bot counts no missions
        assertThat(MissionEvents.of(room, GameRoom.RoomStatus.WAITING, -1)).isEmpty();
    }

    @Test
    void theOwnerChoosesHowWellTheBotPlaysBeforeTheGame() {
        GameRoom room = service.createRoom("owner", RoomOptions.BOT);
        String id = room.getRoomId();
        service.applyAction(id, "owner", BotPlayer.action("JOIN_ROOM", Map.of("username", "Ana")), false);
        assertThat(room.getBotLevel()).isEqualTo(BotCatalog.NORMAL);

        GameStateUpdate hard = service.applyAction(id, "owner", BotPlayer.action("SET_BOT_LEVEL", Map.of("level", BotCatalog.HARD)), false);
        assertThat(hard.getError()).isNull();
        assertThat(hard.getBotLevel()).isEqualTo(BotCatalog.HARD);
        assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_BOT_LEVEL", Map.of("level", "imposible")), false).getError())
                .contains("nivel");
        assertThat(room.getBotLevel()).isEqualTo(BotCatalog.HARD);

        // Each level is the best bot of a stage of the tuning (2026-11-06): the first tuned one (two turns, no sequences),
        // the champion of the tests (sequences of own moves), the champion with the user's corrections (exchanges by search)
        assertThat(((LookaheadBot) BotCatalog.botAt(BotCatalog.EASY)).settings().turns()).isEqualTo(2);
        assertThat(((LookaheadBot) BotCatalog.botAt(BotCatalog.EASY)).settings().get("seqDepth")).isZero();
        assertThat(((LookaheadBot) BotCatalog.botAt(BotCatalog.NORMAL)).settings().get("seqDepth")).isEqualTo(3);
        assertThat(((LookaheadBot) BotCatalog.botAt(BotCatalog.NORMAL)).settings().get("hCombat")).isZero();
        assertThat(((LookaheadBot) BotCatalog.botAt(BotCatalog.HARD)).settings().get("hCombat")).isEqualTo(1);

        // The models to try (bot/models.txt) come after the levels, marked apart, and are chosen the same way
        assertThat(hard.getBotChoices()).extracting(c -> c.get("id")).startsWith(BotCatalog.EASY, BotCatalog.NORMAL, BotCatalog.HARD).contains("previo");
        assertThat(hard.getBotChoices()).filteredOn(c -> "level".equals(c.get("kind"))).hasSize(3);
        assertThat(service.applyAction(id, "owner", BotPlayer.action("SET_BOT_LEVEL", Map.of("level", "previo")), false).getError()).isNull();
        assertThat(((LookaheadBot) BotCatalog.botAt("previo")).settings().get("rankCands")).isEqualTo(0);
    }

    @Test
    void theSameSeedPlaysTheSameGame() {
        // Without a time limit the look-ahead bot decides the same way every time: the game depends on the seed only
        BotSettings untimed = BotSettings.DEFAULT.with("budgetMs=1000000");
        BotMatch.Result a = BotMatch.playSeeded("st3", "st2", new LookaheadBot(untimed), new HeuristicBot(), 42);
        BotMatch.Result b = BotMatch.playSeeded("st3", "st2", new LookaheadBot(untimed), new HeuristicBot(), 42);
        BotMatch.Result c = BotMatch.playSeeded("st3", "st2", new LookaheadBot(untimed), new HeuristicBot(), 43);
        assertThat(b.winner()).isEqualTo(a.winner());
        assertThat(b.turns()).isEqualTo(a.turns());
        assertThat(b.actions()).isEqualTo(a.actions());
        assertThat(b.end()).isEqualTo(a.end());
        assertThat(c.actions() != a.actions() || !c.end().equals(a.end())).as("another seed, another game").isTrue();
    }

    @Test
    void aBotThatTriesItsMovesBySuccessiveHalvingOnTheSameDealsFinishesAndRepeatsItsGames() {
        BotSettings halving = BotSettings.DEFAULT.with("budgetMs=1000000,samples=4,sameDeals=1,halving=1");
        BotMatch.Result a = BotMatch.playSeeded("st1", "st4", new LookaheadBot(halving), new HeuristicBot(), 7);
        BotMatch.Result b = BotMatch.playSeeded("st1", "st4", new LookaheadBot(halving), new HeuristicBot(), 7);
        assertThat(a.winner()).as("the game ends with a winner").isNotNull();
        assertThat(b.actions()).isEqualTo(a.actions());
        assertThat(b.end()).isEqualTo(a.end());
    }

    @Test
    void botsAreDescribedBySettings() {
        assertThat(BotMatch.bot("heuristic")).isExactlyInstanceOf(HeuristicBot.class);
        LookaheadBot bot = (LookaheadBot) BotMatch.bot("lookahead:turns=2, life=12");
        assertThat(bot.settings().turns()).isEqualTo(2);
        assertThat(bot.settings().life()).isEqualTo(12);
        assertThat(bot.settings().samples()).isEqualTo(BotSettings.DEFAULT.samples());
        // A card's own bias: by name in any case, 0 when not set, and it adds to the card's worth
        BotSettings biased = BotSettings.DEFAULT.with("bias:Fhalid=2.5");
        assertThat(biased.bias("fhalid")).isEqualTo(2.5);
        assertThat(biased.get("bias:FHALID")).isEqualTo(2.5);
        assertThat(biased.bias("Damon")).isZero();
        CardSlot fhalid = new CardSlot();
        fhalid.setCardName("Fhalid");
        fhalid.setCost(3);
        // Each zone has its own: the field's counts on the field, the Tributos' in the Tributos
        BotSettings zones = BotSettings.DEFAULT.with("bias:Fhalid=2.5,biasT:fhalid=4");
        assertThat(zones.get("biasT:Fhalid")).isEqualTo(4);
        assertThat(zones.get("biasD:fhalid")).isZero();
        PlayerState p = new PlayerState("p", "P");
        p.getTributeZone().add(fhalid);
        assertThat(TableScore.side(p, zones) - TableScore.side(p, BotSettings.DEFAULT)).isEqualTo(4);
        p.getTributeZone().clear();
        p.getField().add(fhalid);
        assertThat(TableScore.side(p, zones) - TableScore.side(p, BotSettings.DEFAULT)).isEqualTo(2.5);
    }

    @Test
    void cardsGoFromTheHandToTheTributosCreaturesFirstAndUtilitiesLast() {
        // "Tributa 1 carta de tu mano" with a utility and a structure (cheap) and a creature in the hand
        GameRoom room = new GameRoom();
        PlayerState me = new PlayerState("host", "Ana");
        room.getPlayers().put("host", me);
        room.getPlayers().put("guest", new PlayerState("guest", "Beto"));
        CardSlot utility = card("u", "Caza Nocturna", "utility", 1, null);
        CardSlot structure = card("s", "Cueva De Bestias", "structure", 1, null);
        CardSlot creature = card("c", "Virdris", "creature", 2, 6);
        me.getHand().addAll(List.of(utility, structure, creature));
        com.drive.drive_manager.simulator.engine.Prompt prompt = new com.drive.drive_manager.simulator.engine.Prompt();
        prompt.setId("p1");
        prompt.setEffectId("e1");
        prompt.setChosenLabel("Tributar");
        prompt.setMin(1);
        prompt.setMax(1);
        prompt.setCards(List.of(utility, structure, creature));
        prompt.setValidIds(List.of("u", "s", "c"));

        // By default the least valuable card goes: a utility. With the rule on, a creature: in the Tributos it still
        // counts (effects take it back or count it); a utility or structure does nothing there
        assertThat(new HeuristicBot().answer(room, "host", prompt)).containsExactly("u");
        BotSettings rule = BotSettings.DEFAULT.with("hTributeCreatures=1");
        assertThat(new HeuristicBot(rule).answer(room, "host", prompt)).containsExactly("c");
        assertThat(new LookaheadBot(rule).answer(room, "host", prompt)).containsExactly("c");
    }

    @Test
    void aBotThatSearchesSequencesOfItsOwnMovesFinishesAndRepeatsItsGames() {
        BotSettings seq = BotSettings.DEFAULT.with("budgetMs=1000000,turns=2,samples=2,sameDeals=1,halving=1,seqDepth=2,seqKeep=3,seqTriggers=1");
        BotMatch.Result a = BotMatch.playSeeded("st2", "st3", new LookaheadBot(seq), new HeuristicBot(), 11);
        BotMatch.Result b = BotMatch.playSeeded("st2", "st3", new LookaheadBot(seq), new HeuristicBot(), 11);
        assertThat(a.winner()).as("the game ends with a winner").isNotNull();
        assertThat(b.actions()).isEqualTo(a.actions());
        assertThat(b.end()).isEqualTo(a.end());
        // A sequence is one candidate: the bot plays its first move
        GameAction m1 = BotPlayer.action("PLAY_CARD", Map.of("instanceId", "x")), m2 = BotPlayer.action("ACTIVATE_EFFECT", Map.of("instanceId", "x"));
        assertThat(SequenceSearch.moves(SequenceSearch.of(List.of(m1, m2)))).containsExactly(m1, m2);
        assertThat(SequenceSearch.first(SequenceSearch.of(List.of(m1, m2)))).isSameAs(m1);
        assertThat(SequenceSearch.first(m2)).isSameAs(m2);
    }

    @Test
    void aBotThatThinksItsOwnTurnInTheCopiesAndScreensSequencesByTriesFinishesItsGames() {
        BotSettings deep = BotSettings.DEFAULT.with("turns=2,samples=2,sameDeals=1,halving=1,seqDepth=2,seqKeep=2,seqRollout=1,innerCands=3,innerMs=10,hUseful=1");
        BotMatch.Result r = BotMatch.playSeeded("st1", "st3", new LookaheadBot(deep), new HeuristicBot(), 5);
        assertThat(r.winner()).as("the game ends with a winner").isNotNull();
    }

    @Test
    void utilitiesAreWorthHoldingOnceAndStructuresHoldingAndPlaying() {
        PlayerState p = new PlayerState("p", "P");
        p.getHand().addAll(List.of(card("u1", "Caza Nocturna", "utility", 1, null), card("u2", "caza nocturna", "utility", 1, null),
                card("u3", "Alimento De Las Sombras", "utility", 1, null), card("s1", "Cueva De Bestias", "structure", 1, null)));
        p.getField().add(card("s2", "Cueva De Bestias", "structure", 1, null));
        BotSettings off = BotSettings.DEFAULT;
        BotSettings on = off.with("uHand=2,uCopies=3,sHand=1,sField=4");
        // Two utilities held once (+2 each), a second copy of one (2 - 3), a structure in the hand (+1), one on the field (+4)
        assertThat(TableScore.side(p, on) - TableScore.side(p, off)).isEqualTo(2 + 2 + (2 - 3) + 1 + 4);
        // At 0 the knobs change nothing
        assertThat(TableScore.side(p, off.with("uHand=0,uCopies=0,uTribute=0,sHand=0,sField=0"))).isEqualTo(TableScore.side(p, off));
        // A utility in the Tributos is a loss, unless a copy of it is still held: Caza Nocturna (still in the hand) is not
        p.getTributeZone().addAll(List.of(card("t1", "Caza Nocturna", "utility", 1, null), card("t2", "Paso Interdimensional", "utility", 1, null)));
        assertThat(TableScore.side(p, off.with("uTribute=3")) - TableScore.side(p, off)).isEqualTo(-3);
        // Damped: times uDamp per copy still to play. Caza Nocturna has two in the hand (4 * 0.25), Paso none (4), then one in the deck (4 * 0.5)
        assertThat(TableScore.side(p, off.with("uTribute=4,uDamp=0.5")) - TableScore.side(p, off)).isEqualTo(-5);
        p.getDeck().add(card("d1", "Paso Interdimensional", "utility", 1, null));
        assertThat(TableScore.side(p, off.with("uTribute=4,uDamp=0.5")) - TableScore.side(p, off)).isEqualTo(-3);
    }

    @Test
    void handCopiesAndOpenLanesAreScoredWhenTheirKnobsAreOn() {
        BotSettings off = BotSettings.DEFAULT;
        PlayerState p = new PlayerState("p", "P");
        p.getHand().addAll(List.of(card("a1", "Peón", "creature", 1, 2), card("a2", "peón", "creature", 1, 2), card("a3", "Peón", "creature", 1, 2)));
        // Two copies beyond the first
        assertThat(TableScore.side(p, off.with("hCopies=1")) - TableScore.side(p, off)).isEqualTo(-2);
        PlayerState me = new PlayerState("me", "M"), rival = new PlayerState("r", "R");
        me.getField().addAll(List.of(card("m1", "Fuerte", "creature", 1, 5), card("m2", "Débil", "creature", 1, 2)));
        CardSlot wall = card("w", "Muro", "creature", 1, 3);
        wall.setY(10.0);
        rival.getField().add(wall);
        // The wall (3, active, in the front line) stops the 2 but not the 5
        assertThat(TableScore.lanes(me, rival)).isEqualTo(1);
        wall.setTapped(true);
        assertThat(TableScore.lanes(me, rival)).isEqualTo(2);
    }

    @Test
    void aCardInTheHandWeighsLessTheLongerItTakesToPlayAndABigHandAddsLess() {
        BotSettings off = BotSettings.DEFAULT;
        PlayerState p = new PlayerState("p", "P");
        p.setResourceCount(1);
        p.getHand().addAll(List.of(card("big", "Rey Franco", "creature", 7, 13), card("u", "Caza Nocturna", "utility", 1, null)));
        assertThat(TableScore.hand(p, off)).isEqualTo(2);
        // The 7 waits 7 turns (more than 5: reductions), the utility none
        assertThat(TableScore.hand(p, off.with("hWait=0.5"))).isCloseTo(1 + Math.pow(0.5, 7), org.assertj.core.data.Offset.offset(1e-9));
        // Decay: the most playable first, then times 0.5
        assertThat(TableScore.hand(p, off.with("hWait=0.5,handDecay=0.5"))).isCloseTo(1 + 0.5 * Math.pow(0.5, 7), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void theHandsRankingGivesUpCopiesFirstThenTheCrowdedType() {
        PlayerState p = new PlayerState("p", "P");
        p.getHand().addAll(List.of(card("a", "Ballesta Montada", "creature", 3, 8), card("b", "Ballesta Montada", "creature", 3, 8),
                card("f", "Caballero Férreo", "creature", 2, 4), card("i", "Investidura", "utility", 1, null)));
        BotSettings on = BotSettings.DEFAULT.with("hRank=1");
        // Without the room every card counts playable: the second Ballesta 0.4 (copy) x 0.8^2 (third creature),
        // Férreo 0.8 (second creature), the first Ballesta and Investidura 1
        assertThat(HandRank.weakestFirst(null, p, List.of("a", "b", "f", "i"), on).subList(0, 2)).containsExactly("b", "f");
        assertThat(TableScore.hand(p, on)).isCloseTo(1 + 0.8 + 0.4 * 0.64 + 1, org.assertj.core.data.Offset.offset(1e-9));
        // Off, the hand counts as before
        assertThat(TableScore.hand(p, BotSettings.DEFAULT)).isEqualTo(4);
    }

    @Test
    void theLastCardsOfTheHandAreScarce() {
        PlayerState p = new PlayerState("p", "P");
        p.getHand().add(card("a", "Buratán", "creature", 1, 4));
        BotSettings off = BotSettings.DEFAULT;
        // One card, two short of the floor of 3: minus 2 x handLow
        assertThat(TableScore.side(p, off.with("handLow=2.5")) - TableScore.side(p, off)).isEqualTo(-5);
        p.getHand().addAll(List.of(card("b", "Krokler", "creature", 2, 5), card("c", "Zhaglizer", "creature", 3, 8)));
        assertThat(TableScore.side(p, off.with("handLow=2.5"))).isEqualTo(TableScore.side(p, off));
    }

    private static CardSlot card(String id, String name, String type, int cost, Integer strength) {
        CardSlot c = new CardSlot();
        c.setInstanceId(id);
        c.setCardName(name);
        c.setCardType(type);
        c.setCost(cost);
        c.setStrength(strength);
        c.setLevel(strength == null ? null : 2);
        return c;
    }
}
