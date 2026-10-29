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
import static com.drive.drive_manager.simulator.engine.Summons.*;
import static com.drive.drive_manager.simulator.engine.Removals.*;
import static com.drive.drive_manager.simulator.engine.TurnRules.*;
import static com.drive.drive_manager.simulator.engine.CombatRules.*;
import static com.drive.drive_manager.simulator.engine.Chain.*;
import static com.drive.drive_manager.simulator.engine.Steps.*;

/**
 * The players' answers to the engine's choices: the rules' own (Ritual, move up, Reforzar, chaining, responding) here; an
 * effect step's choice is applied by {@link Steps}.
 */
public final class Answers {

    private Answers() {}

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
        Set<String> allowed = new HashSet<>(prompt.getValidIds());
        if (prompt.getEffects() != null) prompt.getEffects().forEach(e -> allowed.add(e.effectId()));
        if (!allowed.containsAll(picks)) return "Esa carta no cumple lo que pide el efecto";
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
    static String answerRules(GameRoom room, Prompt prompt, PlayerState player, List<String> picks) {
        String userId = player.getUserId();
        String purpose = prompt.getPurpose();
        if (PURPOSE_REPLACE.equals(purpose) || PURPOSE_REPLACE_HAND.equals(purpose)) {
            String error = answerRemoval(room, prompt, player, picks);
            if (error != null) return error;
            advance(room);
            return "";
        }
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
            CardSlot card = specialSource(player, prompt.getSubjectId());
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
            SpecialRequirement req = specialRequirement(card);
            List<CardSlot> materials = new ArrayList<>();
            for (String id : picks) {
                CardSlot material = find(player.getTributeZone(), id);
                if (material == null) material = find(player.getField(), id);
                if (material == null) return "Esa criatura ya no está disponible";
                materials.add(material);
            }
            if (summoner == null) return "Esa criatura ya no está disponible";
            String wrong = checkMaterials(req, summoner, materials);
            if (wrong != null) return wrong;
            if (!canPay(room, userId, card.getSpecialCost() - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, "materialization", card, summoner)))
                return "Ya no tienes gemas suficientes";
            room.setPrompt(null);
            handSpecial(room, player, card, req, summoner, materials, find(player.getHand(), card.getInstanceId()) != null ? "hand" : "tributeZone");
            return "";
        }
        if (PURPOSE_SUMMONER_DISCOUNT.equals(purpose)) {
            CardSlot card = specialSource(player, prompt.getSubjectId());
            CardSlot summoner = find(player.getField(), prompt.getSummonerId());
            if (card == null || summoner == null) {
                room.setPrompt(null);
                advance(room);
                return "";
            }
            SpecialRequirement req = specialRequirement(card);
            CostModifiers.Use use = CostModifiers.choice(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, summoner);
            int count = use == null ? 0 : use.modifier().payment().count();
            if (!picks.isEmpty() && picks.size() != count) return "Elige " + count + " cartas, o ninguna";
            int reduce = picks.isEmpty() || use == null ? 0 : use.modifier().reduce();
            // Without it, the others still apply on their own
            if (!canPay(room, userId, card.getSpecialCost() - reduce - CostModifiers.available(room, player, CostModifiers.Event.SPECIAL_SUMMON, req.kind(), card, summoner)
                    + (use == null ? 0 : use.modifier().reduce())))
                return "Sin la reducción no tienes gemas suficientes: elige " + count + " cartas";
            room.setPrompt(null);
            List<CardSlot> paid = new ArrayList<>();
            for (String id : picks) {
                CardSlot c = find(player.getTributeZone(), id);
                if (c == null) continue;
                player.getTributeZone().remove(c);
                c.setFaceDown(true);
                player.getDeck().add(c);
                paid.add(c);
                record(room, GameEvent.SEND, userId, c, "tributeZone", "deck", null, Map.of("position", "bottom"), null);
            }
            if (reduce > 0) log(room, player.getUsername() + " envía " + paid.size() + " tributos al fondo de su mazo: " + name(use.holder())
                    + " reduce en " + reduce + " el coste especial" + names(paid), room.getEventSeq());
            specialSummon(room, player, card, req, summoner, List.of(), find(player.getHand(), card.getInstanceId()) != null ? "hand" : "tributeZone",
                    reduce, null);
            advance(room);
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
                for (int at = 0; at < picks.size(); at++) if (picked(e, picks.get(at))) return at;
                return picks.size();
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
                PendingEffect chosen = group.stream().filter(e -> e.getId().equals(picks.get(0))).findFirst()
                        .or(() -> group.stream().filter(e -> picked(e, picks.get(0))).findFirst()).orElse(null);
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

}
