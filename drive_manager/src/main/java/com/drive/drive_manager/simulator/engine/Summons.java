package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.FloatingEffect;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.drive.drive_manager.simulator.engine.EffectEngine.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Steps.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** Summons and playing cards by the rules: normal summon, playing utilities and structures, Ritual, Evolución, Ascenso and Materialización (rule 9), from the hand or ordered by an effect. */
public final class Summons {

    private Summons() {}

    // ------------------------------------------------------------------ Normal summon and playing cards

    /**
     * Normal summon (rules 6.2.1.1, 9.1.0.1): taking a creature from the hand to the board without an effect.
     * Pays its cost in gems, enters the Retaguardia and fires the summon triggers.
     *
     * @return an error message for the player, or null when done
     */
    public static String normalSummon(GameRoom room, String userId, String instanceId, Double x, Double y) {
        String error = checkMainPhase(room, userId, "invocar");
        if (error != null) return error;

        PlayerState player = room.getPlayer(userId);
        CardSlot card = player == null ? null : find(player.getHand(), instanceId);
        if (card == null) return "La carta no está en tu mano";
        if (!"creature".equals(card.getCardType())) return "Solo las criaturas se invocan";
        if (card.getCost() == null) return "Esta carta no tiene coste normal: no puede invocarse normalmente";

        boolean advantage = starterAdvantage(room, userId, card, card.getCost());
        int cost = card.getCost() - (advantage ? 1 : 0);
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + cost;
        pay(room, userId, cost);
        if (advantage) room.setStarterAdvantageUserId(null);

        player.getHand().remove(card);
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(x != null ? x : 50.0);
        // Normal summons enter the Retaguardia (the lower half of the owner's field); Asalto lets it enter the Línea frontal, exhausted
        boolean assault = y != null && y < 55 && keywordLevel(card, "Asalto") != null;
        card.setY(assault ? y : y != null && y >= 55 ? y : 75.0);
        if (assault) card.setTapped(true);
        player.getField().add(card);

        record(room, GameEvent.NORMAL_SUMMON, userId, card, "hand", "field", null, Map.of("cost", cost),
                player.getUsername() + " invocó " + name(card) + " pagando " + gemsText(cost) + (advantage ? " (ventaja del iniciador)" : ""));
        offerAssault(room, player, card);
        advance(room);
        return null;
    }

    /** Plays a utility or structure from the hand (rules 6.2.2, 6.2.3): pays its cost, enters the Retaguardia. */
    public static String playByPlayer(GameRoom room, String userId, String instanceId, Double x, Double y) {
        String error = checkMainPhase(room, userId, "jugar cartas");
        if (error != null) return error;
        PlayerState player = room.getPlayer(userId);
        CardSlot card = player == null ? null : find(player.getHand(), instanceId);
        if (card == null) return "La carta no está en tu mano";
        if (!"utility".equals(card.getCardType()) && !"structure".equals(card.getCardType())) return "Solo las utilidades y estructuras se juegan";
        // Only one structure can be played normally per turn (by effect or from a life it does not count)
        boolean structure = "structure".equals(card.getCardType());
        if (structure && structurePlayed(room, userId)) return "Solo puedes jugar una estructura por turno";
        String blocked = Chain.cannotActivateOnPlay(room, userId, card);
        if (blocked != null) return blocked;
        int cost = CardEffects.playCost(room, userId, card);
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + cost;
        pay(room, userId, cost);
        if (structure) room.getUsedThisTurn().add(STRUCTURE_PLAYED + userId);
        play(room, player, card, cost, x, y, null);
        advance(room);
        return null;
    }

    /** Utilities in the player's hand whose "En Juego" cannot be activated now: the board does not offer to play them. */
    public static List<String> unplayable(GameRoom room, String userId) {
        PlayerState player = room.getPlayer(userId);
        if (player == null || room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return List.of();
        return new ArrayList<>(player.getHand()).stream().filter(c -> Chain.cannotActivateOnPlay(room, userId, c) != null)
                .map(CardSlot::getInstanceId).toList();
    }

    /** The player already played a structure normally this turn. */
    public static boolean structurePlayed(GameRoom room, String userId) {
        return room.getUsedThisTurn().contains(STRUCTURE_PLAYED + userId);
    }

    static void play(GameRoom room, PlayerState player, CardSlot card, int cost, Double x, Double y, PendingEffect effect) {
        player.getHand().remove(card);
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(x != null ? x : 50.0);
        card.setY(y != null && y >= 55 ? y : 75.0);
        player.getField().add(card);
        record(room, GameEvent.PLAY, player.getUserId(), card, "hand", "field", effect, Map.of("cost", cost),
                player.getUsername() + " juega " + name(card) + (cost == 0 ? " sin pagar su coste" : " pagando " + gemsText(cost))
                        + (effect != null ? " (" + effect.getSourceName() + ")" : ""));
    }

    static String checkMainPhase(GameRoom room, String userId, String what) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return "La partida no está en curso";
        if (!userId.equals(room.getCurrentTurnUserId())) return "Solo puedes " + what + " durante tu turno";
        if (!"MAIN".equals(room.getCurrentPhase())) return "Solo puedes " + what + " en la fase principal";
        if (room.isEngineBusy()) return room.getCombat() != null ? "Hay un combate en curso" : "Hay un efecto resolviéndose";
        return null;
    }

    /**
     * A special summon done on the board (Ritual, Materialización, Evolución, Ascenso): records it and fires
     * the summon triggers. A Ritual sacrifices the summoner (rule 9.3.0.3): it counts as destroyed, so its
     * "En Destrucción" effects trigger too, once the whole Ritual is done (rule 9.3.0.7).
     */
    public static void specialSummoned(GameRoom room, String userId, CardSlot summoned, CardSlot summoner, String summonType, String logText) {
        PlayerState player = room.getPlayer(userId);
        if (player == null) return;
        if ("ritual".equals(summonType)) {
            record(room, GameEvent.SACRIFICE, userId, summoner, "field", "tributeZone", null,
                    Map.of("cause", CAUSE_RITUAL, "ownerId", userId), null);
        } else if ("promotion".equals(summonType)) {
            // Ascenso only tributes the summoner: not a sacrifice nor a destruction (rule 9.6.0.2)
            record(room, GameEvent.TRIBUTE, userId, summoner, "field", "tributeZone", null, null, null);
        }
        record(room, GameEvent.SPECIAL_SUMMON, userId, summoned, null, "field", null,
                Map.of("summonType", summonType == null ? "special" : summonType), logText);
        afterSpecialSummon(room, player, summoned, null);
        advance(room);
    }

    /**
     * Rule 6.2.1.4, "ventaja del iniciador": once per game the second player summons an Iniciador paying 1 less
     * (a reduction by the rules, not by an effect). It is used whenever it lowers a cost.
     */
    static boolean starterAdvantage(GameRoom room, String userId, CardSlot card, Integer cost) {
        return card.isStarter() && cost != null && cost > 0 && userId.equals(room.getStarterAdvantageUserId());
    }

    /**
     * Rule 4.0.7.3: right after a special summon, before anything else, the creature may move up to the Línea
     * frontal. Then the regulatory draw (rule 9.1.0.4); only after both do its triggers go on.
     */
    static void afterSpecialSummon(GameRoom room, PlayerState player, CardSlot card, PendingEffect effect) {
        if ("creature".equals(card.getCardType()) && !inFrontLine(card) && find(player.getField(), card.getInstanceId()) != null) {
            rulesPrompt(room, player.getUserId(), PURPOSE_SPECIAL_FRONT, name(card) + " · Línea frontal",
                    "¿Mueves a " + name(card) + " a tu línea frontal? Después robas 1 carta por la invocación.",
                    "A la línea frontal", List.of(card), List.of(card));
            room.getPrompt().setKind(Prompt.Kind.CONFIRM);
            room.getPrompt().setSubjectId(card.getInstanceId());
            if (effect != null) room.getPrompt().setEffectId(effect.getId());
            return;
        }
        regulatoryDraw(room, player, effect);
    }

    /**
     * "Todos Los Turnos: cuando fueras a invocar especialmente por X, puedes agotar esta carta para reducir el coste especial en 1"
     * (Bosque Frondoso and the like): optional, so it is asked before the summon goes on. The answer is kept for this summon.
     */
    static boolean askTap(GameRoom room, PlayerState player, CardSlot card, String kind, String summonerId) {
        CostModifiers.Use use = CostModifiers.unansweredTap(room, player, CostModifiers.Event.SPECIAL_SUMMON, kind, card);
        if (use == null) return false;
        rulesPrompt(room, player.getUserId(), PURPOSE_COST_TAP, name(use.holder()) + " · " + use.modifier().label(),
                "¿Agotas " + name(use.holder()) + " para reducir en " + use.modifier().reduce() + " el coste especial de " + name(card) + "?",
                "Agotar", List.of(use.holder()), List.of(use.holder()));
        room.getPrompt().setKind(Prompt.Kind.CONFIRM);
        room.getPrompt().setSubjectId(card.getInstanceId());
        room.getPrompt().setSummonerId(summonerId);
        return true;
    }

    // ------------------------------------------------------------------ Ritual from the hand (rule 9.3)

    /**
     * Ritual summon from the hand (rule 9.3): pays the special cost and uses creatures that meet the
     * Requerimiento and share a type or color with it; the summoner is sacrificed and the creature takes its
     * place. {@code summonerId} is the creature it was dropped onto, if any; whatever is still missing is asked
     * with a prompt, and nothing is paid until it is done.
     */
    public static String ritualByPlayer(GameRoom room, String userId, String instanceId, String summonerId) {
        String error = checkMainPhase(room, userId, "invocar");
        if (error != null) return error;
        PlayerState player = room.getPlayer(userId);
        CardSlot card = player == null ? null : find(player.getHand(), instanceId);
        if (card == null) return "La carta no está en tu mano";
        Requirement req = requirement(card);
        if (!"ritual".equalsIgnoreCase(card.getSpecialSummonKind()) || req == null || card.getSpecialCost() == null) {
            return name(card) + " no se invoca por Ritual";
        }
        if (askTap(room, player, card, "ritual", summonerId)) return null;
        int cost = ritualCost(room, userId, card);
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y el coste especial es " + cost;
        List<CardSlot> materials = ritualMaterials(player, card);
        String missing = "Tus criaturas no cumplen el Requerimiento de " + name(card) + ": " + card.getRequirement().trim();

        if (summonerId != null) {
            CardSlot summoner = find(player.getField(), summonerId);
            if (summoner == null) return "El invocador debe ser una criatura de tu campo";
            if (!materials.contains(summoner)) return name(summoner) + " no comparte tipo ni color con " + name(card);
            if (!req.sum()) {
                if (!req.fits(summoner)) return missing;
                handRitual(room, player, card, summoner, List.of());
                return null;
            }
            if (summoner.getLevel() == req.level()) {
                handRitual(room, player, card, summoner, List.of());
                return null;
            }
            List<CardSlot> others = materials.stream().filter(c -> c != summoner).toList();
            if (summoner.getLevel() > req.level() || !sumsExactly(others, req.level() - summoner.getLevel())) return missing;
            ritualPrompt(room, player, card, summoner, others, "Elige las demás criaturas que se sacrifican junto a " + name(summoner)
                    + ": sus niveles deben sumar exactamente " + req.level() + ".", others.size());
            return null;
        }

        // Rule 9.2.0.13: first the summoner (it must meet part of the Requerimiento), then the rest
        List<CardSlot> valid = req.sum() ? summonerCandidates(materials, req.level())
                : materials.stream().filter(req::fits).toList();
        if (valid.isEmpty()) return missing;
        ritualPrompt(room, player, card, null, valid, "Elige el invocador" + (req.sum() ? "" : ": 1 criatura nivel " + req.level() + (req.atLeast() ? " o mayor" : ""))
                + ". Se sacrifica y " + name(card) + " ocupa su lugar y hereda su estado.", 1);
        room.getPrompt().setChosenLabel("Invocador");
        return null;
    }

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
                              boolean alone, boolean strongest, List<String> summonerZones) {
        SpecialRequirement(String kind, int level, int alliesSum, int materialLevel, int levelSum, boolean atLeast, int maxLevel,
                           int materialCount, List<String> materialZones, boolean noSummoner, SpecialRequirement alternative,
                           boolean alone, boolean strongest) {
            this(kind, level, alliesSum, materialLevel, levelSum, atLeast, maxLevel, materialCount, materialZones, noSummoner, alternative,
                    alone, strongest, null);
        }

        SpecialRequirement withAlternative(SpecialRequirement other) {
            return new SpecialRequirement(kind, level, alliesSum, materialLevel, levelSum, atLeast, maxLevel, materialCount, materialZones,
                    noSummoner, other, alone, strongest, summonerZones);
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

    private static SpecialRequirement parseRequirement(String kind, String text) {
        List<String> zones = materialZones(text);
        boolean materialization = "materialization".equals(kind);
        // "criatura nivel 3 en mano + 2 criaturas nivel 3 en tu mano o tributos": the summoner is declared from where it says (not
        // the field) and enters with the others as its materials (Roto-G0)
        Matcher declared = Pattern.compile("^(?:invocador|criatura)\\s+ni\\w*\\s*(\\d+)\\s+(en\\s+[^+]*)\\+\\s*(\\d+)\\s*criaturas?\\s*ni\\w*\\s*(\\d+)(.*)$")
                .matcher(text);
        if (materialization && declared.find()) {
            List<String> summonerZones = materialZones(declared.group(2));
            List<String> rest = declared.group(5).isBlank() ? summonerZones : materialZones(declared.group(5));
            int level = Integer.parseInt(declared.group(1));
            return new SpecialRequirement(kind, level, 0, Integer.parseInt(declared.group(4)), 0, false, level, Integer.parseInt(declared.group(3)),
                    rest, true, null, false, false, summonerZones);
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
    private static List<String> materialZones(String text) {
        Matcher m = Pattern.compile("en\\s+(?:tu\\s+|tus\\s+|el\\s+)?(campo|mano|tributos)(?:\\s*o\\s+(?:tu\\s+|tus\\s+|el\\s+)?(campo|mano|tributos))?")
                .matcher(text);
        if (!m.find()) return List.of("tributeZone", "field");
        List<String> zones = new ArrayList<>();
        for (int g = 1; g <= 2; g++) {
            if (m.group(g) == null) continue;
            zones.add(switch (m.group(g)) {
                case "campo" -> "field";
                case "mano" -> "hand";
                default -> "tributeZone";
            });
        }
        return zones;
    }

    /** The way of the Requerimiento that summoner meets (the printed one or its alternative), or null. */
    static SpecialRequirement optionFor(SpecialRequirement req, CardSlot summoner) {
        if (summoner == null) return req.noSummoner() ? req : null;
        int level = summoner.getLevel() == null ? -1 : summoner.getLevel();
        if (req.fits(level)) return req;
        return req.alternative() != null && req.alternative().fits(level) ? req.alternative() : null;
    }

    static String kindName(String kind) {
        return switch (kind) {
            case "evolution" -> "Evolución";
            case "promotion" -> "Ascenso";
            case "materialization" -> "Materialización";
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
                        && c.getLevel() == option.materialLevel() && shares(c, card))
                .toList();
    }

    /** The summoner has what else the Requerimiento needs (always, but for Materialización). */
    static boolean materialsPossible(PlayerState player, CardSlot card, SpecialRequirement req, CardSlot summoner) {
        if (!"materialization".equals(req.kind())) return true;
        SpecialRequirement option = optionFor(req, summoner);
        if (option == null) return false;
        List<CardSlot> materials = specialMaterials(player, card, req, summoner);
        return option.levelSum() > 0 ? sumsExactly(materials, option.levelSum() - summoner.getLevel()) && option.levelSum() > summoner.getLevel()
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
                .filter(c -> c != card && "creature".equals(c.getCardType()) && c.getLevel() != null && c.getLevel() == req.level() && shares(c, card))
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
            int sum = summoner.getLevel() + materials.stream().mapToInt(CardSlot::getLevel).sum();
            return sum == option.levelSum() ? null : "Los niveles deben sumar exactamente " + option.levelSum() + " con el invocador (suman " + sum + ")";
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
            return "Elige " + req.materialCount() + " " + plural(req.materialCount(), "criatura", "criaturas") + " nivel " + req.materialLevel() + " de " + zones
                    + ": con " + name(summoner) + " (el invocador) se convierten en materiales de " + name(card) + ", que entra en tu retaguardia.";
        }
        if (summoner == null) {
            return "Elige " + option.materialCount() + " criaturas nivel " + option.materialLevel() + " de " + zones + ": se convierten en materiales de "
                    + name(card) + ", que entra en tu retaguardia.";
        }
        return option.levelSum() > 0
                ? "Elige los aliados que se convierten en materiales de " + name(card) + ": con " + name(summoner) + " (nivel " + summoner.getLevel()
                        + ") sus niveles deben sumar exactamente " + option.levelSum() + "."
                : "Elige el material: " + option.materialCount() + " criatura nivel " + option.materialLevel() + " de " + zones
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

    /** Whether the card can be summoned now by its special summon (any kind), for the board's legal actions. */
    public static boolean canSpecial(GameRoom room, String userId, CardSlot card) {
        if ("ritual".equalsIgnoreCase(card.getSpecialSummonKind())) return canRitual(room, userId, card);
        PlayerState player = room.getPlayer(userId);
        SpecialRequirement req = specialRequirement(card);
        if (player == null || req == null || card.getSpecialCost() == null) return false;
        if (!canPay(room, userId, card.getSpecialCost() - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, null))) return false;
        if ("promotion".equals(req.kind()) && !alliesAddUp(player, req)) return false;
        if (req.declaredSummoner()) return !declaredSummoners(player, card, req).isEmpty();
        if (req.noSummoner()) return specialMaterials(player, card, req, null).size() >= req.materialCount();
        return specialSummoners(player, card, req).stream().anyMatch(s -> materialsPossible(player, card, req, s));
    }

    /**
     * Special summon from the hand by the rules (rule 9): Ritual, Evolución, Ascenso or Materialización, as the
     * card says. {@code summonerId} is the creature it was dropped onto; whatever is missing is asked for.
     */
    public static String specialByPlayer(GameRoom room, String userId, String instanceId, String summonerId) {
        PlayerState player = room.getPlayer(userId);
        CardSlot card = player == null ? null : specialSource(player, instanceId);
        if (card != null && "ritual".equalsIgnoreCase(card.getSpecialSummonKind())) return ritualByPlayer(room, userId, instanceId, summonerId);
        String error = checkMainPhase(room, userId, "invocar");
        if (error != null) return error;
        if (card == null) return "La carta no está en tu mano";
        String fromZone = find(player.getHand(), card.getInstanceId()) != null ? "hand" : "tributeZone";
        SpecialRequirement req = specialRequirement(card);
        if (req == null || card.getSpecialCost() == null) return name(card) + " no tiene una invocación especial que el motor conozca";
        String kind = kindName(req.kind());
        if (!canPay(room, userId, card.getSpecialCost() - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, null)))
            return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y el coste especial es " + card.getSpecialCost();
        if (askTap(room, player, card, req.kind(), summonerId)) return null;
        if ("promotion".equals(req.kind()) && !alliesAddUp(player, req)) return "Para el Ascenso tus aliados deben sumar " + req.alliesSum() + " o más en nivel";
        String missing = "Tus criaturas no cumplen el Requerimiento de " + name(card) + ": " + card.getRequirement().trim();
        if (req.declaredSummoner()) {
            if (summonerId == null) {
                // Rule 9.2.0.13: the summoner is declared first
                List<CardSlot> summoners = declaredSummoners(player, card, req);
                if (summoners.isEmpty()) return missing;
                List<CardSlot> shown = new ArrayList<>();
                for (String z : req.summonerZones()) shown.addAll(zone(player, z));
                shown.remove(card);
                rulesPrompt(room, userId, PURPOSE_SPECIAL, name(card) + " · " + kind,
                        "Declara el invocador: 1 criatura nivel " + req.level() + " de " + zonesText(req.summonerZones()) + ". Luego eliges "
                                + req.materialCount() + " " + plural(req.materialCount(), "material", "materiales") + ". Si no eliges ninguna, no se invoca.",
                        "Invocador", summoners, shown);
                room.getPrompt().setSubjectId(card.getInstanceId());
                return null;
            }
            CardSlot summoner = findDeclared(player, req, summonerId);
            if (summoner == null || !declaredSummoners(player, card, req).contains(summoner)) return missing;
            List<CardSlot> shown = new ArrayList<>();
            for (String z : req.materialZones()) shown.addAll(zone(player, z));
            shown.remove(card);
            shown.remove(summoner);
            rulesPrompt(room, userId, PURPOSE_SPECIAL, name(card) + " · " + kind, materialInstruction(req, card, summoner), "Material",
                    specialMaterials(player, card, req, summoner), shown);
            room.getPrompt().setMin(req.materialCount());
            room.getPrompt().setMax(req.materialCount());
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(summoner.getInstanceId());
            return null;
        }
        if (req.noSummoner()) {
            List<CardSlot> materials = specialMaterials(player, card, req, null);
            if (materials.size() < req.materialCount()) return missing;
            List<CardSlot> shown = new ArrayList<>();
            for (String z : req.materialZones()) shown.addAll(zone(player, z));
            rulesPrompt(room, userId, PURPOSE_SPECIAL, name(card) + " · " + kind, materialInstruction(req, card, null), "Material", materials, shown);
            room.getPrompt().setMin(req.materialCount());
            room.getPrompt().setMax(req.materialCount());
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(NO_SUMMONER);
            return null;
        }
        List<CardSlot> summoners = specialSummoners(player, card, req).stream().filter(s -> materialsPossible(player, card, req, s)).toList();
        if (summonerId == null) {
            if (summoners.isEmpty()) return missing;
            rulesPrompt(room, userId, PURPOSE_SPECIAL, name(card) + " · " + kind,
                    "Elige el invocador: 1 criatura nivel " + req.level() + (req.atLeast() ? " o mayor" : req.maxLevel() > req.level() ? " o " + req.maxLevel() : "")
                            + (req.alternative() != null ? " (o nivel " + req.alternative().level() + ")" : "") + ". " + name(card)
                            + " ocupa su lugar y hereda su estado. Si no eliges ninguna, no se invoca.",
                    "Invocador", summoners, player.getField());
            room.getPrompt().setSubjectId(card.getInstanceId());
            return null;
        }
        CardSlot summoner = find(player.getField(), summonerId);
        if (summoner == null) return "El invocador debe ser una criatura de tu campo";
        if (!summoners.contains(summoner)) return shares(summoner, card) ? missing : name(summoner) + " no comparte tipo ni color con " + name(card);
        SpecialRequirement option = optionFor(req, summoner);
        if ("materialization".equals(req.kind()) && (option.materialCount() > 0 || option.levelSum() > 0)) {
            List<CardSlot> materials = specialMaterials(player, card, req, summoner);
            List<CardSlot> shown = new ArrayList<>();
            for (String z : option.materialZones()) shown.addAll(zone(player, z));
            rulesPrompt(room, userId, PURPOSE_SPECIAL, name(card) + " · " + kind, materialInstruction(req, card, summoner), "Material", materials, shown);
            room.getPrompt().setMin(Math.max(1, option.materialCount()));
            room.getPrompt().setMax(option.levelSum() > 0 ? materials.size() : option.materialCount());
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(summoner.getInstanceId());
            return null;
        }
        if (!canPay(room, userId, card.getSpecialCost() - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, summoner))) {
            return "No tienes gemas suficientes para invocarla con " + name(summoner);
        }
        handSpecial(room, player, card, req, summoner, List.of(), fromZone);
        return null;
    }

    /**
     * Carries out the special summon (rules 9.4–9.6): pays the special cost, the creature takes the summoner's
     * place and state; the summoner is tributed (Ascenso), becomes a Recurso (Evolución) or, with the other
     * material, Materiales (Materialización). Then the move up and the regulatory draw.
     */
    static void handSpecial(GameRoom room, PlayerState player, CardSlot card, SpecialRequirement req, CardSlot summoner,
                                    List<CardSlot> materials, String fromZone) {
        // "cuando esta carta fuera a ser ascendida: puede declarar ataque" (Príncipe Charles): asked before the Ascenso
        if ("promotion".equals(req.kind()) && summoner != null && CardEffects.attacksBeforeAscended(summoner) && room.getCombat() == null
                && room.getPendingAscension() == null && inFrontLine(summoner)) {
            List<CardSlot> options = new ArrayList<>(List.of(summoner));
            options.addAll(CombatRules.attackTargets(room, player.getUserId(), summoner));
            PlayerState opponent = room.getPlayer(room.getOpponentId(player.getUserId()));
            List<CardSlot> shown = new ArrayList<>(List.of(summoner));
            if (opponent != null) shown.addAll(opponent.getField());
            rulesPrompt(room, player.getUserId(), PURPOSE_ASCEND_ATTACK, name(summoner) + " va a ser ascendida",
                    "Puede declarar ataque antes: elige " + name(summoner) + " para atacar al oponente, o una criatura enemiga agotada. "
                            + "O ninguna para ascenderla ya.", "Atacar", options, shown);
            room.getPrompt().setMin(0);
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(summoner.getInstanceId());
            return;
        }
        // A cost modifier that asks for its payment (Flambreik's tributes) is asked before the summon (a Reacción applies
        // before its event); only when no materials are being chosen
        CostModifiers.Use use = materials.isEmpty() ? CostModifiers.choice(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, summoner) : null;
        if (use != null) {
            rulesPrompt(room, player.getUserId(), PURPOSE_SUMMONER_DISCOUNT, name(use.holder()) + " · " + use.modifier().label(),
                    CostModifiers.describe(use), "Pagar", CostModifiers.paymentOptions(player, use), player.getTributeZone());
            room.getPrompt().setMax(use.modifier().payment().count());
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(summoner.getInstanceId());
            return;
        }
        specialSummon(room, player, card, req, summoner, materials, fromZone, 0, null);
        advance(room);
    }

    static void specialSummon(GameRoom room, PlayerState player, CardSlot card, SpecialRequirement req, CardSlot summoner,
                                      List<CardSlot> materials, String fromZone, int reduce, PendingEffect effect) {
        String userId = player.getUserId();
        int cost = Math.max(0, card.getSpecialCost() - reduce);
        // Its cost modifiers that ask for no choice ("reduce en 1 el coste especial", "puedes agotar esta carta para reducir…")
        cost -= CostModifiers.applyAutomatic(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, summoner, cost);
        pay(room, userId, cost);
        zone(player, fromZone).remove(card);
        String used;
        if (summoner == null) {
            // No summoner ("2 criaturas nivel 1 en tu mano o tributos"): it enters the Retaguardia, active
            card.setFaceDown(false);
            card.setTapped(false);
            card.setX(50.0);
            card.setY(75.0);
            used = "";
        } else {
            inheritFromSummoner(room, card, summoner);
            player.getField().remove(summoner);
            leaveField(summoner);
            used = name(summoner);
        }
        switch (req.kind()) {
            case "promotion" -> {
                player.getTributeZone().add(summoner);
                record(room, GameEvent.TRIBUTE, userId, summoner, "field", "tributeZone", null, Map.of("cause", "promotion"), null);
            }
            case "evolution" -> card.getResources().add(0, summoner);
            default -> {
                if (summoner != null) card.getMaterials().add(0, summoner);
                for (CardSlot material : materials) {
                    String from = player.getTributeZone().remove(material) ? "tributeZone" : player.getHand().remove(material) ? "hand" : "field";
                    // A creature of the field used as a material loses its state; its attachments go to the Descarte
                    if ("field".equals(from)) removeFromField(player, material);
                    material.setFaceDown(false);
                    card.getMaterials().add(material);
                    used += (used.isEmpty() ? "" : " y ") + name(material);
                }
            }
        }
        player.getField().add(card);
        record(room, GameEvent.SPECIAL_SUMMON, userId, card, fromZone, "field", effect, Map.of("summonType", req.kind(), "cost", cost),
                player.getUsername() + " invoca por " + kindName(req.kind()) + " a " + name(card) + " usando " + used + " y pagando "
                        + gemsText(cost) + (effect != null ? " (" + effect.getSourceName() + ")" : ""));
        afterSpecialSummon(room, player, card, effect);
    }

    /** Creatures an effect lets be summoned by a special summon of that kind, from that zone. */
    static List<CardSlot> specialTargets(GameRoom room, PendingEffect effect, SpecialFromZone special) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        return specialZones(player, special).stream()
                .filter(c -> c != find(player.getField(), effect.getSourceInstanceId()))
                .filter(c -> special.kind().equalsIgnoreCase(c.getSpecialSummonKind()) && c.getSpecialCost() != null)
                .filter(special.filter()::matches)
                .filter(c -> {
                    SpecialRequirement req = specialRequirement(c);
                    if (req == null || !canPay(room, player.getUserId(), Math.max(0, c.getSpecialCost() - special.reduce()
                            - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), c, null)))) return false;
                    if ("promotion".equals(req.kind()) && !alliesAddUp(player, req)) return false;
                    return specialSummoners(player, c, req).stream().anyMatch(s -> summonerAllowed(special, s, effect)
                            && materialsPossible(player, c, req, s));
                })
                .toList();
    }

    /** "esta carta puede invocar…" (only this card), "otro aliado…" (not this card) or "1 aliado…" (any). */
    static boolean summonerAllowed(SpecialFromZone special, CardSlot summoner, PendingEffect effect) {
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

    /** "puede invocar por Ascenso…": the creature (or none), then its summoner. */
    static boolean runSpecialFromZone(GameRoom room, PendingEffect effect, PlayerState player, SpecialFromZone special) {
        CardSlot target = effect.getRitualTargetId() == null ? null : find(specialZones(player, special), effect.getRitualTargetId());
        if (target == null) {
            List<CardSlot> valid = specialTargets(room, effect, special);
            if (valid.isEmpty()) {
                log(room, "No hay criaturas que puedan invocarse por " + kindName(special.kind()) + " (" + effect.getSourceName() + ")", null);
                return false;
            }
            pick(room, effect, "Puedes elegir 1 criatura de tu " + zonesName(special) + " para invocarla por " + kindName(special.kind())
                    + (special.reduce() > 0 ? " reduciendo su coste especial en " + special.reduce() : "") + ", o ninguna.", "Invocar",
                    specialZones(player, special), ids(valid), 1);
            room.getPrompt().setMin(0);
            return true;
        }
        SpecialRequirement req = specialRequirement(target);
        CardSlot summoner = "subject".equals(special.summoner()) ? subject(room, effect) : find(player.getField(), effect.getRitualSummonerId());
        if (summoner == null) {
            List<CardSlot> summoners = specialSummoners(player, target, req).stream()
                    .filter(s -> summonerAllowed(special, s, effect) && materialsPossible(player, target, req, s)).toList();
            pick(room, effect, "Elige el invocador: 1 criatura nivel " + req.level() + (req.atLeast() ? " o mayor" : "") + ". " + name(target)
                            + " ocupa su lugar y hereda su estado.",
                    "Invocador", player.getField(), ids(summoners), 1);
            return true;
        }
        // Materialización: then the material, from the Tributos or the field
        List<CardSlot> materials = specialMaterials(player, target, req, summoner);
        List<CardSlot> shown = new ArrayList<>(player.getTributeZone());
        shown.addAll(player.getField());
        pick(room, effect, materialInstruction(req, target, summoner), "Material", shown, ids(materials), req.levelSum() > 0 ? materials.size() : 1);
        if (req.levelSum() > 0) room.getPrompt().setMin(1);
        return true;
    }

    /** The zones a special summon by effect takes the creature from ("de tu mano o tributos"). */
    static List<CardSlot> specialZones(PlayerState player, SpecialFromZone special) {
        List<CardSlot> all = new ArrayList<>();
        for (String z : special.zone().split(",")) all.addAll(zone(player, z.trim()));
        return all;
    }

    static String zonesName(SpecialFromZone special) {
        return String.join(" o ", Arrays.stream(special.zone().split(",")).map(z -> zoneName(z.trim())).toList());
    }

    /** Whether the card can be summoned by Ritual from the hand now, for the board's legal actions. */
    public static boolean canRitual(GameRoom room, String userId, CardSlot card) {
        PlayerState player = room.getPlayer(userId);
        Requirement req = requirement(card);
        if (player == null || req == null || card.getSpecialCost() == null || !"ritual".equalsIgnoreCase(card.getSpecialSummonKind())) return false;
        if (!canPay(room, userId, ritualCost(room, userId, card))) return false;
        List<CardSlot> materials = ritualMaterials(player, card);
        return req.sum() ? sumsExactly(materials, req.level()) : materials.stream().anyMatch(req::fits);
    }

    static int ritualCost(GameRoom room, String userId, CardSlot card) {
        int cost = card.getSpecialCost() - (starterAdvantage(room, userId, card, card.getSpecialCost()) ? 1 : 0);
        PlayerState player = room.getPlayer(userId);
        return player == null ? cost : Math.max(0, cost - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", card, null));
    }

    static void ritualPrompt(GameRoom room, PlayerState player, CardSlot card, CardSlot summoner, List<CardSlot> valid,
                                     String instruction, int max) {
        rulesPrompt(room, player.getUserId(), PURPOSE_RITUAL, name(card) + " · Ritual",
                instruction + " Si no eliges ninguna, no se invoca.", "Sacrificar", valid, player.getField());
        room.getPrompt().setMax(max);
        room.getPrompt().setSubjectId(card.getInstanceId());
        if (summoner != null) room.getPrompt().setSummonerId(summoner.getInstanceId());
    }

    static void handRitual(GameRoom room, PlayerState player, CardSlot card, CardSlot summoner, List<CardSlot> others) {
        String userId = player.getUserId();
        boolean advantage = starterAdvantage(room, userId, card, card.getSpecialCost());
        int cost = Math.max(0, card.getSpecialCost() - (advantage ? 1 : 0));
        cost -= CostModifiers.applyAutomatic(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", card, summoner, cost);
        pay(room, userId, cost);
        if (advantage) room.setStarterAdvantageUserId(null);
        player.getHand().remove(card);
        List<String> used = new ArrayList<>(List.of(name(summoner)));
        others.forEach(o -> used.add(name(o)));
        ritualSummon(room, player, card, "hand", cost, summoner, others, null,
                player.getUsername() + " invoca por Ritual a " + name(card) + " usando " + String.join(", ", used) + " y pagando "
                        + gemsText(cost) + (advantage ? " (ventaja del iniciador)" : ""));
        advance(room);
    }

    // ------------------------------------------------------------------ Ritual by effect (rule 9.3)

    /** Requerimiento of a Ritual: one creature of level X, or several whose levels add up to X. */
    /**
     * A Ritual's Requerimiento: "criatura nivel N [o mayor] [de fuerza M o menos]" or "criaturas que [en nivel] sumen N".
     */
    record Requirement(boolean sum, int level, boolean atLeast, Integer maxStrength) {
        /** The creature alone meets a single-creature Requerimiento. */
        boolean fits(CardSlot c) {
            if (c.getLevel() == null) return false;
            boolean level = atLeast ? c.getLevel() >= this.level : c.getLevel() == this.level;
            return level && (maxStrength == null || strength(c) <= maxStrength);
        }
    }

    static Requirement requirement(CardSlot card) {
        if (card.getRequirement() == null) return null;
        String text = card.getRequirement().toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("nivel\\s*(\\d+)|sumen\\s*(\\d+)").matcher(text);
        if (!m.find()) return null;
        int level = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
        boolean atLeast = Pattern.compile("nivel\\s*\\d+\\s*o\\s*mayor").matcher(text).find();
        Matcher strength = Pattern.compile("fuerza\\s*(\\d+)\\s*o\\s*menos").matcher(text);
        return new Requirement(text.contains("sum"), level, atLeast, strength.find() ? Integer.valueOf(strength.group(1)) : null);
    }

    /** Creatures that can be the summoner of a level-sum Ritual: the rest of the field can complete the sum exactly. */
    static List<CardSlot> summonerCandidates(List<CardSlot> materials, int level) {
        return materials.stream()
                .filter(s -> s.getLevel() <= level && sumsExactly(materials.stream().filter(c -> c != s).toList(), level - s.getLevel()))
                .toList();
    }

    /** Whether some of the creatures' levels add up exactly to {@code target} ("criaturas que en nivel sumen N"). */
    static boolean sumsExactly(List<CardSlot> cards, int target) {
        if (target < 0) return false;
        boolean[] reachable = new boolean[target + 1];
        reachable[0] = true;
        for (CardSlot c : cards) {
            int level = c.getLevel() == null ? 0 : c.getLevel();
            if (level <= 0) continue;
            for (int t = target; t >= level; t--) if (reachable[t - level]) reachable[t] = true;
        }
        return reachable[target];
    }

    /** Rule 9.2.0.10: the cards used must share a type or a color with the summoned creature. */
    static boolean shares(CardSlot used, CardSlot summoned) {
        boolean color = used.getColors().stream().anyMatch(summoned.getColors()::contains);
        boolean type = used.getClasses().stream().anyMatch(t -> summoned.getClasses().stream().anyMatch(t::equalsIgnoreCase));
        return color || type;
    }

    /** Creatures that may be used for the Ritual of {@code target}. */
    static List<CardSlot> ritualMaterials(PlayerState player, CardSlot target) {
        return player.getField().stream().filter(c -> "creature".equals(c.getCardType()) && c.getLevel() != null && shares(c, target)).toList();
    }

    static boolean ritualFeasible(GameRoom room, PendingEffect effect, RitualFromZone ritual, PlayerState player, CardSlot target) {
        Requirement req = requirement(target);
        if (req == null || target.getSpecialCost() == null) return false;
        if (!canPay(room, player.getUserId(), Math.max(0, target.getSpecialCost() - ritual.reduce()
                - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", target, null)))) return false;
        List<CardSlot> materials = ritualMaterials(player, target);
        if (ritual.selfSummoner()) {
            CardSlot self = find(materials, effect.getSourceInstanceId());
            if (self == null) return false;
            if (!req.sum()) return req.fits(self);
            // The summoner is one of them: the others make up the rest
            return self.getLevel() <= req.level() && sumsExactly(materials.stream().filter(c -> c != self).toList(), req.level() - self.getLevel());
        }
        if (!req.sum()) return materials.stream().anyMatch(req::fits);
        return sumsExactly(materials, req.level());
    }

    static List<CardSlot> ritualTargets(GameRoom room, PendingEffect effect, RitualFromZone ritual) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        return zone(player, ritual.zone()).stream()
                .filter(c -> "creature".equals(c.getCardType()) && "ritual".equalsIgnoreCase(c.getSpecialSummonKind()))
                .filter(c -> ritualFeasible(room, effect, ritual, player, c))
                .toList();
    }

    static boolean runRitual(GameRoom room, PendingEffect effect, PlayerState player, RitualFromZone ritual) {
        CardSlot target = effect.getRitualTargetId() == null ? null : find(zone(player, ritual.zone()), effect.getRitualTargetId());
        if (target == null) {
            effect.setRitualTargetId(null);
            List<CardSlot> valid = ritualTargets(room, effect, ritual);
            if (valid.isEmpty()) {
                log(room, "No hay criaturas que puedan invocarse por Ritual (" + effect.getSourceName() + ")", null);
                return false;
            }
            pick(room, effect, "Elige 1 criatura de tus " + zoneName(ritual.zone()) + " para invocarla por Ritual pagando su coste especial reducido en "
                    + ritual.reduce() + ".", "Invocar", zone(player, ritual.zone()), ids(valid), 1);
            return true;
        }
        Requirement req = requirement(target);
        List<CardSlot> materials = ritualMaterials(player, target);
        if (ritual.selfSummoner() && !req.sum()) {
            performRitual(room, effect, player, target, ritual, find(player.getField(), effect.getSourceInstanceId()), List.of());
            return false;
        }
        // Rule 9.2.0.13: the summoner is declared first; it must meet part of the Requerimiento
        CardSlot summoner = ritual.selfSummoner() ? find(player.getField(), effect.getSourceInstanceId())
                : find(player.getField(), effect.getRitualSummonerId());
        if (summoner == null) {
            effect.setRitualSummonerId(null);
            List<CardSlot> valid = req.sum() ? summonerCandidates(materials, req.level())
                    : materials.stream().filter(req::fits).toList();
            Prompt prompt = prompt(room, effect, Prompt.Kind.PICK, "Elige el invocador" + (req.sum() ? "" : ": 1 criatura nivel " + req.level() + (req.atLeast() ? " o mayor" : ""))
                    + ". Se sacrifica y " + name(target) + " ocupa su lugar y hereda su estado.");
            prompt.setCards(new ArrayList<>(player.getField()));
            prompt.setValidIds(new ArrayList<>(ids(valid)));
            prompt.setMin(1);
            prompt.setMax(1);
            prompt.setChosenLabel("Invocador");
            room.setPrompt(prompt);
            return true;
        }
        if (!req.sum() || summoner.getLevel() == req.level()) {
            performRitual(room, effect, player, target, ritual, summoner, List.of());
            effect.setRitualSummonerId(null);
            return false;
        }
        CardSlot chosenSummoner = summoner;
        List<CardSlot> valid = materials.stream().filter(c -> c != chosenSummoner).toList();
        Prompt prompt = prompt(room, effect, Prompt.Kind.PICK, "Elige las demás criaturas que se sacrifican junto a " + name(summoner)
                + " (el invocador): sus niveles deben sumar exactamente " + (req.level() - summoner.getLevel()) + ".");
        prompt.setCards(new ArrayList<>(player.getField()));
        prompt.setValidIds(new ArrayList<>(ids(valid)));
        prompt.setMin(1);
        prompt.setMax(valid.size());
        prompt.setChosenLabel("Sacrificar");
        room.setPrompt(prompt);
        return true;
    }

    /**
     * Rules 9.2.0.2–9.2.0.7: the summoned creature takes the summoner's zone and its whole game state (active or
     * exhausted, attacker or blocker, strength changes, effects until the end of the turn) but keeps its own printed
     * properties; the summoner's Recursos and Materiales pass to it. Continuous effects are checked again afterwards.
     */
    public static void inheritFromSummoner(GameRoom room, CardSlot target, CardSlot summoner) {
        target.setFaceDown(false);
        target.setX(summoner.getX());
        target.setY(summoner.getY());
        target.setTapped(summoner.isTapped());
        target.setStrengthModifier(summoner.getStrengthModifier());
        target.getTurnKeywords().clear();
        target.getTurnKeywords().addAll(summoner.getTurnKeywords());
        // Its strength is its own: only the abilities gained pass on
        target.getFloating().clear();
        summoner.getFloating().stream().filter(f -> f.keyword() != null).forEach(target.getFloating()::add);
        target.getMaterials().addAll(0, summoner.getMaterials());
        target.getResources().addAll(0, summoner.getResources());
        summoner.setMaterials(new ArrayList<>());
        summoner.setResources(new ArrayList<>());
        Combat c = room.getCombat();
        if (c != null) {
            String from = summoner.getInstanceId();
            String to = target.getInstanceId();
            if (from.equals(c.getAttackerId())) c.setAttackerId(to);
            if (from.equals(c.getBlockerId())) c.setBlockerId(to);
            if (from.equals(c.getTargetId())) c.setTargetId(to);
        }
    }

    /** The Ascenso that waited for its summoner's attack: the creature arrives now and takes its place in the combat. */
    static void completeAscension(GameRoom room) {
        PendingAscension pending = room.getPendingAscension();
        room.setPendingAscension(null);
        PlayerState player = room.getPlayer(pending.playerId());
        if (player == null) return;
        CardSlot card = find(zone(player, pending.fromZone()), pending.cardId());
        CardSlot summoner = find(player.getField(), pending.summonerId());
        if (card == null || summoner == null) {
            log(room, "El Ascenso no se completa: " + (summoner == null ? "el invocador dejó el campo" : "la carta ya no está"), null);
            return;
        }
        specialSummon(room, player, card, specialRequirement(card), summoner, List.of(), pending.fromZone(), 0, null);
    }

    /**
     * Asalto: "en lugar de quedar en tu retaguardia, puedes colocarla agotada en tu línea frontal". It modifies the summon, so it is
     * asked as the creature enters the Retaguardia, before anything reacts to it (not a triggered effect).
     */
    static void offerAssault(GameRoom room, PlayerState player, CardSlot card) {
        if (room.getPrompt() != null || keywordLevel(card, "Asalto") == null || inFrontLine(card)) return;
        rulesPrompt(room, player.getUserId(), PURPOSE_ASSAULT, name(card) + " · Asalto",
                "En lugar de quedar en tu retaguardia, puedes colocarla agotada en tu línea frontal.", "Línea frontal", List.of(card), List.of(card));
        room.getPrompt().setKind(Prompt.Kind.CONFIRM);
        room.getPrompt().setSubjectId(card.getInstanceId());
    }

    /** Ritual by effect (rule 9.3): pays the special cost minus the reduction and summons it. */
    static void performRitual(GameRoom room, PendingEffect effect, PlayerState player, CardSlot target, RitualFromZone ritual,
                                      CardSlot summoner, List<CardSlot> others) {
        int cost = Math.max(0, target.getSpecialCost() - ritual.reduce());
        cost -= CostModifiers.applyAutomatic(room, player, CostModifiers.Event.SPECIAL_SUMMON, "ritual", target, summoner, cost);
        pay(room, player.getUserId(), cost);
        zone(player, ritual.zone()).remove(target);
        effect.setRitualTargetId(null);
        ritualSummon(room, player, target, ritual.zone(), cost, summoner, others, effect,
                player.getUsername() + " invoca por Ritual " + name(target) + " de sus " + zoneName(ritual.zone()) + " usando "
                        + name(summoner) + " y pagando " + gemsText(cost) + " (" + effect.getSourceName() + ")");
    }

    /**
     * Rule 9.3: the summoner's attachments pass to the summoned creature, which takes its place and state; the
     * summoner and the other creatures used are sacrificed. Then the move up and the regulatory draw.
     */
    static void ritualSummon(GameRoom room, PlayerState player, CardSlot target, String fromZone, int cost,
                                     CardSlot summoner, List<CardSlot> others, PendingEffect effect, String logText) {
        inheritFromSummoner(room, target, summoner);
        destroy(room, summoner, player, true, CAUSE_RITUAL, player.getUserId(), null, effect);
        for (CardSlot other : others) destroy(room, other, player, true, CAUSE_RITUAL, player.getUserId(), null, effect);
        player.getField().add(target);
        record(room, GameEvent.SPECIAL_SUMMON, player.getUserId(), target, fromZone, "field", effect,
                Map.of("summonType", "ritual", "cost", cost), logText);
        afterSpecialSummon(room, player, target, effect);
    }

    /** Summon by effect (rule 9.1.0.2): pays the cost, enters the Retaguardia, then the regulatory draw (9.1.0.4). */
    static void summonByEffect(GameRoom room, PendingEffect effect, PlayerState player, CardSlot card, String fromZone, int reduce,
                                       boolean toFront) {
        int cost = Math.max(0, card.getCost() - reduce);
        pay(room, player.getUserId(), cost);
        zone(player, fromZone).remove(card);
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(50.0);
        // By effect it enters the Retaguardia, unless the effect says "en tu línea frontal"
        card.setY(toFront ? 25.0 : 75.0);
        player.getField().add(card);
        record(room, GameEvent.EFFECT_SUMMON, player.getUserId(), card, fromZone, "field", effect, Map.of("cost", cost),
                player.getUsername() + " invoca " + name(card) + ("hand".equals(fromZone) ? " de su mano" : " de sus " + zoneName(fromZone))
                        + " pagando " + gemsText(cost)
                        + " (" + effect.getSourceName() + ")");
        regulatoryDraw(room, player, effect);
        offerAssault(room, player, card);
    }

    static void regulatoryDraw(GameRoom room, PlayerState player, PendingEffect effect) {
        if (player.getDeck().isEmpty()) return;
        drawOne(room, player, effect, true);
        log(room, player.getUsername() + " roba 1 carta por la invocación", room.getEventSeq());
    }

    static void drawOne(GameRoom room, PlayerState player, PendingEffect effect, boolean regulatory) {
        CardSlot card = player.getDeck().remove(0);
        card.setFaceDown(false);
        player.getHand().add(card);
        record(room, GameEvent.DRAW, player.getUserId(), card, "deck", "hand", effect, regulatory ? Map.of("regulatory", true) : null, null);
    }
}
