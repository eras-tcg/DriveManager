package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The special summon Requerimientos of the cards the tests make up (no card file), programmed by hand as a card file's `requirement`
 * section would: the engine never reads the printed text. Each fixture text the tests print on a made-up card has its section here, written
 * out; [program] registers it for that card's name. A real card (with a card file) keeps its own section and is left alone.
 */
final class Req {

    private Req() {}

    /** No zones written: the Tributos or the field. Lists are empty when absent, as a card file reads. */
    private static final List<String> TRIBUTES_OR_FIELD = List.of();

    /** The sections by the text printed on the test card and its method ("evolution", "promotion", "materialization", "ritual"). */
    private static final Map<String, RequirementSpec> BY_TEXT = new HashMap<>();

    /** Names of real cards (their file has a section), so they are never overwritten. */
    private static final Set<String> REAL = new HashSet<>();

    static RequirementSpec summoner(int level) {
        return spec(new SummonerSpec(level, null, false, false, null, List.of(), false), null);
    }

    static RequirementSpec spec(SummonerSpec summoner, MaterialSpec materials) {
        return new RequirementSpec(summoner, materials, 0, false, false, 0, false, 0, false, null, null, null, null);
    }

    static RequirementSpec ritual(boolean sum, int level, Integer maxLevel, boolean atLeast, Integer maxStrength, int extra) {
        return new RequirementSpec(null, null, 0, false, false, 0, false, 0, false, null, null, null,
                new RitualSpec(sum, level, maxLevel, atLeast, maxStrength, extra, null, List.of(), false, null));
    }

    static RequirementSpec ritualParts(List<RitualParts.Part> parts) {
        return new RequirementSpec(null, null, 0, false, false, 0, false, 0, false, null, null, null,
                new RitualSpec(false, 0, null, false, null, 0, null, parts, false, null));
    }

    private static SummonerSpec level(int level) {
        return new SummonerSpec(level, null, false, false, null, List.of(), false);
    }

    private static MaterialSpec material(int count, int level, List<String> zones) {
        return new MaterialSpec(count, false, level, false, 0, zones, null);
    }

    private static void put(String kind, String text, RequirementSpec spec) {
        BY_TEXT.put(kind + "|" + text.trim().toLowerCase(), spec);
    }

    static {
        for (int n = 1; n <= 5; n++) {
            for (String kind : List.of("evolution", "promotion", "materialization")) {
                put(kind, "criatura nivel " + n + ".", summoner(n));
                put(kind, "criatura nivel " + n, summoner(n));
            }
            put("ritual", "criatura nivel " + n + ".", ritual(false, n, null, false, null, 0));
            put("ritual", "criatura nivel " + n, ritual(false, n, null, false, null, 0));
            put("promotion", "ascenso: criatura nivel " + n + ".", summoner(n));
        }
        put("ritual", "criatura nivel 3 o mayor.", ritual(false, 3, null, true, null, 0));
        put("ritual", "criatura nivel 1 de fuerza 3 o menos.", ritual(false, 1, null, false, 3, 0));
        put("ritual", "criaturas que sumen 2 en nivel incluido el invocador.", ritual(true, 2, null, false, null, 0));
        put("ritual", "criaturas que sumen 2 en nivel.", ritual(true, 2, null, false, null, 0));
        put("ritual", "criaturas que en nivel sumen 2.", ritual(true, 2, null, false, null, 0));
        put("ritual", "criatura nivel 4 + 1 o más aliados.", ritual(false, 4, null, false, null, 1));
        put("ritual", "2 criaturas nivel 2 o menor.", ritualParts(List.of(new RitualParts.Part(2, true, null), new RitualParts.Part(2, true, null))));
        put("materialization", "criatura nivel 2 o 3 + material nivel 2 en campo o mano.",
                spec(new SummonerSpec(2, 3, false, false, null, List.of(), false), material(1, 2, List.of("field", "hand"))));
        put("materialization", "criatura nivel 1 + material nivel 1 en tributos o campo.", spec(level(1), material(1, 1, TRIBUTES_OR_FIELD)));
        put("materialization", "criatura nivel 2 + material nivel 2 en campo o tributos.",
                spec(level(2), material(1, 2, List.of("field", "tributeZone"))));
        put("materialization", "criatura nivel 3 en mano + material en mano.", spec(level(3), material(1, 3, List.of("hand"))));
        put("materialization", "2 criaturas en mano.",
                spec(new SummonerSpec(0, null, false, false, null, List.of("hand"), false), material(1, 0, List.of("hand"))));
        put("materialization", "2 criaturas nivel 1 en tu mano o tributos.",
                spec(new SummonerSpec(0, null, false, false, null, List.of(), true), material(2, 1, List.of("hand", "tributeZone"))));
        put("materialization", "criatura nivel 3 + aliados que sumen 3 o 4 en nivel.",
                spec(level(3), new MaterialSpec(0, false, 3, false, 3, List.of(), null)));
        // "(A) o (B)": a made-up card with both ways
        put("materialization", "(criatura nivel 3) o (criatura nivel 2 + material nivel 2 en tu mano o campo).",
                new RequirementSpec(level(3), null, 0, false, false, 0, false, 0, false, null, null,
                        spec(level(2), material(1, 2, List.of("hand", "field"))), null));
        RequirementSpec aloneAt1 = new RequirementSpec(level(1), null, 0, false, false, 1, false, 0, false, null, null, null, null);
        put("promotion", "criatura nivel 1, mientras no controlas otros aliados nivel 1.", aloneAt1);
        put("promotion", "criatura nivel 1, mientras tus aliados suman 2 o más en nivel.",
                new RequirementSpec(level(1), null, 2, false, false, 0, false, 0, false, null, null, null, null));
    }

    /** Programs the Requerimiento of a made-up test card from the fixture text printed on it (a real card keeps its file's). */
    static CardSlot program(CardSlot card) {
        if (card.getCardName() == null || card.getRequirement() == null || card.getSpecialSummonKind() == null) return card;
        String name = card.getCardName().trim().toLowerCase();
        if (REAL.contains(name)) return card;
        boolean madeUp = !CardEffects.programmedNames().contains(CardEffects.nameOf(name)) || CardEffects.requirementOf(card) == null
                || BY_NAME.containsKey(name);
        if (!madeUp) {
            REAL.add(name);
            return card;
        }
        RequirementSpec spec = BY_TEXT.get(card.getSpecialSummonKind().toLowerCase() + "|" + card.getRequirement().trim().toLowerCase());
        if (spec == null) throw new IllegalArgumentException("Req: sin requisito programado para \"" + card.getRequirement() + "\" ("
                + card.getSpecialSummonKind() + ", carta de prueba \"" + card.getCardName() + "\")");
        BY_NAME.put(name, spec);
        CardEffects.registerRequirement(CardEffects.nameOf(name), spec);
        return card;
    }

    /** Test cards programmed here, by name. */
    private static final Map<String, RequirementSpec> BY_NAME = new HashMap<>();
}
