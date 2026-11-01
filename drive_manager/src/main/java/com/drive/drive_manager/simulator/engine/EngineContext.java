package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.drive.drive_manager.simulator.engine.effects.model.Memory;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.EffectContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The game as actions see it. While old steps and new actions live together (until phase 7 of the refactor), the memory's
 * well-known names are the fields the old steps read, so one effect can mix both.
 */
final class EngineContext implements EffectContext {

    private final GameRoom room;
    private final PendingEffect effect;

    EngineContext(GameRoom room, PendingEffect effect) {
        this.room = room;
        this.effect = effect;
    }

    @Override
    public PlayerState player() {
        return room.getPlayer(effect.getControllerId());
    }

    @Override
    public PlayerState opponent() {
        return room.getPlayer(room.getOpponentId(effect.getControllerId()));
    }

    @Override
    public boolean isSelf(CardSlot card) {
        return card.getInstanceId().equals(effect.getSourceInstanceId());
    }

    @Override
    public boolean isOpponents(CardSlot card) {
        PlayerState opponent = opponent();
        return opponent != null && EffectEngine.find(opponent.getField(), card.getInstanceId()) != null;
    }

    @Override
    public boolean isCreature(CardSlot card) {
        return "creature".equals(card.getCardType());
    }

    @Override
    public List<CardSlot> attached(String zone) {
        CardSlot host = zone.endsWith(":subject") ? Steps.subject(room, effect) : EffectEngine.find(player().getField(), effect.getSourceInstanceId());
        if (host == null) return List.of();
        return zone.startsWith("materials") ? host.getMaterials() : host.getResources();
    }

    @Override
    public String describeZones(String zones) {
        List<String> names = new ArrayList<>();
        for (String raw : zones.split(",")) {
            String zone = raw.trim();
            if (zone.startsWith("materials")) names.add("los materiales");
            else if (zone.startsWith("resources")) names.add("los recursos");
            else names.add("hand".equals(zone) || "deck".equals(zone) ? "tu " + EffectEngine.zoneName(zone) : "tus " + EffectEngine.zoneName(zone));
        }
        return String.join(" o ", names);
    }

    @Override
    public boolean alreadyIn(CardSlot card, String to) {
        Origin origin = origin(card);
        return origin != null && origin.zone().equals(destination(to));
    }

    /** Where a card is now, and the card it is attached to, if any. */
    private record Origin(PlayerState owner, String zone, CardSlot host) {}

    private Origin origin(CardSlot card) {
        for (String id : List.of(effect.getControllerId(), room.getOpponentId(effect.getControllerId()) == null ? "" : room.getOpponentId(effect.getControllerId()))) {
            PlayerState p = room.getPlayer(id);
            if (p == null) continue;
            EffectEngine.Located at = EffectEngine.locate(p, card.getInstanceId());
            if (at != null) return new Origin(p, at.zone(), null);
            for (CardSlot host : p.getField()) {
                if (EffectEngine.find(host.getMaterials(), card.getInstanceId()) != null) return new Origin(p, "materials", host);
                if (EffectEngine.find(host.getResources(), card.getInstanceId()) != null) return new Origin(p, "resources", host);
            }
        }
        return null;
    }

    private static String destination(String to) {
        return Action.Move.DECK_TOP.equals(to) || Action.Move.DECK_BOTTOM.equals(to) ? "deck" : to;
    }

    @Override
    public void move(List<CardSlot> cards, String to) {
        String zone = destination(to);
        List<CardSlot> shown = new ArrayList<>();
        String from = null;
        for (CardSlot card : cards) {
            Origin origin = origin(card);
            if (origin == null) continue;
            from = origin.zone();
            if (origin.host() != null) ("materials".equals(from) ? origin.host().getMaterials() : origin.host().getResources()).remove(card);
            else if ("field".equals(from)) Removals.removeFromField(origin.owner(), card);
            else EffectEngine.zone(origin.owner(), from).remove(card);
            card.setFaceDown("deck".equals(zone));
            // A card leaving the field or another card always goes to its owner's zones
            List<CardSlot> into = EffectEngine.zone(origin.owner(), zone);
            if (Action.Move.DECK_TOP.equals(to)) into.add(0, card);
            else into.add(card);
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("byCard", origin.host() != null ? origin.host().getInstanceId() : effect.getSourceInstanceId());
            if ("deck".equals(zone)) data.put("position", Action.Move.DECK_TOP.equals(to) ? "top" : "bottom");
            EffectEngine.record(room, eventOf(zone), player().getUserId(), card, from, zone, effect, data, null);
            shown.add(card);
        }
        if (shown.isEmpty()) return;
        // A card of the hand or the deck stays hidden when it goes to the deck
        boolean hidden = "deck".equals(zone) && ("hand".equals(from) || "deck".equals(from));
        String origin = "deck".equals(from) ? " del tope de su mazo" : "";
        EffectEngine.log(room, player().getUsername() + " " + verb(to) + " " + shown.size() + " " + (shown.size() == 1 ? "carta" : "cartas") + origin
                + " (" + effect.getSourceName() + ")" + (hidden ? "" : EffectEngine.names(shown)), room.getEventSeq());
    }

    /** Rule of the designer (2026-11-01): the event comes from the destination, in this one place, whatever the verb printed. */
    private static String eventOf(String zone) {
        return switch (zone) {
            case "tributeZone" -> GameEvent.TRIBUTE;
            case "hand" -> GameEvent.ADD_TO_HAND;
            default -> GameEvent.SEND;
        };
    }

    private static String verb(String to) {
        return switch (to) {
            case Action.Move.TRIBUTES -> "tributa";
            case Action.Move.DISCARD -> "descarta";
            case Action.Move.DECK_TOP -> "coloca al tope de su mazo";
            case Action.Move.DECK_BOTTOM -> "envía al fondo de su mazo";
            case Action.Move.HAND -> "añade a su mano";
            default -> "mueve";
        };
    }

    @Override
    public void moved(List<CardSlot> cards) {
        effect.setLastCount(cards.size());
        effect.setLastTributed(new ArrayList<>(EffectEngine.ids(cards)));
        // "Si lo haces" reads what the resolution did, not the cost paid before it
        if (!Chain.paying(effect)) effect.setLastDone(!cards.isEmpty());
    }

    @Override
    public void requireInChoice(CardSlot card) {
        room.getPrompt().setRequiredIds(List.of(card.getInstanceId()));
    }

    @Override
    public boolean immune(CardSlot card) {
        return EffectEngine.immuneTo(card, Steps.sourceLevel(room, effect));
    }

    @Override
    public boolean inRetaguardia() {
        return Steps.inRetaguardia(room, effect);
    }

    @Override
    public boolean inFrontLine(CardSlot card) {
        return EffectEngine.inFrontLine(card);
    }

    @Override
    public List<CardSlot> zone(PlayerState player, String zone) {
        return EffectEngine.zone(player, zone);
    }

    @Override
    public int amount(Amount amount) {
        if (amount instanceof Amount.LastCount) return effect.getLastCount();
        return amount.of(room, effect.getControllerId());
    }

    @Override
    public String describe(Selector selector) {
        String who = selector.owner() == Selector.Owner.OPPONENT ? "enemigo" : "carta";
        String filter = selector.filter().describe();
        return filter.isBlank() ? who : who + " " + filter;
    }

    @Override
    public String name(CardSlot card) {
        return EffectEngine.name(card);
    }

    @Override
    public CardSlot find(String instanceId) {
        CardSlot mine = EffectEngine.findAnywhere(room, effect.getControllerId(), instanceId);
        return mine != null ? mine : EffectEngine.findAnywhere(room, room.getOpponentId(effect.getControllerId()), instanceId);
    }

    @Override
    public void ask(String instruction, String chosenLabel, List<CardSlot> shown, List<CardSlot> valid, int min, int max) {
        Steps.pick(room, effect, instruction, chosenLabel, shown, EffectEngine.ids(valid), max);
        room.getPrompt().setMin(min);
    }

    @Override
    public List<String> saved(String name) {
        return switch (name) {
            case Memory.TARGETS -> effect.getTargetIds();
            case Memory.LAST -> effect.getLastTributed();
            case Memory.SUBJECT -> effect.getSubjectId() == null ? List.of() : List.of(effect.getSubjectId());
            default -> effect.getSaved().getOrDefault(name, List.of());
        };
    }

    @Override
    public void save(String name, List<String> instanceIds) {
        switch (name) {
            // Several choices of one effect add up ("elige hasta 1 enemigo nivel 2 y 1 enemigo nivel 1")
            case Memory.TARGETS -> effect.getTargetIds().addAll(instanceIds);
            case Memory.LAST -> effect.setLastTributed(new ArrayList<>(instanceIds));
            case Memory.SUBJECT -> {
                if (!instanceIds.isEmpty()) effect.setSubjectId(instanceIds.get(0));
            }
            default -> effect.getSaved().computeIfAbsent(name, k -> new ArrayList<>()).addAll(instanceIds);
        }
    }

    @Override
    public void event(String type, CardSlot card, Map<String, Object> data, String message) {
        EffectEngine.record(room, type, effect.getControllerId(), card, null, null, effect, data, message + " (" + effect.getSourceName() + ")");
    }
}
