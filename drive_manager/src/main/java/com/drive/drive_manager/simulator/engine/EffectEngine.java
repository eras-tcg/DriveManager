package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectScript.Limit;
import com.drive.drive_manager.simulator.engine.EffectStep.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules engine of the simulator (Reglamento v1.1). Grows card by card: actions it knows are carried out by
 * the rules; everything else is still played by hand on the board.
 *
 * Flow: an action produces timeline events → once no effect is in the middle of its resolution, those events
 * trigger effects (rule 11.6.0.4) → triggered effects are activated into the chain in rules order (optional
 * ones ask first) → the chain resolves last-in first-out, step by step; a step that needs a choice pauses
 * with a {@link Prompt} until the player answers. Events of a resolution open a new window when it ends.
 * A combat (rule 10) goes on step by step whenever the chain is empty.
 */
public final class EffectEngine {

    private static final int TIMELINE_LIMIT = 2000;
    private static final int LOG_LIMIT = 300;

    /** Why a card was destroyed (rule 12.0.0.1). MANUAL: done from the board, outside the engine's rules. */
    public static final String CAUSE_EFFECT = "EFFECT";
    public static final String CAUSE_BATTLE = "BATTLE";
    public static final String CAUSE_RITUAL = "RITUAL";
    public static final String CAUSE_MANUAL = "MANUAL";

    private static final String PURPOSE_BLOCK = "BLOCK";
    private static final String PURPOSE_PROVOKE = "PROVOKE";
    private static final String PURPOSE_ADVANCE = "ADVANCE";
    private static final String PURPOSE_RITUAL = "RITUAL";
    /** Evolución, Ascenso and Materialización from the hand: the summoner, then (Materialización) the material. */
    private static final String PURPOSE_SPECIAL = "SPECIAL";
    /** Key in the room's per-turn usage: the player already played a structure normally this turn. */
    private static final String STRUCTURE_PLAYED = "structure-played|";
    private static final String PURPOSE_SPECIAL_FRONT = "SPECIAL_FRONT";
    private static final String PURPOSE_REINFORCE = "REINFORCE";
    private static final String PURPOSE_LIFE_PLAY = "LIFE_PLAY";
    private static final String PURPOSE_ORDER = "ORDER";
    private static final String PURPOSE_OPTIONAL = "OPTIONAL";
    private static final String PURPOSE_PRIORITY = "PRIORITY";

    private EffectEngine() {}

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
        // Normal summons enter the Retaguardia: the lower half of the owner's field
        card.setY(y != null && y >= 55 ? y : 75.0);
        player.getField().add(card);

        record(room, GameEvent.NORMAL_SUMMON, userId, card, "hand", "field", null, Map.of("cost", cost),
                player.getUsername() + " invocó " + name(card) + " pagando " + gemsText(cost) + (advantage ? " (ventaja del iniciador)" : ""));
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
        int cost = CardEffects.playCost(room, userId, card);
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + cost;
        pay(room, userId, cost);
        if (structure) room.getUsedThisTurn().add(STRUCTURE_PLAYED + userId);
        play(room, player, card, cost, x, y, null);
        advance(room);
        return null;
    }

    /** The player already played a structure normally this turn. */
    public static boolean structurePlayed(GameRoom room, String userId) {
        return room.getUsedThisTurn().contains(STRUCTURE_PLAYED + userId);
    }

    private static void play(GameRoom room, PlayerState player, CardSlot card, int cost, Double x, Double y, PendingEffect effect) {
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

    private static String checkMainPhase(GameRoom room, String userId, String what) {
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
    private static boolean starterAdvantage(GameRoom room, String userId, CardSlot card, Integer cost) {
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
        int cost = ritualCost(room, userId, card);
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y el coste especial es " + cost;
        List<CardSlot> materials = ritualMaterials(player, card);
        String missing = "Tus criaturas no cumplen el Requerimiento de " + name(card) + ": " + card.getRequirement().trim();

        if (summonerId != null) {
            CardSlot summoner = find(player.getField(), summonerId);
            if (summoner == null) return "El invocador debe ser una criatura de tu campo";
            if (!materials.contains(summoner)) return name(summoner) + " no comparte tipo ni color con " + name(card);
            if (!req.sum()) {
                if (summoner.getLevel() != req.level()) return missing;
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
                : materials.stream().filter(c -> c.getLevel() == req.level()).toList();
        if (valid.isEmpty()) return missing;
        ritualPrompt(room, player, card, null, valid, "Elige el invocador" + (req.sum() ? "" : ": 1 criatura nivel " + req.level())
                + ". Se sacrifica y " + name(card) + " ocupa su lugar y hereda su estado.", 1);
        room.getPrompt().setChosenLabel("Invocador");
        return null;
    }

    // ------------------------------------------------------------------ Evolución, Ascenso, Materialización (rule 9)

    /**
     * What a non-Ritual special summon needs: the summoner's level, for Ascenso the level the allies must add up
     * to, for Materialización the level of the other material (from the Tributos or the field).
     */
    private record SpecialRequirement(String kind, int level, int alliesSum, int materialLevel) {}

    private static SpecialRequirement specialRequirement(CardSlot card) {
        String kind = card.getSpecialSummonKind() == null ? "" : card.getSpecialSummonKind().toLowerCase(Locale.ROOT);
        if (!List.of("evolution", "promotion", "materialization").contains(kind) || card.getRequirement() == null) return null;
        String text = card.getRequirement().toLowerCase(Locale.ROOT);
        Matcher level = Pattern.compile("ni[ev]*l\\s*(\\d+)").matcher(text);
        if (!level.find()) return null;
        int summonerLevel = Integer.parseInt(level.group(1));
        Matcher sum = Pattern.compile("suman\\s*(\\d+)").matcher(text);
        int alliesSum = "promotion".equals(kind) && sum.find() ? Integer.parseInt(sum.group(1)) : 0;
        int materialLevel = summonerLevel;
        if ("materialization".equals(kind)) {
            Matcher material = Pattern.compile("material\\s*ni[ev]*l\\s*(\\d+)").matcher(text);
            if (material.find()) materialLevel = Integer.parseInt(material.group(1));
        }
        return new SpecialRequirement(kind, summonerLevel, alliesSum, materialLevel);
    }

    private static String kindName(String kind) {
        return switch (kind) {
            case "evolution" -> "Evolución";
            case "promotion" -> "Ascenso";
            case "materialization" -> "Materialización";
            default -> "Ritual";
        };
    }

    /** Creatures of the field that can be the summoner: the level asked for, sharing a type or color. */
    private static List<CardSlot> specialSummoners(PlayerState player, CardSlot card, SpecialRequirement req) {
        return player.getField().stream()
                .filter(c -> "creature".equals(c.getCardType()) && c.getLevel() != null && c.getLevel() == req.level() && shares(c, card))
                .toList();
    }

    /** Materialización: the other material, a creature of that level in the Tributos or on the field (not the summoner). */
    private static List<CardSlot> specialMaterials(PlayerState player, CardSlot card, SpecialRequirement req, CardSlot summoner) {
        List<CardSlot> all = new ArrayList<>(player.getTributeZone());
        all.addAll(player.getField());
        return all.stream()
                .filter(c -> c != summoner && "creature".equals(c.getCardType()) && c.getLevel() != null
                        && c.getLevel() == req.materialLevel() && shares(c, card))
                .toList();
    }

    /** Ascenso: "mientras tus aliados suman N o más en nivel". */
    private static boolean alliesAddUp(PlayerState player, SpecialRequirement req) {
        return allies(player).stream().mapToInt(c -> c.getLevel() == null ? 0 : c.getLevel()).sum() >= req.alliesSum();
    }

    /** Whether the card can be summoned now by its special summon (any kind), for the board's legal actions. */
    public static boolean canSpecial(GameRoom room, String userId, CardSlot card) {
        if ("ritual".equalsIgnoreCase(card.getSpecialSummonKind())) return canRitual(room, userId, card);
        PlayerState player = room.getPlayer(userId);
        SpecialRequirement req = specialRequirement(card);
        if (player == null || req == null || card.getSpecialCost() == null) return false;
        if (!canPay(room, userId, card.getSpecialCost())) return false;
        if ("promotion".equals(req.kind()) && !alliesAddUp(player, req)) return false;
        return specialSummoners(player, card, req).stream()
                .anyMatch(s -> !"materialization".equals(req.kind()) || !specialMaterials(player, card, req, s).isEmpty());
    }

    /**
     * Special summon from the hand by the rules (rule 9): Ritual, Evolución, Ascenso or Materialización, as the
     * card says. {@code summonerId} is the creature it was dropped onto; whatever is missing is asked for.
     */
    public static String specialByPlayer(GameRoom room, String userId, String instanceId, String summonerId) {
        PlayerState player = room.getPlayer(userId);
        CardSlot card = player == null ? null : find(player.getHand(), instanceId);
        if (card != null && "ritual".equalsIgnoreCase(card.getSpecialSummonKind())) return ritualByPlayer(room, userId, instanceId, summonerId);
        String error = checkMainPhase(room, userId, "invocar");
        if (error != null) return error;
        if (card == null) return "La carta no está en tu mano";
        SpecialRequirement req = specialRequirement(card);
        if (req == null || card.getSpecialCost() == null) return name(card) + " no tiene una invocación especial que el motor conozca";
        String kind = kindName(req.kind());
        if (!canPay(room, userId, card.getSpecialCost())) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y el coste especial es " + card.getSpecialCost();
        if ("promotion".equals(req.kind()) && !alliesAddUp(player, req)) return "Para el Ascenso tus aliados deben sumar " + req.alliesSum() + " o más en nivel";
        String missing = "Tus criaturas no cumplen el Requerimiento de " + name(card) + ": " + card.getRequirement().trim();
        List<CardSlot> summoners = specialSummoners(player, card, req).stream()
                .filter(s -> !"materialization".equals(req.kind()) || !specialMaterials(player, card, req, s).isEmpty()).toList();
        if (summonerId == null) {
            if (summoners.isEmpty()) return missing;
            rulesPrompt(room, userId, PURPOSE_SPECIAL, name(card) + " · " + kind,
                    "Elige el invocador: 1 criatura nivel " + req.level() + ". " + name(card) + " ocupa su lugar y hereda su estado. Si no eliges ninguna, no se invoca.",
                    "Invocador", summoners, player.getField());
            room.getPrompt().setSubjectId(card.getInstanceId());
            return null;
        }
        CardSlot summoner = find(player.getField(), summonerId);
        if (summoner == null) return "El invocador debe ser una criatura de tu campo";
        if (!summoners.contains(summoner)) return shares(summoner, card) ? missing : name(summoner) + " no comparte tipo ni color con " + name(card);
        if ("materialization".equals(req.kind())) {
            List<CardSlot> materials = specialMaterials(player, card, req, summoner);
            List<CardSlot> shown = new ArrayList<>(player.getTributeZone());
            shown.addAll(player.getField());
            rulesPrompt(room, userId, PURPOSE_SPECIAL, name(card) + " · " + kind,
                    "Elige el material: 1 criatura nivel " + req.materialLevel() + " de tus tributos o de tu campo. Se convierte en material de "
                            + name(card) + " junto a " + name(summoner) + ".", "Material", materials, shown);
            room.getPrompt().setMin(1);
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(summoner.getInstanceId());
            return null;
        }
        handSpecial(room, player, card, req, summoner, null);
        return null;
    }

    /**
     * Carries out the special summon (rules 9.4–9.6): pays the special cost, the creature takes the summoner's
     * place and state; the summoner is tributed (Ascenso), becomes a Recurso (Evolución) or, with the other
     * material, Materiales (Materialización). Then the move up and the regulatory draw.
     */
    private static void handSpecial(GameRoom room, PlayerState player, CardSlot card, SpecialRequirement req, CardSlot summoner, CardSlot material) {
        specialSummon(room, player, card, req, summoner, material, "hand", 0, null);
        advance(room);
    }

    private static void specialSummon(GameRoom room, PlayerState player, CardSlot card, SpecialRequirement req, CardSlot summoner,
                                      CardSlot material, String fromZone, int reduce, PendingEffect effect) {
        String userId = player.getUserId();
        int cost = Math.max(0, card.getSpecialCost() - reduce - CardEffects.summonerReduction(room, player, req.kind(), summoner));
        pay(room, userId, cost);
        zone(player, fromZone).remove(card);
        inheritFromSummoner(room, card, summoner);
        player.getField().remove(summoner);
        leaveField(summoner);
        String used = name(summoner);
        switch (req.kind()) {
            case "promotion" -> {
                player.getTributeZone().add(summoner);
                record(room, GameEvent.TRIBUTE, userId, summoner, "field", "tributeZone", null, Map.of("cause", "promotion"), null);
            }
            case "evolution" -> card.getResources().add(0, summoner);
            default -> {
                card.getMaterials().add(0, summoner);
                if (material != null) {
                    String from = player.getTributeZone().remove(material) ? "tributeZone" : "field";
                    if ("field".equals(from)) {
                        // A creature of the field used as a material loses its state; its attachments go to the Descarte
                        player.getField().remove(material);
                        for (CardSlot a : concat(material.getMaterials(), material.getResources())) player.getDiscardPile().add(a);
                        material.setMaterials(new ArrayList<>());
                        material.setResources(new ArrayList<>());
                        leaveField(material);
                    }
                    material.setFaceDown(false);
                    card.getMaterials().add(material);
                    used += " y " + name(material);
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
    private static List<CardSlot> specialTargets(GameRoom room, PendingEffect effect, SpecialFromZone special) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        return zone(player, special.zone()).stream()
                .filter(c -> c != find(player.getField(), effect.getSourceInstanceId()))
                .filter(c -> special.kind().equalsIgnoreCase(c.getSpecialSummonKind()) && c.getSpecialCost() != null)
                .filter(special.filter()::matches)
                .filter(c -> {
                    SpecialRequirement req = specialRequirement(c);
                    if (req == null || !canPay(room, player.getUserId(), Math.max(0, c.getSpecialCost() - special.reduce()))) return false;
                    if ("promotion".equals(req.kind()) && !alliesAddUp(player, req)) return false;
                    return specialSummoners(player, c, req).stream().anyMatch(s -> summonerAllowed(special, s, effect));
                })
                .toList();
    }

    /** "esta carta puede invocar…" (only this card), "otro aliado…" (not this card) or "1 aliado…" (any). */
    private static boolean summonerAllowed(SpecialFromZone special, CardSlot summoner, PendingEffect effect) {
        boolean self = summoner.getInstanceId().equals(effect.getSourceInstanceId());
        return switch (special.summoner()) {
            case "self" -> self;
            case "other" -> !self;
            default -> true;
        };
    }

    /** The level of the card whose effect caused this trigger ("reduciendo su coste en el nivel de dicho aliado"). */
    private static int triggerSourceLevel(GameRoom room, PendingEffect effect) {
        GameEvent trigger = room.getTimeline().stream().filter(e -> e.getSeq() == effect.getTriggerSeq()).findFirst().orElse(null);
        Object by = trigger == null || trigger.getData() == null ? null : trigger.getData().get("byCard");
        CardSlot source = by == null ? null : findAnywhere(room, trigger.getPlayerId(), (String) by);
        return source == null || source.getLevel() == null ? 0 : source.getLevel();
    }

    /** "puede invocar por Ascenso…": the creature (or none), then its summoner. */
    private static boolean runSpecialFromZone(GameRoom room, PendingEffect effect, PlayerState player, SpecialFromZone special) {
        CardSlot target = effect.getRitualTargetId() == null ? null : find(zone(player, special.zone()), effect.getRitualTargetId());
        if (target == null) {
            List<CardSlot> valid = specialTargets(room, effect, special);
            if (valid.isEmpty()) {
                log(room, "No hay criaturas que puedan invocarse por " + kindName(special.kind()) + " (" + effect.getSourceName() + ")", null);
                return false;
            }
            pick(room, effect, "Puedes elegir 1 criatura de tu " + zoneName(special.zone()) + " para invocarla por " + kindName(special.kind())
                    + (special.reduce() > 0 ? " reduciendo su coste especial en " + special.reduce() : "") + ", o ninguna.", "Invocar",
                    zone(player, special.zone()), ids(valid), 1);
            room.getPrompt().setMin(0);
            return true;
        }
        SpecialRequirement req = specialRequirement(target);
        List<CardSlot> summoners = specialSummoners(player, target, req).stream().filter(s -> summonerAllowed(special, s, effect)).toList();
        pick(room, effect, "Elige el invocador: 1 criatura nivel " + req.level() + ". " + name(target) + " ocupa su lugar y hereda su estado.",
                "Invocador", player.getField(), ids(summoners), 1);
        return true;
    }

    /** Whether the card can be summoned by Ritual from the hand now, for the board's legal actions. */
    public static boolean canRitual(GameRoom room, String userId, CardSlot card) {
        PlayerState player = room.getPlayer(userId);
        Requirement req = requirement(card);
        if (player == null || req == null || card.getSpecialCost() == null || !"ritual".equalsIgnoreCase(card.getSpecialSummonKind())) return false;
        if (!canPay(room, userId, ritualCost(room, userId, card))) return false;
        List<CardSlot> materials = ritualMaterials(player, card);
        return req.sum() ? sumsExactly(materials, req.level()) : materials.stream().anyMatch(c -> c.getLevel() == req.level());
    }

    private static int ritualCost(GameRoom room, String userId, CardSlot card) {
        return card.getSpecialCost() - (starterAdvantage(room, userId, card, card.getSpecialCost()) ? 1 : 0);
    }

    private static void ritualPrompt(GameRoom room, PlayerState player, CardSlot card, CardSlot summoner, List<CardSlot> valid,
                                     String instruction, int max) {
        rulesPrompt(room, player.getUserId(), PURPOSE_RITUAL, name(card) + " · Ritual",
                instruction + " Si no eliges ninguna, no se invoca.", "Sacrificar", valid, player.getField());
        room.getPrompt().setMax(max);
        room.getPrompt().setSubjectId(card.getInstanceId());
        if (summoner != null) room.getPrompt().setSummonerId(summoner.getInstanceId());
    }

    private static void handRitual(GameRoom room, PlayerState player, CardSlot card, CardSlot summoner, List<CardSlot> others) {
        String userId = player.getUserId();
        boolean advantage = starterAdvantage(room, userId, card, card.getSpecialCost());
        int cost = ritualCost(room, userId, card);
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

    /** Gems the player has on the shared Contador (rule 4.0.9): positive on their side. */
    public static int gemsOf(GameRoom room, String userId) {
        return room.getSharedCounter() * direction(room, userId);
    }

    /** Same convention as the room: the first player counts the Contador negatively. */
    static int direction(GameRoom room, String userId) {
        String firstId = room.getPlayers().keySet().iterator().next();
        return firstId.equals(userId) ? -1 : 1;
    }

    /**
     * Rule 5.0.0.1: on your turn, paying may not leave the opponent with more gems than their Marcador shows.
     * On the opponent's turn that limit does not apply: only the Contador's end (10 gems for the opponent).
     * Paying nothing does not move the Contador, so a cost of 0 can always be paid.
     */
    static boolean canPay(GameRoom room, String userId, int cost) {
        if (cost <= 0) return true;
        if (!userId.equals(room.getCurrentTurnUserId())) return gemsOf(room, userId) - cost >= -10;
        PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
        int opponentMarker = opponent == null ? 0 : opponent.getResourceCount();
        return gemsOf(room, userId) - cost >= -opponentMarker;
    }

    private static void pay(GameRoom room, String userId, int cost) {
        room.setSharedCounter(clamp(room.getSharedCounter() - cost * direction(room, userId)));
    }

    // ------------------------------------------------------------------ Destruction and sacrifice

    /**
     * Destroys a card on the field from the board (cases the engine does not play yet): it goes to its
     * owner's Descarte and its "En Destrucción" effects trigger.
     */
    public static String destroyByPlayer(GameRoom room, String userId, String ownerId, String instanceId) {
        String error = checkOpen(room);
        if (error != null) return error;
        PlayerState owner = room.getPlayer(ownerId != null ? ownerId : userId);
        CardSlot card = owner == null ? null : find(owner.getField(), instanceId);
        if (card == null) return "La carta no está en el campo";
        destroy(room, card, owner, false, CAUSE_MANUAL, userId, null, null);
        advance(room);
        return null;
    }

    /** Sacrifices one of your cards on the field from the board: destroyed, to your Tributos (rule 4.0.4.2). */
    public static String sacrificeByPlayer(GameRoom room, String userId, String instanceId) {
        String error = checkOpen(room);
        if (error != null) return error;
        PlayerState owner = room.getPlayer(userId);
        CardSlot card = owner == null ? null : find(owner.getField(), instanceId);
        if (card == null) return "Solo puedes sacrificar tus cartas del campo";
        destroy(room, card, owner, true, CAUSE_MANUAL, userId, null, null);
        advance(room);
        return null;
    }

    /**
     * A creature whose strength was reduced to 0 is destroyed right away (rule 12.0.0.2), by effect and
     * attributed to whoever reduced it.
     */
    public static void destroyIfNoStrength(GameRoom room, String userId, PlayerState owner, CardSlot card) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || room.isEngineBusy()) return;
        if (!"creature".equals(card.getCardType()) || card.getStrength() == null) return;
        if (strength(card) > 0 || find(owner.getField(), card.getInstanceId()) == null) return;
        destroy(room, card, owner, false, CAUSE_EFFECT, userId, null, null);
        advance(room);
    }

    private static String checkOpen(GameRoom room) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return "La partida no está en curso";
        if (room.isEngineBusy()) return room.getCombat() != null ? "Hay un combate en curso" : "Hay un efecto resolviéndose";
        return null;
    }

    /**
     * Destroys (to the Descarte) or sacrifices (to the Tributos) a card on its owner's field. Its Recursos and
     * Materiales go to the Descarte (rules 6.3.0.5, 6.4.0.5) and it loses its state on the field.
     *
     * @param byCard the card that destroyed it (in a clash), if any; for effects, the effect's card
     */
    static GameEvent destroy(GameRoom room, CardSlot card, PlayerState owner, boolean sacrifice, String cause, String byId,
                             String byCard, PendingEffect effect) {
        owner.getField().remove(card);
        Combat combat = room.getCombat();
        if (combat != null && owner.getUserId().equals(combat.getDefenderId())) combat.setEnemyDestroyed(true);
        for (CardSlot attached : concat(card.getMaterials(), card.getResources())) {
            attached.setFaceDown(false);
            owner.getDiscardPile().add(attached);
        }
        card.setMaterials(new ArrayList<>());
        card.setResources(new ArrayList<>());
        leaveField(card);
        String toZone = sacrifice ? "tributeZone" : "discardPile";
        (sacrifice ? owner.getTributeZone() : owner.getDiscardPile()).add(card);

        PlayerState by = room.getPlayer(byId);
        String who = by != null ? by.getUsername() : "Las reglas";
        String whose = by != null && by != owner ? " de " + owner.getUsername() : "";
        String why = effect != null ? " (" + effect.getSourceName() + ")"
                : CAUSE_BATTLE.equals(cause) ? " en el enfrentamiento"
                : CAUSE_RITUAL.equals(cause) ? " para el Ritual"
                : CAUSE_EFFECT.equals(cause) ? " (fuerza 0)" : "";
        String text = sacrifice ? who + " sacrifica " + name(card) + why : who + " destruye " + name(card) + whose + why;
        Map<String, Object> data = new HashMap<>();
        data.put("cause", cause);
        data.put("ownerId", owner.getUserId());
        if (byCard == null && effect != null) byCard = effect.getSourceInstanceId();
        if (byCard != null) data.put("byCard", byCard);
        return record(room, sacrifice ? GameEvent.SACRIFICE : GameEvent.DESTROY, byId, card, "field", toZone, effect, data, text);
    }

    private static void leaveField(CardSlot card) {
        card.setFaceDown(false);
        card.setTapped(false);
        card.setStrengthModifier(0);
        card.setTurnStrength(0);
        card.setLaterStrength(0);
        card.getTurnKeywords().clear();
        card.setX(null);
        card.setY(null);
    }

    // ------------------------------------------------------------------ Línea frontal

    /**
     * Rules 8.1.0.4 and 8.3.0.3: at the start of the turn and in the end phase the active player may move any of
     * their creatures from the Retaguardia to the Línea frontal, all in one action. Asks only if there are some.
     */
    public static void offerAdvance(GameRoom room, String userId, boolean startOfTurn) {
        PlayerState player = room.getPlayer(userId);
        if (player == null || room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || room.getPrompt() != null) return;
        List<CardSlot> back = player.getField().stream().filter(c -> "creature".equals(c.getCardType()) && !inFrontLine(c)).toList();
        if (back.isEmpty()) return;
        String title = startOfTurn ? "Inicio de turno" : "Final de turno";
        rulesPrompt(room, userId, PURPOSE_ADVANCE, title + " · Línea frontal",
                "Elige las criaturas de tu retaguardia que suben a la línea frontal (todas a la vez), o ninguna.",
                "A la línea frontal", back, back);
        room.getPrompt().setMax(back.size());
    }

    /**
     * Rule 8.1: the start of the turn, after the gems, the draw and the reactivation: records it (start-of-turn
     * triggers), offers the move up to the Línea frontal and lets pending effects resolve.
     */
    public static void turnStarted(GameRoom room, String playerId) {
        PlayerState player = room.getPlayer(playerId);
        if (player == null) return;
        record(room, GameEvent.TURN_START, playerId, null, null, null, null, null, "Inicio del turno de " + player.getUsername());
        // Reactivar: "al inicio del turno: reactiva esta carta" (any turn)
        for (PlayerState p : room.getPlayers().values()) {
            for (CardSlot c : p.getField()) {
                if (c.isTapped() && keywordLevel(c, "Reactivar") != null) {
                    c.setTapped(false);
                    record(room, GameEvent.UNTAP, p.getUserId(), c, null, null, null, Map.of("keyword", "Reactivar"),
                            name(c) + " se reactiva (Reactivar)");
                }
            }
        }
        offerAdvance(room, playerId, true);
        advance(room);
    }

    /**
     * Rule 8.3: the end phase, after the Contador: offers the move up to the Línea frontal and records it
     * (end-of-turn triggers resolve once the move up is answered).
     */
    public static void turnEnding(GameRoom room, String playerId) {
        PlayerState player = room.getPlayer(playerId);
        if (player == null) return;
        record(room, GameEvent.TURN_END, playerId, null, null, null, null, null, "Final del turno de " + player.getUsername());
        offerAdvance(room, playerId, false);
        advance(room);
    }

    // ------------------------------------------------------------------ Combat (rule 10)

    /**
     * Declares an attack from the board (rule 10.1.1): an active creature of your Línea frontal attacks an
     * exhausted enemy creature ({@code targetId}) or the opponent (null). Exhausting it and declaring the attack
     * are one action; it is only possible with 0 or more gems.
     */
    public static String attackByPlayer(GameRoom room, String userId, String attackerId, String targetId) {
        String error = checkMainPhase(room, userId, "atacar");
        if (error != null) return error;
        PlayerState player = room.getPlayer(userId);
        CardSlot attacker = player == null ? null : find(player.getField(), attackerId);
        if (attacker == null || !"creature".equals(attacker.getCardType())) return "Solo tus criaturas del campo pueden atacar";
        if (!inFrontLine(attacker)) return "Solo las criaturas de la línea frontal pueden atacar";
        if (attacker.isTapped()) return "Esa criatura está agotada";
        if (!attackGemsOk(room, userId, attacker)) {
            Integer furia = furia(attacker);
            return furia == null ? "Para declarar un ataque necesitas 0 o más gemas"
                    : "Con Furia " + furia + " puedes declarar ataque mientras tu rival tenga " + furia + " o menos gemas";
        }
        if (targetId != null) {
            PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
            CardSlot target = opponent == null ? null : find(opponent.getField(), targetId);
            if (target == null || !"creature".equals(target.getCardType())) return "Solo puedes atacar a criaturas enemigas";
            if (!target.isTapped() && !challenges(attacker, target)) return "Solo puedes atacar a criaturas agotadas";
        }
        attacker.setTapped(true);
        startCombat(room, userId, attacker, targetId, false, null);
        advance(room);
        return null;
    }

    /**
     * Rule 10.1.1.1: a regular attack needs 0 or more gems. Furia N lets the creature declare it while the
     * opponent has N or fewer gems (−N or more for you); Furia without a number, with any gems. It does not lift
     * exhausting the creature to declare it.
     */
    public static boolean attackGemsOk(GameRoom room, String userId, CardSlot attacker) {
        int gems = gemsOf(room, userId);
        if (gems >= 0) return true;
        Integer furia = furia(attacker);
        return furia != null && (furia < 0 || gems >= -furia);
    }

    /** Furia N → N; Furia without a number → −1 (no limit); null without Furia. */
    private static Integer furia(CardSlot card) {
        Integer best = null;
        for (String k : concatStrings(card.getKeywords(), card.getTurnKeywords())) {
            if (!k.toLowerCase(Locale.ROOT).startsWith("furia")) continue;
            Matcher m = Pattern.compile("(\\d+)").matcher(k);
            if (!m.find()) return -1;
            best = best == null ? Integer.parseInt(m.group(1)) : Math.max(best, Integer.parseInt(m.group(1)));
        }
        return best;
    }

    /** The declaration is the attack step itself: its "Al Atacar" triggers resolve before the block (rule 10.1.1.3). */
    private static void startCombat(GameRoom room, String userId, CardSlot attacker, String targetId, boolean byEffect, PendingEffect effect) {
        PlayerState player = room.getPlayer(userId);
        PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
        Combat combat = new Combat();
        combat.setAttackerId(attacker.getInstanceId());
        combat.setAttackerOwnerId(userId);
        combat.setDefenderId(opponent == null ? null : opponent.getUserId());
        combat.setTargetId(targetId);
        combat.setByEffect(byEffect);
        combat.setStep(Combat.Step.ATTACK);
        combat.setPhase(Combat.Phase.EXIT);
        room.setCombat(combat);
        CardSlot target = targetId == null || opponent == null ? null : find(opponent.getField(), targetId);
        Map<String, Object> data = new HashMap<>();
        data.put("byEffect", byEffect);
        data.put("exhausted", !byEffect);
        if (targetId != null) data.put("targetId", targetId);
        String targetName = target != null ? name(target) : opponent != null ? opponent.getUsername() : "el rival";
        record(room, GameEvent.ATTACK_DECLARED, userId, attacker, null, null, effect, data, byEffect
                ? player.getUsername() + ": " + name(attacker) + " ataca a " + targetName
                : player.getUsername() + " agota a " + name(attacker) + " y ataca a " + targetName);
    }

    /**
     * Moves the combat on (rule 10.1). Each step is entered, carried out and left; entering and leaving record
     * an event, so the triggers of that moment resolve before the combat goes on.
     */
    private static void combatStep(GameRoom room) {
        Combat c = room.getCombat();
        PlayerState owner = room.getPlayer(c.getAttackerOwnerId());
        PlayerState defender = room.getPlayer(c.getDefenderId());
        CardSlot attacker = owner == null ? null : find(owner.getField(), c.getAttackerId());
        // Rule 10.0.0.2: if the attacker leaves the field the combat ends at once; there is no end of combat
        if (attacker == null || defender == null) {
            room.setCombat(null);
            clearUntilEndOfAttack(room, c);
            log(room, "El combate termina: el atacante dejó el campo", null);
            return;
        }
        switch (c.getPhase()) {
            case ENTER -> {
                if (c.getStep() == Combat.Step.END) {
                    endCombat(room);
                    return;
                }
                record(room, GameEvent.COMBAT_STEP, owner.getUserId(), attacker, null, null, null,
                        Map.of("step", c.getStep().name(), "moment", "ENTER"), "Paso de " + stepName(c.getStep()));
                c.setPhase(Combat.Phase.ACT);
            }
            case ACT -> {
                if (actStep(room, c, owner, defender, attacker)) c.setPhase(Combat.Phase.EXIT);
            }
            case EXIT -> {
                record(room, GameEvent.COMBAT_STEP, owner.getUserId(), attacker, null, null, null,
                        Map.of("step", c.getStep().name(), "moment", "EXIT"), null);
                c.setStep(nextStep(c, defender, attacker));
                c.setPhase(Combat.Phase.ENTER);
            }
        }
    }

    private static Combat.Step nextStep(Combat c, PlayerState defender, CardSlot attacker) {
        return switch (c.getStep()) {
            case ATTACK -> Combat.Step.BLOCK;
            // Rule 10.1.3.1: the clash only happens if there is a blocker when the step starts
            case BLOCK -> c.getBlockerId() != null && find(defender.getField(), c.getBlockerId()) != null
                    ? Combat.Step.CLASH : Combat.Step.DAMAGE;
            // Perforante: "al final del enfrentamiento, si el bloqueador fue destruido: entra al paso de daño directo"
            case CLASH -> keywordLevel(attacker, "Perforante") != null && find(defender.getField(), c.getBlockerId()) == null
                    ? Combat.Step.DAMAGE : Combat.Step.END;
            case DAMAGE, END -> Combat.Step.END;
        };
    }

    private static String stepName(Combat.Step step) {
        return switch (step) {
            case ATTACK -> "ataque";
            case BLOCK -> "bloqueo";
            case CLASH -> "enfrentamiento";
            case DAMAGE -> "daño directo";
            case END -> "final del combate";
        };
    }

    /** Carries out the step. Returns false while it waits for a choice. */
    private static boolean actStep(GameRoom room, Combat c, PlayerState owner, PlayerState defender, CardSlot attacker) {
        switch (c.getStep()) {
            case BLOCK -> {
                // Provocar: "al entrar al paso de bloqueo puedes elegir 1 enemigo; deberá bloquear"
                if (!c.isProvokeAsked()) {
                    c.setProvokeAsked(true);
                    Integer provoke = keywordLevel(attacker, "Provocar");
                    if (provoke != null && c.getBlockerId() == null) {
                        List<CardSlot> options = blockers(defender).stream()
                                .filter(b -> provoke == 0 || (b.getLevel() != null && b.getLevel() <= provoke)).toList();
                        if (!options.isEmpty()) {
                            rulesPrompt(room, owner.getUserId(), PURPOSE_PROVOKE, name(attacker) + " · Provocar",
                                    "Puedes elegir 1 enemigo" + (provoke > 0 ? " nivel " + provoke + " o menor" : "") + ": deberá bloquear este ataque.",
                                    "Provocar", options, defender.getField());
                            return false;
                        }
                    }
                }
                // Rule 10.1.2.3: the defender may declare a blocker, unless one was already declared
                if (c.getBlockerId() == null && !c.isBlockAsked()) {
                    c.setBlockAsked(true);
                    List<CardSlot> options = keywordLevel(attacker, "Imbloqueable") != null ? List.of() : blockers(defender);
                    if (!options.isEmpty()) {
                        rulesPrompt(room, defender.getUserId(), PURPOSE_BLOCK, name(attacker) + " ataca",
                                "Puedes elegir 1 criatura activa de tu línea frontal para bloquear (se agota), o ninguna.",
                                "Bloquear", options, defender.getField());
                        return false;
                    }
                }
                // Rule 10.1.2.7: attacking a creature that is not blocked makes it the blocker
                if (c.getBlockerId() == null && c.getTargetId() != null && find(defender.getField(), c.getTargetId()) != null) {
                    c.setBlockerId(c.getTargetId());
                }
                return true;
            }
            case CLASH -> {
                CardSlot blocker = c.getBlockerId() == null ? null : find(defender.getField(), c.getBlockerId());
                // Rule 10.1.3.5: a blocker removed during the clash leaves the attacker as the winner
                if (blocker == null) return true;
                int a = strength(attacker);
                int b = strength(blocker);
                record(room, GameEvent.CLASH, owner.getUserId(), attacker, null, null, null,
                        Map.of("blockerId", blocker.getInstanceId(), "attackerStrength", a, "blockerStrength", b),
                        "Enfrentamiento: " + name(attacker) + " (" + a + ") contra " + name(blocker) + " (" + b + ")");
                // Rule 10.1.3.2: the weaker one is destroyed; both when equal. Attributed to the opponent and the other creature.
                if (b <= a) destroy(room, blocker, defender, false, CAUSE_BATTLE, owner.getUserId(), attacker.getInstanceId(), null);
                if (a <= b) destroy(room, attacker, owner, false, CAUSE_BATTLE, defender.getUserId(), blocker.getInstanceId(), null);
                return true;
            }
            case DAMAGE -> {
                return directDamage(room, c, owner, defender, attacker);
            }
            default -> {
                return true;
            }
        }
    }

    private static void rulesPrompt(GameRoom room, String playerId, String purpose, String title, String instruction,
                                     String chosenLabel, List<CardSlot> options, List<CardSlot> shown) {
        Prompt p = new Prompt();
        p.setId(room.nextId("prompt"));
        p.setKind(Prompt.Kind.PICK);
        p.setPurpose(purpose);
        p.setPlayerId(playerId);
        p.setSourceName(title);
        p.setTitle(title);
        p.setInstruction(instruction);
        p.setChosenLabel(chosenLabel);
        p.setCards(new ArrayList<>(shown));
        p.setValidIds(new ArrayList<>(ids(options)));
        p.setMin(0);
        p.setMax(1);
        room.setPrompt(p);
    }

    /** Creatures that can block: active, in the Línea frontal (rules 4.0.6.1, 6.2.1.3). */
    private static List<CardSlot> blockers(PlayerState defender) {
        return defender.getField().stream()
                .filter(c -> "creature".equals(c.getCardType()) && inFrontLine(c) && !c.isTapped()).toList();
    }

    /**
     * Rule 10.1.4: the attacker deals damage equal to its strength to the top life card, which is revealed. A
     * creature with that strength or less is tributed; a stronger one goes to the hand, and if it was just
     * revealed it may Reforzar instead (face up at the bottom of the life stack). A utility or structure may be
     * played without paying its cost, otherwise it goes to the hand. Letalidad N repeats the damage.
     *
     * @return false while it waits for the defender's choice
     */
    private static boolean directDamage(GameRoom room, Combat c, PlayerState owner, PlayerState defender, CardSlot attacker) {
        if (c.getHitsLeft() == null) {
            Integer lethality = keywordLevel(attacker, "Letalidad");
            c.setHitsLeft(1 + (lethality == null ? 0 : lethality));
        }
        int damage = strength(attacker);
        while (c.getHitsLeft() > 0 && !defender.getLifeStack().isEmpty()) {
            c.setHitsLeft(c.getHitsLeft() - 1);
            CardSlot life = defender.getLifeStack().remove(0);
            boolean revealedNow = life.isFaceDown();
            life.setFaceDown(false);
            boolean creature = "creature".equals(life.getCardType());
            boolean tribute = creature && (life.getStrength() == null || life.getStrength() <= damage);
            (tribute ? defender.getTributeZone() : defender.getHand()).add(life);
            record(room, GameEvent.LIFE_DAMAGE, owner.getUserId(), life, "lifeStack", tribute ? "tributeZone" : "hand", null,
                    Map.of("damage", damage, "attackerId", attacker.getInstanceId(), "defenderId", defender.getUserId()),
                    name(attacker) + " inflige " + damage + " de daño directo: " + defender.getUsername() + " pierde la vida "
                            + name(life) + (tribute ? ", que va a sus tributos" : ", que va a su mano"));
            if (creature && !tribute && revealedNow) {
                lifePrompt(room, defender, life, PURPOSE_REINFORCE, "Reforzar", name(life)
                        + " tiene más fuerza que el daño y va a tu mano. ¿Refuerzas con ella? Se coloca bocarriba al fondo de tu pila de vidas.", "Reforzar");
                return false;
            }
            if ("utility".equals(life.getCardType()) || "structure".equals(life.getCardType())) {
                lifePrompt(room, defender, life, PURPOSE_LIFE_PLAY, "Vida revelada",
                        "¿Juegas " + name(life) + " sin pagar su coste? Si no, queda en tu mano.", "Jugar");
                return false;
            }
            // The revealed life is fully resolved (its triggers too) before the next hit (Letalidad)
            if (c.getHitsLeft() > 0 && !defender.getLifeStack().isEmpty()) return false;
        }
        // Rule 2.1.0.1: a player whose life stack reaches 0 loses
        if (defender.getLifeStack().isEmpty()) {
            record(room, GameEvent.GAME_LOST, defender.getUserId(), null, null, null, null, Map.of("reason", "lives"),
                    defender.getUsername() + " se quedó sin vidas: pierde la partida");
            room.setWinnerId(owner.getUserId());
            room.setEndReason("lives");
            room.setCombat(null);
            room.getChain().clear();
            room.getTriggerQueue().clear();
            room.getPendingEvents().clear();
            room.getPostGameChoices().clear();
            room.setStatus(GameRoom.RoomStatus.POST_GAME);
        }
        return true;
    }

    private static void lifePrompt(GameRoom room, PlayerState defender, CardSlot life, String purpose, String title,
                                   String instruction, String label) {
        rulesPrompt(room, defender.getUserId(), purpose, title, instruction, label, List.of(life), List.of(life));
        room.getPrompt().setKind(Prompt.Kind.CONFIRM);
        room.getPrompt().setSubjectId(life.getInstanceId());
    }

    /** Rule 10.1.5: the end of the combat fires "Al Final Del Ataque", resolved outside the combat. */
    private static void endCombat(GameRoom room) {
        Combat c = room.getCombat();
        room.setCombat(null);
        clearUntilEndOfAttack(room, c);
        PlayerState owner = room.getPlayer(c.getAttackerOwnerId());
        CardSlot attacker = owner == null ? null : findAnywhere(room, owner.getUserId(), c.getAttackerId());
        if (owner != null) record(room, GameEvent.ATTACK_ENDED, owner.getUserId(), attacker, null, null, null,
                Map.of("enemyDestroyed", c.isEnemyDestroyed()), "Termina el combate");
    }

    /** "hasta el final del ataque" ends with the combat. */
    private static void clearUntilEndOfAttack(GameRoom room, Combat c) {
        for (String entry : c.getUntilEndOfAttack()) {
            String[] parts = entry.split("\\|", 2);
            for (PlayerState p : room.getPlayers().values()) {
                CardSlot card = find(p.getField(), parts[0]);
                if (card != null) card.getTurnKeywords().remove(parts[1]);
            }
        }
        c.getUntilEndOfAttack().clear();
    }

    /**
     * The value of a keyword the card has, printed or gained this turn: 0 when it has no number ("Provocar"),
     * null when it does not have it.
     */
    static Integer keywordLevel(CardSlot card, String keyword) {
        Integer best = null;
        for (String k : concatStrings(card.getKeywords(), card.getTurnKeywords())) {
            if (!k.toLowerCase(Locale.ROOT).startsWith(keyword.toLowerCase(Locale.ROOT))) continue;
            Matcher m = Pattern.compile("(\\d+)").matcher(k);
            int value = m.find() ? Integer.parseInt(m.group(1)) : 0;
            best = best == null ? value : Math.max(best, value);
        }
        return best;
    }

    // ------------------------------------------------------------------ Triggers and chain

    /**
     * Moves the game on until it needs a player: turns pending events into triggered effects (only between
     * resolutions), activates them in rules order, resolves the chain and goes on with the combat.
     */
    static void advance(GameRoom room) {
        while (room.getPrompt() == null && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS) {
            List<PendingEffect> chain = room.getChain();
            // Rules 7.2.0.4 and 11.6.0.4: once a chain is closed and resolving nothing can be added to it; what
            // triggers meanwhile waits until the whole chain has resolved and then forms a new chain
            boolean resolving = chain.stream().anyMatch(e -> e.isPriorityDone() || e.getResolutionId() != null);
            PendingEffect top = chain.isEmpty() ? null : chain.get(chain.size() - 1);
            if (top != null && paying(top)) {
                // The effect just activated pays its cost before anything else, choices included
                if (!run(room, top, script(top).cost().get(top.getCostIndex()))) top.setCostIndex(top.getCostIndex() + 1);
            } else if (!resolving && !room.getPendingEvents().isEmpty()) {
                queueTriggers(room);
            } else if (!resolving && !room.getTriggerQueue().isEmpty()) {
                activateNext(room);
            } else if (!chain.isEmpty()) {
                if (offerPriority(room)) return;
                stepTop(room);
            } else if (room.getCombat() != null) {
                combatStep(room);
            } else {
                return;
            }
        }
    }

    private static void queueTriggers(GameRoom room) {
        List<GameEvent> events = new ArrayList<>(room.getPendingEvents());
        room.getPendingEvents().clear();
        List<PendingEffect> triggered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GameEvent event : events) {
            for (PendingEffect e : triggersOf(room, event)) {
                // "si …" triggers once per window however many events match (rule 11.7.0.1)
                if (seen.add(e.getSourceInstanceId() + "|" + e.getScriptId())) triggered.add(e);
            }
        }
        // Rule 11.7.0.5: mandatory of the active player, mandatory of the opponent, optional of the active
        // player, optional of the opponent (within a group, in the order they happened)
        String active = room.getCurrentTurnUserId();
        triggered.sort(Comparator.comparingInt(e -> rank(e, active)));
        room.getTriggerQueue().addAll(triggered);
    }

    private static List<PendingEffect> triggersOf(GameRoom room, GameEvent event) {
        List<PendingEffect> result = new ArrayList<>();
        // The card's own moment
        EffectScript.Moment moment = switch (event.getType()) {
            case GameEvent.NORMAL_SUMMON, GameEvent.EFFECT_SUMMON, GameEvent.SPECIAL_SUMMON -> EffectScript.Moment.ON_SUMMON;
            case GameEvent.DESTROY, GameEvent.SACRIFICE -> EffectScript.Moment.ON_DESTROY;
            case GameEvent.ATTACK_DECLARED -> EffectScript.Moment.ON_ATTACK;
            case GameEvent.ATTACK_ENDED -> EffectScript.Moment.ON_ATTACK_END;
            case GameEvent.PLAY -> EffectScript.Moment.ON_PLAY;
            default -> null;
        };
        if (moment != null && event.getCardInstanceId() != null) {
            String controllerId = moment == EffectScript.Moment.ON_DESTROY ? ownerOf(event) : event.getPlayerId();
            // "En Destrucción" activates from the zone it was sent to (rule 11.4.1.2d); the others from the field
            String zone = moment == EffectScript.Moment.ON_DESTROY ? event.getToZone() : "field";
            CardSlot source = findIn(room, controllerId, zone, event.getCardInstanceId());
            if (source != null) {
                for (EffectScript script : CardEffects.of(source)) {
                    if (script.moment() == moment) addIfTriggers(room, result, script, event, source, controllerId);
                }
            }
        }
        // Passive effects with a trigger condition, from the cards in play (or in the hand for "En Mano")
        for (PlayerState p : room.getPlayers().values()) {
            for (String zone : List.of("field", "hand", "tributeZone", "discardPile")) {
                for (CardSlot card : new ArrayList<>(zone(p, zone))) {
                    for (EffectScript script : CardEffects.of(card)) {
                        if (script.moment() != EffectScript.Moment.ON_EVENT || !script.sourceZone().equals(zone)) continue;
                        if (script.allyTurn() && !p.getUserId().equals(room.getCurrentTurnUserId())) continue;
                        // A card on the field cannot react to an event that happened before it got there
                        if ("field".equals(zone) && event.getSeq() < card.getFieldSeq()) continue;
                        addIfTriggers(room, result, script, event, card, p.getUserId());
                    }
                }
            }
        }
        return result;
    }

    private static void addIfTriggers(GameRoom room, List<PendingEffect> result, EffectScript script, GameEvent event,
                                      CardSlot source, String controllerId) {
        if (script.condition() != null && !script.condition().test(room, event, source, controllerId)) return;
        PendingEffect e = pending(room, script, controllerId, source, event.getSeq());
        if (script.limit() != Limit.NONE && room.getUsedThisTurn().contains(usageKey(e, script))) return;
        result.add(e);
    }

    private static String usageKey(PendingEffect e, EffectScript script) {
        return script.limit() == Limit.ONCE_PER_TURN_BETWEEN_COPIES
                ? e.getControllerId() + "|" + script.id()
                : e.getControllerId() + "|" + e.getSourceInstanceId() + "|" + script.id();
    }

    private static int rank(PendingEffect e, String active) {
        boolean mandatory = script(e).mandatory();
        boolean mine = e.getControllerId().equals(active);
        return (mandatory ? 0 : 2) + (mine ? 0 : 1);
    }

    /** Mandatory effects go straight into the chain; optional ones ask their controller first. */
    private static void activateNext(GameRoom room) {
        PendingEffect effect = room.getTriggerQueue().get(0);
        EffectScript script = script(effect);
        if (script.limit() != Limit.NONE && room.getUsedThisTurn().contains(usageKey(effect, script))) {
            // Another copy used it meanwhile
            room.getTriggerQueue().remove(0);
            return;
        }
        // Rule 11.7.0.5: the active player's mandatory effects, the opponent's mandatory effects, then the
        // active player's optional ones and the opponent's, all into the same chain
        String active = room.getCurrentTurnUserId();
        int head = rank(effect, active);
        List<PendingEffect> group = new ArrayList<>();
        for (PendingEffect e : room.getTriggerQueue()) {
            if (rank(e, active) != head) break;
            group.add(e);
        }
        if (script.mandatory() && !effect.isOrdered()) {
            // Several mandatory effects: their controller orders them (all of them are chained)
            List<CardSlot> sources = sources(room, group);
            if (sources.size() > 1) {
                rulesPrompt(room, effect.getControllerId(), PURPOSE_ORDER, "Encadenar efectos obligatorios",
                        "Estos efectos son obligatorios: se encadenan todos. Elige cuál entra ahora como Cadena "
                                + (room.getChain().size() + 1) + "; después eliges el siguiente. La cadena se resuelve al revés: "
                                + "el último eslabón se resuelve primero.", "Encadenar", sources, sources);
                room.getPrompt().setMin(1);
                room.getPrompt().setMax(1);
                room.getPrompt().setCardLabels(labels(group));
                return;
            }
        }
        if (!script.mandatory() && !effect.isConfirmed()) {
            // Optional effects that cannot be activated now (cost, nothing to do, limit) lose their moment
            boolean dropped = false;
            for (PendingEffect e : group) {
                EffectScript s = script(e);
                boolean used = s.limit() != Limit.NONE && room.getUsedThisTurn().contains(usageKey(e, s));
                if (used || !canActivate(room, e, s)) {
                    room.getTriggerQueue().remove(e);
                    dropped = true;
                }
            }
            if (dropped) return;
            // Offered one at a time, with the same choice even when only one is left: the chosen one is chained
            // and pays its cost, then the rest are offered again until the player passes; then the opponent
            List<CardSlot> sources = sources(room, group);
            if (!sources.isEmpty()) {
                rulesPrompt(room, effect.getControllerId(), PURPOSE_OPTIONAL, "Encadenar efectos",
                        "Elige 1 efecto para encadenarlo como Cadena " + (room.getChain().size() + 1)
                                + (sources.size() > 1 ? " (después podrás elegir otro)" : "") + ", o no encadenes más. "
                                + "La cadena se resuelve al revés: el último eslabón se resuelve primero.",
                        "Encadenar", sources, sources);
                room.getPrompt().setCardLabels(labels(group));
                return;
            }
        }
        if (script.mandatory() || effect.isConfirmed()) {
            room.getTriggerQueue().remove(0);
            for (EffectStep step : script.cost()) {
                if (!possible(room, effect, step)) {
                    // A cost that cannot be paid (no valid target): it cannot be activated (rule 11.2.0.2)
                    log(room, effect.getSourceName() + ": " + effect.getLabel() + " no puede activarse (no hay objetivos válidos)", null);
                    if (script.moment() == EffectScript.Moment.ON_PLAY) utilityToDiscard(room, effect);
                    return;
                }
            }
            activate(room, effect);
            return;
        }
        if (!canActivate(room, effect, script)) {
            // Its cost cannot be paid or it would do nothing: it loses its moment
            room.getTriggerQueue().remove(0);
            return;
        }
        int link = room.getChain().size() + 1;
        Prompt prompt = prompt(room, effect, Prompt.Kind.CONFIRM, (link == 1 ? "¿Activas este efecto como Cadena 1? "
                : "¿Lo encadenas como Cadena " + link + "? Se resolverá antes que los eslabones que ya están en la cadena. ")
                + effect.getText());
        CardSlot source = findAnywhere(room, effect.getControllerId(), effect.getSourceInstanceId());
        if (source != null) {
            prompt.setCards(new ArrayList<>(List.of(source)));
            prompt.setValidIds(new ArrayList<>(List.of(source.getInstanceId())));
        }
        prompt.setMin(0);
        prompt.setMax(1);
        prompt.setChosenLabel("Activar");
        room.setPrompt(prompt);
    }

    /** The cards of a group of triggered effects, once each. */
    private static List<CardSlot> sources(GameRoom room, List<PendingEffect> group) {
        List<CardSlot> sources = new ArrayList<>();
        for (PendingEffect e : group) {
            CardSlot source = findAnywhere(room, e.getControllerId(), e.getSourceInstanceId());
            if (source != null && !sources.contains(source)) sources.add(source);
        }
        return sources;
    }

    /** What each card of a group triggered ("Celeridad", "En Destrucción"), for the choice. */
    private static Map<String, String> labels(List<PendingEffect> group) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (PendingEffect e : group) labels.merge(e.getSourceInstanceId(), e.getLabel(), (a, b) -> a + " + " + b);
        return labels;
    }

    /** Puts the effect in the chain; its cost, if any, is paid now (rule 11.2.0.2). */
    private static void activate(GameRoom room, PendingEffect effect) {
        EffectScript script = script(effect);
        if (script.limit() != Limit.NONE) room.getUsedThisTurn().add(usageKey(effect, script));
        effect.setLink(room.getChain().size() + 1);
        record(room, GameEvent.EFFECT_ACTIVATED, effect.getControllerId(), null, null, null, effect,
                Map.of("label", effect.getLabel(), "link", effect.getLink()),
                "Cadena " + effect.getLink() + ": se activa el efecto " + effect.getLabel() + " de " + effect.getSourceName());
        // Its cost is paid next (advance), before anything else happens
        room.getChain().add(effect);
    }

    /** The effect is still paying its cost. */
    private static boolean paying(PendingEffect effect) {
        return effect.getCostIndex() < script(effect).cost().size();
    }

    /** An optional effect is offered only when its cost can be paid and its choices have something to choose. */
    private static boolean canActivate(GameRoom room, PendingEffect effect, EffectScript script) {
        for (EffectStep step : script.cost()) if (!possible(room, effect, step)) return false;
        for (EffectStep step : script.steps()) {
            boolean needsTarget = step instanceof DestroyEnemy || step instanceof ChooseEnemy || step instanceof SummonSelfFree
                    || step instanceof SummonSelfFromZone || step instanceof MaterialToLife
                    || step instanceof DestroyFaceUpLife || step instanceof TakeFromZone || step instanceof PlaySelfFromZone
                    || step instanceof SpecialFromZone || step instanceof SummonFromZone || step instanceof RitualFromZone
                    || step instanceof SacrificeAlly || step instanceof UntapAlly || step instanceof PickAlly || step instanceof PlaySelfFree
                    || step instanceof MoveSelfToFront;
            if (needsTarget && !possible(room, effect, step)) return false;
        }
        return true;
    }

    private static PendingEffect pending(GameRoom room, EffectScript script, String controllerId, CardSlot source, long triggerSeq) {
        PendingEffect e = candidate(script, controllerId, source, triggerSeq);
        e.setId(room.nextId("eff"));
        return e;
    }

    /** An effect that could be activated, still without an id. */
    private static PendingEffect candidate(EffectScript script, String controllerId, CardSlot source, long triggerSeq) {
        PendingEffect e = new PendingEffect();
        e.setScriptId(script.id());
        e.setControllerId(controllerId);
        e.setSourceInstanceId(source.getInstanceId());
        e.setSourceName(name(source));
        e.setLabel(script.label());
        e.setText(script.text());
        e.setTriggerSeq(triggerSeq);
        return e;
    }

    /**
     * Rules 11.3.0.3–11.3.0.5: before the chain resolves, the active player and then the opponent may add effects
     * that respond to its last effect; the chain is closed once both pass in a row. A player with nothing to
     * respond with passes on their own.
     *
     * @return true when it waits for a player
     */
    private static boolean offerPriority(GameRoom room) {
        List<PendingEffect> chain = room.getChain();
        PendingEffect top = chain.get(chain.size() - 1);
        if (top.getResolutionId() != null || top.isPriorityDone()) return false;
        String active = room.getCurrentTurnUserId();
        for (String playerId : Arrays.asList(active, active == null ? null : room.getOpponentId(active))) {
            if (playerId == null || top.getPassed().contains(playerId)) continue;
            List<PendingEffect> options = responses(room, playerId, top);
            if (options.isEmpty()) {
                top.getPassed().add(playerId);
                continue;
            }
            List<CardSlot> sources = options.stream().map(e -> findAnywhere(room, playerId, e.getSourceInstanceId()))
                    .filter(Objects::nonNull).distinct().toList();
            rulesPrompt(room, playerId, PURPOSE_PRIORITY, "Responder a " + top.getSourceName(),
                    "Puedes activar un efecto en respuesta a " + top.getLabel() + " de " + top.getSourceName() + ", o pasar.",
                    "Activar", sources, sources);
            return true;
        }
        chain.forEach(e -> e.setPriorityDone(true));
        return false;
    }

    /** Effects a player could add in response to {@code top}: "RESPONSE" effects whose condition accepts its activation. */
    private static List<PendingEffect> responses(GameRoom room, String playerId, PendingEffect top) {
        PlayerState player = room.getPlayer(playerId);
        if (player == null) return List.of();
        GameEvent activation = null;
        for (GameEvent e : room.getTimeline()) {
            if (GameEvent.EFFECT_ACTIVATED.equals(e.getType()) && top.getId().equals(e.getEffectId())) activation = e;
        }
        List<PendingEffect> result = new ArrayList<>();
        for (String zone : List.of("field", "hand")) {
            for (CardSlot card : zone(player, zone)) {
                for (EffectScript script : CardEffects.of(card)) {
                    if (script.moment() != EffectScript.Moment.RESPONSE || !script.sourceZone().equals(zone)) continue;
                    if (script.allyTurn() && !playerId.equals(room.getCurrentTurnUserId())) continue;
                    if (script.condition() != null && (activation == null || !script.condition().test(room, activation, card, playerId))) continue;
                    PendingEffect e = candidate(script, playerId, card, activation == null ? room.getEventSeq() : activation.getSeq());
                    if (script.limit() != Limit.NONE && room.getUsedThisTurn().contains(usageKey(e, script))) continue;
                    if (canActivate(room, e, script)) result.add(e);
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ Slow effects (rule 11.4.1.1)

    /** A slow effect a player can activate now, for the board. */
    public record Activatable(String instanceId, String scriptId, String label, String text) {}

    public static List<Activatable> activatable(GameRoom room, String userId) {
        return slowEffects(room, userId).stream()
                .map(e -> new Activatable(e.getSourceInstanceId(), e.getScriptId(), e.getLabel(), e.getText())).toList();
    }

    /**
     * Passive effects without a trigger condition are slow: only in the open state (no chain, choice or combat
     * pending, main phase), within their moment, zone and limit, and only if all they do is possible.
     */
    private static List<PendingEffect> slowEffects(GameRoom room, String userId) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS || !"MAIN".equals(room.getCurrentPhase()) || room.isEngineBusy()) return List.of();
        PlayerState player = room.getPlayer(userId);
        if (player == null) return List.of();
        List<PendingEffect> result = new ArrayList<>();
        for (String zone : List.of("field", "hand")) {
            for (CardSlot card : zone(player, zone)) {
                for (EffectScript script : CardEffects.of(card)) {
                    if (script.moment() != EffectScript.Moment.ACTIVATED || !script.sourceZone().equals(zone)) continue;
                    if (script.allyTurn() && !userId.equals(room.getCurrentTurnUserId())) continue;
                    if (script.condition() != null && !script.condition().test(room, null, card, userId)) continue;
                    PendingEffect e = candidate(script, userId, card, room.getEventSeq());
                    if (script.limit() != Limit.NONE && room.getUsedThisTurn().contains(usageKey(e, script))) continue;
                    if (canActivate(room, e, script)) result.add(e);
                }
            }
        }
        return result;
    }

    /** Activates a slow effect from the board: it starts a chain (rule 11.5.1). */
    public static String activateByPlayer(GameRoom room, String userId, String instanceId, String scriptId) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return "La partida no está en curso";
        if (room.isEngineBusy()) return room.getCombat() != null ? "Hay un combate en curso" : "Hay un efecto resolviéndose";
        PendingEffect effect = slowEffects(room, userId).stream()
                .filter(e -> e.getSourceInstanceId().equals(instanceId) && (scriptId == null || e.getScriptId().equals(scriptId)))
                .findFirst().orElse(null);
        if (effect == null) return "Ese efecto no se puede activar ahora";
        effect.setId(room.nextId("eff"));
        activate(room, effect);
        advance(room);
        return null;
    }

    // ------------------------------------------------------------------ Resolution

    /** Runs the next step of the last effect in the chain, or finishes it. */
    private static void stepTop(GameRoom room) {
        PendingEffect effect = room.getChain().get(room.getChain().size() - 1);
        if (effect.getResolutionId() == null) effect.setResolutionId(room.nextId("res"));
        EffectScript script = script(effect);
        List<EffectStep> steps = script.steps();
        if (effect.getStepIndex() >= steps.size()) {
            record(room, GameEvent.EFFECT_RESOLVED, effect.getControllerId(), null, null, null, effect, null, null);
            room.getChain().remove(room.getChain().size() - 1);
            if (script.moment() == EffectScript.Moment.ON_PLAY) utilityToDiscard(room, effect);
            return;
        }
        boolean waiting = run(room, effect, steps.get(effect.getStepIndex()));
        if (!waiting) effect.setStepIndex(effect.getStepIndex() + 1);
    }

    /** Rule 6.2.3.1: a utility goes to the Descarte once its effect has resolved. */
    private static void utilityToDiscard(GameRoom room, PendingEffect effect) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot card = player == null ? null : find(player.getField(), effect.getSourceInstanceId());
        if (card == null || !"utility".equals(card.getCardType()) || effect.isKeepOnField()) return;
        player.getField().remove(card);
        leaveField(card);
        player.getDiscardPile().add(card);
        record(room, GameEvent.SEND, player.getUserId(), card, "field", "discardPile", null, null,
                name(card) + " va al descarte tras resolverse");
    }

    /** Whether a step can do anything now (used to offer optional effects and "puedes" choices). */
    private static boolean possible(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return false;
        CardSlot onField = find(player.getField(), effect.getSourceInstanceId());
        return switch (step) {
            case DestroyEnemy destroy -> !enemyTargets(room, effect, destroy.filter()).isEmpty();
            case ChooseEnemy choose -> enemyTargets(room, effect, choose.filter()).size() >= (choose.upTo() ? 1 : choose.count());
            case TributeTop ignored -> !player.getDeck().isEmpty();
            case TributeMaterials tm -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                yield self != null && self.getMaterials().size() >= (tm.upTo() ? 1 : tm.count());
            }
            case MaterialToLife ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                yield self != null && !self.getMaterials().isEmpty();
            }
            case SummonSelfFromZone ss -> {
                CardSlot self = find(zone(player, ss.zone()), effect.getSourceInstanceId());
                yield self != null && self.getCost() != null && canPay(room, player.getUserId(), Math.max(0, self.getCost() - ss.reduce()));
            }
            case TributeFromHand ignored -> !player.getHand().isEmpty();
            case ToDeckTop top -> !zone(player, top.zone()).isEmpty();
            case TributeResource tr -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                yield self != null && self.getResources().stream().anyMatch(r -> tr.name().equalsIgnoreCase(r.getCardName()));
            }
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(effect.getControllerId()));
                yield opponent != null && opponent.getLifeStack().stream().anyMatch(l -> !l.isFaceDown());
            }
            case PlaySelfFromZone play -> find(zone(player, play.zone()), effect.getSourceInstanceId()) != null;
            case SummonFromZone summon -> !summonable(room, effect, summon).isEmpty();
            case SummonSelfFree ignored -> {
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                yield self != null && "creature".equals(self.getCardType()) && self.getCost() != null;
            }
            case SpecialFromZone special -> !specialTargets(room, effect, special).isEmpty();
            case TakeFromZone take -> zone(player, take.zone()).stream().anyMatch(take.filter()::matches);
            case RitualFromZone ritual -> !ritualTargets(room, effect, ritual).isEmpty();
            case SendSelfTo send -> {
                Located at = locate(player, effect.getSourceInstanceId());
                yield at != null && !at.zone().equals(send.zone()) && !"field".equals(at.zone());
            }
            case SendFromZoneToDeckBottom send -> zone(player, send.zone()).stream().filter(send.filter()::matches).count() >= send.count();
            case TapSelf ignored -> onField != null && !onField.isTapped();
            case SacrificeSelf ignored -> onField != null;
            case SacrificeAlly sacrifice -> !(sacrifice.toUntapAnother() ? sacrificeToUntap(player) : allies(player)).isEmpty();
            case UntapAlly untap -> allies(player).stream().anyMatch(c -> reactivable(c) && untap.filter().matches(c));
            case PickAlly pick -> !pickable(player, pick).isEmpty();
            case MoveSelfToFront ignored -> onField != null && !inFrontLine(onField);
            case PlaySelfFree ignored -> find(player.getHand(), effect.getSourceInstanceId()) != null;
            default -> true;
        };
    }

    /** Runs one step. Returns true when it is now waiting for the player's choice. */
    private static boolean run(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return false;
        String userId = player.getUserId();

        switch (step) {
            case LookTop look -> {
                int n = Math.min(look.count(), player.getDeck().size());
                for (int i = 0; i < n; i++) effect.getLooked().add(player.getDeck().remove(0));
                record(room, GameEvent.LOOK_TOP, userId, null, "deck", null, effect, Map.of("count", n),
                        player.getUsername() + " mira las primeras " + n + " cartas de su mazo (" + effect.getSourceName() + ")");
                return false;
            }
            case TakeFromLookedRestToBottom take -> {
                if (effect.getLooked().isEmpty()) return false;
                List<String> valid = effect.getLooked().stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
                Prompt prompt = prompt(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM,
                        "Toma hasta " + take.max() + " " + plural(take.max(), "carta", "cartas") + " " + take.filter().describe()
                                + " para tu mano. Ordena el resto: irá al fondo de tu mazo en ese orden.");
                prompt.setCards(new ArrayList<>(effect.getLooked()));
                prompt.setValidIds(new ArrayList<>(valid));
                prompt.setMin(0);
                prompt.setMax(Math.min(take.max(), valid.size()));
                prompt.setReorderRest(true);
                prompt.setChosenLabel("A tu mano");
                room.setPrompt(prompt);
                return true;
            }
            case TributeFromHand tribute -> {
                if (player.getHand().isEmpty()) return false;
                int n = Math.min(tribute.count(), player.getHand().size());
                pick(room, effect, "Tributa " + n + " " + plural(n, "carta", "cartas") + " de tu mano.", "Tributar",
                        player.getHand(), ids(player.getHand()), n);
                return true;
            }
            case Draw draw -> {
                int n = Math.min(draw.count(), player.getDeck().size());
                for (int i = 0; i < n; i++) drawOne(room, player, effect, false);
                log(room, player.getUsername() + " roba " + n + " " + plural(n, "carta", "cartas") + " (" + effect.getSourceName() + ")"
                        + (n < draw.count() ? ": su mazo no tenía más" : ""), room.getEventSeq());
                return false;
            }
            case DestroyEnemy destroy -> {
                List<CardSlot> valid = enemyTargets(room, effect, destroy.filter());
                if (valid.isEmpty()) {
                    log(room, inRetaguardia(room, effect)
                            ? effect.getSourceName() + " está en la retaguardia: sus efectos no pueden afectar a enemigos"
                            : "No hay enemigos " + destroy.filter().describe() + " en la línea frontal (" + effect.getSourceName() + ")", null);
                    return false;
                }
                int n = Math.min(destroy.count(), valid.size());
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Elige " + n + " " + plural(n, "enemigo", "enemigos") + " " + destroy.filter().describe() + " de la línea frontal: "
                        + plural(n, "será destruido", "serán destruidos") + ".", "Destruir",
                        frontLine(opponent.getField()), ids(valid), n);
                return true;
            }
            case ChooseEnemy choose -> {
                List<CardSlot> valid = enemyTargets(room, effect, choose.filter());
                if (valid.isEmpty()) return false;
                int n = Math.min(choose.count(), valid.size());
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Elige " + (choose.upTo() ? "hasta " : "") + n + " " + plural(n, "enemigo", "enemigos") + " "
                        + choose.filter().describe() + " de la línea frontal: " + (n == 1 ? "es el objetivo" : "son los objetivos") + " del efecto.",
                        "Objetivo", frontLine(opponent.getField()), ids(valid), n);
                if (choose.upTo()) room.getPrompt().setMin(1);
                return true;
            }
            case DestroyChosen ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (String id : effect.getTargetIds()) {
                    CardSlot target = opponent == null ? null : find(frontLine(opponent.getField()), id);
                    if (target == null) {
                        log(room, "El objetivo de " + effect.getSourceName() + " ya no es válido", null);
                        continue;
                    }
                    destroy(room, target, opponent, false, CAUSE_EFFECT, userId, null, effect);
                }
                return false;
            }
            case SummonFromZone summon -> {
                List<CardSlot> valid = summonable(room, effect, summon);
                if (valid.isEmpty()) {
                    log(room, "No hay criaturas que " + effect.getSourceName() + " pueda invocar", null);
                    return false;
                }
                pick(room, effect, "Invoca 1 criatura " + summon.filter().describe() + " de tu " + zoneName(summon.zone())
                                + (summon.reduce() > 0 ? " pagando su coste reducido en " + summon.reduce() : " pagando su coste")
                                + ". Entra en " + (summon.toFront() ? "tu línea frontal" : "Retaguardia") + " y robas 1 carta.", "Invocar",
                        zone(player, summon.zone()), ids(valid), 1);
                return true;
            }
            case SummonSelfFree ignored -> {
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                if (self == null || self.getCost() == null) return false;
                summonByEffect(room, effect, player, self, "hand", self.getCost(), false);
                effect.setLastDone(true);
                return false;
            }
            case SpecialFromZone special -> {
                return runSpecialFromZone(room, effect, player, special);
            }
            case TakeFromZone take -> {
                List<CardSlot> from = zone(player, take.zone());
                List<String> valid = from.stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
                if (valid.isEmpty()) {
                    log(room, "No hay cartas " + take.filter().describe() + " en tus " + zoneName(take.zone()) + " (" + effect.getSourceName() + ")", null);
                    return false;
                }
                int n = Math.min(take.count(), valid.size());
                pick(room, effect, "Toma " + (take.upTo() ? "hasta " : "") + n + " " + plural(n, "carta", "cartas") + " "
                        + take.filter().describe() + " de tus " + zoneName(take.zone()) + " para tu mano.", "A tu mano", from, valid, n);
                if (take.upTo()) room.getPrompt().setMin(0);
                return true;
            }
            case TributeMaterials tm -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || self.getMaterials().isEmpty()) return false;
                int n = Math.min(tm.count(), self.getMaterials().size());
                pick(room, effect, "Tributa " + (tm.upTo() ? "hasta " : "") + n + " " + plural(n, "material", "materiales") + " de "
                        + name(self) + ".", "Tributar", self.getMaterials(), ids(self.getMaterials()), n);
                if (tm.upTo()) room.getPrompt().setMin(1);
                return true;
            }
            case TakePerCount take -> {
                int n = effect.getLastCount();
                List<CardSlot> from = zone(player, take.zone());
                List<String> valid = from.stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
                if (n <= 0 || valid.isEmpty()) return false;
                n = Math.min(n, valid.size());
                pick(room, effect, "Toma " + n + " " + plural(n, "carta", "cartas") + " " + take.filter().describe() + " de tus "
                        + zoneName(take.zone()) + " para tu mano.", "A tu mano", from, valid, n);
                return true;
            }
            case TributeFromHandUpTo up -> {
                if (player.getHand().isEmpty()) {
                    effect.setLastCount(0);
                    return false;
                }
                int n = Math.min(up.count(), player.getHand().size());
                pick(room, effect, "Tributa hasta " + n + " " + plural(n, "carta", "cartas") + " de tu mano.", "Tributar",
                        player.getHand(), ids(player.getHand()), n);
                room.getPrompt().setMin(0);
                return true;
            }
            case DrawLastCount ignored -> {
                int n = Math.min(effect.getLastCount(), player.getDeck().size());
                for (int i = 0; i < n; i++) drawOne(room, player, effect, false);
                if (n > 0) log(room, player.getUsername() + " roba " + n + " " + plural(n, "carta", "cartas") + " (" + effect.getSourceName() + ")", room.getEventSeq());
                return false;
            }
            case IfTributed ift -> {
                boolean match = effect.getLastTributed().stream()
                        .map(id -> findAnywhere(room, userId, id)).anyMatch(c -> c != null && ift.filter().matches(c));
                if (!match) effect.setStepIndex(effect.getStepIndex() + ift.covers());
                return false;
            }
            case TargetAllEnemies ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                effect.setTargetIds(opponent == null ? new ArrayList<>() : new ArrayList<>(ids(frontLine(opponent.getField()).stream()
                        .filter(c -> "creature".equals(c.getCardType())).toList())));
                return false;
            }
            case ReduceChosen reduce -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (String id : effect.getTargetIds()) {
                    CardSlot target = opponent == null ? null : find(frontLine(opponent.getField()), id);
                    if (target == null) continue;
                    reduceStrength(room, effect, opponent, target, reduce.amount(), reduce.untilNextTurn());
                }
                return false;
            }
            case ReduceTriggerToZero ignored -> {
                GameEvent trigger = room.getTimeline().stream().filter(e -> e.getSeq() == effect.getTriggerSeq()).findFirst().orElse(null);
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                CardSlot target = trigger == null || opponent == null ? null : find(opponent.getField(), trigger.getCardInstanceId());
                if (target != null && strength(target) > 0) reduceStrength(room, effect, opponent, target, strength(target), false);
                return false;
            }
            case MaterialToLife ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || self.getMaterials().isEmpty()) return false;
                pick(room, effect, "Coloca hasta 1 material de " + name(self) + " al tope de tus vidas, bocarriba.", "A tus vidas",
                        self.getMaterials(), ids(self.getMaterials()), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case DeckTopToLife ignored -> {
                if (player.getDeck().isEmpty()) return false;
                CardSlot card = player.getDeck().remove(0);
                card.setFaceDown(true);
                player.getLifeStack().add(0, card);
                record(room, GameEvent.SEND, userId, card, "deck", "lifeStack", effect, null,
                        player.getUsername() + " coloca la carta del tope de su mazo como vida (" + effect.getSourceName() + ")");
                return false;
            }
            case DamageByEffect damage -> {
                damageByEffect(room, effect, player, damage.amount());
                return false;
            }
            case SummonSelfFromZone ss -> {
                CardSlot self = find(zone(player, ss.zone()), effect.getSourceInstanceId());
                if (self == null || self.getCost() == null) return false;
                summonByEffect(room, effect, player, self, ss.zone(), ss.reduce(), false);
                return false;
            }
            case AttachLowerFromTributes attach -> {
                GameEvent trigger = room.getTimeline().stream().filter(e -> e.getSeq() == effect.getTriggerSeq()).findFirst().orElse(null);
                CardSlot ally = trigger == null ? null : find(player.getField(), trigger.getCardInstanceId());
                if (ally == null || ally.getLevel() == null) return false;
                List<String> valid = player.getTributeZone().stream()
                        .filter(c -> attach.filter().matches(c) && c.getLevel() != null && c.getLevel() < ally.getLevel())
                        .map(CardSlot::getInstanceId).toList();
                if (valid.isEmpty()) return false;
                effect.setSubjectId(ally.getInstanceId());
                pick(room, effect, "Añade a " + name(ally) + " 1 criatura " + attach.filter().describe() + " de menor nivel de tus tributos como material.",
                        "Material", player.getTributeZone(), valid, 1);
                return true;
            }
            case KeepOnField ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self != null) {
                    self.setTapped(true);
                    effect.setKeepOnField(true);
                    log(room, name(self) + " queda agotada en la retaguardia", null);
                }
                return false;
            }
            case TributeTop top -> {
                int n = Math.min(top.count(), player.getDeck().size());
                for (int i = 0; i < n; i++) {
                    CardSlot card = player.getDeck().remove(0);
                    card.setFaceDown(false);
                    player.getTributeZone().add(card);
                    record(room, GameEvent.TRIBUTE, userId, card, "deck", "tributeZone", effect,
                            Map.of("byCard", effect.getSourceInstanceId()), null);
                }
                log(room, player.getUsername() + " tributa " + n + " " + plural(n, "carta", "cartas") + " del tope de su mazo ("
                        + effect.getSourceName() + ")", room.getEventSeq());
                return false;
            }
            case ToDeckTop top -> {
                List<CardSlot> from = zone(player, top.zone());
                if (from.isEmpty()) return false;
                int n = Math.min(top.count(), from.size());
                pick(room, effect, "Coloca " + (top.upTo() ? "hasta " : "") + n + " " + plural(n, "carta", "cartas") + " de tu "
                        + zoneName(top.zone()) + " al tope de tu mazo.", "Al tope", from, ids(from), n);
                if (top.upTo()) room.getPrompt().setMin(0);
                return true;
            }
            case TributeResource tr -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                CardSlot resource = self == null ? null
                        : self.getResources().stream().filter(r -> tr.name().equalsIgnoreCase(r.getCardName())).findFirst().orElse(null);
                if (resource == null) return false;
                self.getResources().remove(resource);
                resource.setFaceDown(false);
                player.getTributeZone().add(resource);
                record(room, GameEvent.TRIBUTE, userId, resource, "resources", "tributeZone", effect, null,
                        player.getUsername() + " tributa " + name(resource) + " de los recursos de " + name(self));
                return false;
            }
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                List<CardSlot> valid = opponent == null ? List.of() : opponent.getLifeStack().stream().filter(l -> !l.isFaceDown()).toList();
                if (valid.isEmpty()) return false;
                pick(room, effect, "Puedes destruir 1 carta bocarriba de las vidas de tu rival, o ninguna.", "Destruir",
                        opponent.getLifeStack(), ids(valid), 1);
                room.getPrompt().setMin(0);
                return true;
            }
            case GainUntilEndOfAttack gain -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                subject.getTurnKeywords().add(gain.keyword());
                if (room.getCombat() != null) room.getCombat().getUntilEndOfAttack().add(subject.getInstanceId() + "|" + gain.keyword());
                record(room, GameEvent.GAIN, userId, subject, null, null, effect, Map.of("keyword", gain.keyword()),
                        name(subject) + " gana " + gain.keyword() + " hasta el final del ataque");
                return false;
            }
            case PlaySelfFromZone play -> {
                CardSlot self = find(zone(player, play.zone()), effect.getSourceInstanceId());
                if (self == null) return false;
                int reduce = play.reduceBySourceLevel() ? triggerSourceLevel(room, effect) : 0;
                int cost = Math.max(0, (self.getCost() == null ? 0 : self.getCost()) - reduce);
                if (!canPay(room, userId, cost)) {
                    log(room, "No tienes gemas para jugar " + name(self) + " (" + cost + ")", null);
                    return false;
                }
                pay(room, userId, cost);
                zone(player, play.zone()).remove(self);
                player.getHand().add(self);
                play(room, player, self, cost, null, null, effect);
                return false;
            }
            case IfDone ifDone -> {
                if (!effect.isLastDone()) effect.setStepIndex(effect.getStepIndex() + ifDone.covers());
                effect.setLastDone(false);
                return false;
            }
            case GainStrength gain -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                subject.setTurnStrength(subject.getTurnStrength() + gain.amount());
                record(room, GameEvent.GAIN, userId, subject, null, null, effect, Map.of("strength", gain.amount()),
                        name(subject) + " gana fuerza " + gain.amount() + " hasta el final del turno");
                return false;
            }
            case RitualFromZone ritual -> {
                return runRitual(room, effect, player, ritual);
            }
            case SendSelfTo send -> {
                Located at = locate(player, effect.getSourceInstanceId());
                if (at == null || at.zone().equals(send.zone())) return false;
                zone(player, at.zone()).remove(at.card());
                at.card().setFaceDown(false);
                zone(player, send.zone()).add(at.card());
                record(room, GameEvent.SEND, userId, at.card(), at.zone(), send.zone(), effect, null,
                        player.getUsername() + " envía " + name(at.card()) + " a su " + zoneName(send.zone()));
                return false;
            }
            case SendFromZoneToDeckBottom send -> {
                List<CardSlot> from = zone(player, send.zone());
                List<String> valid = from.stream().filter(send.filter()::matches).map(CardSlot::getInstanceId).toList();
                int n = Math.min(send.count(), valid.size());
                if (n == 0) return false;
                pick(room, effect, "Elige " + n + " " + plural(n, "carta", "cartas") + " " + send.filter().describe() + " de tus "
                        + zoneName(send.zone()) + ": irán al fondo de tu mazo en el orden en que las elijas.", "Al fondo", from, valid, n);
                return true;
            }
            case UntapSelf ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || !self.isTapped()) return false;
                self.setTapped(false);
                record(room, GameEvent.UNTAP, userId, self, null, null, effect, null,
                        player.getUsername() + " reactiva " + name(self) + " (" + effect.getSourceName() + ")");
                return false;
            }
            case TapSelf ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || self.isTapped()) return false;
                self.setTapped(true);
                record(room, GameEvent.TAP, userId, self, null, null, effect, null, player.getUsername() + " agota " + name(self));
                return false;
            }
            case GainUntilEndOfTurn gain -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                subject.getTurnKeywords().add(gain.keyword());
                record(room, GameEvent.GAIN, userId, subject, null, null, effect, Map.of("keyword", gain.keyword()),
                        name(subject) + " gana " + gain.keyword() + " hasta el final del turno");
                return false;
            }
            case GainGems gems -> {
                room.setSharedCounter(clamp(room.getSharedCounter() + gems.count() * direction(room, userId)));
                record(room, GameEvent.GAIN_GEMS, userId, null, null, null, effect, Map.of("count", gems.count()),
                        player.getUsername() + " gana " + gemsText(gems.count()) + " (" + effect.getSourceName() + ")");
                return false;
            }
            case DeclareAttack declare -> {
                CardSlot subject = subject(room, effect);
                if (subject == null) return false;
                if (room.getCombat() != null) {
                    log(room, name(subject) + " no puede atacar: ya hay un combate en curso", null);
                    return false;
                }
                if (!inFrontLine(subject)) {
                    log(room, name(subject) + " no puede atacar: no está en la línea frontal", null);
                    return false;
                }
                List<CardSlot> targets = declare.opponentOnly() ? List.of() : attackTargets(room, userId, subject);
                if (targets.isEmpty()) {
                    startCombat(room, userId, subject, null, true, effect);
                    return false;
                }
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                Prompt prompt = prompt(room, effect, Prompt.Kind.PICK,
                        name(subject) + " declara ataque: elige una criatura agotada a la que atacar, o ninguna para atacar a tu rival.");
                prompt.setCards(new ArrayList<>(opponent.getField()));
                prompt.setValidIds(new ArrayList<>(ids(targets)));
                prompt.setMin(0);
                prompt.setMax(1);
                prompt.setChosenLabel("Atacar");
                room.setPrompt(prompt);
                return true;
            }
            case PickAlly pick -> {
                List<CardSlot> valid = pickable(player, pick);
                if (valid.isEmpty()) {
                    // Nothing to act on: the rest of the effect does nothing
                    log(room, "No hay aliados que puedan elegirse (" + effect.getSourceName() + ")", null);
                    effect.setStepIndex(script(effect).steps().size() - 1);
                    return false;
                }
                pick(room, effect, pick.attacker() ? "Elige 1 aliado de tu línea frontal: declarará ataque." : "Elige 1 aliado.",
                        "Elegido", player.getField(), ids(valid), 1);
                return true;
            }
            case SacrificeSelf ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self != null) destroy(room, self, player, true, CAUSE_EFFECT, userId, null, effect);
                return false;
            }
            case SacrificeAlly sacrifice -> {
                List<CardSlot> valid = sacrifice.toUntapAnother() ? sacrificeToUntap(player) : allies(player);
                if (valid.isEmpty()) return false;
                pick(room, effect, sacrifice.toUntapAnother()
                        ? "Sacrifica 1 aliado. No puede ser el único aliado agotado que queda por reactivar."
                        : "Sacrifica 1 aliado.", "Sacrificar", player.getField(), ids(valid), 1);
                return true;
            }
            case UntapAlly untap -> {
                List<CardSlot> valid = allies(player).stream().filter(c -> reactivable(c) && untap.filter().matches(c)).toList();
                if (valid.isEmpty()) {
                    log(room, "No hay aliados agotados que reactivar (" + effect.getSourceName() + ")", null);
                    return false;
                }
                String which = untap.filter().describe();
                pick(room, effect, "Reactiva 1 aliado agotado" + (which.isEmpty() ? "" : " " + which) + ".", "Reactivar", player.getField(), ids(valid), 1);
                return true;
            }
            case MoveSelfToFront ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || inFrontLine(self)) return false;
                self.setY(25.0);
                record(room, GameEvent.MOVE, userId, self, null, "frontLine", effect, null,
                        name(self) + " sube a la línea frontal (" + effect.getLabel() + ")");
                return false;
            }
            case PlaySelfFree ignored -> {
                CardSlot self = find(player.getHand(), effect.getSourceInstanceId());
                if (self != null) play(room, player, self, 0, null, null, effect);
                return false;
            }
            case MayDo may -> {
                List<EffectStep> steps = script(effect).steps();
                int next = effect.getStepIndex() + 1;
                if (next >= steps.size() || !possible(room, effect, steps.get(next))) {
                    effect.setStepIndex(effect.getStepIndex() + may.covers());
                    return false;
                }
                Prompt prompt = prompt(room, effect, Prompt.Kind.CONFIRM, may.question());
                CardSlot source = findAnywhere(room, userId, effect.getSourceInstanceId());
                if (source != null) {
                    prompt.setCards(new ArrayList<>(List.of(source)));
                    prompt.setValidIds(new ArrayList<>(List.of(source.getInstanceId())));
                }
                prompt.setMin(0);
                prompt.setMax(1);
                prompt.setChosenLabel("Sí");
                room.setPrompt(prompt);
                return true;
            }
        }
    }

    private static void pick(GameRoom room, PendingEffect effect, String instruction, String chosenLabel,
                             List<CardSlot> cards, List<String> validIds, int count) {
        Prompt prompt = prompt(room, effect, Prompt.Kind.PICK, instruction);
        prompt.setCards(new ArrayList<>(cards));
        prompt.setValidIds(new ArrayList<>(validIds));
        prompt.setMin(count);
        prompt.setMax(count);
        prompt.setChosenLabel(chosenLabel);
        room.setPrompt(prompt);
    }

    private static Prompt prompt(GameRoom room, PendingEffect effect, Prompt.Kind kind, String instruction) {
        Prompt p = new Prompt();
        p.setId(room.nextId("prompt"));
        p.setKind(kind);
        p.setPlayerId(effect.getControllerId());
        p.setEffectId(effect.getId());
        p.setSourceName(effect.getSourceName());
        p.setTitle((effect.getLink() > 0 ? "Cadena " + effect.getLink() + " · " : "") + effect.getSourceName() + " · " + effect.getLabel());
        p.setInstruction(instruction);
        return p;
    }

    /**
     * Enemies the effect may choose: the opponent's Línea frontal (rule 4.0.6.2), matching the filter. A creature
     * in the Retaguardia cannot affect enemies with its effects.
     */
    private static List<CardSlot> enemyTargets(GameRoom room, PendingEffect effect, CardFilter filter) {
        if (inRetaguardia(room, effect)) return List.of();
        PlayerState opponent = room.getPlayer(room.getOpponentId(effect.getControllerId()));
        if (opponent == null) return List.of();
        return frontLine(opponent.getField()).stream().filter(c -> "creature".equals(c.getCardType())).filter(filter::matches).toList();
    }

    /** Enemy creatures that can be attacked (rule 10.0.0.1): exhausted ones, or active ones the attacker can face (Enfrentar). */
    private static List<CardSlot> attackTargets(GameRoom room, String userId, CardSlot attacker) {
        PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
        if (opponent == null) return List.of();
        return opponent.getField().stream()
                .filter(c -> "creature".equals(c.getCardType()) && (c.isTapped() || challenges(attacker, c))).toList();
    }

    /**
     * Reduces an enemy's strength by an effect, until the end of this turn or of the next one; at 0 it is
     * destroyed by that effect (rule 12.0.0.2). Records it: "si reduces la fuerza …" effects trigger from it.
     */
    private static void reduceStrength(GameRoom room, PendingEffect effect, PlayerState owner, CardSlot target, int amount, boolean untilNextTurn) {
        if (untilNextTurn) target.setLaterStrength(target.getLaterStrength() - amount);
        else target.setTurnStrength(target.getTurnStrength() - amount);
        int now = Math.max(0, strength(target));
        record(room, GameEvent.STRENGTH_REDUCED, effect.getControllerId(), target, null, null, effect,
                Map.of("amount", amount, "to", now, "ownerId", owner.getUserId()),
                name(target) + " pierde " + amount + " de fuerza (" + effect.getSourceName() + "): queda en " + now);
        if (now <= 0) destroy(room, target, owner, false, CAUSE_EFFECT, effect.getControllerId(), null, effect);
    }

    /** Direct damage by an effect to the opponent's top life: a creature with that strength or less is tributed, the rest go to the hand. */
    private static void damageByEffect(GameRoom room, PendingEffect effect, PlayerState player, int damage) {
        PlayerState defender = room.getPlayer(room.getOpponentId(player.getUserId()));
        if (defender == null || defender.getLifeStack().isEmpty()) return;
        CardSlot life = defender.getLifeStack().remove(0);
        life.setFaceDown(false);
        boolean tribute = "creature".equals(life.getCardType()) && (life.getStrength() == null || life.getStrength() <= damage);
        (tribute ? defender.getTributeZone() : defender.getHand()).add(life);
        record(room, GameEvent.LIFE_DAMAGE, player.getUserId(), life, "lifeStack", tribute ? "tributeZone" : "hand", effect,
                Map.of("damage", damage, "attackerId", effect.getSourceInstanceId(), "defenderId", defender.getUserId()),
                effect.getSourceName() + " inflige " + damage + " de daño directo: " + defender.getUsername() + " pierde la vida "
                        + name(life) + (tribute ? ", que va a sus tributos" : ", que va a su mano"));
        if (defender.getLifeStack().isEmpty()) {
            record(room, GameEvent.GAME_LOST, defender.getUserId(), null, null, null, null, Map.of("reason", "lives"),
                    defender.getUsername() + " se quedó sin vidas: pierde la partida");
            room.setWinnerId(player.getUserId());
            room.setEndReason("lives");
            room.setCombat(null);
            room.getChain().clear();
            room.getTriggerQueue().clear();
            room.getPendingEvents().clear();
            room.getPostGameChoices().clear();
            room.setStatus(GameRoom.RoomStatus.POST_GAME);
        }
    }

    /** Enfrentar: "puede atacar enemigos activos"; Enfrentar N, only those of level N or less. */
    public static boolean challenges(CardSlot attacker, CardSlot target) {
        Integer level = attacker == null ? null : keywordLevel(attacker, "Enfrentar");
        return level != null && (level == 0 || (target.getLevel() != null && target.getLevel() <= level));
    }

    private static List<CardSlot> allies(PlayerState player) {
        return player.getField().stream().filter(c -> "creature".equals(c.getCardType())).toList();
    }

    /** Allies "elige 1 aliado" may choose; for an attack, only those in the Línea frontal (rule 10.1.1.1). */
    private static List<CardSlot> pickable(PlayerState player, PickAlly pick) {
        return allies(player).stream().filter(c -> !pick.attacker() || inFrontLine(c)).toList();
    }

    /** The effect's card is a creature in its owner's Retaguardia. */
    private static boolean inRetaguardia(GameRoom room, PendingEffect effect) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        CardSlot source = player == null ? null : find(player.getField(), effect.getSourceInstanceId());
        return source != null && "creature".equals(source.getCardType()) && !inFrontLine(source);
    }

    /**
     * Whether a card can be reactivated: an exhausted creature. Effects that forbid reactivating a creature
     * will be checked here once a card has one.
     */
    static boolean reactivable(CardSlot card) {
        return "creature".equals(card.getCardType()) && card.isTapped();
    }

    /** Allies that may be sacrificed "para reactivar otro": another ally that can be reactivated must remain. */
    private static List<CardSlot> sacrificeToUntap(PlayerState player) {
        List<CardSlot> allies = allies(player);
        return allies.stream().filter(s -> allies.stream().anyMatch(r -> r != s && reactivable(r))).toList();
    }

    /** The card the steps act on: the ally chosen by "elige 1 aliado", or the effect's own card. */
    private static CardSlot subject(GameRoom room, PendingEffect effect) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return null;
        return find(player.getField(), effect.getSubjectId() != null ? effect.getSubjectId() : effect.getSourceInstanceId());
    }

    /** Creatures of the zone the effect may summon: with a normal cost the controller can pay after the reduction. */
    private static List<CardSlot> summonable(GameRoom room, PendingEffect effect, SummonFromZone summon) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        return zone(player, summon.zone()).stream()
                .filter(c -> !c.getInstanceId().equals(effect.getSourceInstanceId()))
                .filter(c -> "creature".equals(c.getCardType()) && c.getCost() != null)
                .filter(summon.filter()::matches)
                .filter(c -> canPay(room, player.getUserId(), Math.max(0, c.getCost() - summon.reduce())))
                .toList();
    }

    public static boolean inFrontLine(CardSlot card) {
        return card.getY() != null && card.getY() < 50;
    }

    private static List<CardSlot> frontLine(List<CardSlot> field) {
        return field.stream().filter(EffectEngine::inFrontLine).toList();
    }

    /** Summon by effect (rule 9.1.0.2): pays the cost, enters the Retaguardia, then the regulatory draw (9.1.0.4). */
    private static void summonByEffect(GameRoom room, PendingEffect effect, PlayerState player, CardSlot card, String fromZone, int reduce,
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
    }

    private static void regulatoryDraw(GameRoom room, PlayerState player, PendingEffect effect) {
        if (player.getDeck().isEmpty()) return;
        drawOne(room, player, effect, true);
        log(room, player.getUsername() + " roba 1 carta por la invocación", room.getEventSeq());
    }

    private static void drawOne(GameRoom room, PlayerState player, PendingEffect effect, boolean regulatory) {
        CardSlot card = player.getDeck().remove(0);
        card.setFaceDown(false);
        player.getHand().add(card);
        record(room, GameEvent.DRAW, player.getUserId(), card, "deck", "hand", effect, regulatory ? Map.of("regulatory", true) : null, null);
    }

    // ------------------------------------------------------------------ Ritual by effect (rule 9.3)

    /** Requerimiento of a Ritual: one creature of level X, or several whose levels add up to X. */
    private record Requirement(boolean sum, int level) {}

    private static Requirement requirement(CardSlot card) {
        if (card.getRequirement() == null) return null;
        String text = card.getRequirement().toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("nivel\\s*(\\d+)|sumen\\s*(\\d+)").matcher(text);
        if (!m.find()) return null;
        int level = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
        return new Requirement(text.contains("sum"), level);
    }

    /** Creatures that can be the summoner of a level-sum Ritual: the rest of the field can complete the sum exactly. */
    private static List<CardSlot> summonerCandidates(List<CardSlot> materials, int level) {
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
    private static boolean shares(CardSlot used, CardSlot summoned) {
        boolean color = used.getColors().stream().anyMatch(summoned.getColors()::contains);
        boolean type = used.getClasses().stream().anyMatch(t -> summoned.getClasses().stream().anyMatch(t::equalsIgnoreCase));
        return color || type;
    }

    /** Creatures that may be used for the Ritual of {@code target}. */
    private static List<CardSlot> ritualMaterials(PlayerState player, CardSlot target) {
        return player.getField().stream().filter(c -> "creature".equals(c.getCardType()) && c.getLevel() != null && shares(c, target)).toList();
    }

    private static boolean ritualFeasible(GameRoom room, PendingEffect effect, RitualFromZone ritual, PlayerState player, CardSlot target) {
        Requirement req = requirement(target);
        if (req == null || target.getSpecialCost() == null) return false;
        if (!canPay(room, player.getUserId(), Math.max(0, target.getSpecialCost() - ritual.reduce()))) return false;
        List<CardSlot> materials = ritualMaterials(player, target);
        if (ritual.selfSummoner()) {
            CardSlot self = find(materials, effect.getSourceInstanceId());
            if (self == null) return false;
            if (!req.sum()) return self.getLevel() == req.level();
            // The summoner is one of them: the others make up the rest
            return self.getLevel() <= req.level() && sumsExactly(materials.stream().filter(c -> c != self).toList(), req.level() - self.getLevel());
        }
        if (!req.sum()) return materials.stream().anyMatch(c -> c.getLevel() == req.level());
        return sumsExactly(materials, req.level());
    }

    private static List<CardSlot> ritualTargets(GameRoom room, PendingEffect effect, RitualFromZone ritual) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return List.of();
        return zone(player, ritual.zone()).stream()
                .filter(c -> "creature".equals(c.getCardType()) && "ritual".equalsIgnoreCase(c.getSpecialSummonKind()))
                .filter(c -> ritualFeasible(room, effect, ritual, player, c))
                .toList();
    }

    private static boolean runRitual(GameRoom room, PendingEffect effect, PlayerState player, RitualFromZone ritual) {
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
                    : materials.stream().filter(c -> c.getLevel() == req.level()).toList();
            Prompt prompt = prompt(room, effect, Prompt.Kind.PICK, "Elige el invocador" + (req.sum() ? "" : ": 1 criatura nivel " + req.level())
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

    /** Ritual by effect (rule 9.3): pays the special cost minus the reduction and summons it. */
    private static void performRitual(GameRoom room, PendingEffect effect, PlayerState player, CardSlot target, RitualFromZone ritual,
                                      CardSlot summoner, List<CardSlot> others) {
        int cost = Math.max(0, target.getSpecialCost() - ritual.reduce());
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
    private static void ritualSummon(GameRoom room, PlayerState player, CardSlot target, String fromZone, int cost,
                                     CardSlot summoner, List<CardSlot> others, PendingEffect effect, String logText) {
        inheritFromSummoner(room, target, summoner);
        destroy(room, summoner, player, true, CAUSE_RITUAL, player.getUserId(), null, effect);
        for (CardSlot other : others) destroy(room, other, player, true, CAUSE_RITUAL, player.getUserId(), null, effect);
        player.getField().add(target);
        record(room, GameEvent.SPECIAL_SUMMON, player.getUserId(), target, fromZone, "field", effect,
                Map.of("summonType", "ritual", "cost", cost), logText);
        afterSpecialSummon(room, player, target, effect);
    }

    // ------------------------------------------------------------------ Answers

    /**
     * The player's answer to the pending prompt.
     *
     * @param chosen chosen card instance ids
     * @param order  for prompts with {@code reorderRest}: the cards that were not chosen, in the order they go to the deck bottom
     * @return an error message, or null when accepted
     */
    public static String answer(GameRoom room, String userId, String promptId, List<String> chosen, List<String> order) {
        Prompt prompt = room.getPrompt();
        if (prompt == null || !prompt.getId().equals(promptId)) return "No hay ninguna elección pendiente";
        if (!prompt.getPlayerId().equals(userId)) return "Esta elección le corresponde al otro jugador";
        List<String> picks = chosen == null ? List.of() : chosen;
        if (new HashSet<>(picks).size() != picks.size()) return "Elegiste la misma carta dos veces";
        if (picks.size() < prompt.getMin() || picks.size() > prompt.getMax()) {
            return prompt.getMin() == prompt.getMax()
                    ? "Debes elegir " + prompt.getMin() + " " + plural(prompt.getMin(), "carta", "cartas")
                    : "Puedes elegir hasta " + prompt.getMax() + " " + plural(prompt.getMax(), "carta", "cartas");
        }
        if (!prompt.getValidIds().containsAll(picks)) return "Esa carta no cumple lo que pide el efecto";
        PlayerState player = room.getPlayer(userId);
        if (player == null) return "No estás en la partida";

        // Creatures that move up to the Línea frontal, all at once
        if (PURPOSE_ADVANCE.equals(prompt.getPurpose())) {
            room.setPrompt(null);
            List<String> moved = new ArrayList<>();
            for (String id : picks) {
                CardSlot card = find(player.getField(), id);
                if (card == null || inFrontLine(card)) continue;
                card.setY(25.0);
                moved.add(name(card));
                record(room, GameEvent.MOVE, userId, card, null, "frontLine", null, Map.of("reason", "advance"), null);
            }
            if (!moved.isEmpty()) log(room, player.getUsername() + " sube a la línea frontal: " + String.join(", ", moved), room.getEventSeq());
            advance(room);
            return null;
        }

        String other = answerRules(room, prompt, player, picks);
        if (other != null) return other.isEmpty() ? null : other;

        // Combat choices: the blocker, or the enemy forced to block by Provocar
        if (prompt.getPurpose() != null) {
            Combat c = room.getCombat();
            room.setPrompt(null);
            if (c != null && !picks.isEmpty()) {
                PlayerState defender = room.getPlayer(c.getDefenderId());
                CardSlot blocker = find(defender.getField(), picks.get(0));
                if (blocker != null) {
                    blocker.setTapped(true);
                    c.setBlockerId(blocker.getInstanceId());
                    boolean forced = PURPOSE_PROVOKE.equals(prompt.getPurpose());
                    record(room, GameEvent.BLOCK, defender.getUserId(), blocker, null, null, null, Map.of("forced", forced),
                            name(blocker) + (forced ? " debe bloquear (Provocar)" : " bloquea el ataque"));
                }
            }
            advance(room);
            return null;
        }

        // An optional triggered effect: activate it or let it go
        List<PendingEffect> queue = room.getTriggerQueue();
        if (!queue.isEmpty() && queue.get(0).getId().equals(prompt.getEffectId())) {
            PendingEffect effect = queue.remove(0);
            room.setPrompt(null);
            if (!picks.isEmpty()) {
                activate(room, effect);
            } else {
                record(room, GameEvent.EFFECT_DECLINED, userId, null, null, null, effect, null,
                        player.getUsername() + " no activa el efecto " + effect.getLabel() + " de " + effect.getSourceName());
            }
            advance(room);
            return null;
        }

        PendingEffect effect = room.getChain().stream().filter(e -> e.getId().equals(prompt.getEffectId())).findFirst().orElse(null);
        if (effect == null) return "El efecto ya no está pendiente";
        boolean paying = paying(effect);
        EffectStep step = paying ? script(effect).cost().get(effect.getCostIndex()) : script(effect).steps().get(effect.getStepIndex());
        boolean[] repeat = {false};
        // Cleared first: the step may ask something else right away (a special summon's move up)
        room.setPrompt(null);
        String error = apply(room, effect, step, player, picks, order, repeat);
        if (error != null) {
            room.setPrompt(prompt);
            return error;
        }
        if (!repeat[0]) {
            if (paying) effect.setCostIndex(effect.getCostIndex() + 1);
            else effect.setStepIndex(effect.getStepIndex() + 1);
        }
        advance(room);
        return null;
    }

    /**
     * Answers to choices made by the rules rather than by an effect step: Ritual from the hand, moving up after a
     * special summon, Reforzar, playing a life, the order of simultaneous triggers and responding to a chain.
     *
     * @return null if the prompt is not one of these, "" when done, or an error
     */
    private static String answerRules(GameRoom room, Prompt prompt, PlayerState player, List<String> picks) {
        String userId = player.getUserId();
        String purpose = prompt.getPurpose();
        if (PURPOSE_RITUAL.equals(purpose)) {
            if (picks.isEmpty()) {
                room.setPrompt(null);
                advance(room);
                return "";
            }
            CardSlot card = find(player.getHand(), prompt.getSubjectId());
            if (card == null) {
                room.setPrompt(null);
                return "Esa carta ya no está en tu mano";
            }
            if (prompt.getSummonerId() == null) {
                // This answer is the summoner: the Ritual goes on as if the card had been dropped onto it
                room.setPrompt(null);
                String error = ritualByPlayer(room, userId, card.getInstanceId(), picks.get(0));
                if (error != null) {
                    room.setPrompt(prompt);
                    return error;
                }
                return "";
            }
            List<CardSlot> used = new ArrayList<>();
            for (String id : picks) used.add(find(player.getField(), id));
            CardSlot summoner = prompt.getSummonerId() != null ? find(player.getField(), prompt.getSummonerId()) : used.get(0);
            if (summoner == null || used.contains(null)) return "Esa criatura ya no está en el campo";
            List<CardSlot> others = new ArrayList<>(used);
            others.remove(summoner);
            Requirement req = requirement(card);
            int total = summoner.getLevel() + others.stream().mapToInt(CardSlot::getLevel).sum();
            if (req.sum() && total != req.level()) return "Los niveles suman " + total + ": deben sumar exactamente " + req.level();
            if (!canPay(room, userId, ritualCost(room, userId, card))) return "Ya no tienes gemas suficientes";
            room.setPrompt(null);
            handRitual(room, player, card, summoner, others);
            return "";
        }
        if (PURPOSE_SPECIAL.equals(purpose)) {
            CardSlot card = find(player.getHand(), prompt.getSubjectId());
            if (picks.isEmpty() || card == null) {
                room.setPrompt(null);
                advance(room);
                return card == null ? "Esa carta ya no está en tu mano" : "";
            }
            if (prompt.getSummonerId() == null) {
                room.setPrompt(null);
                String error = specialByPlayer(room, userId, card.getInstanceId(), picks.get(0));
                if (error != null) {
                    room.setPrompt(prompt);
                    return error;
                }
                return "";
            }
            CardSlot summoner = find(player.getField(), prompt.getSummonerId());
            CardSlot material = find(player.getTributeZone(), picks.get(0));
            if (material == null) material = find(player.getField(), picks.get(0));
            if (summoner == null || material == null) return "Esa criatura ya no está disponible";
            if (!canPay(room, userId, card.getSpecialCost())) return "Ya no tienes gemas suficientes";
            room.setPrompt(null);
            handSpecial(room, player, card, specialRequirement(card), summoner, material);
            return "";
        }
        if (PURPOSE_SPECIAL_FRONT.equals(purpose)) {
            room.setPrompt(null);
            CardSlot card = find(player.getField(), prompt.getSubjectId());
            if (!picks.isEmpty() && card != null && !inFrontLine(card)) {
                card.setY(25.0);
                record(room, GameEvent.MOVE, userId, card, null, "frontLine", null, Map.of("reason", "special-summon"),
                        name(card) + " sube a la línea frontal tras su invocación especial");
            }
            PendingEffect effect = prompt.getEffectId() == null ? null
                    : room.getChain().stream().filter(e -> e.getId().equals(prompt.getEffectId())).findFirst().orElse(null);
            regulatoryDraw(room, player, effect);
            advance(room);
            return "";
        }
        if (PURPOSE_REINFORCE.equals(purpose) || PURPOSE_LIFE_PLAY.equals(purpose)) {
            room.setPrompt(null);
            CardSlot card = find(player.getHand(), prompt.getSubjectId());
            if (!picks.isEmpty() && card != null) {
                if (PURPOSE_REINFORCE.equals(purpose)) {
                    player.getHand().remove(card);
                    card.setFaceDown(false);
                    player.getLifeStack().add(card);
                    record(room, GameEvent.SEND, userId, card, "hand", "lifeStack", null, Map.of("reinforce", true),
                            player.getUsername() + " refuerza con " + name(card) + ": va bocarriba al fondo de su pila de vidas");
                } else {
                    play(room, player, card, 0, null, null, null);
                }
            }
            advance(room);
            return "";
        }
        if (PURPOSE_ORDER.equals(purpose)) {
            room.setPrompt(null);
            // The group at the head of the queue, chained in the order the sources were picked
            List<PendingEffect> queue = room.getTriggerQueue();
            int head = rank(queue.get(0), room.getCurrentTurnUserId());
            List<PendingEffect> group = new ArrayList<>();
            while (!queue.isEmpty() && rank(queue.get(0), room.getCurrentTurnUserId()) == head) group.add(queue.remove(0));
            // The chosen one is chained now; the rest are asked for again
            group.sort(Comparator.comparingInt(e -> {
                int at = picks.indexOf(e.getSourceInstanceId());
                return at < 0 ? picks.size() : at;
            }));
            group.get(0).setOrdered(true);
            queue.addAll(0, group);
            advance(room);
            return "";
        }
        if (PURPOSE_OPTIONAL.equals(purpose)) {
            room.setPrompt(null);
            List<PendingEffect> queue = room.getTriggerQueue();
            int head = rank(queue.get(0), room.getCurrentTurnUserId());
            List<PendingEffect> group = new ArrayList<>();
            for (PendingEffect e : queue) {
                if (rank(e, room.getCurrentTurnUserId()) != head) break;
                group.add(e);
            }
            if (picks.isEmpty()) {
                // No more: the rest of this player's optional effects are not activated
                for (PendingEffect e : group) {
                    queue.remove(e);
                    record(room, GameEvent.EFFECT_DECLINED, userId, null, null, null, e, null,
                            player.getUsername() + " no activa el efecto " + e.getLabel() + " de " + e.getSourceName());
                }
            } else {
                // The chosen one goes first and is chained now (activateNext pays its cost next)
                PendingEffect chosen = group.stream().filter(e -> e.getSourceInstanceId().equals(picks.get(0))).findFirst().orElse(null);
                if (chosen != null) {
                    queue.remove(chosen);
                    chosen.setConfirmed(true);
                    queue.add(0, chosen);
                }
            }
            advance(room);
            return "";
        }
        if (PURPOSE_PRIORITY.equals(purpose)) {
            PendingEffect top = room.getChain().isEmpty() ? null : room.getChain().get(room.getChain().size() - 1);
            if (top == null) {
                room.setPrompt(null);
                return "";
            }
            if (picks.isEmpty()) {
                top.getPassed().add(userId);
            } else {
                PendingEffect response = responses(room, userId, top).stream()
                        .filter(e -> e.getSourceInstanceId().equals(picks.get(0))).findFirst().orElse(null);
                if (response == null) return "Ese efecto ya no puede activarse";
                response.setId(room.nextId("eff"));
                activate(room, response);
            }
            room.setPrompt(null);
            advance(room);
            return "";
        }
        return null;
    }

    /** Carries out the step with the player's choice. Sets {@code repeat[0]} when the step asks again. */
    private static String apply(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks,
                                List<String> order, boolean[] repeat) {
        String userId = player.getUserId();
        switch (step) {
            case TakeFromLookedRestToBottom ignored -> {
                for (String id : picks) {
                    CardSlot card = find(effect.getLooked(), id);
                    effect.getLooked().remove(card);
                    card.setFaceDown(false);
                    player.getHand().add(card);
                    record(room, GameEvent.ADD_TO_HAND, userId, card, "deck", "hand", effect, null,
                            player.getUsername() + " añade " + name(card) + " a su mano (" + effect.getSourceName() + ")");
                }
                // The rest go to the deck bottom in the order the player left them (not part of the timeline)
                List<CardSlot> rest = inOrder(effect.getLooked(), order);
                for (CardSlot card : rest) {
                    card.setFaceDown(true);
                    player.getDeck().add(card);
                }
                if (!rest.isEmpty()) {
                    log(room, player.getUsername() + " envía " + rest.size() + " " + plural(rest.size(), "carta", "cartas") + " al fondo de su mazo", null);
                }
                effect.getLooked().clear();
            }
            case TributeFromHand ignored -> {
                if (!allIn(player.getHand(), picks)) return "Esa carta ya no está en tu mano";
                effect.setLastTributed(new ArrayList<>(picks));
                for (String id : picks) {
                    CardSlot card = find(player.getHand(), id);
                    player.getHand().remove(card);
                    card.setFaceDown(false);
                    player.getTributeZone().add(card);
                    record(room, GameEvent.TRIBUTE, userId, card, "hand", "tributeZone", effect, null,
                            player.getUsername() + " tributa " + name(card) + " de su mano (" + effect.getSourceName() + ")");
                }
            }
            case DestroyEnemy ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent == null || !allIn(frontLine(opponent.getField()), picks)) return "Ese enemigo ya no está en la línea frontal";
                for (String id : picks) destroy(room, find(opponent.getField(), id), opponent, false, CAUSE_EFFECT, userId, null, effect);
            }
            case ChooseEnemy ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                if (opponent == null || !allIn(frontLine(opponent.getField()), picks)) return "Ese enemigo ya no está en la línea frontal";
                effect.setTargetIds(new ArrayList<>(picks));
                List<String> names = picks.stream().map(id -> name(find(opponent.getField(), id))).toList();
                record(room, GameEvent.TARGET, userId, find(opponent.getField(), picks.get(0)), null, null, effect, Map.of("targetIds", picks),
                        player.getUsername() + " elige como objetivo a " + String.join(", ", names) + " (" + effect.getSourceName() + ")");
            }
            case SummonFromZone summon -> {
                CardSlot card = find(summonable(room, effect, summon), picks.get(0));
                if (card == null) return "Ya no puedes invocar esa criatura";
                summonByEffect(room, effect, player, card, summon.zone(), summon.reduce(), summon.toFront());
                effect.setLastDone(true);
            }
            case RitualFromZone ritual -> {
                if (effect.getRitualTargetId() == null) {
                    effect.setRitualTargetId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                CardSlot target = find(zone(player, ritual.zone()), effect.getRitualTargetId());
                if (target == null) return "Esa criatura ya no está en tus " + zoneName(ritual.zone());
                Requirement req = requirement(target);
                CardSlot summoner = ritual.selfSummoner() ? find(player.getField(), effect.getSourceInstanceId())
                        : find(player.getField(), effect.getRitualSummonerId());
                if (summoner == null) {
                    // This answer is the summoner
                    if (find(player.getField(), picks.get(0)) == null) return "Esa criatura ya no está en el campo";
                    effect.setRitualSummonerId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                List<CardSlot> others = new ArrayList<>();
                for (String id : picks) {
                    CardSlot c = find(player.getField(), id);
                    if (c == null) return "Esa criatura ya no está en el campo";
                    if (c != summoner) others.add(c);
                }
                int total = summoner.getLevel() + others.stream().mapToInt(CardSlot::getLevel).sum();
                if (req.sum() && total != req.level()) return "Los niveles suman " + total + ": deben sumar exactamente " + req.level();
                performRitual(room, effect, player, target, ritual, summoner, others);
                effect.setRitualSummonerId(null);
            }
            case SendFromZoneToDeckBottom send -> {
                List<CardSlot> from = zone(player, send.zone());
                if (!allIn(from, picks)) return "Esa carta ya no está en tus " + zoneName(send.zone());
                for (String id : picks) {
                    CardSlot card = find(from, id);
                    from.remove(card);
                    card.setFaceDown(true);
                    player.getDeck().add(card);
                    record(room, GameEvent.SEND, userId, card, send.zone(), "deck", effect, Map.of("position", "bottom"), null);
                }
                log(room, player.getUsername() + " envía " + picks.size() + " " + plural(picks.size(), "carta", "cartas") + " de sus "
                        + zoneName(send.zone()) + " al fondo de su mazo (" + effect.getSourceName() + ")", room.getEventSeq());
            }
            case PickAlly ignored -> {
                if (find(player.getField(), picks.get(0)) == null) return "Ese aliado ya no está en el campo";
                effect.setSubjectId(picks.get(0));
            }
            case SacrificeAlly ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null) return "Ese aliado ya no está en el campo";
                destroy(room, ally, player, true, CAUSE_EFFECT, userId, null, effect);
            }
            case TributeMaterials ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null || !allIn(self.getMaterials(), picks)) return "Ese material ya no está";
                for (String id : picks) {
                    CardSlot material = find(self.getMaterials(), id);
                    self.getMaterials().remove(material);
                    material.setFaceDown(false);
                    player.getTributeZone().add(material);
                    record(room, GameEvent.TRIBUTE, userId, material, "materials", "tributeZone", effect, null,
                            player.getUsername() + " tributa " + name(material) + " de los materiales de " + name(self));
                }
                effect.setLastCount(picks.size());
            }
            case TakePerCount take -> {
                List<CardSlot> from = zone(player, take.zone());
                if (!allIn(from, picks)) return "Esa carta ya no está en tus " + zoneName(take.zone());
                for (String id : picks) {
                    CardSlot card = find(from, id);
                    from.remove(card);
                    card.setFaceDown(false);
                    player.getHand().add(card);
                    record(room, GameEvent.ADD_TO_HAND, userId, card, take.zone(), "hand", effect, null,
                            player.getUsername() + " toma " + name(card) + " de sus " + zoneName(take.zone()) + " (" + effect.getSourceName() + ")");
                }
            }
            case TributeFromHandUpTo ignored -> {
                if (!allIn(player.getHand(), picks)) return "Esa carta ya no está en tu mano";
                for (String id : picks) {
                    CardSlot card = find(player.getHand(), id);
                    player.getHand().remove(card);
                    card.setFaceDown(false);
                    player.getTributeZone().add(card);
                    record(room, GameEvent.TRIBUTE, userId, card, "hand", "tributeZone", effect, null,
                            player.getUsername() + " tributa " + name(card) + " de su mano (" + effect.getSourceName() + ")");
                }
                effect.setLastCount(picks.size());
            }
            case MaterialToLife ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                for (String id : picks) {
                    CardSlot material = self == null ? null : find(self.getMaterials(), id);
                    if (material == null) return "Ese material ya no está";
                    self.getMaterials().remove(material);
                    material.setFaceDown(false);
                    player.getLifeStack().add(0, material);
                    record(room, GameEvent.SEND, userId, material, "materials", "lifeStack", effect, null,
                            player.getUsername() + " coloca " + name(material) + " como vida bocarriba (" + effect.getSourceName() + ")");
                }
            }
            case AttachLowerFromTributes ignored -> {
                CardSlot ally = find(player.getField(), effect.getSubjectId());
                CardSlot card = find(player.getTributeZone(), picks.get(0));
                if (ally == null || card == null) return "Esa carta ya no está disponible";
                player.getTributeZone().remove(card);
                card.setFaceDown(false);
                ally.getMaterials().add(card);
                record(room, GameEvent.SEND, userId, card, "tributeZone", "materials", effect, Map.of("to", ally.getInstanceId()),
                        player.getUsername() + " añade " + name(card) + " como material de " + name(ally) + " (" + effect.getSourceName() + ")");
            }
            case ToDeckTop top -> {
                List<CardSlot> from = zone(player, top.zone());
                if (!allIn(from, picks)) return "Esa carta ya no está en tu " + zoneName(top.zone());
                for (String id : picks) {
                    CardSlot card = find(from, id);
                    from.remove(card);
                    card.setFaceDown(true);
                    player.getDeck().add(0, card);
                    record(room, GameEvent.SEND, userId, card, top.zone(), "deck", effect, Map.of("position", "top"), null);
                }
                if (!picks.isEmpty()) log(room, player.getUsername() + " coloca " + picks.size() + " " + plural(picks.size(), "carta", "cartas")
                        + " al tope de su mazo (" + effect.getSourceName() + ")", room.getEventSeq());
                effect.setLastDone(!picks.isEmpty());
            }
            case DestroyFaceUpLife ignored -> {
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                for (String id : picks) {
                    CardSlot life = opponent == null ? null : find(opponent.getLifeStack(), id);
                    if (life == null) return "Esa vida ya no está";
                    opponent.getLifeStack().remove(life);
                    opponent.getDiscardPile().add(life);
                    record(room, GameEvent.DESTROY, userId, life, "lifeStack", "discardPile", effect,
                            Map.of("cause", CAUSE_EFFECT, "ownerId", opponent.getUserId(), "byCard", effect.getSourceInstanceId()),
                            player.getUsername() + " destruye la vida " + name(life) + " de " + opponent.getUsername() + " (" + effect.getSourceName() + ")");
                }
            }
            case TakeFromZone take -> {
                List<CardSlot> from = zone(player, take.zone());
                if (!allIn(from, picks)) return "Esa carta ya no está en tus " + zoneName(take.zone());
                for (String id : picks) {
                    CardSlot card = find(from, id);
                    from.remove(card);
                    card.setFaceDown(false);
                    player.getHand().add(card);
                    record(room, GameEvent.ADD_TO_HAND, userId, card, take.zone(), "hand", effect, null,
                            player.getUsername() + " toma " + name(card) + " de sus " + zoneName(take.zone()) + " (" + effect.getSourceName() + ")");
                }
                effect.setLastDone(!picks.isEmpty());
            }
            case SpecialFromZone special -> {
                if (effect.getRitualTargetId() == null) {
                    if (picks.isEmpty()) return null;
                    effect.setRitualTargetId(picks.get(0));
                    repeat[0] = true;
                    return null;
                }
                CardSlot target = find(zone(player, special.zone()), effect.getRitualTargetId());
                CardSlot summoner = find(player.getField(), picks.get(0));
                if (target == null || summoner == null) return "Esa criatura ya no está disponible";
                effect.setRitualTargetId(null);
                specialSummon(room, player, target, specialRequirement(target), summoner, null, special.zone(), special.reduce(), effect);
                effect.setLastDone(true);
            }
            case UntapAlly ignored -> {
                CardSlot ally = find(player.getField(), picks.get(0));
                if (ally == null || !reactivable(ally)) return "Ese aliado ya no puede reactivarse";
                ally.setTapped(false);
                record(room, GameEvent.UNTAP, userId, ally, null, null, effect, null,
                        player.getUsername() + " reactiva " + name(ally) + " (" + effect.getSourceName() + ")");
            }
            case DeclareAttack ignored -> {
                CardSlot subject = subject(room, effect);
                if (subject != null && room.getCombat() == null) {
                    startCombat(room, userId, subject, picks.isEmpty() ? null : picks.get(0), true, effect);
                }
            }
            case MayDo may -> {
                // Declined: skip what it covers
                if (picks.isEmpty()) effect.setStepIndex(effect.getStepIndex() + may.covers());
            }
            default -> {
                return "Este paso no espera ninguna elección";
            }
        }
        return null;
    }

    private static boolean allIn(List<CardSlot> cards, List<String> ids) {
        return ids.stream().allMatch(id -> find(cards, id) != null);
    }

    /** {@code cards} in the order given by {@code order}; ids missing from it keep their place at the end. */
    private static List<CardSlot> inOrder(List<CardSlot> cards, List<String> order) {
        List<CardSlot> result = new ArrayList<>();
        if (order != null) {
            for (String id : order) {
                CardSlot card = find(cards, id);
                if (card != null && !result.contains(card)) result.add(card);
            }
        }
        for (CardSlot card : cards) if (!result.contains(card)) result.add(card);
        return result;
    }

    // ------------------------------------------------------------------ Timeline

    /**
     * Adds an event to the timeline (and to the events waiting to trigger effects) and, when {@code logText}
     * is given, a line to the text log.
     */
    static GameEvent record(GameRoom room, String type, String playerId, CardSlot card, String from, String to,
                            PendingEffect effect, Map<String, Object> data, String logText) {
        GameEvent e = new GameEvent();
        e.setSeq(room.getEventSeq() + 1);
        room.setEventSeq(e.getSeq());
        e.setTs(System.currentTimeMillis());
        e.setType(type);
        e.setPlayerId(playerId);
        if (card != null) {
            e.setCardInstanceId(card.getInstanceId());
            e.setCardName(card.getCardName());
            // Entering the field (summoned, played…): it reacts to events from this one on
            if ("field".equals(to)) card.setFieldSeq(e.getSeq());
        }
        e.setFromZone(from);
        e.setToZone(to);
        if (effect != null) {
            e.setEffectId(effect.getId());
            e.setResolutionId(effect.getResolutionId());
        }
        e.setData(data);
        room.getTimeline().add(e);
        if (room.getTimeline().size() > TIMELINE_LIMIT) room.getTimeline().remove(0);
        room.getPendingEvents().add(e);
        if (logText != null) log(room, logText, e.getSeq());
        return e;
    }

    private static void log(GameRoom room, String text, Long seq) {
        room.getEventLog().add(new GameLogEntry(System.currentTimeMillis(), text, seq));
        if (room.getEventLog().size() > LOG_LIMIT) room.getEventLog().remove(0);
    }

    // ------------------------------------------------------------------ Helpers

    private static EffectScript script(PendingEffect effect) {
        return CardEffects.byId(effect.getScriptId())
                .orElseThrow(() -> new IllegalStateException("Unknown effect " + effect.getScriptId()));
    }

    /** The card an event is about, where it is now (its zone after the event). */
    public static CardSlot eventCard(GameRoom room, GameEvent event) {
        String owner = ownerOf(event) != null ? ownerOf(event) : event.getPlayerId();
        String zone = event.getToZone() != null ? event.getToZone() : "field";
        return findIn(room, owner, zone, event.getCardInstanceId());
    }

    private static String ownerOf(GameEvent event) {
        return event.getData() == null ? null : (String) event.getData().get("ownerId");
    }

    public static int strength(CardSlot card) {
        return (card.getStrength() == null ? 0 : card.getStrength()) + card.getStrengthModifier() + card.getTurnStrength()
                + card.getLaterStrength();
    }

    private record Located(CardSlot card, String zone) {}

    private static final List<String> ZONES = List.of("field", "hand", "tributeZone", "discardPile", "deck", "lifeStack");

    private static Located locate(PlayerState player, String instanceId) {
        for (String z : ZONES) {
            CardSlot card = find(zone(player, z), instanceId);
            if (card != null) return new Located(card, z);
        }
        return null;
    }

    private static CardSlot findAnywhere(GameRoom room, String playerId, String instanceId) {
        PlayerState p = room.getPlayer(playerId);
        Located at = p == null ? null : locate(p, instanceId);
        return at == null ? null : at.card();
    }

    private static List<CardSlot> zone(PlayerState player, String zone) {
        return switch (zone) {
            case "field" -> player.getField();
            case "hand" -> player.getHand();
            case "tributeZone" -> player.getTributeZone();
            case "discardPile" -> player.getDiscardPile();
            case "deck" -> player.getDeck();
            case "lifeStack" -> player.getLifeStack();
            default -> List.of();
        };
    }

    private static String zoneName(String zone) {
        return switch (zone) {
            case "tributeZone" -> "tributos";
            case "discardPile" -> "descarte";
            case "hand" -> "mano";
            case "deck" -> "mazo";
            case "field" -> "campo";
            default -> zone;
        };
    }

    private static List<String> ids(List<CardSlot> cards) {
        return cards.stream().map(CardSlot::getInstanceId).toList();
    }

    private static CardSlot find(List<CardSlot> cards, String instanceId) {
        if (instanceId == null) return null;
        return cards.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
    }

    private static CardSlot findIn(GameRoom room, String playerId, String zone, String instanceId) {
        PlayerState p = playerId == null ? null : room.getPlayer(playerId);
        return p == null || zone == null ? null : find(zone(p, zone), instanceId);
    }

    private static List<CardSlot> concat(List<CardSlot> a, List<CardSlot> b) {
        List<CardSlot> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static List<String> concatStrings(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a == null ? List.of() : a);
        if (b != null) all.addAll(b);
        return all;
    }

    private static String name(CardSlot card) {
        return card.getCardName() != null ? card.getCardName() : "una carta";
    }

    private static String plural(int n, String one, String many) {
        return n == 1 ? one : many;
    }

    private static String gemsText(int n) {
        return n + " " + plural(n, "gema", "gemas");
    }

    private static int clamp(int value) {
        return Math.max(-10, Math.min(10, value));
    }
}
