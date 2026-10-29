package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** What a Recurso gives its creature: inherited abilities and effects, and how the history shows them. */
class InheritanceTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(5, 6);
        room = table.room();
        host = table.host();
        guest = table.guest();
    }

    @Test
    void reactivarInheritedFromAResourceReactivatesAtTheStartOfAnyTurn() {
        CardSlot carmesi = onField(host, card("dc", "Dragón Carmesí", 5, 4, "G"));
        CardSlot dracoventus = card("dv", "Dracoventus", 3, 3, "G");
        dracoventus.setInheritKeywords(new ArrayList<>(List.of("Reactivar")));
        carmesi.getResources().add(dracoventus);
        carmesi.setTapped(true);

        assertThat(EffectEngine.keywords(carmesi)).contains("Reactivar");
        // The opponent's turn starts: Reactivar works on any turn
        room.setCurrentTurnUserId("guest");
        TurnRules.turnStarted(room, "guest");
        assertThat(carmesi.isTapped()).isFalse();
    }

    @Test
    void inheritedEffectsNameTheirRecursoAndTheHistoryKnowsWhoseStepsTheyAre() {
        room.setConfirmMandatory(true);
        guest.getLifeStack().addAll(deck("gl", 3));
        CardSlot galveta = onField(host, card("gal", "Galveta", 2, 2, "G"));
        CardSlot duregon = card("dur", "Duregón", 1, 1, "G");
        duregon.setImageUrl("img-dur");
        galveta.getResources().add(duregon);

        CombatRules.attackByPlayer(room, "host", "gal", null);
        Prompt order = room.getPrompt();
        assertThat(order.getEffects()).anySatisfy(e -> {
            assertThat(e.inheritedFromName()).isEqualTo("Duregón");
            assertThat(e.inheritedFromImage()).isEqualTo("img-dur");
        });
        // Chain it: draw 1, then tribute 1 from the hand
        String inherited = order.getEffects().stream().filter(e -> e.inheritedFromName() != null).findFirst().orElseThrow().effectId();
        Answers.answer(room, "host", order.getId(), List.of(inherited), List.of());
        while (room.getPrompt() != null) {
            Prompt p = room.getPrompt();
            List<String> pick = p.getMin() > 0 ? p.getValidIds().subList(0, p.getMin()) : List.of();
            Answers.answer(room, p.getPlayerId(), p.getId(), pick, List.of());
        }

        List<com.drive.drive_manager.simulator.GameLogEntry> log = room.getEventLog();
        assertThat(log).anySatisfy(l -> {
            assertThat(l.getKind()).isEqualTo(GameEvent.EFFECT_ACTIVATED);
            assertThat(l.getSourceId()).isEqualTo("gal");
            assertThat(l.isDetail()).isFalse();
            // Inherited: its Recurso, as in the chaining choice
            assertThat(l.getInheritedFromName()).isEqualTo("Duregón");
            assertThat(l.getInheritedFromImage()).isEqualTo("img-dur");
        });
        assertThat(log).anySatisfy(l -> assertThat(l.getKind()).isEqualTo(GameEvent.EFFECT_RESOLVED));
        // An effect's step: whose it is, and hidden in the short view
        assertThat(log).anySatisfy(l -> {
            assertThat(l.getKind()).isEqualTo(GameEvent.TRIBUTE);
            assertThat(l.getSourceId()).isEqualTo("gal");
            assertThat(l.isDetail()).isTrue();
        });
    }
}
