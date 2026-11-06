package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.engine.EffectScript;
import com.drive.drive_manager.simulator.engine.Prompt;
import com.drive.drive_manager.simulator.room.CardLocator;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.room.Zone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * hReduce (the user's call, 2026-11-05, the ST1 deck): a creature reduced to 0 strength is destroyed, so a strength
 * reduction is a removal where it reaches 0, and only there; reducing a creature it cannot bring to 0 pays only when a
 * later play finishes it or it opens or wins a clash. The heuristic picked "the most valuable enemy" for it (Hez of 10
 * for 3 points, Carnhaus of 3 left alone), and as it plays both sides of the tries, activating Mathaus or Arc-xioma
 * looked worthless and the bot never did. Its pick for "reduce su fuerza en N", in order:
 * <ol>
 *   <li>the most valuable enemy it brings to 0: strength at most N plus what its own cards add on the way ("reducir su
 *   fuerza en 2 más", Mihel) or finish ("si reduces a K o menos … reducirla a 0", Arc-xioma);</li>
 *   <li>else the active enemy of the Línea frontal that blocks and beats one of its attackers ready to attack now, and
 *   would no longer after the reduction (the strongest such);</li>
 *   <li>else nothing when it may choose nothing, the most valuable enemy when it must choose.</li>
 * </ol>
 */
final class StrengthCuts {

    private static final Pattern AMOUNT = Pattern.compile("(?:reduce|reducir|reduzca)[^.;]*?fuerza en (\\d+)|(?:reduce|reducir) en (\\d+) su fuerza");
    private static final Pattern MORE = Pattern.compile("reducir su fuerza en (\\d+) más");
    private static final Pattern TO_ZERO = Pattern.compile("si reduces a (\\d+) o menos la fuerza[^.]*reducirla a 0");

    private StrengthCuts() {}

    /** The amount a prompt reduces, from its texts; -1 when it is not a strength reduction. */
    static int amount(Prompt prompt) {
        for (String t : new String[] {prompt.getEffectText(), prompt.getInstruction()}) {
            if (t == null) continue;
            Matcher m = AMOUNT.matcher(t.toLowerCase(Locale.ROOT));
            if (m.find()) return Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return -1;
    }

    /** The pick (ids), or null when the prompt is not a strength reduction on enemies. */
    static List<String> pick(GameRoom room, PlayerState self, Prompt prompt, List<String> choices) {
        int n = amount(prompt);
        if (n < 0 || self == null) return null;
        // What its own cards add: "en 2 más" (Mihel, in the hand or on the field) and "si reduces a K o menos … a 0"
        int more = 0, zeroAt = 0;
        List<CardSlot> own = new ArrayList<>(self.getField());
        own.addAll(self.getHand());
        for (CardSlot c : own) {
            for (EffectScript e : CardEffects.of(c)) {
                String t = e.text() == null ? "" : e.text().toLowerCase(Locale.ROOT);
                Matcher m = MORE.matcher(t);
                if (m.find()) more = Math.max(more, Integer.parseInt(m.group(1)));
                Matcher z = TO_ZERO.matcher(t);
                if (z.find() && self.getField().contains(c)) zeroAt = Math.max(zeroAt, Integer.parseInt(z.group(1)));
            }
        }
        List<CardSlot> enemies = choices.stream().map(id -> find(room, self, id)).filter(c -> c != null && "creature".equals(c.getCardType())).toList();
        if (enemies.isEmpty()) return null;
        final int reach = n + more, zero = zeroAt;
        CardSlot kill = enemies.stream().filter(c -> EffectEngine.strength(c) - reach <= Math.max(0, zero))
                .max(Comparator.comparingDouble(BotPlayer::value)).orElse(null);
        if (kill != null) return List.of(kill.getInstanceId());
        // A blocker that beats one of its ready attackers and would not after the reduction
        List<CardSlot> attackers = self.getField().stream().filter(c -> "creature".equals(c.getCardType()) && !c.isTapped() && EffectEngine.inFrontLine(c)).toList();
        CardSlot opened = enemies.stream().filter(c -> !c.isTapped() && EffectEngine.inFrontLine(c))
                .filter(c -> attackers.stream().anyMatch(a -> EffectEngine.strength(c) >= EffectEngine.strength(a) && EffectEngine.strength(c) - n < EffectEngine.strength(a)))
                .max(Comparator.comparingInt(EffectEngine::strength)).orElse(null);
        if (opened != null) return List.of(opened.getInstanceId());
        if (prompt.getMin() == 0) return List.of();
        return List.of(enemies.stream().max(Comparator.comparingDouble(BotPlayer::value)).get().getInstanceId());
    }

    private static CardSlot find(GameRoom room, PlayerState self, String id) {
        PlayerState rival = room.getPlayer(room.getOpponentId(self.getUserId()));
        return CardLocator.find(java.util.Arrays.asList(rival, self), id, List.of(Zone.FIELD), false);
    }
}
