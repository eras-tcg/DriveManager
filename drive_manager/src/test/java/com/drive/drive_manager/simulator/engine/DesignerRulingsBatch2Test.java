package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Designer corrections of 2026-10-30 (second batch): timing, removal from the field, rules destruction, blocker swap, "hasta 2". */
class DesignerRulingsBatch2Test {

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

    private GameEvent event(long seq, String type, String cardId, String resolution) {
        GameEvent e = new GameEvent();
        e.setSeq(seq);
        e.setType(type);
        e.setPlayerId("host");
        e.setCardInstanceId(cardId);
        e.setResolutionId(resolution);
        room.getTimeline().add(e);
        return e;
    }

    @Test
    void inmediatamenteMissesItsMomentWhenSomethingElseHappenedAfter() {
        onField(host, card("s", "Invocada", 1, 1, "R"));
        Condition espada = new Condition.Immediately(new Condition.AllySummonedByEffect(CardFilter.any()));

        GameEvent summon = event(1, GameEvent.EFFECT_SUMMON, "s", "r1");
        event(2, GameEvent.EFFECT_RESOLVED, null, "r1");
        assertThat(espada.test(room, summon, null, "host")).isTrue();

        // "Si lo haces: mueve esta carta a tu línea frontal" happened after the summon, in the same resolution
        event(3, GameEvent.MOVE, "x", "r1");
        assertThat(espada.test(room, summon, null, "host")).isFalse();
        // A plain "si…" is still met
        assertThat(new Condition.AllySummonedByEffect(CardFilter.any()).test(room, summon, null, "host")).isTrue();
    }

    @Test
    void aCreatureThatLeavesTheFieldLosesItsFloatingEffects() {
        CardSlot c = onField(host, card("c", "Alabarda", 1, 1, "R"));
        c.setTurnStrength(3);
        c.getTurnKeywords().add("Furia 1");
        c.getLaterKeywords().add("Reactivar");
        c.getUntilNextAttack().add("Provocar");
        c.setNoUntap(1);
        c.getFloating().add(new com.drive.drive_manager.simulator.FloatingEffect("Gana fuerza 3", "Valor Del Heroe", "turn", 3, null));

        Removals.leaveField(c);

        assertThat(c.getTurnStrength()).isZero();
        assertThat(c.getTurnKeywords()).isEmpty();
        assertThat(c.getLaterKeywords()).isEmpty();
        assertThat(c.getUntilNextAttack()).isEmpty();
        assertThat(c.getNoUntap()).isZero();
        assertThat(c.getFloating()).isEmpty();
    }

    @Test
    void atStrengthZeroAnUnremovableCreatureIsDestroyedByTheRulesNotByTheEffect() {
        CardSlot target = onField(guest, card("t", "Protegida", 1, 1, "B"));
        target.setStrength(3);
        target.getLaterKeywords().add(EffectEngine.UNREMOVABLE);
        PendingEffect effect = new PendingEffect();
        effect.setControllerId("host");
        effect.setSourceName("Reductor");

        Steps.reduceStrength(room, effect, guest, target, 3, false);

        assertThat(guest.getField()).doesNotContain(target);
        assertThat(guest.getDiscardPile()).contains(target);
        GameEvent destroyed = room.getTimeline().stream().filter(e -> GameEvent.DESTROY.equals(e.getType())).reduce((a, b) -> b).orElseThrow();
        assertThat(destroyed.getData()).containsEntry("cause", EffectEngine.CAUSE_RULES);
        assertThat(destroyed.getPlayerId()).isNull();
        assertThat(destroyed.getEffectId()).isNull();
        // Nothing that reacts to a destruction by an effect
        assertThat(new Condition.DestroyedByEffect().test(room, destroyed, target, "guest")).isFalse();
    }

    @Test
    void comandanteVeteranoMayDeclareAnExhaustedAllyAndDoesNotExhaustIt() {
        onField(guest, card("cv", "Comandante Veterano", 5, 4, "R"), FRONT);
        CardSlot blocker = onField(guest, card("b1", "Bloqueador", 1, 1, "R"), FRONT);
        CardSlot exhausted = onField(guest, card("b2", "Agotado", 1, 1, "R"), FRONT);
        exhausted.setTapped(true);
        blocker.setTapped(true);
        Combat combat = new Combat();
        combat.setAttackerId("at");
        combat.setDefenderId("guest");
        combat.setBlockerId("b1");
        room.setCombat(combat);

        assertThat(CombatRules.offerBlockerSwap(room, guest, blocker)).isTrue();
        Prompt swap = room.getPrompt();
        assertThat(swap.getValidIds()).contains("b2");
        room.setPrompt(null);
        CombatRules.swapBlocker(room, guest, swap, List.of("b2"));

        assertThat(combat.getBlockerId()).isEqualTo("b2");
        assertThat(blocker.isTapped()).isFalse();
        // It was exhausted and stays so; an active one would have stayed active
        assertThat(exhausted.isTapped()).isTrue();
    }

    @Test
    void escudosLealesReactivatesUpToTwoAlliesInOneChoice() {
        CardSlot a = onField(host, card("a1", "Aliado 1", 1, 1, "R"));
        CardSlot b = onField(host, card("a2", "Aliado 2", 1, 1, "R"));
        a.setTapped(true);
        b.setTapped(true);
        host.getHand().add(utility("el", "Escudos Leales"));

        assertThat(Summons.playByPlayer(room, "host", "el", 50.0, BACK)).isNull();
        Prompt p = room.getPrompt();
        assertThat(p.getMin()).isZero();
        assertThat(p.getMax()).isEqualTo(2);
        assertThat(Answers.answer(room, "host", p.getId(), List.of("a1", "a2"), List.of())).isNull();

        assertThat(a.isTapped()).isFalse();
        assertThat(b.isTapped()).isFalse();
        assertThat(room.getPrompt()).isNull();
    }

    @Test
    void whatWasGivenToTheTableEndsWithItsTurn() {
        room.getFieldGrants().add(new FieldGrant("host", CardFilter.creatures(), "Furia", "turn", "A La Carga!"));
        CardSlot c = onField(host, card("c", "Criatura", 1, 1, "R"));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(c)).contains("Furia");
        // The rival's creatures do not get it
        CardSlot rival = onField(guest, card("r", "Rival", 1, 1, "R"));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(rival)).doesNotContain("Furia");
    }
}
