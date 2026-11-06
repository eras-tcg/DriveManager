package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.Zone;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectStep.*;

import java.util.*;

import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.SpecialRequirements.*;
import static com.drive.drive_manager.simulator.engine.Rituals.*;
import static com.drive.drive_manager.simulator.engine.EffectEngine.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Resolution.*;
import static com.drive.drive_manager.simulator.engine.StepHelpers.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** Evolución, Ascenso and Materialización carried out, by the player or by an effect. */
public final class SpecialSummons {

    private SpecialSummons() {}

    /**
     * Special summon from the hand by the rules (rule 9): Ritual, Evolución, Ascenso or Materialización, as the
     * card says. {@code summonerId} is the creature it was dropped onto; whatever is missing is asked for.
     */
    public static String specialByPlayer(GameRoom room, String userId, String instanceId, String summonerId) {
        PlayerState who = room.getPlayer(userId);
        return summoningAs(who == null ? null : specialSource(who, instanceId), () -> specialByPlayerAs(room, userId, instanceId, summonerId));
    }

    static String specialByPlayerAs(GameRoom room, String userId, String instanceId, String summonerId) {
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
        if (!canPay(room, userId, card.getSpecialCost() - possibleFieldPiecesDiscount(player, card)
                - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, null)))
            return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y el coste especial es " + card.getSpecialCost();
        if (askTap(room, player, card, req.kind(), summonerId)) return null;
        if ("promotion".equals(req.kind()) && !alliesAddUp(player, req)) return "Para el Ascenso tus aliados deben sumar " + req.alliesSum() + " o más en nivel";
        if (!enoughCreatures(player, card)) return "Para invocar a " + name(card) + " debes controlar al menos " + creaturesNeeded(card) + " criaturas";
        String missing = "Tus criaturas no cumplen el Requerimiento de " + name(card) + ": " + card.getRequirement().trim();
        if (req.declaredSummoner()) {
            if (summonerId == null) {
                // Rule 9.2.0.13: the summoner is declared first
                List<CardSlot> summoners = declaredSummoners(player, card, req);
                if (summoners.isEmpty()) return missing;
                List<CardSlot> shown = new ArrayList<>();
                for (String z : req.summonerZones()) shown.addAll(zone(player, z));
                shown.remove(card);
                rulesPrompt(room, userId, PromptPurpose.SPECIAL.id(), name(card) + " · " + kind,
                        "Declara el invocador: 1 criatura nivel " + req.level() + " de " + zonesText(req.summonerZones()) + ". Luego eliges "
                                + req.materialCount() + (req.moreMaterials() ? " o más " : " ") + plural(req.materialCount(), "material", "materiales")
                                + ". Si no eliges ninguna, no se invoca.",
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
            List<CardSlot> materialOptions = specialMaterials(player, card, req, summoner);
            rulesPrompt(room, userId, PromptPurpose.SPECIAL.id(), name(card) + " · " + kind, materialInstruction(req, card, summoner), "Material",
                    materialOptions, shown);
            room.getPrompt().setMin(req.materialCount());
            room.getPrompt().setMax(req.moreMaterials() ? materialOptions.size() : req.materialCount());
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(summoner.getInstanceId());
            return null;
        }
        if (req.noSummoner()) {
            List<CardSlot> materials = specialMaterials(player, card, req, null);
            if (materials.size() < req.materialCount()) return missing;
            List<CardSlot> shown = new ArrayList<>();
            for (String z : req.materialZones()) shown.addAll(zone(player, z));
            rulesPrompt(room, userId, PromptPurpose.SPECIAL.id(), name(card) + " · " + kind, materialInstruction(req, card, null), "Material", materials, shown);
            room.getPrompt().setMin(req.materialCount());
            room.getPrompt().setMax(req.materialCount());
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(NO_SUMMONER);
            return null;
        }
        List<CardSlot> possible = specialSummoners(player, card, req).stream().filter(s -> materialsPossible(player, card, req, s)).toList();
        // Only those it can be paid with: one that could not would be refused once chosen
        List<CardSlot> summoners = possible.stream().filter(s -> affordableWith(room, player, card, req, s)).toList();
        if (summonerId == null) {
            if (summoners.isEmpty()) return possible.isEmpty() ? missing : "No tienes gemas suficientes para invocar a " + name(card) + " con ninguna de tus criaturas";
            rulesPrompt(room, userId, PromptPurpose.SPECIAL.id(), name(card) + " · " + kind,
                    "Elige el invocador: 1 criatura nivel " + req.level() + (req.atLeast() ? " o mayor" : req.maxLevel() > req.level() ? " o " + req.maxLevel() : "")
                            + (req.alternative() != null ? " (o nivel " + req.alternative().level() + ")" : "") + ". " + name(card)
                            + " ocupa su lugar y hereda su estado. Si no eliges ninguna, no se invoca.",
                    "Invocador", summoners, player.getField());
            room.getPrompt().setSubjectId(card.getInstanceId());
            return null;
        }
        CardSlot summoner = find(player.getField(), summonerId);
        if (summoner == null) return "El invocador debe ser una criatura de tu campo";
        if (!possible.contains(summoner)) return shares(summoner, card) ? missing : name(summoner) + " no comparte tipo ni color con " + name(card);
        SpecialRequirement option = optionFor(req, summoner);
        if ("materialization".equals(req.kind()) && (option.materialCount() > 0 || option.levelSum() > 0)) {
            List<CardSlot> materials = specialMaterials(player, card, req, summoner);
            List<CardSlot> shown = new ArrayList<>();
            for (String z : option.materialZones()) shown.addAll(zone(player, z));
            rulesPrompt(room, userId, PromptPurpose.SPECIAL.id(), name(card) + " · " + kind, materialInstruction(req, card, summoner), "Material", materials, shown);
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
            rulesPrompt(room, player.getUserId(), PromptPurpose.ASCEND_ATTACK.id(), name(summoner) + " va a ser ascendida",
                    "Puede declarar ataque antes: elige " + name(summoner) + " para atacar al oponente, o una criatura enemiga agotada. "
                            + "O ninguna para ascenderla ya.", "Atacar", options, shown);
            room.getPrompt().setMin(0);
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(summoner.getInstanceId());
            return;
        }
        // A cost modifier that asks for its payment (Flambreik's tributes) is asked before the summon (a Reacción applies
        // before its event); only when no materials are being chosen
        CostModifiers.Use use = CostModifiers.choice(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, summoner);
        if (use != null && !materials.isEmpty() && use.modifier().payment().kind() != CostModifiers.PaymentKind.SELF_DAMAGE) use = null;
        if (use != null) {
            room.getUsedThisTurn().removeIf(k -> k.startsWith(DISCOUNT_MATERIALS + card.getInstanceId() + "|"));
            if (!materials.isEmpty()) room.getUsedThisTurn().add(DISCOUNT_MATERIALS + card.getInstanceId() + "|" + String.join(",", ids(materials)));
            rulesPrompt(room, player.getUserId(), PromptPurpose.SUMMONER_DISCOUNT.id(), name(use.holder()) + " · " + use.modifier().label(),
                    CostModifiers.describe(use), "Pagar", CostModifiers.paymentOptions(player, use, card), player.getTributeZone());
            room.getPrompt().setMax(use.modifier().payment().count());
            if (use.modifier().payment().kind() == CostModifiers.PaymentKind.SELF_DAMAGE) room.getPrompt().setMin(0);
            // "puedes enviar 5…": exactly that many, or none to pay it all
            else room.getPrompt().setExactOrNone(true);
            // Without this reduction the summon cannot be paid: the payment is not optional any more
            int others = CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, summoner) - use.modifier().reduceNow(room, player.getUserId());
            if (use.modifier().payment().kind() != CostModifiers.PaymentKind.SELF_DAMAGE && !canPay(room, player.getUserId(), card.getSpecialCost() - others)) {
                room.getPrompt().setMin(use.modifier().payment().count());
            }
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(summoner.getInstanceId());
            return;
        }
        specialSummon(room, player, card, req, summoner, materials, fromZone, 0, null);
        advance(room);
    }

    /** The materials chosen before a payment the player is asked for (Biok): card id | material ids. */
    static final String DISCOUNT_MATERIALS = "discount-materials|";

    static void specialSummon(GameRoom room, PlayerState player, CardSlot card, SpecialRequirement req, CardSlot summoner,
                                      List<CardSlot> materials, String fromZone, int reduce, PendingEffect effect) {
        String userId = player.getUserId();
        int cost = Math.max(0, card.getSpecialCost() - reduce - fieldPiecesDiscount(player, card, summoner, materials));
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
            // "cuando esta carta fuera a ser ascendida: esta carta gana Furia 2 hasta el final del turno" (it passes to the new creature)
            String gain = "promotion".equals(req.kind()) ? CardEffects.gainBeforeAscended(summoner) : null;
            if (gain != null && userId.equals(room.getCurrentTurnUserId())) {
                summoner.getTurnKeywords().add(gain);
                floating(summoner, null, "Gana " + gain + " hasta el final del turno", "turn", 0, gain);
            }
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
                    String from = player.getTributeZone().remove(material) ? "tributeZone" : player.getHand().remove(material) ? "hand"
                            : player.getDiscardPile().remove(material) ? "discardPile" : "field";
                    // A creature of the field used as a material loses its state; its attachments go to the Descarte
                    if (Zone.FIELD.is(from)) removeFromField(player, material);
                    material.setFaceDown(false);
                    card.getMaterials().add(material);
                    used += (used.isEmpty() ? "" : " y ") + name(material);
                }
            }
        }
        player.getField().add(card);
        record(room, GameEvent.SPECIAL_SUMMON, userId, card, fromZone, "field", effect,
                Map.of("summonType", req.kind(), "cost", cost, "used", (summoner == null ? 0 : 1) + materials.size()),
                player.getUsername() + " invoca por " + kindName(req.kind()) + " a " + name(card) + " usando " + used + " y pagando "
                        + gemsText(cost) + (effect != null ? " (" + effect.getSourceName() + ")" : ""));
        afterSpecialSummon(room, player, card, effect);
    }

    /**
     * "en su lugar, puede invocar especialmente esta carta" (EXO-91, SARS-2): a regular special summon of {@code card} with {@code ally} as its
     * summoner is possible now: the ally meets the Requerimiento, the materials are there and the cost can be paid.
     */
    static boolean canSummonInsteadOfRemoval(GameRoom room, PlayerState player, CardSlot card, CardSlot ally, int reduce) {
        return summoningAs(card, () -> canSummonInsteadOfRemovalAs(room, player, card, ally, reduce));
    }

    static boolean canSummonInsteadOfRemovalAs(GameRoom room, PlayerState player, CardSlot card, CardSlot ally, int reduce) {
        SpecialRequirement req = specialRequirement(card);
        if (req == null || card.getSpecialCost() == null || "ritual".equals(req.kind()) || req.declaredSummoner() || req.noSummoner()) return false;
        if (!specialSummoners(player, card, req).contains(ally) || !materialsPossible(player, card, req, ally)) return false;
        if ("promotion".equals(req.kind()) && !alliesAddUp(player, req)) return false;
        return canPay(room, player.getUserId(), card.getSpecialCost() - reduce
                - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, ally));
    }

    /** Carries out that special summon: asks for the materials when the Requerimiento has some, then summons (the ally takes no other part). */
    static void summonInsteadOfRemoval(GameRoom room, PlayerState player, CardSlot card, CardSlot ally) {
        SpecialRequirement req = specialRequirement(card);
        CardEffects.AllyRemovalSummon s = CardEffects.allyRemovalSummon(card);
        String from = find(player.getHand(), card.getInstanceId()) != null ? "hand" : "tributeZone";
        SpecialRequirement option = optionFor(req, ally);
        if ("materialization".equals(req.kind()) && (option.materialCount() > 0 || option.levelSum() > 0)) {
            List<CardSlot> materials = specialMaterials(player, card, req, ally);
            List<CardSlot> shown = new ArrayList<>();
            for (String z : option.materialZones()) shown.addAll(zone(player, z));
            shown.remove(card);
            shown.remove(ally);
            rulesPrompt(room, player.getUserId(), PromptPurpose.REPLACE_SUMMON.id(), name(card) + " · en lugar de que " + name(ally) + " sea removida",
                    materialInstruction(req, card, ally), "Material", materials, shown);
            room.getPrompt().setMin(Math.max(1, option.materialCount()));
            room.getPrompt().setMax(option.levelSum() > 0 ? materials.size() : option.materialCount());
            room.getPrompt().setSubjectId(card.getInstanceId());
            room.getPrompt().setSummonerId(ally.getInstanceId());
            return;
        }
        specialSummon(room, player, card, req, ally, List.of(), from, s == null ? 0 : s.reduce(), null);
    }

    /** The materials chosen for {@link #summonInsteadOfRemoval}. */
    static String answerSummonInsteadOfRemoval(GameRoom room, Prompt prompt, PlayerState player, List<String> picks) {
        CardSlot card = find(player.getHand(), prompt.getSubjectId());
        String from = "hand";
        if (card == null) {
            card = find(player.getTributeZone(), prompt.getSubjectId());
            from = "tributeZone";
        }
        CardSlot ally = find(player.getField(), prompt.getSummonerId());
        if (card == null || ally == null) {
            room.setPrompt(null);
            advance(room);
            return null;
        }
        List<CardSlot> materials = new ArrayList<>();
        for (String id : picks) {
            CardSlot m = find(player.getTributeZone(), id);
            if (m == null) m = find(player.getField(), id);
            if (m == null) m = find(player.getHand(), id);
            if (m == null) return "Esa criatura ya no está disponible";
            materials.add(m);
        }
        SpecialRequirement req = specialRequirement(card);
        String wrong = checkMaterials(req, ally, materials);
        if (wrong != null) return wrong;
        room.setPrompt(null);
        CardEffects.AllyRemovalSummon s = CardEffects.allyRemovalSummon(card);
        specialSummon(room, player, card, req, ally, materials, from, s == null ? 0 : s.reduce(), null);
        advance(room);
        return null;
    }
}
