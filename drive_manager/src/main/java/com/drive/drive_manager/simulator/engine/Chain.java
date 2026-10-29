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
import static com.drive.drive_manager.simulator.engine.Steps.*;
import static com.drive.drive_manager.simulator.engine.Answers.*;

/** Triggers and the chain (rule 11): what an event triggers, the order effects are chained in, the priority to respond, and the slow effects a player activates in the open state. */
public final class Chain {

    private Chain() {}

    // ------------------------------------------------------------------ Triggers and chain

    /**
     * Moves the game on until it needs a player: turns pending events into triggered effects (only between
     * resolutions), activates them in rules order, resolves the chain and goes on with the combat.
     */
    static void advance(GameRoom room) {
        refreshContinuous(room);
        while (room.getPrompt() == null && room.getStatus() == GameRoom.RoomStatus.IN_PROGRESS) {
            // A card about to leave the field with a replacement: its owner chooses before anything else
            if (!room.getPendingRemovals().isEmpty()) {
                promptRemoval(room);
                continue;
            }
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

    static void queueTriggers(GameRoom room) {
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
        // A utility played whose "En Juego" does not activate (its condition, a Definitive one already used) has
        // nothing to resolve: it goes to the Descarte (rule 6.2.3.1)
        for (GameEvent event : events) {
            if (!GameEvent.PLAY.equals(event.getType())) continue;
            boolean resolves = triggered.stream().anyMatch(e -> e.getSourceInstanceId().equals(event.getCardInstanceId())
                    && script(e).moment() == EffectScript.Moment.ON_PLAY);
            PlayerState owner = room.getPlayer(event.getPlayerId());
            CardSlot card = owner == null ? null : find(owner.getField(), event.getCardInstanceId());
            if (!resolves && card != null && "utility".equals(card.getCardType())
                    && CardEffects.of(card).stream().anyMatch(sc -> sc.moment() == EffectScript.Moment.ON_PLAY)) {
                owner.getField().remove(card);
                leaveField(card);
                owner.getDiscardPile().add(card);
                record(room, GameEvent.SEND, owner.getUserId(), card, "field", "discardPile", null, null,
                        name(card) + " va al descarte: su efecto no se activa");
            }
        }
    }

    static List<PendingEffect> triggersOf(GameRoom room, GameEvent event) {
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
                List<EffectScript> scripts = new ArrayList<>(CardEffects.of(source));
                if (moment == EffectScript.Moment.ON_DESTROY && event.getData() != null && event.getData().get("inherited") instanceof List<?> names) {
                    for (Object n : names) scripts.addAll(CardEffects.inheritedOf((String) n));
                }
                for (EffectScript script : scripts) {
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

    static void addIfTriggers(GameRoom room, List<PendingEffect> result, EffectScript script, GameEvent event,
                                      CardSlot source, String controllerId) {
        if (script.condition() != null && !script.condition().test(room, event, source, controllerId)) return;
        PendingEffect e = pending(room, script, controllerId, source, event.getSeq());
        if (script.limit() != Limit.NONE && room.getUsedThisTurn().contains(usageKey(e, script))) {
            if (script.limit() == Limit.DEFINITIVE)
                log(room, name(source) + ": ya activaste un Efecto Definitivo este turno (solo 1 por turno)", null);
            return;
        }
        result.add(e);
    }

    static String usageKey(PendingEffect e, EffectScript script) {
        if (script.limit() == Limit.DEFINITIVE) return e.getControllerId() + "|" + DEFINITIVE_USED;
        if (script.limit() == Limit.ONE_EFFECT_OF_NAME_PER_TURN) return e.getControllerId() + "|name:" + e.getSourceName().toLowerCase(java.util.Locale.ROOT);
        return script.limit() == Limit.ONCE_PER_TURN_BETWEEN_COPIES
                ? e.getControllerId() + "|" + script.id()
                : e.getControllerId() + "|" + e.getSourceInstanceId() + "|" + script.id();
    }

    static int rank(PendingEffect e, String active) {
        boolean mandatory = script(e).mandatory();
        boolean mine = e.getControllerId().equals(active);
        return (mandatory ? 0 : 2) + (mine ? 0 : 1);
    }

    /** Mandatory effects go straight into the chain; optional ones ask their controller first. */
    static void activateNext(GameRoom room) {
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
            // Several mandatory effects (also several of one card, like its inherited ones): their controller
            // chains them one at a time (all of them are chained)
            // A lone one too (when the room asks for it): its controller sees what triggered and chains it
            if (group.size() > 1 || room.isConfirmMandatory()) {
                List<CardSlot> sources = sources(room, group);
                String instruction = group.size() > 1
                        ? "Estos efectos son obligatorios: se encadenan todos. Elige cuál entra ahora como Cadena "
                                + (room.getChain().size() + 1) + "; después eliges el siguiente. La cadena se resuelve al revés: "
                                + "el último eslabón se resuelve primero."
                        : "Este efecto es obligatorio: confírmalo para encadenarlo como Cadena " + (room.getChain().size() + 1) + ".";
                rulesPrompt(room, effect.getControllerId(), PURPOSE_ORDER,
                        group.size() > 1 ? "Encadenar efectos obligatorios" : "Efecto obligatorio", instruction, "Encadenar", sources, sources);
                offerEffects(room, room.getPrompt(), group);
                room.getPrompt().setMin(1);
                room.getPrompt().setMax(1);
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
                                + (group.size() > 1 ? " (después podrás elegir otro)" : "") + ", o no encadenes más. "
                                + "La cadena se resuelve al revés: el último eslabón se resuelve primero.",
                        "Encadenar", sources, sources);
                offerEffects(room, room.getPrompt(), group);
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
    static List<CardSlot> sources(GameRoom room, List<PendingEffect> group) {
        List<CardSlot> sources = new ArrayList<>();
        for (PendingEffect e : group) {
            CardSlot source = findAnywhere(room, e.getControllerId(), e.getSourceInstanceId());
            if (source != null && !sources.contains(source)) sources.add(source);
        }
        return sources;
    }

    /**
     * The effects of a group with their whole text, for a chaining choice. Each one is chosen by its own id: a
     * card can trigger several at once (its own and the inherited ones); its card's id picks its first one.
     */
    static void offerEffects(GameRoom room, Prompt prompt, List<PendingEffect> group) {
        prompt.setCardLabels(labels(group));
        prompt.setEffects(group.stream().map(e -> {
            // An inherited effect: the Recurso that gives it is shown on the card
            String from = CardEffects.inheritedFrom(e.getScriptId());
            CardSlot source = from == null ? null : findAnywhere(room, e.getControllerId(), e.getSourceInstanceId());
            CardSlot resource = source == null ? null
                    : source.getResources().stream().filter(r -> from.equalsIgnoreCase(r.getCardName())).findFirst().orElse(null);
            return new Prompt.OfferedEffect(e.getId(), e.getSourceInstanceId(), e.getSourceName(), e.getLabel(), e.getText(),
                    from == null ? null : resource != null ? resource.getCardName() : from, resource == null ? null : resource.getImageUrl());
        }).toList());
    }

    /** The effect a chaining choice picked: by its own id, or the first one of the card picked. */
    static boolean picked(PendingEffect e, String pick) {
        return e.getId().equals(pick) || e.getSourceInstanceId().equals(pick);
    }

    /** What each card of a group triggered ("Celeridad", "En Destrucción"), for the choice. */
    static Map<String, String> labels(List<PendingEffect> group) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (PendingEffect e : group) labels.merge(e.getSourceInstanceId(), e.getLabel(), (a, b) -> a + " + " + b);
        return labels;
    }

    /** Puts the effect in the chain; its cost, if any, is paid now (rule 11.2.0.2). */
    static void activate(GameRoom room, PendingEffect effect) {
        EffectScript script = script(effect);
        if (script.limit() != Limit.NONE) room.getUsedThisTurn().add(usageKey(effect, script));
        effect.setLink(room.getChain().size() + 1);
        record(room, GameEvent.EFFECT_ACTIVATED, effect.getControllerId(), null, null, null, effect,
                Map.of("label", effect.getLabel(), "link", effect.getLink(), "sourceId", effect.getSourceInstanceId(),
                        "sourceName", effect.getSourceName(), "scriptId", effect.getScriptId()),
                "Cadena " + effect.getLink() + ": se activa el efecto " + effect.getLabel() + " de " + effect.getSourceName());
        // Its cost is paid next (advance), before anything else happens
        room.getChain().add(effect);
    }

    /** The effect is still paying its cost. */
    static boolean paying(PendingEffect effect) {
        return effect.getCostIndex() < script(effect).cost().size();
    }

    /** An optional effect is offered only when its cost can be paid and its choices have something to choose. */
    /** How many of the next steps a connector makes conditional (0 for any other step). */
    static int covers(EffectStep step) {
        return switch (step) {
            case EffectStep.IfDone s -> s.covers();
            case EffectStep.IfNotDone s -> s.covers();
            case EffectStep.IfTributed s -> s.covers();
            case EffectStep.IfCondition s -> s.covers();
            case EffectStep.IfChosenGone s -> s.covers();
            case EffectStep.MayDo s -> s.covers();
            default -> 0;
        };
    }

    static boolean canActivate(GameRoom room, PendingEffect effect, EffectScript script) {
        for (EffectStep step : script.cost()) if (!possible(room, effect, step)) return false;
        if (!allPossibleAfterIfPossible(room, effect, script.steps(), 0)) return false;
        // "Si ningún enemigo fue …: …" (IfNotDone): the effect does something even when there is nothing to choose
        if (script.steps().stream().anyMatch(s -> s instanceof IfNotDone)) return true;
        List<EffectStep> steps = script.steps();
        for (int i = 0; i < steps.size(); i++) {
            int covers = covers(steps.get(i));
            if (covers > 0) {
                i += covers;
                continue;
            }
            if (steps.get(i) instanceof NeedsTarget && !possible(room, effect, steps.get(i))) return false;
        }
        return true;
    }

    static PendingEffect pending(GameRoom room, EffectScript script, String controllerId, CardSlot source, long triggerSeq) {
        PendingEffect e = candidate(script, controllerId, source, triggerSeq);
        e.setId(room.nextId("eff"));
        return e;
    }

    /** An effect that could be activated, still without an id. */
    static PendingEffect candidate(EffectScript script, String controllerId, CardSlot source, long triggerSeq) {
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
    static boolean offerPriority(GameRoom room) {
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
    static List<PendingEffect> responses(GameRoom room, String playerId, PendingEffect top) {
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
    static List<PendingEffect> slowEffects(GameRoom room, String userId) {
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
}
