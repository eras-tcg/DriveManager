package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Duels to read: {@code bot.duel.a} against {@code bot.duel.b}, each deal from both seats, on the deck pairs of
 * {@code bot.duel.pairs} ("st1-st2,st3-st3"), {@code bot.duel.seeds} deals each. Each game is summed up turn by turn:
 * what each side did (summons, plays, attacks and blocks, destructions, hits on lives and where the life went), the
 * lives and the creatures on the field (with strength, "*" exhausted, "^" in the Línea frontal) at the turn's end. One
 * file per game in {@code bot.duel.out}; a line per game with who won.
 */
class BotDuelRun {

    private static final List<String> SHOWN = List.of(GameEvent.NORMAL_SUMMON, GameEvent.SPECIAL_SUMMON, GameEvent.EFFECT_SUMMON,
            GameEvent.PLAY, GameEvent.ATTACK_DECLARED, GameEvent.BLOCK, GameEvent.DESTROY, GameEvent.LIFE_DAMAGE, GameEvent.TRIBUTE);

    @Test
    void duels() throws Exception {
        assumeTrue(Boolean.getBoolean("bot.duel"), "runs only with -Dbot.duel=true");
        BotSettings a = BotSettings.DEFAULT.with(System.getProperty("bot.duel.a", ""));
        BotSettings b = BotSettings.DEFAULT.with(System.getProperty("bot.duel.b", ""));
        Path out = Path.of(System.getProperty("bot.duel.out", "target/bot-duels"));
        Files.createDirectories(out);
        int seeds = Integer.getInteger("bot.duel.seeds", 1);
        ExecutorService pool = Executors.newFixedThreadPool(Integer.getInteger("bot.duel.threads", 2));
        List<Future<String>> games = new ArrayList<>();
        for (String pair : System.getProperty("bot.duel.pairs", "st1-st2").split(",")) {
            String[] d = pair.trim().split("-");
            for (int s = 1; s <= seeds; s++) for (boolean aHosts : List.of(true, false)) {
                long seed = 13000L + s * 100 + pair.hashCode() % 97;
                games.add(pool.submit(() -> {
                    StringBuilder sb = new StringBuilder();
                    // The last event and log line printed: the room keeps only the latest ones (timeline 2000, log 300), so an
                    // index stops working once they are full; the printed one is looked up again instead
                    Object[] lastEvent = {null}, lastLine = {null};
                    String[] host = {aHosts ? "A" : "B"}, guest = {aHosts ? "B" : "A"};
                    BotMatch.Result r = BotMatch.playSeededWatching(d[0], d[1], new LookaheadBot(aHosts ? a : b), new LookaheadBot(aHosts ? b : a), seed, room -> {
                        List<GameEvent> tl = room.getTimeline();
                        List<GameEvent> turn = after(tl, lastEvent);
                        if (turn.isEmpty()) return;
                        Map<String, String> who = Map.of("host", host[0] + "(" + d[0] + ")", "guest", guest[0] + "(" + d[1] + ")");
                        StringBuilder acts = new StringBuilder();
                        for (GameEvent e : turn) {
                            if (!SHOWN.contains(e.getType())) continue;
                            String p = who.getOrDefault(String.valueOf(e.getPlayerId()), String.valueOf(e.getPlayerId()));
                            acts.append("  ").append(p).append(' ').append(describe(room, e)).append('\n');
                        }
                        // The gems the player whose turn ended closed it with (below 0: overextended; the new player's
                        // gems are their Marcador minus that)
                        String now = room.getCurrentTurnUserId(), before = room.getOpponentId(now);
                        int closed = -(EffectEngine.gemsOf(room, now) - room.getPlayer(now).getResourceCount());
                        sb.append("— turno (").append(who.getOrDefault(String.valueOf(now), "?")).append(" empieza; ")
                                .append(who.getOrDefault(String.valueOf(before), "?")).append(" cerró con ").append(closed).append(" gemas; timeline ")
                                .append(tl.size()).append(", evento ").append(room.getEventSeq()).append(") | ");
                        for (String seat : List.of("host", "guest")) {
                            PlayerState p = room.getPlayer(seat);
                            if (p == null) continue;
                            sb.append(who.get(seat)).append(": vidas ").append(p.getLifeStack().size()).append(", mano ").append(p.getHand().size())
                                    .append(", gemas ").append(EffectEngine.gemsOf(room, seat)).append(", mazo ").append(p.getDeck().size()).append(", mesa [");
                            sb.append(String.join(", ", p.getField().stream().filter(c -> "creature".equals(c.getCardType()))
                                    .map(c -> c.getCardName() + " " + EffectEngine.strength(c) + (c.isTapped() ? "*" : "") + (EffectEngine.inFrontLine(c) ? "^" : "")).toList()));
                            sb.append("], mano {").append(String.join(", ", p.getHand().stream()
                                    .map(c -> c.getCardName() + " " + (c.getCost() == null ? "esp" : c.getCost())).toList())).append("}  ");
                        }
                        sb.append('\n').append(acts);
                        // bot.duel.log: the whole history of the turn (costs paid, blocks, effects)
                        if (Boolean.getBoolean("bot.duel.log")) {
                            for (var l : after(room.getEventLog(), lastLine)) sb.append("      · ").append(l.getText()).append('\n');
                        }
                    });
                    String winner = "host".equals(r.winner()) ? host[0] : "guest".equals(r.winner()) ? guest[0] : "nadie";
                    String name = pair + "-s" + seed + "-A" + (aHosts ? "host" : "guest") + ".txt";
                    Files.writeString(out.resolve(name), "A = " + System.getProperty("bot.duel.a") + "\nB = " + System.getProperty("bot.duel.b")
                            + "\nGana " + winner + " (" + r.reason() + ", " + r.turns() + " turnos)\n\n" + sb, StandardCharsets.UTF_8);
                    return name + " → gana " + winner + " en " + r.turns() + " turnos (" + r.reason() + ")";
                }));
            }
        }
        for (Future<String> g : games) System.out.println("[duel] " + g.get());
        pool.shutdown();
    }

    /** What the list holds after {@code last[0]} (all of it when that one is gone); then {@code last[0]} is its last. */
    private static <T> List<T> after(List<T> list, Object[] last) {
        int at = -1;
        if (last[0] != null) for (int k = list.size() - 1; k >= 0; k--) if (list.get(k) == last[0]) { at = k; break; }
        List<T> out = new ArrayList<>(list.subList(at + 1, list.size()));
        if (!list.isEmpty()) last[0] = list.get(list.size() - 1);
        return out;
    }

    private static String describe(GameRoom room, GameEvent e) {
        String card = e.getCardName() == null ? name(room, e.getCardInstanceId()) : e.getCardName();
        return switch (e.getType()) {
            case GameEvent.NORMAL_SUMMON -> "invoca " + card;
            case GameEvent.SPECIAL_SUMMON -> "invoca especial " + card;
            case GameEvent.EFFECT_SUMMON -> "invoca por efecto " + card;
            case GameEvent.PLAY -> "juega " + card;
            case GameEvent.ATTACK_DECLARED -> "ataca con " + card + (e.getData() != null && e.getData().get("targetId") != null ? " a " + name(room, String.valueOf(e.getData().get("targetId"))) : "");
            case GameEvent.BLOCK -> "bloquea con " + card;
            case GameEvent.DESTROY -> "destruye " + card;
            case GameEvent.TRIBUTE -> "tributa " + card;
            case GameEvent.LIFE_DAMAGE -> "quita una vida: " + card + " → " + e.getToZone() + (e.getData() != null ? " (daño " + e.getData().get("damage") + ")" : "");
            default -> e.getType() + " " + card;
        };
    }

    private static String name(GameRoom room, String id) {
        if (id == null) return "?";
        for (PlayerState p : room.getPlayers().values())
            for (List<CardSlot> z : List.of(p.getField(), p.getHand(), p.getTributeZone(), p.getDiscardPile(), p.getDeck(), p.getLifeStack()))
                for (CardSlot c : z) if (id.equals(c.getInstanceId())) return c.getCardName();
        return id;
    }
}
