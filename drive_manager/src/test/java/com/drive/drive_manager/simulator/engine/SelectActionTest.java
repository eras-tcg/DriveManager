package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.drive.drive_manager.simulator.engine.effects.model.Memory;
import com.drive.drive_manager.simulator.engine.effects.model.Selector;
import com.drive.drive_manager.simulator.engine.effects.runtime.ActionHandler;
import com.drive.drive_manager.simulator.engine.effects.runtime.Actions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The core of the new effect language: {@link Action.Select}, the memory, and old steps reading what actions saved. */
class SelectActionTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;
    private CardSlot source;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(5, 5);
        room = table.room();
        host = table.host();
        guest = table.guest();
        source = onField(host, card("src", "Fuente", 1, 1, "R"));
    }

    private static EffectStep target(Selector selector) {
        return new EffectStep.Do(new Action.Select(selector, true, null, null));
    }

    private static EffectStep pointAt(Selector selector) {
        return new EffectStep.Do(new Action.Select(selector, false, null, null));
    }

    private PendingEffect effect() {
        PendingEffect e = new PendingEffect();
        e.setId("e1");
        e.setControllerId("host");
        e.setSourceInstanceId(source.getInstanceId());
        e.setSourceName(source.getCardName());
        e.setLabel("Prueba");
        e.setText("Prueba");
        return e;
    }

    @Test
    void everyActionHasAHandler() {
        assertThat(Actions.handled()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(Action.class.getPermittedSubclasses()).map(c -> c.asSubclass(Action.class)).toList());
    }

    @Test
    void onlyElegirIsStoppedByCannotBeTargeted() {
        CardSlot immune = onField(guest, card("imm", "Inmune", 1, 1, "R"));
        immune.getTurnKeywords().add(EffectEngine.IMMUNE);
        Selector oneEnemy = Selector.enemies(CardFilter.creatures(), 1);
        assertThat(Resolution.possible(room, effect(), target(oneEnemy))).isFalse();
        assertThat(Resolution.possible(room, effect(), pointAt(oneEnemy))).isTrue();
    }

    @Test
    void enemiesInTheRetaguardiaCannotBeReached() {
        onField(guest, card("back", "Atrás", 1, 1, "R"), BACK);
        assertThat(Resolution.possible(room, effect(), pointAt(Selector.enemies(CardFilter.creatures(), 1)))).isFalse();
    }

    @Test
    void aSourceInTheRetaguardiaCannotReachEnemies() {
        source.setY(BACK);
        onField(guest, card("front", "Frente", 1, 1, "R"));
        assertThat(Resolution.possible(room, effect(), target(Selector.enemies(CardFilter.creatures(), 1)))).isFalse();
    }

    @Test
    void aCostNeedsTheWholeAmountButAResolutionDoesWhatItCan() {
        host.getHand().add(card("h1", "Mano", 1, 1, "R"));
        EffectStep two = pointAt(Selector.yours("hand", CardFilter.any(), 2));
        assertThat(Resolution.possible(room, effect(), two)).isFalse();

        assertThat(Resolution.run(room, effect(), two)).isTrue();
        assertThat(room.getPrompt().getMin()).isEqualTo(1);
        assertThat(room.getPrompt().getMax()).isEqualTo(1);
        assertThat(room.getPrompt().getValidIds()).containsExactly("h1");
    }

    @Test
    void upToAllowsNone() {
        host.getHand().add(card("h1", "Mano", 1, 1, "R"));
        assertThat(Resolution.run(room, effect(), pointAt(Selector.yours("hand", CardFilter.any(), 1).upTo()))).isTrue();
        assertThat(room.getPrompt().getMin()).isZero();
        assertThat(room.getPrompt().getMax()).isEqualTo(1);
    }

    @Test
    void includingThisCardIsRequiredWhenTheSelectorSaysSo() {
        host.getTributeZone().add(card("t1", "Tributo", 1, 1, "R"));
        source = card("self", "Esta", 1, 1, "R");
        host.getTributeZone().add(source);
        EffectStep step = pointAt(Selector.yours("tributeZone", CardFilter.any(), 1).with(Selector.Self.REQUIRE));
        PendingEffect e = effect();
        EngineContext ctx = new EngineContext(room, e);
        Action action = ((EffectStep.Do) step).action();
        assertThat(Actions.answer(ctx, action, new ActionHandler.Answer(List.of("t1"), List.of()))).contains("incluir");
        assertThat(Actions.answer(ctx, action, new ActionHandler.Answer(List.of("self"), List.of()))).isNull();
    }

    @Test
    void anotherCardExcludesThisOne() {
        source = card("self", "Esta", 1, 1, "R");
        host.getHand().add(source);
        assertThat(Resolution.possible(room, effect(), pointAt(Selector.yours("hand", CardFilter.any(), 1).with(Selector.Self.EXCLUDE)))).isFalse();
    }

    @Test
    void namedMemoryKeepsCardsApart() {
        EngineContext ctx = new EngineContext(room, effect());
        Action action = new Action.Select(Selector.yours("field", CardFilter.any(), 1), false, "pago", "Pago");
        assertThat(Actions.run(ctx, action)).isTrue();
        assertThat(room.getPrompt().getChosenLabel()).isEqualTo("Pago");
        assertThat(Actions.answer(ctx, action, new ActionHandler.Answer(List.of("src"), List.of()))).isNull();
        assertThat(ctx.saved("pago")).containsExactly("src");
        assertThat(ctx.saved(Memory.TARGETS)).isEmpty();
    }

    @Test
    void aSelectedTargetIsAnEventAndOldStepsReadIt() {
        CardSlot enemy = onField(guest, card("en", "Enemigo", 1, 1, "R"));
        PendingEffect e = effect();
        EngineContext ctx = new EngineContext(room, e);
        Action action = new Action.Select(Selector.enemies(CardFilter.creatures(), 1), true, null, null);
        assertThat(Actions.answer(ctx, action, new ActionHandler.Answer(List.of("en"), List.of()))).isNull();
        assertThat(e.getTargetIds()).containsExactly(enemy.getInstanceId());
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.TARGET);
    }
}
