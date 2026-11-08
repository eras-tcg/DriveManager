package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.actions.PreRoomActions;
import com.drive.drive_manager.simulator.rank.RankRules;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.DeckLoader;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.RoomCopy;
import com.drive.drive_manager.simulator.room.RoomEngine;
import com.drive.drive_manager.simulator.room.RoomService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The rooms played against the bot: the bot's seat, its deck, and when it moves. After each action in the room the
 * bot is woken; it waits a moment (so the player sees each of its moves on the board) and sends its next move
 * through the room service, as a player would, until it has nothing to do.
 */
public final class BotRooms {

    /** The bot's seat in a room against it. */
    public static final String BOT = BotSeat.BOT;
    /** Moves the bot makes in a row at most when played right away (tests). */
    private static final int MAX_MOVES_NOW = 5000;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BotRooms.class);

    private final RoomService service;
    /** The bot of each room played against it, by room id. */
    private final Map<String, BotPlayer> bots = new ConcurrentHashMap<>();
    /** Sala de pruebas: the bot that plays the owner's own side, by room id (the owner watches both bots play). */
    private final Map<String, BotPlayer> selfBots = new ConcurrentHashMap<>();
    private ScheduledExecutorService clock;
    /** Moves the engine refused in a row, by bot (it concedes after BotPlayer.MAX_ATTEMPTS). */
    private final Map<BotPlayer, Integer> misses = new ConcurrentHashMap<>();
    /** The chance of the copies the bots think on (never the room's own). */
    private final java.util.Random chance = new java.util.Random();

    public BotRooms(RoomService service) {
        this.service = service;
    }

    /** Seats the bot in a new room against it. */
    public void seat(GameRoom room) {
        room.setBot(true);
        PlayerState seat = new PlayerState(BOT, BotCatalog.nameOf(room.getBotLevel()));
        seat.setConnected(true);
        room.getPlayers().put(BOT, seat);
        bots.put(room.getRoomId(), BotCatalog.botAt(room.getBotLevel()));
        if (room.isRanked()) rankedBot(room);
    }

    /** The owner's strength in the ranked ladder (a new player's when there is no ladder: tests). */
    private int ownerStrength(GameRoom room) {
        return service.ranks == null || room.getHostId() == null ? RankRules.START_MMR : service.ranks.of(room.getHostId()).mmr();
    }

    /** The owner chooses the bot's level before the game (in the Sala de pruebas, at any moment) ("level": easy, normal or hard); a new bot takes the seat. */
    public GameStateUpdate setLevel(GameRoom room, String userId, GameAction action) {
        String level = action.getString("level");
        if ("self".equals(action.getString("side"))) return setSelfLevel(room, userId, level);
        if (!room.isBot() && !room.isEditorBot()) return GameStateUpdate.error(room.getRoomId(), "Esta sala no es contra el bot");
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala elige el nivel del bot");
        if (room.isRanked()) return GameStateUpdate.error(room.getRoomId(), "En una partida ranked el nivel del bot lo da tu rango");
        if (!room.isEditor() && room.getStatus() != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.getRoomId(), "El nivel se elige antes de empezar la partida");
        if (level == null || !BotCatalog.LEVELS.contains(level)) return GameStateUpdate.error(room.getRoomId(), "Ese nivel no existe");
        room.setBotLevel(level);
        bots.put(room.getRoomId(), BotCatalog.botAt(level));
        if (room.isBot() && room.getPlayer(BOT) != null) room.getPlayer(BOT).setUsername(BotCatalog.nameOf(level));
        return GameStateUpdate.from(room, ActionType.SET_BOT_LEVEL, userId);
    }

    /** Sala de pruebas: the level or model of the bot that plays the owner's side (kept for when it is switched on). */
    private GameStateUpdate setSelfLevel(GameRoom room, String userId, String level) {
        if (!room.isEditor()) return GameStateUpdate.error(room.getRoomId(), "Solo en la sala de pruebas un bot juega tu lado");
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala elige el nivel del bot");
        if (level == null || !BotCatalog.LEVELS.contains(level)) return GameStateUpdate.error(room.getRoomId(), "Ese nivel no existe");
        room.setSelfBotLevel(level);
        if (room.isEditorSelfBot()) selfBots.put(room.getRoomId(), BotCatalog.botAt(level));
        return GameStateUpdate.from(room, ActionType.SET_BOT_LEVEL, userId);
    }

    /**
     * The owner makes the game ranked ("ranked": true) or friendly, before it starts (not in a test room). Ranked: who
     * goes first is drawn and, against the bot, it plays the owner's deck at the level of their strength (MMR).
     */
    public GameStateUpdate setRanked(GameRoom room, String userId, GameAction action) {
        if (room.isEditor()) return GameStateUpdate.error(room.getRoomId(), "La sala de pruebas no es ranked");
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala elige el tipo de partida");
        if (room.getStatus() != GameRoom.RoomStatus.WAITING) return GameStateUpdate.error(room.getRoomId(), "El tipo de partida se elige antes de empezar");
        if (!room.getReserved().isEmpty()) return GameStateUpdate.error(room.getRoomId(), "Una partida de la cola ranked siempre es ranked");
        boolean ranked = action.getBoolean("ranked", false);
        room.setRanked(ranked);
        room.getPlayers().values().forEach(p -> { if (!BOT.equals(p.getUserId())) p.setReady(false); });
        if (ranked) {
            room.setFirstChoice("random");
            if (room.isBot()) rankedBot(room);
        } else if (room.isBot()) {
            room.setBotEdge(0);
        }
        return GameStateUpdate.from(room, ActionType.SET_RANKED, userId);
    }

    /** Ranked against the bot: it plays at the level of the owner's strength (MMR) with a copy of their deck. */
    private void rankedBot(GameRoom room) {
        int strength = ownerStrength(room);
        String level = BotCatalog.levelFor(strength);
        room.setBotLevel(level);
        room.setBotEdge(BotCatalog.HARD.equals(level) ? BotEdge.levelFor(strength) : 0);
        bots.put(room.getRoomId(), BotCatalog.botAt(level));
        PlayerState bot = room.getPlayer(BOT);
        PlayerState owner = room.getPlayer(room.getHostId());
        if (bot == null) return;
        bot.setUsername(BotCatalog.nameOf(level));
        bot.setDeckId(null);
        bot.setDeckName(null);
        bot.setDeck(new ArrayList<>());
        bot.setOriginalDeck(new ArrayList<>());
        bot.setStarterCardId(null);
        // Its own instances of the owner's cards (sharing their ids, the board and the engine would mix the two sides up),
        // without the owner's finishes
        if (owner != null && owner.getDeckId() != null && owner.getOriginalDeck() != null) {
            List<CardSlot> copy = DeckLoader.freshCopy(owner.getOriginalDeck());
            copy.forEach(c -> c.setFinish(null));
            mirrorDeck(room, owner, copy);
        }
    }

    /** The owner sets up the bot's side (its deck or starter) with this action. */
    public static boolean forBot(GameRoom room, GameAction action) {
        return BotSeat.forBot(room, action);
    }

    /** The bot plays the owner's deck until they choose another one for it (ranked: always the owner's deck). */
    public void mirrorDeck(GameRoom room, PlayerState owner, List<CardSlot> deck) {
        PlayerState bot = room.isBot() ? room.getPlayer(BOT) : null;
        if (bot == null || bot.getDeckId() != null && !room.isRanked() || owner.getUserId().equals(BOT)) return;
        bot.setDeckId(owner.getDeckId());
        bot.setDeckName(owner.getDeckName());
        bot.setDeck(new ArrayList<>(deck));
        bot.setOriginalDeck(PreRoomActions.deepCopySlots(deck));
        bot.setStarterCardId(deck.stream().filter(CardSlot::isStarter).map(CardSlot::getCardId).findFirst().orElse(null));
    }

    /** The game changed under the bots (the Sala de pruebas went back or forth in time): they start afresh on it. */
    public void fresh(GameRoom room) {
        if (bots.containsKey(room.getRoomId())) bots.put(room.getRoomId(), BotCatalog.botAt(room.getBotLevel()));
        if (selfBots.containsKey(room.getRoomId())) selfBots.put(room.getRoomId(), BotCatalog.botAt(room.getSelfBotLevel()));
    }

    /** Only the bots of the rooms still open are kept. */
    public void keepOnly(Set<String> roomIds) {
        bots.keySet().retainAll(roomIds);
        selfBots.keySet().retainAll(roomIds);
        misses.keySet().removeIf(b -> !bots.containsValue(b) && !selfBots.containsValue(b));
    }

    BotPlayer of(String roomId) {
        return bots.get(roomId);
    }

    /** Wakes every bot with something to do (a move missed is never lost). */
    public void wakeAll() {
        Set<String> rooms = new java.util.HashSet<>(bots.keySet());
        rooms.addAll(selfBots.keySet());
        rooms.forEach(this::wake);
    }

    /** The bot plays when it has a move, a moment later (its own pause). */
    public void wake(String roomId) {
        GameRoom room = service.liveRoom(roomId);
        if (room == null) return;
        wake(roomId, bots.get(roomId), seatOf(room));
        if (room.isEditor()) wake(roomId, selfBots.get(roomId), room.getHostId());
    }

    /**
     * One bot of the room, for its seat: after a pause (the room's pace when the owner set one, else its own) it
     * thinks, if it has something to do. Waking is cheap: the thinking happens later, on the bots' threads.
     */
    private void wake(String roomId, BotPlayer bot, String seat) {
        GameRoom room = service.liveRoom(roomId);
        if (bot == null || room == null || seat == null || room.isPaused() || !bot.claimWake()) return;
        long pause;
        synchronized (room) {
            pause = room.getBotDelayMs() != null ? room.getBotDelayMs() : bot.pause(room, seat);
        }
        clock().schedule(() -> think(roomId, bot, seat), pause, TimeUnit.MILLISECONDS);
    }

    /**
     * The bot thinks on a copy of the room, without holding it (the players and the other bot keep acting meanwhile),
     * then plays its move if the room did not change since; if it did, it thinks again on the room as it is now. A move
     * the engine refuses is told to the bot, which tries another one; a bot that runs out of moves concedes.
     */
    private void think(String roomId, BotPlayer bot, String seat) {
        boolean again = false;
        try {
            GameRoom live = service.liveRoom(roomId);
            if (live == null) return;
            GameRoom copy;
            long version;
            synchronized (live) {
                if (!mayMove(live)) return;
                version = live.getVersion();
                copy = RoomCopy.of(live, new GameRandom(chance.nextLong()));
            }
            GameAction action = bot.next(copy, seat);
            if (action == null) return;
            synchronized (live) {
                if (service.liveRoom(roomId) != live || live.getVersion() != version || !mayMove(live)) {
                    again = true;
                    return;
                }
                GameStateUpdate update = apply(live, seat, action);
                if (update == null || update.getError() == null) {
                    misses.remove(bot);
                    bot.played(live, action);
                    service.broadcast(roomId, update);
                    again = true;
                } else if (misses.merge(bot, 1, Integer::sum) >= BotPlayer.MAX_ATTEMPTS) {
                    misses.remove(bot);
                    concede(live, seat);
                } else {
                    bot.refused(action, update.getError());
                    again = true;
                }
            }
        } catch (RuntimeException e) {
            LOG.error("The bot of room {} failed on its move", roomId, e);
        } finally {
            bot.releaseWake();
        }
        if (again) wake(roomId);
    }

    /** The bots may play in the room now: not paused, and in a test room only the game itself (not the pre-room nor its end). */
    private static boolean mayMove(GameRoom room) {
        if (room.isPaused()) return false;
        return !room.isEditor() || (room.getStatus() != GameRoom.RoomStatus.WAITING && room.getStatus() != GameRoom.RoomStatus.POST_GAME);
    }

    private synchronized ScheduledExecutorService clock() {
        if (clock == null) {
            // Two at once: a bot thinking ahead (up to its budgetMs) does not hold up the other rooms
            clock = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "simulator-bot");
                t.setDaemon(true);
                return t;
            });
        }
        return clock;
    }

    /**
     * One move of the bot, sent to the room. A move the engine refuses is told to the bot, which then tries another
     * one; a bot that runs out of moves concedes, so a game never waits for it forever. False: it had nothing to do.
     */
    boolean move(GameRoom room, BotPlayer bot) {
        return move(room, bot, seatOf(room));
    }

    private boolean move(GameRoom room, BotPlayer bot, String seat) {
        String roomId = room.getRoomId();
        // Sala de pruebas: the owner confirms for both sides and chooses after the game, so the bot only plays the game
        if (!mayMove(room)) return false;
        for (int attempt = 0; attempt < BotPlayer.MAX_ATTEMPTS; attempt++) {
            GameAction action = bot.next(room, seat);
            if (action == null) return false;
            GameStateUpdate update = apply(room, seat, action);
            if (update == null || update.getError() == null) {
                bot.played(room, action);
                service.broadcast(roomId, update);
                return true;
            }
            bot.refused(action, update.getError());
        }
        concede(room, seat);
        return false;
    }

    /** A bot out of valid moves concedes (a game never waits for it forever). */
    private void concede(GameRoom room, String seat) {
        GameStateUpdate update = apply(room, seat, BotPlayer.action(ActionType.SURRENDER, Map.of()));
        if (update != null && update.getError() == null) {
            update.setLogEntry("El bot no encontró una jugada válida y se rindió");
            service.recordLog(room, update);
            update.setEventLog(new ArrayList<>(room.getEventLog()));
            service.broadcast(room.getRoomId(), update);
        }
    }

    /** The seat the bot plays: its own in a room against it, the test rival's in a test room (Sala de pruebas) it plays. */
    static String seatOf(GameRoom room) {
        return BotSeat.seatOf(room);
    }

    /** A move of the bot: in a test room only its owner acts, so the bot moves for the rival as the owner does ("actAs"). */
    private GameStateUpdate apply(GameRoom room, String seat, GameAction action) {
        return applyAs(service, room, seat, action);
    }

    /**
     * A move of {@code seat}: its own in a room against the bot or online; in a test room the owner plays both sides,
     * so the rival's moves are the owner's acting for it (the real room and the bot's copies alike).
     */
    public static GameStateUpdate applyAs(RoomService engine, GameRoom room, String seat, GameAction action) {
        if (!room.isEditor() || seat.equals(room.getHostId())) return engine.applyAction(room.getRoomId(), seat, action, false);
        return engine.applyAction(room.getRoomId(), room.getHostId(), RoomEngine.asRival(action), false);
    }

    /**
     * Sala de pruebas: the owner has the bot play the test rival's side ("on": true) or plays it again by hand (false), at
     * any moment (on, it takes the rival's next decision: the service wakes it after every action); the bot plays at the room's level or model, and everything stays in view as in any test room.
     */
    public GameStateUpdate setEditorBot(GameRoom room, String userId, GameAction action) {
        if (!room.isEditor()) return GameStateUpdate.error(room.getRoomId(), "Solo en la sala de pruebas el bot juega el lado del rival");
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala decide quién juega el rival");
        boolean on = Boolean.TRUE.equals(action.getPayload() == null ? null : action.getPayload().get("on"));
        room.setEditorBot(on);
        if (on) bots.put(room.getRoomId(), BotCatalog.botAt(room.getBotLevel()));
        else bots.remove(room.getRoomId());
        return GameStateUpdate.from(room, ActionType.SET_EDITOR_BOT, userId);
    }

    /**
     * Sala de pruebas: a bot plays the owner's own side too ("on": true), at the level or model "selfBotLevel", so the owner
     * watches two bots play; off, they play it by hand again. At any moment.
     */
    public GameStateUpdate setEditorSelfBot(GameRoom room, String userId, GameAction action) {
        if (!room.isEditor()) return GameStateUpdate.error(room.getRoomId(), "Solo en la sala de pruebas un bot juega tu lado");
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala decide quién juega su lado");
        boolean on = Boolean.TRUE.equals(action.getPayload() == null ? null : action.getPayload().get("on"));
        if (on && !room.getAdminPlayers().contains(userId))
            return GameStateUpdate.error(room.getRoomId(), "Solo un administrador del simulador puede hacer que un bot juegue su lado");
        room.setEditorSelfBot(on);
        if (on) selfBots.put(room.getRoomId(), BotCatalog.botAt(room.getSelfBotLevel()));
        else selfBots.remove(room.getRoomId());
        return GameStateUpdate.from(room, ActionType.SET_EDITOR_SELF_BOT, userId);
    }

    /** The pause before each move of the room's bots, in ms ("ms"; null or below 0: each bot's own); at any moment. */
    public GameStateUpdate setDelay(GameRoom room, String userId, GameAction action) {
        String refused = pace(room, userId, action);
        return refused != null ? GameStateUpdate.error(room.getRoomId(), refused) : GameStateUpdate.from(room, ActionType.SET_BOT_DELAY, userId);
    }

    /**
     * Sets the pace, with no need of the room's lock (a bot may be thinking with it held): it changes no game state,
     * only how long the next pause is. The refusal, or null.
     */
    public String pace(GameRoom room, String userId, GameAction action) {
        if (!room.isEditor() && !room.isBot()) return "Esta sala no tiene bots";
        if (!userId.equals(room.getHostId())) return "Solo el dueño de la sala elige el ritmo de los bots";
        Object ms = action.getPayload() == null ? null : action.getPayload().get("ms");
        room.setBotDelayMs(ms instanceof Number n && n.intValue() >= 0 ? Math.min(10_000, n.intValue()) : null);
        return null;
    }

    /** A test room's owner gives its rival's side an edge, 0–8 (BotEdge); it applies from the next turn on. */
    public GameStateUpdate setEdge(GameRoom room, String userId, GameAction action) {
        if (!room.isEditor()) return GameStateUpdate.error(room.getRoomId(), "La ventaja del bot se elige solo en la sala de pruebas");
        if (!userId.equals(room.getHostId())) return GameStateUpdate.error(room.getRoomId(), "Solo el dueño de la sala elige la ventaja del bot");
        Object level = action.getPayload() == null ? null : action.getPayload().get("level");
        room.setBotEdge(BotEdge.clamp(level instanceof Number n ? n.intValue() : 0));
        return GameStateUpdate.from(room, ActionType.SET_BOT_EDGE, userId);
    }

    /** Plays the bots' moves right away until they have none (tests: no pause, no thread). Returns how many they made. */
    public int playNow(String roomId) {
        BotPlayer bot = bots.get(roomId), self = selfBots.get(roomId);
        GameRoom room = service.liveRoom(roomId);
        int moves = 0;
        if ((bot == null && self == null) || room == null) return 0;
        synchronized (room) {
            while (moves < MAX_MOVES_NOW && ((bot != null && move(room, bot)) || (self != null && room.isEditor() && move(room, self, room.getHostId())))) moves++;
        }
        return moves;
    }
}
