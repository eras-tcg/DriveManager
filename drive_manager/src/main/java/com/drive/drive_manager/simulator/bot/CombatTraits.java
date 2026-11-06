package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.CardEffects;
import com.drive.drive_manager.simulator.engine.EffectScript;
import com.drive.drive_manager.simulator.room.CardSlot;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a creature's effects do to a combat (the user's call, 2026-11-05: CombatSearch must see them; Mawser destroys a
 * second creature when it wins a clash, which made pressing it a dramatic loss). Read once per card from the effects'
 * texts, as RemovalRisk reads the destructions, so it works for any card written the same way:
 * <ul>
 *   <li>{@code bonusLevel}: "si esta carta destruye algún enemigo, elige 1 enemigo nivel N o menor; destrúyelo"
 *   (Mawser): each clash it wins takes one more enemy of level N or less.</li>
 *   <li>{@code attackStr} / {@code attackLevel}: an effect on attack that destroys an enemy of strength / level N or
 *   less (Fadrake, Zhaglizer, Zhauz, Dracoventus); {@code attackTributes} tributes it must send to pay it and
 *   {@code attackSacrifice} an ally it must sacrifice (it then needs one).</li>
 *   <li>{@code refundCards}: cards it draws when destroyed less half the ones it tributes then (Damon, Virdris,
 *   Carnhaus); {@code refundSummon}: a summon when destroyed (Slicker, Espada De Plata). Losing it costs less.</li>
 *   <li>{@code attackSummon}: a summon or evolution when it attacks (Flamagón, Saildreik, Caballero Umbrío, Comandante,
 *   Caballero Real): attacking is worth something even when it dies.</li>
 * </ul>
 * -1 / 0 / false: none. Reactivating, gaining Letalidad or Enfrentar are not read.
 */
record CombatTraits(int bonusLevel, int attackStr, int attackLevel, int attackTributes, boolean attackSacrifice,
                    double refundCards, boolean refundSummon, boolean attackSummon) {

    static final CombatTraits NONE = new CombatTraits(-1, -1, -1, 0, false, 0, false, false);

    private static final Pattern LEVEL = Pattern.compile("nivel (\\d+) o menor");
    private static final Pattern STRENGTH = Pattern.compile("fuerza (\\d+) o menos");
    private static final Pattern DRAW = Pattern.compile("roba (\\d+) carta");
    private static final Pattern TRIBUTE = Pattern.compile("tributa (\\d+) carta");
    private static final Pattern SEND = Pattern.compile("enviar (\\d+) de tus tributos");
    private static final Map<String, CombatTraits> BY_CARD = new ConcurrentHashMap<>();

    boolean attackKills() {
        return attackStr >= 0 || attackLevel >= 0;
    }

    static CombatTraits of(CardSlot card) {
        if (card == null || card.getCardName() == null) return NONE;
        // A tower's traits are its own and those its Recursos give it: the key names them all
        StringBuilder key = new StringBuilder(BotSettings.cardKey(card.getCardName()));
        card.getResources().stream().map(r -> r.getCardName() == null ? "" : BotSettings.cardKey(r.getCardName())).sorted().forEach(n -> key.append('|').append(n));
        return BY_CARD.computeIfAbsent(key.toString(), k -> read(card));
    }

    private static CombatTraits read(CardSlot card) {
        int bonus = -1, aStr = -1, aLevel = -1, tributes = 0;
        boolean sacrifice = false, refundSummon = false, attackSummon = false;
        double refund = 0;
        // CardEffects.of(card) holds the effects its Recursos give it too (a tower's inherited ones)
        for (EffectScript e : CardEffects.of(card)) {
            String t = e.text() == null ? "" : e.text().toLowerCase(Locale.ROOT);
            boolean destroysEnemy = t.contains("destrú") && t.contains("enemigo");
            switch (e.moment()) {
                case ON_ATTACK -> {
                    if (destroysEnemy && !t.contains("vidas")) {
                        aStr = first(STRENGTH, t);
                        aLevel = first(LEVEL, t);
                        if (aStr < 0 && aLevel < 0) aLevel = 99;
                        tributes = Math.max(0, first(SEND, t));
                        sacrifice = t.contains("sacrificar");
                    }
                    if (t.contains("invoca")) attackSummon = true;
                }
                case ON_DESTROY -> {
                    int draw = first(DRAW, t), trib = first(TRIBUTE, t);
                    if (draw > 0) refund = draw - 0.5 * Math.max(0, trib);
                    if (t.contains("invoca")) refundSummon = true;
                }
                default -> {
                    if (t.contains("si esta carta destruye") && destroysEnemy) bonus = Math.max(0, first(LEVEL, t) < 0 ? 99 : first(LEVEL, t));
                }
            }
        }
        return new CombatTraits(bonus, aStr, aLevel, tributes, sacrifice, Math.max(0, refund), refundSummon, attackSummon);
    }

    private static int first(Pattern p, String t) {
        Matcher m = p.matcher(t);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
