package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
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
