package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.GameRoom;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What each line of the history is about, for the board: its event's type, the card behind it (the effect's card for an
 * effect's steps), the Recurso of an inherited effect, and whether it is one of an effect's steps (activations and
 * resolutions stand on their own; the steps hide in the short view, rule 11: "disparo, activación y resolución").
 */
final class HistoryDescriber {

    /** An effect's own moments, not its steps. */
    private static final Set<String> EFFECT_MOMENTS = Set.of(GameEvent.EFFECT_ACTIVATED, GameEvent.EFFECT_RESOLVED, GameEvent.EFFECT_DECLINED);

    private HistoryDescriber() {}

    static void describe(GameRoom room, GameLogEntry entry) {
        GameEvent event = EffectEngine.eventAt(room, entry.getEventSeq());
        if (event == null) {
            // A note while an effect resolves or pays its cost ("envía el resto al fondo de su mazo"): one of its steps
            PendingEffect top = room.getChain().isEmpty() ? null : room.getChain().get(room.getChain().size() - 1);
            if (top != null) {
                source(room, entry, top.getSourceInstanceId(), top.getSourceName());
                entry.setDetail(true);
            }
            return;
        }
        entry.setKind(event.getType());
        String sourceId = event.getCardInstanceId();
        String sourceName = event.getCardName();
        String scriptId = null;
        if (event.getEffectId() != null) {
            Whose whose = whose(room, event.getEffectId());
            if (whose != null) {
                sourceId = whose.sourceId();
                sourceName = whose.sourceName();
                scriptId = whose.scriptId();
            }
            boolean regulatory = event.getData() != null && Boolean.TRUE.equals(event.getData().get("regulatory"));
            entry.setDetail(!regulatory && !EFFECT_MOMENTS.contains(event.getType()));
        }
        CardSlot card = source(room, entry, sourceId, sourceName);
        inheritedFrom(room, entry, card, scriptId);
    }

    private record Whose(String sourceId, String sourceName, String scriptId) {}

    /** Whose effect it is: still pending (chain, triggers), else what its activation recorded. */
    private static Whose whose(GameRoom room, String effectId) {
        List<PendingEffect> pending = new ArrayList<>(room.getChain());
        pending.addAll(room.getTriggerQueue());
        for (PendingEffect e : pending) {
            if (effectId.equals(e.getId())) return new Whose(e.getSourceInstanceId(), e.getSourceName(), e.getScriptId());
        }
        Whose found = null;
        for (GameEvent e : room.getTimeline()) {
            if (GameEvent.EFFECT_ACTIVATED.equals(e.getType()) && effectId.equals(e.getEffectId()) && e.getData() != null) {
                found = new Whose((String) e.getData().get("sourceId"), (String) e.getData().get("sourceName"), (String) e.getData().get("scriptId"));
            }
        }
        return found;
    }

    /** The card behind the line, with its image. */
    private static CardSlot source(GameRoom room, GameLogEntry entry, String sourceId, String sourceName) {
        entry.setSourceId(sourceId);
        entry.setSourceName(sourceName);
        CardSlot card = sourceId == null ? null : EffectEngine.findInRoom(room, sourceId);
        if (card != null) entry.setImageUrl(card.getImageUrl());
        return card;
    }

    /** An inherited effect: the Recurso that gives it (on the card, else a card of that name in the room, for its image). */
    private static void inheritedFrom(GameRoom room, GameLogEntry entry, CardSlot card, String scriptId) {
        String from = scriptId == null ? null : CardEffects.inheritedFrom(scriptId);
        if (from == null) return;
        CardSlot resource = card == null ? null
                : card.getResources().stream().filter(r -> from.equalsIgnoreCase(r.getCardName())).findFirst().orElse(null);
        if (resource == null) resource = EffectEngine.cardsInRoom(room).filter(c -> from.equalsIgnoreCase(c.getCardName())).findFirst().orElse(null);
        entry.setInheritedFromName(resource != null ? resource.getCardName() : from);
        if (resource != null) entry.setInheritedFromImage(resource.getImageUrl());
    }
}
