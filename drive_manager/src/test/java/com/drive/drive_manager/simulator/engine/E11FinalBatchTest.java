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

/** E1.1, last batch: Festín, Zamaranzas, Composición Refractaria, Fuego Perforante, Escudos Leales, Mercenario, Comandante, Wyverno Rapaz. */
class E11FinalBatchTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(8, 8);
        room = table.room();
        host = table.host();
        guest = table.guest();
        for (int i = 0; i < 3; i++) {
            host.getLifeStack().add(card("hl" + i, "Vida H" + i, 1, 1, "R"));
            guest.getLifeStack().add(card("gl" + i, "Vida G" + i, 1, 1, "R"));
        }
    }

    @Test
    void festinSacrificesAnAllySoAnotherOfUpToOneLevelMoreAttacks() {
        CardSlot small = onField(host, card("s", "Pequeño", 1, 1, "P"), BACK);
        CardSlot level2 = onField(host, card("l2", "Nivel 2", 2, 2, "P"));
        onField(host, card("l3", "Nivel 3", 3, 3, "P"));
        host.getHand().add(utility("fe", "Festin"));
        assertThat(Summons.playByPlayer(room, "host", "fe", 50.0, 75.0)).isNull();
        answer(room.getPrompt(), "fe");
        answer(room.getPrompt(), "s");
        Prompt attacker = room.getPrompt();
        assertThat(attacker.getValidIds()).containsExactly("l2");
        answer(attacker, "l2");
        assertThat(host.getTributeZone()).contains(small);
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.ATTACK_DECLARED.equals(e.getType()) && "l2".equals(e.getCardInstanceId()));
        assertThat(level2).isNotNull();
    }

    @Test
    void zamaranzasFromTheTributosAddsAnAllyAsMaterialOfAHigherOneAndEntersForFree() {
        CardSlot low = onField(host, card("lo", "Bajo", 1, 1, "B"));
        CardSlot high = onField(host, card("hi", "Alto", 2, 2, "B"));
        CardSlot zam = withClasses(card("za", "Zamaranzas", 1, 1, "B"), "ente");
        host.getTributeZone().add(zam);
        int gems = EffectEngine.gemsOf(room, "host");
        assertThat(Chain.activateByPlayer(room, "host", "za", "zamaranzas:en-tributos")).isNull();
        answer(room.getPrompt(), "lo");
        Prompt holder = room.getPrompt();
        assertThat(holder.getValidIds()).containsExactly("hi");
        answer(holder, "hi");
        assertThat(high.getMaterials()).contains(low);
        assertThat(host.getField()).contains(zam);
        assertThat(host.getTributeZone()).doesNotContain(zam);
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems);
        // Printed "Turno Aliado / En Tributos": not from the hand
        CardSlot other = withClasses(card("z2", "Zamaranzas", 1, 1, "B"), "ente");
        host.getHand().add(other);
        assertThat(Chain.activatable(room, "host")).noneMatch(a -> "z2".equals(a.instanceId()));
    }

    @Test
    void mercenarioValerosoEntersInsteadOfTheDirectDamage() {
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(4);
        CardSlot attacker = onField(guest, card("at", "Atacante", 1, 3, "R"));
        attacker.setStrength(7);
        CardSlot merc = card("mv", "Mercenario Valeroso", 2, 2, "R");
        host.getHand().add(merc);
        assertThat(CombatRules.attackByPlayer(room, "guest", "at", null)).isNull();
        Prompt replace = untilPurpose(EffectEngine.PURPOSE_DAMAGE_REPLACE);
        assertThat(replace.getValidIds()).containsExactly("mv");
        answer(replace, "mv");
        finishCombat();
        assertThat(host.getField()).contains(merc);
        assertThat(merc.isTapped()).isTrue();
        assertThat(EffectEngine.inFrontLine(merc)).isTrue();
        assertThat(host.getLifeStack()).hasSize(3);
    }

    @Test
    void comandanteVeteranoSwapsTheDeclaredBlocker() {
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(4);
        onField(guest, card("at", "Atacante", 1, 3, "R"));
        onField(host, card("cv", "Comandante Veterano", 5, 4, "R"), BACK);
        CardSlot first = onField(host, card("b1", "Bloqueador 1", 1, 1, "R"));
        CardSlot second = onField(host, card("b2", "Bloqueador 2", 1, 1, "R"));
        assertThat(CombatRules.attackByPlayer(room, "guest", "at", null)).isNull();
        Prompt block = untilPurpose(EffectEngine.PURPOSE_BLOCK);
        answer(block, "b1");
        Prompt swap = untilPurpose(EffectEngine.PURPOSE_BLOCK_SWAP);
        answer(swap, "b2");
        assertThat(first.isTapped()).isFalse();
        assertThat(room.getCombat() == null || "b2".equals(room.getCombat().getBlockerId())).isTrue();
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.BLOCK.equals(e.getType()) && "b2".equals(e.getCardInstanceId()));
        assertThat(second).isNotNull();
    }

    @Test
    void fuegoPerforanteInTheTributesGivesTheDragonOneMoreHit() {
        CardSlot fuego = withClasses(utility("fp", "Fuego Perforante"), "dragón");
        host.getTributeZone().add(fuego);
        for (int i = 0; i < 9; i++) host.getTributeZone().add(withClasses(card("d" + i, "Dragón " + i, 1, 1, "G"), "dragón"));
        onField(host, withClasses(card("dr", "Dragón Atacante", 1, 1, "G"), "dragón"));
        assertThat(CombatRules.attackByPlayer(room, "host", "dr", null)).isNull();
        Prompt offer = untilSourceOffered("fp");
        answer(offer, "fp");
        Prompt nine = room.getPrompt();
        answer(nine, nine.getValidIds().toArray(String[]::new));
        finishCombat();
        assertThat(guest.getLifeStack()).hasSize(1);
        assertThat(host.getDeck()).contains(fuego);
    }

    @Test
    void escudosLealesIsPlayedForFreeWhenAnEnemyAttacksWithTwoLivesOrLess() {
        host.getLifeStack().remove(0);
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(4);
        onField(guest, card("at", "Atacante", 1, 3, "R"));
        CardSlot escudos = utility("el", "Escudos Leales");
        host.getHand().add(escudos);
        assertThat(CombatRules.attackByPlayer(room, "guest", "at", null)).isNull();
        Prompt offer = untilSourceOffered("el");
        answer(offer, "el");
        assertThat(host.getHand()).doesNotContain(escudos);
    }

    @Test
    void escudosLealesIsNotOfferedAfterADefinitiveEffectTheTurnBefore() {
        host.getLifeStack().remove(0);
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(4);
        room.getUsedThisTurn().add(EffectEngine.DEFINITIVE_LAST_TURN + "host");
        onField(guest, card("at", "Atacante", 1, 3, "R"));
        host.getHand().add(utility("el", "Escudos Leales"));
        assertThat(CombatRules.attackByPlayer(room, "guest", "at", null)).isNull();
        assertThat(room.getPrompt() == null || !room.getPrompt().getValidIds().contains("el")).isTrue();
    }

    @Test
    void wyvernoRapazAddsATributeAsResourceBeforeItEvolves() {
        CardSlot wyverno = onField(host, withClasses(card("wr", "Wyverno Rapaz", 2, 2, "G"), "dragón", "wyverno"));
        CardSlot tribute = card("tr", "Tributo", 1, 1, "G");
        tribute.setStrength(5);
        host.getTributeZone().add(tribute);
        CardSlot evolved = card("ev", "Evolución", 3, 3, "G");
        evolved.setSpecialSummonKind("evolution");
        evolved.setSpecialCost(1);
        evolved.setRequirement("criatura nivel 2.");
        host.getHand().add(evolved);
        assertThat(Summons.specialByPlayer(room, "host", "ev", "wr")).isNull();
        answer(room.getPrompt(), "tr");
        assertThat(host.getField()).contains(evolved);
        assertThat(evolved.getResources()).contains(tribute, wyverno);
    }

    @Test
    void principeCharlesAttacksFirstThenTheAscendedCreatureTakesItsPlaceWithoutItsAlAtacar() {
        // The ascended creature has an "Al Atacar" that must not trigger: the declaration already happened
        CardEffects.register("ascendido de prueba", new EffectScript("ascendido-prueba:al-atacar", EffectScript.Moment.ON_ATTACK, true,
                "Al Atacar", "Gana 3 gemas.", List.of(new EffectStep.GainGems(3))));
        CardSlot principe = onField(host, withClasses(card("pc", "Principe Charles Di Castirion", 4, 4, "R"), "armada", "caballero"));
        CardSlot ascended = card("as", "Ascendido De Prueba", 5, 5, "R");
        ascended.setStrength(10);
        ascended.setSpecialSummonKind("promotion");
        ascended.setSpecialCost(1);
        ascended.setRequirement("criatura nivel 4.");
        host.getHand().add(ascended);
        int gems = EffectEngine.gemsOf(room, "host");
        assertThat(Summons.specialByPlayer(room, "host", "as", "pc")).isNull();
        Prompt attack = room.getPrompt();
        assertThat(attack.getPurpose()).isEqualTo(EffectEngine.PURPOSE_ASCEND_ATTACK);
        answer(attack, "pc");
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.ATTACK_DECLARED.equals(e.getType()) && "pc".equals(e.getCardInstanceId()));
        assertThat(host.getField()).contains(ascended).doesNotContain(principe);
        // The ascended creature dealt the direct damage (it took Príncipe Charles' place in the combat)
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.LIFE_DAMAGE.equals(e.getType()) && "as".equals(e.getData().get("attackerId")));
        finishCombat();
        assertThat(guest.getLifeStack()).hasSize(2);
        // Only the Ascenso's cost: its "Al Atacar" did not trigger
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems - 1);
    }

    @Test
    void xiromorfoGainsALevelAndThreeStrengthPerCopyInTheTributes() {
        CardSlot xiro = onField(host, withClasses(card("xi", "Xiromorfo", 2, 2, "P"), "bestia", "mutante"));
        xiro.setStrength(4);
        host.getTributeZone().addAll(List.of(card("c1", "Xiromorfo", 2, 2, "P"), card("c2", "Xiromorfo", 2, 2, "P")));
        EffectEngine.refreshContinuous(room);
        assertThat(xiro.getLevel()).isEqualTo(4);
        assertThat(xiro.getPrintedLevel()).isEqualTo(2);
        assertThat(EffectEngine.strength(xiro)).isEqualTo(10);
        // Every rule reads the level it has now ("nivel 3 o menor" no longer includes it)
        assertThat(CardFilter.creatures().withMaxLevel(3).matches(xiro)).isFalse();
        assertThat(CardFilter.creatures().withLevel(4).matches(xiro)).isTrue();
        // Off the field it is its printed card again
        Removals.removeFromField(host, xiro);
        assertThat(xiro.getLevel()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ Helpers

    /** Answers "none" to everything else until a prompt of that purpose comes. */
    private Prompt untilPurpose(String purpose) {
        for (int i = 0; i < 12; i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                if (room.getCombat() == null) break;
                Chain.advance(room);
                continue;
            }
            if (purpose.equals(p.getPurpose())) return p;
            Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
        throw new AssertionError("No llegó la elección " + purpose);
    }

    private Prompt untilSourceOffered(String sourceId) {
        for (int i = 0; i < 12; i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                if (room.getCombat() == null) break;
                Chain.advance(room);
                continue;
            }
            if (p.getValidIds().contains(sourceId)) return p;
            Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
        throw new AssertionError("No se ofreció " + sourceId);
    }

    private void finishCombat() {
        for (int i = 0; i < 12 && (room.getCombat() != null || room.getPrompt() != null); i++) {
            if (room.getPrompt() != null) Answers.answer(room, room.getPrompt().getPlayerId(), room.getPrompt().getId(), List.of(), List.of());
            else Chain.advance(room);
        }
    }

    private static CardSlot withClasses(CardSlot card, String... classes) {
        card.setClasses(new ArrayList<>(List.of(classes)));
        return card;
    }

    private void answer(Prompt prompt, String... ids) {
        assertThat(prompt).isNotNull();
        assertThat(Answers.answer(room, prompt.getPlayerId(), prompt.getId(), List.of(ids), List.of())).isNull();
    }
}
