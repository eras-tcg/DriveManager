package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectStep.LookTop;
import com.drive.drive_manager.simulator.engine.EffectStep.TakeFromLookedRestToBottom;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeFromHand;

import java.util.*;

/**
 * Rules engine of the simulator (Reglamento v1.1). Grows card by card: actions it knows are carried out by
 * the rules; everything else is still played by hand on the board.
 *
 * Flow: an action produces timeline events → events trigger effects → triggered effects are activated into
 * the chain in rules order → the chain resolves last-in first-out, step by step; a step that needs a choice
 * pauses with a {@link Prompt} until the player answers.
 */
public final class EffectEngine {

    private static final int TIMELINE_LIMIT = 2000;
    private static final int LOG_LIMIT = 300;

    private EffectEngine() {}

    // ------------------------------------------------------------------ Normal summon

    /**
     * Normal summon (rules 6.2.1.1, 9.1.0.1): taking a creature from the hand to the board without an effect.
     * Pays its cost in gems, enters the Retaguardia and fires the summon triggers.
     *
     * @return an error message for the player, or null when done
     */
    public static String normalSummon(GameRoom room, String userId, String instanceId, Double x, Double y) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return "La partida no está en curso";
        if (!userId.equals(room.getCurrentTurnUserId())) return "Solo puedes invocar durante tu turno";
        if (!"MAIN".equals(room.getCurrentPhase())) return "Solo puedes invocar en la fase principal";
        if (room.isEngineBusy()) return "Hay un efecto resolviéndose";

        PlayerState player = room.getPlayer(userId);
        CardSlot card = player == null ? null : find(player.getHand(), instanceId);
        if (card == null) return "La carta no está en tu mano";
        if (!"creature".equals(card.getCardType())) return "Solo las criaturas se invocan";
        if (card.getCost() == null) return "Esta carta no tiene coste normal: no puede invocarse normalmente";

        // Rule 5.0.0.1: paying may not leave the opponent with more gems than their Marcador shows
        int cost = card.getCost();
        int gems = gemsOf(room, userId);
        PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
        int opponentMarker = opponent == null ? 0 : opponent.getResourceCount();
        if (gems - cost < -opponentMarker) return "No tienes gemas suficientes: tienes " + gems + " y cuesta " + cost;

        int dir = direction(room, userId);
        room.setSharedCounter(clamp(room.getSharedCounter() - cost * dir));

        player.getHand().remove(card);
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(x != null ? x : 50.0);
        // Normal summons enter the Retaguardia: the lower half of the owner's field
        card.setY(y != null && y >= 55 ? y : 75.0);
        player.getField().add(card);

        GameEvent summoned = record(room, GameEvent.NORMAL_SUMMON, userId, card, "hand", "field", null, Map.of("cost", cost),
                player.getUsername() + " invocó " + name(card) + " pagando " + cost + (cost == 1 ? " gema" : " gemas"));
        onEvents(room, List.of(summoned));
        return null;
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

    // ------------------------------------------------------------------ Triggers and chain

    /** Effects triggered by the events of the last action or resolution, activated into the chain in rules order. */
    static void onEvents(GameRoom room, List<GameEvent> events) {
        List<PendingEffect> triggered = new ArrayList<>();
        for (GameEvent event : events) {
            if (GameEvent.NORMAL_SUMMON.equals(event.getType())) {
                CardSlot source = findOnField(room, event.getPlayerId(), event.getCardInstanceId());
                if (source == null) continue;
                for (EffectScript script : CardEffects.of(source.getCardName())) {
                    if (script.moment() == EffectScript.Moment.ON_SUMMON) {
                        triggered.add(pending(room, script, event.getPlayerId(), source, event.getSeq()));
                    }
                }
            }
        }
        if (triggered.isEmpty()) return;

        // Rule 11.7.0.5: mandatory of the active player, mandatory of the opponent, optional of the active
        // player, optional of the opponent. Optional triggers ask before activating (not needed yet).
        String active = room.getCurrentTurnUserId();
        triggered.sort(Comparator.comparingInt(e -> rank(e, active)));
        for (PendingEffect effect : triggered) {
            EffectScript script = script(effect);
            if (!script.mandatory()) continue;
            record(room, GameEvent.EFFECT_ACTIVATED, effect.getControllerId(), null, null, null, effect, Map.of("label", effect.getLabel()),
                    "Se activa el efecto " + effect.getLabel() + " de " + effect.getSourceName());
            room.getChain().add(effect);
        }
        // Rules 11.3.0.3–11.3.0.5: each player could now add effects that respond to the chain. None of the
        // effects the engine knows can respond yet, so both players pass and the chain resolves.
        resolve(room);
    }

    private static int rank(PendingEffect e, String active) {
        boolean mandatory = script(e).mandatory();
        boolean mine = e.getControllerId().equals(active);
        return (mandatory ? 0 : 2) + (mine ? 0 : 1);
    }

    private static PendingEffect pending(GameRoom room, EffectScript script, String controllerId, CardSlot source, long triggerSeq) {
        PendingEffect e = new PendingEffect();
        e.setId(room.nextId("eff"));
        e.setScriptId(script.id());
        e.setControllerId(controllerId);
        e.setSourceInstanceId(source.getInstanceId());
        e.setSourceName(name(source));
        e.setLabel(script.label());
        e.setText(script.text());
        e.setTriggerSeq(triggerSeq);
        return e;
    }

    // ------------------------------------------------------------------ Resolution

    /** Resolves the chain from the last effect down, until it is empty or a step waits for a choice. */
    static void resolve(GameRoom room) {
        while (!room.getChain().isEmpty() && room.getPrompt() == null) {
            PendingEffect effect = room.getChain().get(room.getChain().size() - 1);
            if (effect.getResolutionId() == null) effect.setResolutionId(room.nextId("res"));
            List<EffectStep> steps = script(effect).steps();
            if (effect.getStepIndex() >= steps.size()) {
                record(room, GameEvent.EFFECT_RESOLVED, effect.getControllerId(), null, null, null, effect, null, null);
                room.getChain().remove(room.getChain().size() - 1);
                continue;
            }
            boolean waiting = run(room, effect, steps.get(effect.getStepIndex()));
            if (!waiting) effect.setStepIndex(effect.getStepIndex() + 1);
        }
    }

    /** Runs one step. Returns true when it is now waiting for the player's choice. */
    private static boolean run(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return false;

        if (step instanceof LookTop look) {
            int n = Math.min(look.count(), player.getDeck().size());
            for (int i = 0; i < n; i++) effect.getLooked().add(player.getDeck().remove(0));
            record(room, GameEvent.LOOK_TOP, player.getUserId(), null, "deck", null, effect, Map.of("count", n),
                    player.getUsername() + " mira las primeras " + n + " cartas de su mazo (" + effect.getSourceName() + ")");
            return false;
        }

        if (step instanceof TakeFromLookedRestToBottom take) {
            if (effect.getLooked().isEmpty()) return false;
            List<String> valid = effect.getLooked().stream().filter(take.filter()::matches).map(CardSlot::getInstanceId).toList();
            Prompt prompt = prompt(room, effect, Prompt.Kind.TAKE_REVEALED_REST_BOTTOM,
                    "Toma hasta " + take.max() + " " + (take.max() == 1 ? "carta" : "cartas") + " " + take.filter().describe()
                            + " para tu mano. Ordena el resto: irá al fondo de tu mazo en ese orden.");
            prompt.setCards(new ArrayList<>(effect.getLooked()));
            prompt.setValidIds(new ArrayList<>(valid));
            prompt.setMin(0);
            prompt.setMax(Math.min(take.max(), valid.size()));
            prompt.setReorderRest(true);
            room.setPrompt(prompt);
            return true;
        }

        if (step instanceof TributeFromHand tribute) {
            if (player.getHand().isEmpty()) return false;
            int n = Math.min(tribute.count(), player.getHand().size());
            Prompt prompt = prompt(room, effect, Prompt.Kind.PICK_FROM_HAND,
                    "Tributa " + n + " " + (n == 1 ? "carta" : "cartas") + " de tu mano.");
            prompt.setCards(new ArrayList<>(player.getHand()));
            prompt.setValidIds(player.getHand().stream().map(CardSlot::getInstanceId).toList());
            prompt.setMin(n);
            prompt.setMax(n);
            room.setPrompt(prompt);
            return true;
        }
        return false;
    }

    private static Prompt prompt(GameRoom room, PendingEffect effect, Prompt.Kind kind, String instruction) {
        Prompt p = new Prompt();
        p.setId(room.nextId("prompt"));
        p.setKind(kind);
        p.setPlayerId(effect.getControllerId());
        p.setEffectId(effect.getId());
        p.setSourceName(effect.getSourceName());
        p.setTitle(effect.getSourceName() + " · " + effect.getLabel());
        p.setInstruction(instruction);
        return p;
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
                    ? "Debes elegir " + prompt.getMin() + (prompt.getMin() == 1 ? " carta" : " cartas")
                    : "Puedes elegir hasta " + prompt.getMax() + (prompt.getMax() == 1 ? " carta" : " cartas");
        }
        if (!prompt.getValidIds().containsAll(picks)) return "Esa carta no cumple lo que pide el efecto";

        PendingEffect effect = room.getChain().stream().filter(e -> e.getId().equals(prompt.getEffectId())).findFirst().orElse(null);
        PlayerState player = room.getPlayer(userId);
        if (effect == null || player == null) return "El efecto ya no está pendiente";

        switch (prompt.getKind()) {
            case TAKE_REVEALED_REST_BOTTOM -> {
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
                    log(room, player.getUsername() + " envía " + rest.size() + (rest.size() == 1 ? " carta" : " cartas") + " al fondo de su mazo", null);
                }
                effect.getLooked().clear();
            }
            case PICK_FROM_HAND -> {
                for (String id : picks) {
                    CardSlot card = find(player.getHand(), id);
                    if (card == null) return "Esa carta ya no está en tu mano";
                }
                for (String id : picks) {
                    CardSlot card = find(player.getHand(), id);
                    player.getHand().remove(card);
                    card.setFaceDown(false);
                    player.getTributeZone().add(card);
                    record(room, GameEvent.TRIBUTE, userId, card, "hand", "tributeZone", effect, null,
                            player.getUsername() + " tributa " + name(card) + " de su mano (" + effect.getSourceName() + ")");
                }
            }
        }
        room.setPrompt(null);
        effect.setStepIndex(effect.getStepIndex() + 1);
        resolve(room);
        return null;
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

    /** Adds an event to the timeline and, when {@code logText} is given, a line to the text log. */
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
        if (logText != null) log(room, logText, e.getSeq());
        return e;
    }

    private static void log(GameRoom room, String text, Long seq) {
        room.getEventLog().add(new GameLogEntry(System.currentTimeMillis(), text, seq));
        if (room.getEventLog().size() > LOG_LIMIT) room.getEventLog().remove(0);
    }

    // ------------------------------------------------------------------ Helpers

    private static EffectScript script(PendingEffect effect) {
        // Scripts are looked up by the source card's name, which the pending effect keeps
        return CardEffects.of(effect.getSourceName()).stream()
                .filter(s -> s.id().equals(effect.getScriptId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown effect " + effect.getScriptId()));
    }

    private static CardSlot find(List<CardSlot> cards, String instanceId) {
        if (instanceId == null) return null;
        return cards.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
    }

    private static CardSlot findOnField(GameRoom room, String playerId, String instanceId) {
        PlayerState p = room.getPlayer(playerId);
        return p == null ? null : find(p.getField(), instanceId);
    }

    private static String name(CardSlot card) {
        return card.getCardName() != null ? card.getCardName() : "una carta";
    }

    private static int clamp(int value) {
        return Math.max(-10, Math.min(10, value));
    }
}
