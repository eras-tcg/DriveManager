package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectStep.DeclareAttack;
import com.drive.drive_manager.simulator.engine.EffectStep.DestroyEnemy;
import com.drive.drive_manager.simulator.engine.EffectStep.Draw;
import com.drive.drive_manager.simulator.engine.EffectStep.GainUntilEndOfTurn;
import com.drive.drive_manager.simulator.engine.EffectStep.LookTop;
import com.drive.drive_manager.simulator.engine.EffectStep.MayDo;
import com.drive.drive_manager.simulator.engine.EffectStep.SendFromZoneToDeckBottom;
import com.drive.drive_manager.simulator.engine.EffectStep.SendSelfTo;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.TakeFromLookedRestToBottom;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeFromHand;
import com.drive.drive_manager.simulator.engine.EffectStep.UntapSelf;

import java.util.*;

/**
 * Rules engine of the simulator (Reglamento v1.1). Grows card by card: actions it knows are carried out by
 * the rules; everything else is still played by hand on the board.
 *
 * Flow: an action produces timeline events → once no effect is in the middle of its resolution, those events
 * trigger effects (rule 11.6.0.4) → triggered effects are activated into the chain in rules order (optional
 * ones ask first) → the chain resolves last-in first-out, step by step; a step that needs a choice pauses
 * with a {@link Prompt} until the player answers. Events of a resolution open a new window when it ends.
 */
public final class EffectEngine {

    private static final int TIMELINE_LIMIT = 2000;
    private static final int LOG_LIMIT = 300;

    /** Why a card was destroyed (rule 12.0.0.1). MANUAL: done from the board while combat is still played by hand. */
    public static final String CAUSE_EFFECT = "EFFECT";
    public static final String CAUSE_RITUAL = "RITUAL";
    public static final String CAUSE_MANUAL = "MANUAL";

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

        int cost = card.getCost();
        if (!canPay(room, userId, cost)) return "No tienes gemas suficientes: tienes " + gemsOf(room, userId) + " y cuesta " + cost;
        pay(room, userId, cost);

        player.getHand().remove(card);
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(x != null ? x : 50.0);
        // Normal summons enter the Retaguardia: the lower half of the owner's field
        card.setY(y != null && y >= 55 ? y : 75.0);
        player.getField().add(card);

        record(room, GameEvent.NORMAL_SUMMON, userId, card, "hand", "field", null, Map.of("cost", cost),
                player.getUsername() + " invocó " + name(card) + " pagando " + gemsText(cost));
        advance(room);
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
        record(room, GameEvent.SPECIAL_SUMMON, userId, summoned, null, "field", null, Map.of("summonType", summonType == null ? "special" : summonType), logText);
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

    /** Rule 5.0.0.1: paying may not leave the opponent with more gems than their Marcador shows. */
    static boolean canPay(GameRoom room, String userId, int cost) {
        PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
        int opponentMarker = opponent == null ? 0 : opponent.getResourceCount();
        return gemsOf(room, userId) - cost >= -opponentMarker;
    }

    private static void pay(GameRoom room, String userId, int cost) {
        room.setSharedCounter(clamp(room.getSharedCounter() - cost * direction(room, userId)));
    }

    // ------------------------------------------------------------------ Destruction and sacrifice

    /**
     * Destroys a card on the field from the board (combat and other cases still played by hand): it goes to
     * its owner's Descarte and its "En Destrucción" effects trigger.
     */
    public static String destroyByPlayer(GameRoom room, String userId, String ownerId, String instanceId) {
        String error = checkOpen(room);
        if (error != null) return error;
        PlayerState owner = room.getPlayer(ownerId != null ? ownerId : userId);
        CardSlot card = owner == null ? null : find(owner.getField(), instanceId);
        if (card == null) return "La carta no está en el campo";
        destroy(room, card, owner, false, CAUSE_MANUAL, userId, null);
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
        destroy(room, card, owner, true, CAUSE_MANUAL, userId, null);
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
        if (card.getStrength() + card.getStrengthModifier() > 0 || find(owner.getField(), card.getInstanceId()) == null) return;
        destroy(room, card, owner, false, CAUSE_EFFECT, userId, null);
        advance(room);
    }

    private static String checkOpen(GameRoom room) {
        if (room.getStatus() != GameRoom.RoomStatus.IN_PROGRESS) return "La partida no está en curso";
        if (room.isEngineBusy()) return "Hay un efecto resolviéndose";
        return null;
    }

    /**
     * Destroys (to the Descarte) or sacrifices (to the Tributos) a card on its owner's field. Its Recursos and
     * Materiales go to the Descarte (rules 6.3.0.5, 6.4.0.5) and it loses its state on the field.
     */
    static GameEvent destroy(GameRoom room, CardSlot card, PlayerState owner, boolean sacrifice, String cause, String byId,
                             PendingEffect effect) {
        owner.getField().remove(card);
        for (CardSlot attached : concat(card.getMaterials(), card.getResources())) {
            attached.setFaceDown(false);
            owner.getDiscardPile().add(attached);
        }
        card.setMaterials(new ArrayList<>());
        card.setResources(new ArrayList<>());
        card.setFaceDown(false);
        card.setTapped(false);
        card.setStrengthModifier(0);
        card.getTurnKeywords().clear();
        card.setX(null);
        card.setY(null);
        String toZone = sacrifice ? "tributeZone" : "discardPile";
        (sacrifice ? owner.getTributeZone() : owner.getDiscardPile()).add(card);

        PlayerState by = room.getPlayer(byId);
        String who = by != null ? by.getUsername() : "Las reglas";
        String whose = by != null && by != owner ? " de " + owner.getUsername() : "";
        String why = effect != null ? " (" + effect.getSourceName() + ")"
                : CAUSE_EFFECT.equals(cause) ? " (fuerza 0)" : "";
        String text = sacrifice ? who + " sacrifica " + name(card) + why : who + " destruye " + name(card) + whose + why;
        return record(room, sacrifice ? GameEvent.SACRIFICE : GameEvent.DESTROY, byId, card, "field", toZone, effect,
                Map.of("cause", cause, "ownerId", owner.getUserId()), text);
    }

    // ------------------------------------------------------------------ Triggers and chain

    /**
     * Moves the game on until it needs a player: turns pending events into triggered effects (only between
     * resolutions), activates them in rules order and resolves the chain.
     */
    static void advance(GameRoom room) {
        while (room.getPrompt() == null) {
            List<PendingEffect> chain = room.getChain();
            // Rule 11.6.0.4: triggers do not interrupt the effect that caused them
            boolean midResolution = !chain.isEmpty() && chain.get(chain.size() - 1).getResolutionId() != null;
            if (!midResolution && !room.getPendingEvents().isEmpty()) {
                queueTriggers(room);
            } else if (!midResolution && !room.getTriggerQueue().isEmpty()) {
                activateNext(room);
            } else if (!chain.isEmpty()) {
                // Rules 11.3.0.3–11.3.0.5: players could now respond. None of the effects the engine knows can
                // respond to a chain yet, so both players pass and the chain resolves.
                stepTop(room);
            } else {
                return;
            }
        }
    }

    private static void queueTriggers(GameRoom room) {
        List<GameEvent> events = new ArrayList<>(room.getPendingEvents());
        room.getPendingEvents().clear();
        List<PendingEffect> triggered = new ArrayList<>();
        for (GameEvent event : events) triggered.addAll(triggersOf(room, event));
        // Rule 11.7.0.5: mandatory of the active player, mandatory of the opponent, optional of the active
        // player, optional of the opponent (within a group, in the order they happened)
        String active = room.getCurrentTurnUserId();
        triggered.sort(Comparator.comparingInt(e -> rank(e, active)));
        room.getTriggerQueue().addAll(triggered);
    }

    private static List<PendingEffect> triggersOf(GameRoom room, GameEvent event) {
        EffectScript.Moment moment;
        String controllerId;
        CardSlot source;
        switch (event.getType()) {
            case GameEvent.NORMAL_SUMMON, GameEvent.EFFECT_SUMMON, GameEvent.SPECIAL_SUMMON -> {
                moment = EffectScript.Moment.ON_SUMMON;
                controllerId = event.getPlayerId();
                source = findIn(room, controllerId, "field", event.getCardInstanceId());
            }
            case GameEvent.DESTROY, GameEvent.SACRIFICE -> {
                // It activates from the zone it was sent to (rule 11.4.1.2d), for its owner
                moment = EffectScript.Moment.ON_DESTROY;
                controllerId = String.valueOf(event.getData().get("ownerId"));
                source = findIn(room, controllerId, event.getToZone(), event.getCardInstanceId());
            }
            default -> {
                return List.of();
            }
        }
        if (source == null) return List.of();
        List<PendingEffect> result = new ArrayList<>();
        for (EffectScript script : CardEffects.of(source.getCardName())) {
            if (script.moment() == moment) result.add(pending(room, script, controllerId, source, event.getSeq()));
        }
        return result;
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
        if (script.mandatory()) {
            room.getTriggerQueue().remove(0);
            activate(room, effect);
            return;
        }
        if (!canActivate(room, effect, script)) {
            // Its cost cannot be paid or it would do nothing: it loses its moment
            room.getTriggerQueue().remove(0);
            return;
        }
        Prompt prompt = prompt(room, effect, Prompt.Kind.CONFIRM, "¿Activas este efecto? " + effect.getText());
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

    /** Puts the effect in the chain; its cost, if any, is paid now (rule 11.2.0.2). */
    private static void activate(GameRoom room, PendingEffect effect) {
        record(room, GameEvent.EFFECT_ACTIVATED, effect.getControllerId(), null, null, null, effect, Map.of("label", effect.getLabel()),
                "Se activa el efecto " + effect.getLabel() + " de " + effect.getSourceName());
        for (EffectStep step : script(effect).cost()) run(room, effect, step);
        room.getChain().add(effect);
    }

    private static boolean canActivate(GameRoom room, PendingEffect effect, EffectScript script) {
        for (EffectStep step : script.cost()) if (!possible(room, effect, step)) return false;
        return script.steps().isEmpty() || possible(room, effect, script.steps().get(0));
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

    /** Runs the next step of the last effect in the chain, or finishes it. */
    private static void stepTop(GameRoom room) {
        PendingEffect effect = room.getChain().get(room.getChain().size() - 1);
        if (effect.getResolutionId() == null) effect.setResolutionId(room.nextId("res"));
        List<EffectStep> steps = script(effect).steps();
        if (effect.getStepIndex() >= steps.size()) {
            record(room, GameEvent.EFFECT_RESOLVED, effect.getControllerId(), null, null, null, effect, null, null);
            room.getChain().remove(room.getChain().size() - 1);
            return;
        }
        boolean waiting = run(room, effect, steps.get(effect.getStepIndex()));
        if (!waiting) effect.setStepIndex(effect.getStepIndex() + 1);
    }

    /** Whether a step can do anything now (used to offer optional effects and "puedes" choices). */
    private static boolean possible(GameRoom room, PendingEffect effect, EffectStep step) {
        PlayerState player = room.getPlayer(effect.getControllerId());
        if (player == null) return false;
        return switch (step) {
            case DestroyEnemy destroy -> !enemyTargets(room, effect.getControllerId(), destroy.filter()).isEmpty();
            case SummonFromZone summon -> !summonable(room, effect, summon).isEmpty();
            case SendSelfTo send -> {
                Located at = locate(player, effect.getSourceInstanceId());
                yield at != null && !at.zone().equals(send.zone()) && !"field".equals(at.zone());
            }
            case SendFromZoneToDeckBottom send -> zone(player, send.zone()).stream().filter(send.filter()::matches).count() >= send.count();
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
                        player.getHand(), player.getHand().stream().map(CardSlot::getInstanceId).toList(), n);
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
                List<CardSlot> valid = enemyTargets(room, userId, destroy.filter());
                if (valid.isEmpty()) {
                    log(room, "No hay enemigos " + destroy.filter().describe() + " en la línea frontal (" + effect.getSourceName() + ")", null);
                    return false;
                }
                int n = Math.min(destroy.count(), valid.size());
                PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
                pick(room, effect, "Elige " + n + " " + plural(n, "enemigo", "enemigos") + " " + destroy.filter().describe() + " de la línea frontal: "
                        + plural(n, "será destruido", "serán destruidos") + ".", "Destruir",
                        frontLine(opponent.getField()), valid.stream().map(CardSlot::getInstanceId).toList(), n);
                return true;
            }
            case SummonFromZone summon -> {
                List<CardSlot> valid = summonable(room, effect, summon);
                if (valid.isEmpty()) {
                    log(room, "No hay criaturas que " + effect.getSourceName() + " pueda invocar", null);
                    return false;
                }
                pick(room, effect, "Invoca 1 criatura " + summon.filter().describe() + " de tus " + zoneName(summon.zone())
                                + " pagando su coste reducido en " + summon.reduce() + ". Entra en Retaguardia y robas 1 carta.", "Invocar",
                        zone(player, summon.zone()), valid.stream().map(CardSlot::getInstanceId).toList(), 1);
                return true;
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
            case GainUntilEndOfTurn gain -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null) return false;
                self.getTurnKeywords().add(gain.keyword());
                record(room, GameEvent.GAIN, userId, self, null, null, effect, Map.of("keyword", gain.keyword()),
                        name(self) + " gana " + gain.keyword() + " hasta el final del turno");
                return false;
            }
            case DeclareAttack ignored -> {
                CardSlot self = find(player.getField(), effect.getSourceInstanceId());
                if (self == null) return false;
                record(room, GameEvent.DECLARE_ATTACK, userId, self, null, null, effect, null,
                        name(self) + " declara ataque: resuelve el combate en el tablero");
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
        p.setTitle(effect.getSourceName() + " · " + effect.getLabel());
        p.setInstruction(instruction);
        return p;
    }

    /** Enemies the effect may choose: the opponent's Línea frontal (rule 4.0.6.2), matching the filter. */
    private static List<CardSlot> enemyTargets(GameRoom room, String userId, CardFilter filter) {
        PlayerState opponent = room.getPlayer(room.getOpponentId(userId));
        if (opponent == null) return List.of();
        return frontLine(opponent.getField()).stream().filter(filter::matches).toList();
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

    private static List<CardSlot> frontLine(List<CardSlot> field) {
        return field.stream().filter(c -> c.getY() != null && c.getY() < 50).toList();
    }

    /** Summon by effect (rule 9.1.0.2): pays the cost, enters the Retaguardia, then the regulatory draw (9.1.0.4). */
    private static void summonByEffect(GameRoom room, PendingEffect effect, PlayerState player, CardSlot card, String fromZone, int reduce) {
        int cost = Math.max(0, card.getCost() - reduce);
        pay(room, player.getUserId(), cost);
        zone(player, fromZone).remove(card);
        card.setFaceDown(false);
        card.setTapped(false);
        card.setX(50.0);
        card.setY(75.0);
        player.getField().add(card);
        record(room, GameEvent.EFFECT_SUMMON, player.getUserId(), card, fromZone, "field", effect, Map.of("cost", cost),
                player.getUsername() + " invoca " + name(card) + " de sus " + zoneName(fromZone) + " pagando " + gemsText(cost)
                        + " (" + effect.getSourceName() + ")");
        if (!player.getDeck().isEmpty()) {
            drawOne(room, player, effect, true);
            log(room, player.getUsername() + " roba 1 carta por la invocación", room.getEventSeq());
        }
    }

    private static void drawOne(GameRoom room, PlayerState player, PendingEffect effect, boolean regulatory) {
        CardSlot card = player.getDeck().remove(0);
        card.setFaceDown(false);
        player.getHand().add(card);
        record(room, GameEvent.DRAW, player.getUserId(), card, "deck", "hand", effect, regulatory ? Map.of("regulatory", true) : null, null);
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
        EffectStep step = script(effect).steps().get(effect.getStepIndex());
        String error = apply(room, effect, step, player, picks, order);
        if (error != null) return error;
        room.setPrompt(null);
        effect.setStepIndex(effect.getStepIndex() + 1);
        advance(room);
        return null;
    }

    /** Carries out the step with the player's choice. */
    private static String apply(GameRoom room, PendingEffect effect, EffectStep step, PlayerState player, List<String> picks, List<String> order) {
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
                for (String id : picks) destroy(room, find(opponent.getField(), id), opponent, false, CAUSE_EFFECT, userId, effect);
            }
            case SummonFromZone summon -> {
                List<CardSlot> valid = summonable(room, effect, summon);
                CardSlot card = find(valid, picks.get(0));
                if (card == null) return "Ya no puedes invocar esa criatura";
                summonByEffect(room, effect, player, card, summon.zone(), summon.reduce());
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
        // Scripts are looked up by the source card's name, which the pending effect keeps
        return CardEffects.of(effect.getSourceName()).stream()
                .filter(s -> s.id().equals(effect.getScriptId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown effect " + effect.getScriptId()));
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
            default -> throw new IllegalArgumentException("Unknown zone " + zone);
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

    private static CardSlot find(List<CardSlot> cards, String instanceId) {
        if (instanceId == null) return null;
        return cards.stream().filter(c -> instanceId.equals(c.getInstanceId())).findFirst().orElse(null);
    }

    private static CardSlot findIn(GameRoom room, String playerId, String zone, String instanceId) {
        PlayerState p = room.getPlayer(playerId);
        return p == null || zone == null ? null : find(zone(p, zone), instanceId);
    }

    private static List<CardSlot> concat(List<CardSlot> a, List<CardSlot> b) {
        List<CardSlot> all = new ArrayList<>(a);
        all.addAll(b);
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
