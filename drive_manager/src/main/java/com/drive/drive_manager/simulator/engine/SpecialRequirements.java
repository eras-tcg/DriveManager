package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.FloatingEffect;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.SpecialSummons.*;
import static com.drive.drive_manager.simulator.engine.Rituals.*;
import static com.drive.drive_manager.simulator.engine.EffectEngine.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Resolution.*;
import static com.drive.drive_manager.simulator.engine.StepHelpers.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** What a special summon asks for: its Requerimiento, who can summon and with which materials. */
public final class SpecialRequirements {

    private SpecialRequirements() {}

    // ------------------------------------------------------------------ Evolución, Ascenso, Materialización (rule 9)

    /**
     * What a non-Ritual special summon needs: the summoner's level, for Ascenso the level the allies must add up
     * to, for Materialización the level of the other material (from the Tributos or the field).
     */
    /**
     * A special summon's Requerimiento as printed.
     *
     * @param level         the summoner's level (the lowest, with {@code maxLevel}: "criatura nivel 3 o 4")
     * @param maxLevel      the highest summoner level
     * @param atLeast       "nivel N o mayor"
     * @param materialCount Materialización: how many other materials ("+ material nivel 2": 1; "3 criaturas nivel 3": 3; none: 0)
     * @param materialZones where they may come from ("en campo o mano"): "field", "hand", "tributeZone"
     * @param noSummoner    "K criaturas nivel N en tu mano o tributos": no summoner on the field, all of them are materials; it enters the Retaguardia
     * @param summonerZones "invocador nivel N en tu mano o tributos + K criaturas nivel M…": the summoner is declared first, from those
     *                      zones (it becomes the first material); null when there is no such summoner
     * @param alternative   "(A) o (B)": the other way, or null
     * @param alone         "mientras no controles otras criaturas": the summoner is the only creature on the field
     * @param strongest     "con fuerza igual o mayor que la de cualquier otro aliado"
     */
    record SpecialRequirement(String kind, int level, int alliesSum, int materialLevel, int levelSum, boolean atLeast, int maxLevel,
                              int materialCount, List<String> materialZones, boolean noSummoner, SpecialRequirement alternative,
                              boolean alone, boolean strongest, List<String> summonerZones, boolean moreMaterials) {
        /** {@code moreMaterials}: "N o más criaturas" as materials. */
        SpecialRequirement(String kind, int level, int alliesSum, int materialLevel, int levelSum, boolean atLeast, int maxLevel,
                           int materialCount, List<String> materialZones, boolean noSummoner, SpecialRequirement alternative,
                           boolean alone, boolean strongest, List<String> summonerZones) {
            this(kind, level, alliesSum, materialLevel, levelSum, atLeast, maxLevel, materialCount, materialZones, noSummoner, alternative,
                    alone, strongest, summonerZones, false);
        }

        SpecialRequirement(String kind, int level, int alliesSum, int materialLevel, int levelSum, boolean atLeast, int maxLevel,
                           int materialCount, List<String> materialZones, boolean noSummoner, SpecialRequirement alternative,
                           boolean alone, boolean strongest) {
            this(kind, level, alliesSum, materialLevel, levelSum, atLeast, maxLevel, materialCount, materialZones, noSummoner, alternative,
                    alone, strongest, null);
        }

        SpecialRequirement withAlternative(SpecialRequirement other) {
            return new SpecialRequirement(kind, level, alliesSum, materialLevel, levelSum, atLeast, maxLevel, materialCount, materialZones,
                    noSummoner, other, alone, strongest, summonerZones, moreMaterials);
        }

        /** The summoner is declared from the hand or the Tributos (not the field). */
        boolean declaredSummoner() {
            return summonerZones != null;
        }

        /** A summoner of that level meets it. */
        boolean fits(int summonerLevel) {
            return !noSummoner && (atLeast ? summonerLevel >= level : summonerLevel >= level && summonerLevel <= maxLevel);
        }
    }

    /** A Requerimiento level that any level meets ("2 criaturas en mano"). */
    static final int ANY_LEVEL = 0;

    static boolean levelMeets(CardSlot card, int level) {
        return level == ANY_LEVEL || hasLevel(card, level);
    }

    /** " nivel N", or nothing for any level. */
    static String levelText(int level) {
        return level == ANY_LEVEL ? "" : " nivel " + level;
    }

    /** The marker of a Materialización without a summoner, in the prompt's summoner. */
    static final String NO_SUMMONER = "-";

    static SpecialRequirement specialRequirement(CardSlot card) {
        String kind = card.getSpecialSummonKind() == null ? "" : card.getSpecialSummonKind().toLowerCase(Locale.ROOT);
        if (!List.of("evolution", "promotion", "materialization").contains(kind) || card.getRequirement() == null) return null;
        String text = card.getRequirement().toLowerCase(Locale.ROOT).trim();
        // "(criatura nivel 3) o (criatura nivel 2 + material nivel 2 en tu mano o campo)"
        Matcher either = Pattern.compile("^\\((.*)\\)\\s*o\\s*\\((.*)\\)\\.?$").matcher(text);
        if (either.find()) {
            SpecialRequirement first = parseRequirement(kind, either.group(1));
            SpecialRequirement second = parseRequirement(kind, either.group(2));
            return first == null ? second : first.withAlternative(second);
        }
        return parseRequirement(kind, text);
    }

    static SpecialRequirement parseRequirement(String kind, String text) {
        List<String> zones = materialZones(text);
        boolean materialization = "materialization".equals(kind);
        // "criatura nivel 3 en mano + 2 criaturas nivel 3 en tu mano o tributos": the summoner is declared from where it says (not
        // the field) and enters with the others as its materials (Roto-G0)
        // "criatura nivel 3 en mano o campo (invocador) + 1 o más criaturas nivel 3 en mano o campo" (Prototipo XTRM-1N3)
        Matcher declared = Pattern.compile("^(?:invocador|criatura)\\s+ni\\w*\\s*(\\d+)\\s+(en\\s+[^+]*)\\+\\s*(\\d+)(\\s*o\\s*m[aá]s)?\\s*criaturas?\\s*ni\\w*\\s*(\\d+)(.*)$")
                .matcher(text);
        if (materialization && declared.find()) {
            List<String> summonerZones = materialZones(declared.group(2));
            List<String> rest = declared.group(6).isBlank() ? summonerZones : materialZones(declared.group(6));
            int level = Integer.parseInt(declared.group(1));
            return new SpecialRequirement(kind, level, 0, Integer.parseInt(declared.group(5)), 0, false, level, Integer.parseInt(declared.group(3)),
                    rest, true, null, false, false, summonerZones, declared.group(4) != null);
        }
        // "2 criaturas en mano" (Unidad de Seguridad A-5H): one is the summoner declared from there, the rest its materials, of any level
        Matcher anyLevel = Pattern.compile("^(\\d+)\\s*criaturas\\s+(en\\s+[^+]*?)\\.?$").matcher(text);
        if (materialization && anyLevel.find()) {
            List<String> from = materialZones(anyLevel.group(2));
            return new SpecialRequirement(kind, ANY_LEVEL, 0, ANY_LEVEL, 0, false, ANY_LEVEL, Integer.parseInt(anyLevel.group(1)) - 1, from, true, null,
                    false, false, from);
        }
        // "2 criaturas nivel 1 en tu mano o tributos": no summoner
        Matcher many = Pattern.compile("(\\d+)\\s*criaturas\\s*ni\\w*\\s*(\\d+)").matcher(text);
        if (materialization && !text.contains("+") && many.find()) {
            return new SpecialRequirement(kind, 0, 0, Integer.parseInt(many.group(2)), 0, false, 0, Integer.parseInt(many.group(1)), zones, true, null,
                    false, false);
        }
        Matcher level = Pattern.compile("ni[ev]*l\\s*(\\d+)(?:\\s*o\\s*(\\d+))?").matcher(text);
        if (!level.find()) return null;
        int summonerLevel = Integer.parseInt(level.group(1));
        int maxLevel = level.group(2) != null ? Integer.parseInt(level.group(2)) : summonerLevel;
        Matcher sum = Pattern.compile("suman\\s*(\\d+)").matcher(text);
        int alliesSum = "promotion".equals(kind) && sum.find() ? Integer.parseInt(sum.group(1)) : 0;
        int materialLevel = summonerLevel;
        int levelSum = 0;
        int materialCount = 0;
        if (materialization) {
            // "+ material nivel 2" (also with a misprint: "material novel 4", "material nivel en tributos")
            Matcher material = Pattern.compile("\\+\\s*material\\s*\\w*\\s*(\\d+)?").matcher(text);
            if (material.find()) {
                materialCount = 1;
                if (material.group(1) != null) materialLevel = Integer.parseInt(material.group(1));
            }
            // "aliados que sumen 6 en nivel, incluido el invocador nivel 3 o mayor"
            Matcher allies = Pattern.compile("sumen\\s*(\\d+)").matcher(text);
            if (allies.find()) levelSum = Integer.parseInt(allies.group(1));
        }
        boolean atLeast = Pattern.compile("ni[ev]*l\\s*\\d+\\s*o\\s*mayor").matcher(text).find();
        boolean alone = Pattern.compile("no\\s+control\\w*\\s+otras\\s+criaturas").matcher(text).find();
        boolean strongest = Pattern.compile("fuerza\\s+igual\\s+o\\s+mayor\\s+que\\s+la\\s+de\\s+cualquier\\s+otro\\s+aliado").matcher(text).find();
        return new SpecialRequirement(kind, summonerLevel, alliesSum, materialLevel, levelSum, atLeast, maxLevel, materialCount, zones, false, null,
                alone, strongest);
    }

    /** "en campo o mano", "en tu mano o tributos", "en el campo"; when it says none, the Tributos or the field. */
    static List<String> materialZones(String text) {
        Matcher m = Pattern.compile("(?:en|de)\\s+(?:tu\\s+|tus\\s+|el\\s+)?(campo|mano|tributos|descarte)(?:\\s*o\\s+(?:tu\\s+|tus\\s+|el\\s+)?(campo|mano|tributos|descarte))?")
                .matcher(text);
        if (!m.find()) return List.of("tributeZone", "field");
        List<String> zones = new ArrayList<>();
        for (int g = 1; g <= 2; g++) {
            if (m.group(g) == null) continue;
            zones.add(switch (m.group(g)) {
                case "campo" -> "field";
                case "mano" -> "hand";
                case "descarte" -> "discardPile";
                default -> "tributeZone";
            });
        }
        return zones;
    }

    /** The way of the Requerimiento that summoner meets (the printed one or its alternative), or null. */
    static SpecialRequirement optionFor(SpecialRequirement req, CardSlot summoner) {
        if (summoner == null) return req.noSummoner() ? req : null;
        for (int level : levels(summoner)) if (req.fits(level)) return req;
        if (req.alternative() != null) for (int level : levels(summoner)) if (req.alternative().fits(level)) return req.alternative();
        return null;
    }

    static String kindName(String kind) {
        return switch (kind) {
            case "evolution" -> "Evolución";
            case "promotion" -> "Ascenso";
            case "materialization" -> "Materialización";
            case "any" -> "invocación especial";
            default -> "Ritual";
        };
    }

    /** Creatures of the field that can be the summoner: the level asked for, sharing a type or color. */
    static List<CardSlot> specialSummoners(PlayerState player, CardSlot card, SpecialRequirement req) {
        List<CardSlot> creatures = allies(player);
        return player.getField().stream()
                .filter(c -> "creature".equals(c.getCardType()) && c.getLevel() != null && shares(c, card) && optionFor(req, c) != null)
                // "mientras no controles otras criaturas"
                .filter(c -> !req.alone() || creatures.stream().allMatch(o -> o == c))
                // "con fuerza igual o mayor que la de cualquier otro aliado"
                .filter(c -> !req.strongest() || creatures.stream().allMatch(o -> o == c || strength(o) <= strength(c)))
                .toList();
    }

    /**
     * Materialización: the other material, a creature of that level in the Tributos or on the field (not the
     * summoner); with a level sum ("aliados que sumen 6"), the other allies whose levels complete it.
     */
    static List<CardSlot> specialMaterials(PlayerState player, CardSlot card, SpecialRequirement req, CardSlot summoner) {
        // A summoner declared from the hand or the Tributos: the others are its materials
        if (req.declaredSummoner() && summoner != null) {
            return specialMaterials(player, card, req, null).stream().filter(c -> c != summoner).toList();
        }
        SpecialRequirement option = optionFor(req, summoner);
        if (option == null) return List.of();
        if (option.levelSum() > 0) {
            return player.getField().stream()
                    .filter(c -> c != summoner && c != card && "creature".equals(c.getCardType()) && c.getLevel() != null && shares(c, card))
                    .toList();
        }
        List<CardSlot> all = new ArrayList<>();
        for (String z : option.materialZones()) all.addAll(zone(player, z));
        return all.stream()
                .filter(c -> c != summoner && c != card && "creature".equals(c.getCardType()) && c.getLevel() != null
                        && levelMeets(c, option.materialLevel()) && shares(c, card))
                .toList();
    }

    /** The summoner has what else the Requerimiento needs (always, but for Materialización). */
    static boolean materialsPossible(PlayerState player, CardSlot card, SpecialRequirement req, CardSlot summoner) {
        if (!"materialization".equals(req.kind())) return true;
        SpecialRequirement option = optionFor(req, summoner);
        if (option == null) return false;
        List<CardSlot> materials = specialMaterials(player, card, req, summoner);
        return option.levelSum() > 0 ? levels(summoner).stream().anyMatch(l -> option.levelSum() > l && sumsExactly(materials, option.levelSum() - l))
                : materials.size() >= option.materialCount();
    }

    /**
     * "invocador nivel N en tu mano o tributos": the creatures of those zones that can be it, sharing a type or color, with enough
     * others left to be its materials.
     */
    static List<CardSlot> declaredSummoners(PlayerState player, CardSlot card, SpecialRequirement req) {
        List<CardSlot> all = new ArrayList<>();
        for (String z : req.summonerZones()) all.addAll(zone(player, z));
        return all.stream()
                .filter(c -> c != card && "creature".equals(c.getCardType()) && c.getLevel() != null && levelMeets(c, req.level()) && shares(c, card))
                .filter(c -> specialMaterials(player, card, req, c).size() >= req.materialCount())
                .toList();
    }

    /** The summoner declared from the hand or the Tributos, wherever it is now. */
    static CardSlot findDeclared(PlayerState player, SpecialRequirement req, String id) {
        for (String z : req.summonerZones()) {
            CardSlot c = find(zone(player, z), id);
            if (c != null) return c;
        }
        return null;
    }

    /** The materials chosen meet the Requerimiento: one of the level asked for, or those completing the level sum. */
    static String checkMaterials(SpecialRequirement req, CardSlot summoner, List<CardSlot> materials) {
        if (!"materialization".equals(req.kind())) return null;
        SpecialRequirement option = optionFor(req, summoner);
        if (option == null) return "Ese invocador no cumple el Requerimiento";
        if (option.levelSum() > 0) {
            List<CardSlot> all = new ArrayList<>(materials);
            all.add(summoner);
            int sum = all.stream().mapToInt(CardSlot::getLevel).sum();
            return addsUpTo(all, option.levelSum()) ? null : "Los niveles deben sumar exactamente " + option.levelSum() + " con el invocador (suman " + sum + ")";
        }
        return materials.size() == option.materialCount() ? null
                : "Elige " + option.materialCount() + " " + plural(option.materialCount(), "material", "materiales");
    }

    /** The choice of the materials of a Materialización. */
    static String materialInstruction(SpecialRequirement req, CardSlot card, CardSlot summoner) {
        SpecialRequirement option = optionFor(req, summoner);
        if (option == null) option = req;
        String zones = zonesText(option.materialZones());
        if (req.declaredSummoner() && summoner != null) {
            return "Elige " + req.materialCount() + " " + plural(req.materialCount(), "criatura", "criaturas") + levelText(req.materialLevel()) + " de " + zones
                    + ": con " + name(summoner) + " (el invocador) se convierten en materiales de " + name(card) + ", que entra en tu retaguardia.";
        }
        if (summoner == null) {
            return "Elige " + option.materialCount() + " criaturas" + levelText(option.materialLevel()) + " de " + zones + ": se convierten en materiales de "
                    + name(card) + ", que entra en tu retaguardia.";
        }
        return option.levelSum() > 0
                ? "Elige los aliados que se convierten en materiales de " + name(card) + ": con " + name(summoner) + " (nivel " + summoner.getLevel()
                        + ") sus niveles deben sumar exactamente " + option.levelSum() + "."
                : "Elige el material: " + option.materialCount() + " criatura" + levelText(option.materialLevel()) + " de " + zones
                        + ". Se convierte en material de " + name(card) + " junto a " + name(summoner) + ".";
    }

    /** "tu mano o tus tributos". */
    static String zonesText(List<String> zones) {
        return String.join(" o ", zones.stream().map(z -> switch (z) {
            case "field" -> "tu campo";
            case "hand" -> "tu mano";
            default -> "tus tributos";
        }).toList());
    }

    /** The card can be special summoned from where it is: the hand, or the Tributos when it says so (Falanx). */
    static CardSlot specialSource(PlayerState player, String instanceId) {
        CardSlot card = find(player.getHand(), instanceId);
        if (card != null) return card;
        card = find(player.getTributeZone(), instanceId);
        return card != null && CardEffects.specialFromTributes(card) ? card : null;
    }

    /** Cards a player can special summon now (any kind), from the hand or the Tributos, for the board. */
    public static List<String> specialSummonable(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null || room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || !userId.equals(room.getCurrentTurnUserId())
                || !"MAIN".equals(room.getCurrentPhase()) || room.isEngineBusy()) return List.of();
        List<CardSlot> cards = new ArrayList<>(player.getHand());
        player.getTributeZone().stream().filter(CardEffects::specialFromTributes).forEach(cards::add);
        return cards.stream().filter(c -> "creature".equals(c.getCardType()) && canSpecial(room, userId, c)).map(CardSlot::getInstanceId).toList();
    }

    /** Ascenso: "mientras tus aliados suman N o más en nivel". */
    static boolean alliesAddUp(PlayerState player, SpecialRequirement req) {
        return allies(player).stream().mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum() >= req.alliesSum();
    }

    /**
     * "mientras controlas al menos N criaturas" in a Requerimiento (Explorador Temerario: "criatura nivel 1, mientras
     * controlas al menos 2 criaturas"): how many creatures you must control, the summoner among them; 0 without it.
     */
    static int creaturesNeeded(CardSlot card) {
        if (card.getRequirement() == null) return 0;
        Matcher m = Pattern.compile("controlas\\s+al\\s+menos\\s+(\\d+)\\s+criaturas?").matcher(card.getRequirement().toLowerCase(Locale.ROOT));
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    static boolean enoughCreatures(PlayerState player, CardSlot card) {
        return player.getField().stream().filter(c -> "creature".equals(c.getCardType())).count() >= creaturesNeeded(card);
    }

    /** Whether the card can be summoned now by its special summon (any kind), for the board's legal actions. */
    public static boolean canSpecial(GameRoom room, String userId, CardSlot card) {
        return summoningAs(card, () -> canSpecialAs(room, userId, card));
    }

    static boolean canSpecialAs(GameRoom room, String userId, CardSlot card) {
        if ("ritual".equalsIgnoreCase(card.getSpecialSummonKind())) return canRitual(room, userId, card);
        PlayerState player = room.getPlayer(userId);
        SpecialRequirement req = specialRequirement(card);
        if (player == null || req == null || card.getSpecialCost() == null) return false;
        if (!canPay(room, userId, card.getSpecialCost() - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, null))) return false;
        if ("promotion".equals(req.kind()) && !alliesAddUp(player, req)) return false;
        if (!enoughCreatures(player, card)) return false;
        if (req.declaredSummoner()) return !declaredSummoners(player, card, req).isEmpty();
        if (req.noSummoner()) return specialMaterials(player, card, req, null).size() >= req.materialCount();
        return specialSummoners(player, card, req).stream().anyMatch(s -> materialsPossible(player, card, req, s));
    }

    /**
     * "Cuando fueras a invocar esta carta, si utilizas al menos N criaturas en tu campo: reduce el coste especial en M" (Prototipo XTRM-1N3):
     * the summoner and the materials that come from the field. Designer ruling 2026-10-31.
     */
    static int fieldPiecesDiscount(PlayerState player, CardSlot card, CardSlot summoner, List<CardSlot> materials) {
        CardEffects.FieldPiecesDiscount d = CardEffects.fieldPiecesDiscount(card);
        if (d == null) return 0;
        long used = (summoner != null && player.getField().contains(summoner) ? 1 : 0) + materials.stream().filter(player.getField()::contains).count();
        return used >= d.pieces() ? d.reduce() : 0;
    }

    /** That discount, when the field could give the pieces (to tell whether the summon is affordable before they are chosen). */
    static int possibleFieldPiecesDiscount(PlayerState player, CardSlot card) {
        CardEffects.FieldPiecesDiscount d = CardEffects.fieldPiecesDiscount(card);
        return d != null && allies(player).size() >= d.pieces() ? d.reduce() : 0;
    }

    /** Creatures an effect lets be summoned by a special summon of that kind, from that zone. */
    static List<CardSlot> specialTargets(GameRoom room, PendingEffect effect, SpecialRule special) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        return specialZones(player, special).stream()
                .filter(c -> c != find(player.getField(), effect.getSourceInstanceId()))
                // "invoca especialmente" (kind "any"): by the method the creature has, other than Ritual (its own rules)
                .filter(c -> ("any".equals(special.kind()) ? c.getSpecialSummonKind() != null && !"ritual".equalsIgnoreCase(c.getSpecialSummonKind())
                        : special.kind().equalsIgnoreCase(c.getSpecialSummonKind())) && c.getSpecialCost() != null)
                .filter(special.filter()::matches)
                .filter(c -> summoningAs(c, () -> {
                    SpecialRequirement req = specialRequirement(c);
                    if (req == null || !canPay(room, player.getUserId(), Math.max(0, c.getSpecialCost() - special.reduce()
                            - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), c, null)))) return false;
                    if ("promotion".equals(req.kind()) && !alliesAddUp(player, req)) return false;
                    if (!enoughCreatures(player, c)) return false;
                    return specialSummoners(player, c, req).stream().anyMatch(s -> summonerAllowed(special, s, effect)
                            && materialsPossible(player, c, req, s));
                }))
                .toList();
    }

    /** "esta carta puede invocar…" (only this card), "otro aliado…" (not this card) or "1 aliado…" (any). */
    static boolean summonerAllowed(SpecialRule special, CardSlot summoner, PendingEffect effect) {
        boolean self = summoner.getInstanceId().equals(effect.getSourceInstanceId());
        return switch (special.summoner()) {
            case "self" -> self;
            case "other" -> !self;
            // "a un aliado para que invoque": the ally chosen before (any, while it is not chosen yet)
            case "subject" -> effect.getSubjectId() == null || summoner.getInstanceId().equals(effect.getSubjectId());
            // "1 aliado nivel N puede invocar…"
            default -> !special.summoner().startsWith("level:")
                    || Integer.valueOf(special.summoner().substring(6)).equals(summoner.getLevel());
        };
    }

    /** The level of the card whose effect caused this trigger ("reduciendo su coste en el nivel de dicho aliado"). */
    static int triggerSourceLevel(GameRoom room, PendingEffect effect) {
        GameEvent trigger = triggerEvent(room, effect);
        Object by = trigger == null || trigger.getData() == null ? null : trigger.getData().get("byCard");
        CardSlot source = by == null ? null : findAnywhere(room, trigger.getPlayerId(), (String) by);
        return source == null || source.getLevel() == null ? 0 : source.getLevel();
    }

    /** The zones a special summon by effect takes the creature from ("de tu mano o tributos"). */
    static List<CardSlot> specialZones(PlayerState player, SpecialRule special) {
        List<CardSlot> all = new ArrayList<>();
        for (String z : special.zone().split(",")) all.addAll(zone(player, z.trim()));
        return all;
    }

    static String zonesName(SpecialRule special) {
        return String.join(" o ", Arrays.stream(special.zone().split(",")).map(z -> zoneName(z.trim())).toList());
    }
}
