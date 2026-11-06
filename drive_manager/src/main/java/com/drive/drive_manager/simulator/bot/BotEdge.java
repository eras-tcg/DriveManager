package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.rank.RankRules;
import com.drive.drive_manager.simulator.room.GameLogEntry;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.RoomService;

/**
 * The bot's edge: past the strength of the hardest bot, a stronger player faces it with an advantage, in levels (one
 * every {@value #MMR_STEP} of MMR over it, up to {@value #MAX}). Each level moves a Marcador at the start of a turn:
 * <ul>
 *   <li>levels 1–4: the bot's Marcador goes up 1 more in its turn 4, then (higher levels) in its turn 3, 2, 1;</li>
 *   <li>levels 5–8, besides: the rival's Marcador goes up 1 less in their turn 5, then in their turn 4, 3, 2.</li>
 * </ul>
 * Modo solo ranked sets it from the owner's MMR; a test room may set it by hand (for the rival's side). The bot counts
 * as that much stronger for the ranks.
 */
public final class BotEdge {

    public static final int MAX = 8;
    /** Each this much MMR over the hardest bot's strength: one level more. */
    public static final int MMR_STEP = 100;
    /** The levels from which the rival's Marcador is held back too. */
    private static final int RIVAL_FROM = 5;

    private BotEdge() {}

    /** The level for a player of this MMR (0 up to the hardest bot's strength). */
    public static int levelFor(int mmr) {
        int over = mmr - RankRules.BOT_MMR.get(BotCatalog.HARD);
        return Math.max(0, Math.min(MAX, over / MMR_STEP));
    }

    /** How strong the hardest bot is with this edge, for the ranks. */
    public static int strength(int level) {
        return RankRules.BOT_MMR.get(BotCatalog.HARD) + clamp(level) * MMR_STEP;
    }

    public static int clamp(int level) {
        return Math.max(0, Math.min(MAX, level));
    }

    /** The bot's own turn in which its Marcador goes up 1 more (0: none). */
    public static int botTurn(int level) {
        return level <= 0 ? 0 : Math.max(1, 5 - level);
    }

    /** The rival's own turn in which their Marcador goes up 1 less (0: none). */
    public static int rivalTurn(int level) {
        return level < RIVAL_FROM ? 0 : 10 - clamp(level);
    }

    /** The side the edge favors: the bot of Modo solo, else (a test room) the rival's side. */
    public static String favoredSide(GameRoom room) {
        if (room.getPlayers().containsKey(BotRooms.BOT)) return BotRooms.BOT;
        return room.isEditor() ? RoomService.EDITOR_RIVAL : null;
    }

    /**
     * The Marcador of `player` as their turn starts: `raised` (it went up 1, or it is 1 in the first turn) with the
     * edge's 1 more or 1 less in this turn of theirs (from 0 to 5), said in the history when it applies.
     */
    public static int marcador(GameRoom room, PlayerState player, int raised) {
        int delta = delta(room, player.getUserId(), player.getTurnsStarted());
        int count = Math.max(0, Math.min(5, raised + delta));
        if (delta != 0 && count != Math.max(0, Math.min(5, raised))) {
            room.getEventLog().add(new GameLogEntry(System.currentTimeMillis(), delta > 0
                    ? "Ventaja del bot: el Marcador de " + player.getUsername() + " sube 1 más en su turno " + player.getTurnsStarted()
                    : "Ventaja del bot: el Marcador de " + player.getUsername() + " sube 1 menos en su turno " + player.getTurnsStarted()));
        }
        return count;
    }

    /** How much more (or less) the Marcador of `playerId` goes up in their own turn `ownTurn` (0, +1 or −1). */
    public static int delta(GameRoom room, String playerId, int ownTurn) {
        int level = clamp(room.getBotEdge());
        String favored = level > 0 ? favoredSide(room) : null;
        if (favored == null) return 0;
        if (favored.equals(playerId)) return ownTurn == botTurn(level) ? 1 : 0;
        return ownTurn == rivalTurn(level) ? -1 : 0;
    }
}
