package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.Zone;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectStep.*;
import com.drive.drive_manager.simulator.engine.effects.model.SpecialRule;

import java.util.*;

import static com.drive.drive_manager.simulator.engine.SpecialRequirements.*;
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

/** Summons and playing cards by the rules: normal summon, utilities and structures, and what follows a summon. */
public final class Summons {

    private Summons() {}

    // ------------------------------------------------------------------ Normal summon and playing cards

    /**
     * Normal summon (rules 6.2.1.1, 9.1.0.1): taking a creature from the hand to the board without an effect.
     * Pays its cost in gems, enters the Retaguardia and fires the summon triggers.
     *
     * @return an error message for the player, or null when done
     */
    /** The mark of a "puedes agotar…" asked before a normal summon: the summon goes on with the answer (and where it was dropped). */
    static final String NORMAL_SUMMON = "normal-summon|";

    /** Why this card of the hand cannot be summoned normally now; null: it can (the main phase of the turn is asked apart). */
    static String whyCannotSummon(GameRoom room, String userId, PlayerState player, CardSlot card) {
        if (card == null) return "La carta no está en tu mano";
        if (!"creature".equals(card.getCardType())) return "Solo las criaturas se invocan";
        if (card.getCost() == null) return "Esta carta no tiene coste normal: no puede invocarse normalmente";
        int cost = card.getCost() - (starterAdvantage(room, userId, card, card.getCost()) ? 1 : 0);
        // Its Reactivo–Modificadores ("cuando fueras a invocar esta carta: reduce su coste…"); "puedes agotar esta carta para reducir…" is asked
        // first, even when the cost is already 0 (designer ruling 2026-10-31: it stays 0)
        // Carnirex: the most a sacrifice could lower it
        CardFilter canSacrifice = CardEffects.sacrificeToSummon(card);
        int bySacrifice = canSacrifice == null ? 0 : allies(player).stream().filter(c -> canSacrifice.matches(c) && !name(c).equalsIgnoreCase(name(card)))
                .mapToInt(c -> c.getCost() == null ? 0 : c.getCost()).max().orElse(0);
        if (!canPay(room, userId, Math.max(0, cost - bySacrifice - CostModifiers.available(room, player, CostModifiers.Event.SUMMON, null, card, null))))
            return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + cost;
        return null;
    }

    public static String normalSummon(GameRoom room, String userId, String instanceId, Double x, Double y) {
        String error = checkMainPhase(room, userId, "invocar");
        if (error != null) return error;

        PlayerState player = room.getPlayer(userId);
        CardSlot card = player == null ? null : find(player.getHand(), instanceId);
        error = whyCannotSummon(room, userId, player, card);
        if (error != null) return error;

        boolean advantage = starterAdvantage(room, userId, card, card.getCost());
        int cost = card.getCost() - (advantage ? 1 : 0);
        // Carnirex: "puedes sacrificar 1 aliado [dinosaurio] activo … para invocar esta carta en tu línea frontal y reducir su coste en su coste"
        CardFilter sacrifice = CardEffects.sacrificeToSummon(card);
        CardSlot sacrificed = null;
        if (sacrifice != null) {
            List<CardSlot> options = allies(player).stream().filter(c -> sacrifice.matches(c) && !name(c).equalsIgnoreCase(name(card))).toList();
            String answer = room.getUsedThisTurn().stream().filter(k -> k.startsWith(SUMMON_SACRIFICE + card.getInstanceId() + "|")).findFirst().orElse(null);
            if (answer == null && !options.isEmpty()) {
                rulesPrompt(room, userId, PromptPurpose.SUMMON_SACRIFICE.id(), name(card) + " · al invocarla",
                        "Puedes sacrificar 1 aliado [dinosaurio] activo para invocar " + name(card) + " en tu línea frontal reduciendo su coste en el coste "
                                + "del aliado. O ninguno.", "Sacrificar", options, player.getField());
                room.getPrompt().setMin(0);
                room.getPrompt().setSubjectId(card.getInstanceId());
                room.getPrompt().setSummonerId(y == null ? "" : String.valueOf(y));
                return null;
            }
            if (answer != null) {
                room.getUsedThisTurn().remove(answer);
                String id = answer.substring((SUMMON_SACRIFICE + card.getInstanceId() + "|").length());
                sacrificed = find(options, id);
            }
            if (sacrificed != null) {
                int lower = sacrificed.getCost() == null ? 0 : sacrificed.getCost();
                if (!canPay(room, userId, Math.max(0, cost - lower))) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + (cost - lower);
                destroy(room, sacrificed, player, true, CAUSE_EFFECT, userId, card.getInstanceId(), null);
                cost = Math.max(0, cost - lower);
            }
        }
        CostModifiers.Use tap = CostModifiers.unansweredTap(room, player, CostModifiers.Event.SUMMON, null, card);
        if (tap != null) {
            rulesPrompt(room, userId, PromptPurpose.COST_TAP.id(), name(tap.holder()) + " · " + tap.modifier().label(),
                    "¿Agotas " + name(tap.holder()) + " para reducir en " + tap.modifier().reduceNow(room, userId) + " el coste de " + name(card) + "?",
                    "Agotar", List.of(tap.holder()), List.of(tap.holder()));
            room.getPrompt().setKind(Prompt.Kind.CONFIRM);
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(NORMAL_SUMMON + (y == null ? "" : y));
            return null;
        }
        cost = Math.max(0, cost - CostModifiers.applyAutomatic(room, player, CostModifiers.Event.SUMMON, null, card, null, cost));
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + cost;
        pay(room, userId, cost);
        if (advantage) room.setStarterAdvantageUserId(null);

        player.getHand().remove(card);
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(x != null ? x : 50.0);
        // Normal summons enter the Retaguardia (the lower half of the owner's field); Asalto lets it enter the Línea frontal, exhausted
        boolean assault = y != null && y < 55 && keywordLevel(card, "Asalto") != null;
        // Carnirex by a sacrifice: "invocar esta carta en tu línea frontal" (active, it is not Asalto)
        card.setY(sacrificed != null ? 25.0 : assault ? y : y != null && y >= 55 ? y : 75.0);
        if (assault) card.setTapped(true);
        player.getField().add(card);

        record(room, GameEvent.NORMAL_SUMMON, userId, card, "hand", "field", null, Map.of("cost", cost),
                player.getUsername() + " invocó " + name(card) + " pagando " + gemsText(cost) + (advantage ? " (ventaja del iniciador)" : ""));
        offerAssault(room, player, card);
        advance(room);
        return null;
    }

    /** Plays a utility or structure from the hand (rules 6.2.2, 6.2.3): pays its cost, enters the Retaguardia. */
    /** Why this card of the hand cannot be played now; null: it can (the main phase of the turn is asked apart). */
    static String whyCannotPlay(GameRoom room, String userId, CardSlot card) {
        if (card == null) return "La carta no está en tu mano";
        if (!"utility".equals(card.getCardType()) && !"structure".equals(card.getCardType())) return "Solo las utilidades y estructuras se juegan";
        // Only one structure can be played normally per turn (by effect or from a life it does not count)
        if ("structure".equals(card.getCardType()) && structurePlayed(room, userId)) return "Solo puedes jugar una estructura por turno";
        String blocked = Chain.cannotActivateOnPlay(room, userId, card);
        if (blocked != null) return blocked;
        int cost = CardEffects.playCost(room, userId, card);
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + cost;
        return null;
    }

    public static String playByPlayer(GameRoom room, String userId, String instanceId, Double x, Double y) {
        String error = checkMainPhase(room, userId, "jugar cartas");
        if (error != null) return error;
        PlayerState player = room.getPlayer(userId);
        CardSlot card = player == null ? null : find(player.getHand(), instanceId);
        error = whyCannotPlay(room, userId, card);
        if (error != null) return error;
        boolean structure = "structure".equals(card.getCardType());
        int cost = CardEffects.playCost(room, userId, card);
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
        // "manual": done by hand, nothing checked its cost or requirement (unlike specialByPlayer), so it does not count
        // for the missions (MissionEvents)
        record(room, GameEvent.SPECIAL_SUMMON, userId, summoned, null, "field", null,
                Map.of("summonType", summonType == null ? "special" : summonType, GameEvent.MANUAL, true), logText);
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
            rulesPrompt(room, player.getUserId(), PromptPurpose.SPECIAL_FRONT.id(), name(card) + " · Línea frontal",
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
        rulesPrompt(room, player.getUserId(), PromptPurpose.COST_TAP.id(), name(use.holder()) + " · " + use.modifier().label(),
                "¿Agotas " + name(use.holder()) + " para reducir en " + use.modifier().reduceNow(room, player.getUserId()) + " el coste especial de " + name(card) + "?",
                "Agotar", List.of(use.holder()), List.of(use.holder()));
        room.getPrompt().setKind(Prompt.Kind.CONFIRM);
        room.getPrompt().setSubjectId(card.getInstanceId());
        room.getPrompt().setSummonerId(summonerId);
        return true;
    }

    /**
     * Rules 9.2.0.2–9.2.0.7: the summoned creature takes the summoner's zone and its whole game state (active or
     * exhausted, attacker or blocker, strength changes and every floating effect) but keeps its own printed
     * properties; the summoner's Recursos and Materiales pass to it. Continuous effects are checked again afterwards.
     */
    public static void inheritFromSummoner(GameRoom room, CardSlot target, CardSlot summoner) {
        target.setFaceDown(false);
        target.setX(summoner.getX());
        target.setY(summoner.getY());
        target.setTapped(summoner.isTapped());
        target.setStrengthModifier(summoner.getStrengthModifier());
        // Every effect that lasts a while passes on (designer: the summoner always hands its floating effects to the
        // creature summoned): strength and abilities until the end of the turn, of the next turn or of its next attack,
        // and "no se reactiva"; its printed strength stays its own
        target.getTurnKeywords().clear();
        target.getTurnKeywords().addAll(summoner.getTurnKeywords());
        target.getLaterKeywords().clear();
        target.getLaterKeywords().addAll(summoner.getLaterKeywords());
        target.getUntilNextAttack().clear();
        target.getUntilNextAttack().addAll(summoner.getUntilNextAttack());
        target.setTurnStrength(summoner.getTurnStrength());
        target.setLaterStrength(summoner.getLaterStrength());
        target.setNoUntap(summoner.getNoUntap());
        target.setNoStartUntap(summoner.getNoStartUntap());
        target.getFloating().clear();
        target.getFloating().addAll(summoner.getFloating());
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
        PlayerState player = room.getPlayer(pending.getPlayerId());
        if (player == null) return;
        CardSlot card = find(zone(player, pending.getFromZone()), pending.getCardId());
        CardSlot summoner = find(player.getField(), pending.getSummonerId());
        if (card == null || summoner == null) {
            log(room, "El Ascenso no se completa: " + (summoner == null ? "el invocador dejó el campo" : "la carta ya no está"), null);
            return;
        }
        specialSummon(room, player, card, specialRequirement(card), summoner, List.of(), pending.getFromZone(), 0, null);
    }

    /**
     * Asalto: "en lugar de quedar en tu retaguardia, puedes colocarla agotada en tu línea frontal". It modifies the summon, so it is
     * asked as the creature enters the Retaguardia, before anything reacts to it (not a triggered effect).
     */
    static void offerAssault(GameRoom room, PlayerState player, CardSlot card) {
        if (room.getPrompt() != null || keywordLevel(card, "Asalto") == null || inFrontLine(card)) return;
        rulesPrompt(room, player.getUserId(), PromptPurpose.ASSAULT.id(), name(card) + " · Asalto",
                "En lugar de quedar en tu retaguardia, puedes colocarla agotada en tu línea frontal.", "Línea frontal", List.of(card), List.of(card));
        room.getPrompt().setKind(Prompt.Kind.CONFIRM);
        room.getPrompt().setSubjectId(card.getInstanceId());
    }

    /** Ritual by effect (rule 9.3): pays the special cost minus the reduction and summons it. */
    static void performRitual(GameRoom room, PendingEffect effect, PlayerState player, CardSlot target, SpecialRule ritual,
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
                Map.of("summonType", "ritual", "cost", cost, "used", 1 + others.size()), logText);
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
                player.getUsername() + " invoca " + name(card) + (Zone.HAND.is(fromZone) ? " de su mano" : " de sus " + zoneName(fromZone))
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
