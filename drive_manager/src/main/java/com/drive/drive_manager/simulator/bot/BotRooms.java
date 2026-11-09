package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.GameRandom;
import com.drive.drive_manager.simulator.rank.RankRules;
import com.drive.drive_manager.simulator.room.ActionType;
import com.drive.drive_manager.simulator.room.DeckLoader;
import com.drive.drive_manager.simulator.room.GameAction;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.GameStateUpdate;
import com.drive.drive_manager.simulator.room.RoomCopy;
import com.drive.drive_manager.simulator.room.RoomEngine;
import com.drive.drive_manager.simulator.room.RoomService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The rooms played against the bot, on the server: the bot's seat, its deck, the owner's settings and what one move of a
 * bot is are the room session's ({@link RoomBots}, common with the game in the browser); here are the bot players of each
 * room and when they move. After each action in the room the bot is woken; it waits a moment (so the player sees each of
 * its moves on the board), thinks on a copy of the room on the bots' threads and sends its next move through the room
 * service, as a player would, until it has nothing to do.
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

    /** What the room session's bots ask of the server: the bot players of each room, the ladder, the instance ids. */
    private final RoomBots.Roster roster = new RoomBots.Roster() {
        @Override
        public void newBot(GameRoom room, String level) {
            bots.put(room.getRoomId(), BotCatalog.botAt(level));
        }

        /** The owner's strength in the ranked ladder (a new player's when there is no ladder: tests). */
        @Override
        public int ownerStrength(GameRoom room) {
            return service.ranks == null || room.getHostId() == null ? RankRules.START_MMR : service.ranks.of(room.getHostId()).mmr();
        }

        @Override
        public String nextInstanceId() {
            return DeckLoader.newInstanceId();
        }

        @Override
        public void dropBot(GameRoom room) {
            bots.remove(room.getRoomId());
        }

        @Override
        public void newSelfBot(GameRoom room, String level) {
            selfBots.put(room.getRoomId(), BotCatalog.botAt(level));
        }

        @Override
        public void dropSelfBot(GameRoom room) {
            selfBots.remove(room.getRoomId());
        }
    };

    /** Seats the bot in a new room against it. */
    public void seat(GameRoom room) {
        RoomBots.seat(room, roster);
    }

    /** The owner's settings of the room's bots (RoomHost.botSetting): level, ranked, the test room's bots, pace, edge. */
    public GameStateUpdate setting(ActionType type, GameRoom room, String userId, GameAction action) {
        return RoomBots.setting(type, room, userId, action, roster);
    }

    /** The owner sets up the bot's side (its deck or starter) with this action. */
    public static boolean forBot(GameRoom room, GameAction action) {
        return BotSeat.forBot(room, action);
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
                if (!RoomBots.mayMove(live)) return;
                version = live.getVersion();
                copy = RoomCopy.of(live, new GameRandom(chance.nextLong()));
            }
            GameAction action = bot.next(copy, seat);
            if (action == null) return;
            synchronized (live) {
                if (service.liveRoom(roomId) != live || live.getVersion() != version || !RoomBots.mayMove(live)) {
                    again = true;
                    return;
                }
                GameStateUpdate update = service.linkByLink(live, seat, action, () -> apply(live, seat, action));
                if (update == null || update.getError() == null) {
                    misses.remove(bot);
                    bot.played(live, action);
                    service.broadcast(roomId, update);
                    again = true;
                } else if (misses.merge(bot, 1, Integer::sum) >= BotPlayer.MAX_ATTEMPTS) {
                    misses.remove(bot);
                    RoomBots.concede(live, a -> apply(live, seat, a), u -> broadcast(live, u));
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
     * One move of the bot, sent to the room ({@link RoomBots#move}: a refused move is told to the bot, which tries another
     * one; a bot that runs out of moves concedes). False: it had nothing to do (or conceded).
     */
    boolean move(GameRoom room, BotPlayer bot) {
        return move(room, bot, seatOf(room));
    }

    private boolean move(GameRoom room, BotPlayer bot, String seat) {
        return RoomBots.move(room, bot, seat, a -> service.linkByLink(room, seat, a, () -> apply(room, seat, a)), u -> broadcast(room, u)) == RoomBots.Move.MOVED;
    }

    private kotlin.Unit broadcast(GameRoom room, GameStateUpdate update) {
        service.broadcast(room.getRoomId(), update);
        return kotlin.Unit.INSTANCE;
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
