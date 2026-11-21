package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cards in testing T8, archetype Soporte Autómata (2026-11-19): one test (or more) per programmed effect, from the printed text.
 */
@Tag("cards")
@Tag("t8")
class T8AutomataCardsTest extends CardBatchSupport {

    private static CardSlot automata(String id, String name, Integer cost, int level, int strength) {
        CardSlot c = withClasses(card(id, name, cost, level, "B"), "autómata");
        c.setStrength(strength);
        return c;
    }

    private static CardSlot materialization(CardSlot c, int specialCost) {
        c.setSpecialSummonKind("materialization");
        c.setSpecialCost(specialCost);
        return c;
    }

    private static CardSlot ax4(String id) {
        return materialization(automata(id, "AX-4", 3, 3, 9), 2);
    }

    private static CardSlot es707(String id) {
        return materialization(automata(id, "ES-707", 5, 4, 10), 4);
    }

    private static CardSlot ka0(String id) {
        CardSlot c = materialization(automata(id, "KA-0 X1", 7, 5, 11), 4);
        c.setClasses(new ArrayList<>(List.of("autómata", "mecha")));
        return c;
    }

    private static CardSlot extheo(String id) {
        CardSlot c = materialization(automata(id, "Extheo", 3, 3, 8), 1);
        c.setClasses(new ArrayList<>(List.of("autómata", "neogeo")));
        return c;
    }

    private static CardSlot safikoe(String id) {
        CardSlot c = materialization(automata(id, "Safikoe", 3, 3, 6), 2);
        c.setClasses(new ArrayList<>(List.of("autómata", "ente", "neogeo")));
        return c;
    }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "R");
        c.setStrength(strength);
        return c;
    }

    /** Materializes `card` from the hand with that summoner and material; it stays in the Retaguardia. */
    private void materialize(CardSlot card, CardSlot summoner, CardSlot material) {
        if (!host.getHand().contains(card)) host.getHand().add(card);
        assertThat(SpecialSummons.specialByPlayer(room, "host", card.getInstanceId(), summoner.getInstanceId())).isNull();
        answer(material.getInstanceId());
        Prompt p = room.getPrompt();
        if (p != null && PromptPurpose.SPECIAL_FRONT.is(p.getPurpose())) answer(p);
    }

    // ------------------------------------------------------------------ B01 Corrupción Biomecánica

    @Test
    void corrupcionReducesByThreeAndOneGemMoreReducesThreeMore() {
        CardSlot target = onField(guest, enemy("en", 6));
        CardSlot corrupcion = utility("cb", "Corrupción Biomecánica", 1, "B");
        play(corrupcion);
        answer("en");
        accept(corrupcion);
        drain();
        // Reduced to 0: destroyed
        assertThat(guest.getDiscardPile()).contains(target);
        assertThat(gems()).isEqualTo(8 - 1 - 1);
    }

    @Test
    void corrupcionIsPlayedFromTheHandWhenTheBlockStepBegins() {
        CardSlot corrupcion = utility("cb", "Corrupción Biomecánica", 1, "B");
        host.getHand().add(corrupcion);
        onField(guest, enemy("en", 5));
        room.setCurrentTurnUserId("guest");
        EffectEngine.record(room, GameEvent.COMBAT_STEP, "guest", null, null, null, null,
                new HashMap<>(Map.of("step", "BLOCK", "moment", "ENTER")), "paso de bloqueo");
        Chain.advance(room);
        accept(corrupcion);
        answer("en");
        drain();
        assertThat(host.getDiscardPile()).contains(corrupcion);
    }

    // ------------------------------------------------------------------ B02 AX-4

    @Test
    void ax4TributesAMaterialToReactivate() {
        CardSlot summoner = onField(host, automata("s2", "Autómata Dos", 2, 2, 4));
        summoner.setTapped(true);
        CardSlot material = automata("m2", "Material Dos", 2, 2, 4);
        host.getHand().add(material);
        CardSlot ax = ax4("ax");
        materialize(ax, summoner, material);
        untilOffered(ax);
        accept(ax);
        answer(ax.getMaterials().get(0).getInstanceId());
        drain();
        assertThat(ax.isTapped()).isFalse();
        assertThat(ax.getMaterials()).hasSize(1);
    }

    @Test
    void ax4FromTheTributosBecomesAMaterialOfALevelThreeAllyWithoutLevelThreeMaterials() {
        CardSlot ax = ax4("ax");
        host.getTributeZone().add(ax);
        CardSlot ally = automata("l3", "Autómata Tres", 3, 3, 7);
        summon(ally);
        untilOffered(ax);
        accept(ax);
        drain();
        assertThat(ally.getMaterials()).containsExactly(ax);
        assertThat(host.getTributeZone()).doesNotContain(ax);
    }

    // ------------------------------------------------------------------ B03 ES-707

    @Test
    void es707ActivatesTheSummonEffectOfAnAutomataMaterial() {
        CardSlot summoner = onField(host, ax4("ax"));
        CardSlot material = automata("m2", "Material Dos", 2, 2, 4);
        host.getHand().add(material);
        CardSlot es = es707("es");
        es.setTapped(false);
        summoner.setTapped(true);
        materialize(es, summoner, material);
        // ES-707's own: choose AX-4 (level 3); then AX-4's En Invocación runs as ES-707's
        assertThat(room.getPrompt().getValidIds()).containsExactly("ax");
        answer("ax");
        // AX-4's "puedes tributar 1 material de esta carta", activated as ES-707's
        answer("m2");
        drain();
        assertThat(es.isTapped()).isFalse();
        assertThat(host.getTributeZone()).contains(material);
    }

    @Test
    void es707AttackingTributesMaterialsToLowerAsManyEnemies() {
        CardSlot es = onField(host, es707("es"));
        es.getMaterials().addAll(List.of(automata("m1", "M1", 1, 1, 1), automata("m2", "M2", 1, 2, 1)));
        CardSlot a = onField(guest, enemy("ea", 5));
        CardSlot b = onField(guest, enemy("eb", 5));
        assertThat(CombatRules.attackByPlayer(room, "host", "es", null)).isNull();
        untilOffered(es);
        accept(es);
        answer("m1", "m2");
        answer("ea");
        answer("eb");
        assertThat(EffectEngine.strength(a)).isEqualTo(4);
        assertThat(EffectEngine.strength(b)).isEqualTo(4);
        drain();
    }

    // ------------------------------------------------------------------ B04 KA-0 X1

    @Test
    void ka0WithALevelFiveMaterialReactivatesAndGainsTwoGemsAndFourStrength() {
        CardSlot summoner = onField(host, automata("s5", "Autómata Cinco", 7, 5, 11));
        summoner.setTapped(true);
        CardSlot material = automata("m3", "Material Tres", 3, 3, 6);
        host.getHand().add(material);
        CardSlot ka = ka0("ka");
        materialize(ka, summoner, material);
        drain();
        assertThat(ka.isTapped()).isFalse();
        assertThat(EffectEngine.strength(ka)).isEqualTo(15);
        assertThat(gems()).isEqualTo(8 - 4 + 2);
    }

    @Test
    void ka0WithoutALevelFiveMaterialOnlyReactivates() {
        CardSlot summoner = onField(host, automata("s4", "Autómata Cuatro", 5, 4, 9));
        summoner.setTapped(true);
        CardSlot material = automata("m3", "Material Tres", 3, 3, 6);
        host.getHand().add(material);
        CardSlot ka = ka0("ka");
        materialize(ka, summoner, material);
        drain();
        assertThat(ka.isTapped()).isFalse();
        assertThat(EffectEngine.strength(ka)).isEqualTo(11);
        assertThat(gems()).isEqualTo(8 - 4);
    }

    @Test
    void ka0ExhaustedTributesTwoMaterialsToReactivateOncePerTurn() {
        CardSlot ka = onField(host, ka0("ka"));
        ka.getMaterials().addAll(List.of(automata("m1", "M1", 1, 1, 1), automata("m2", "M2", 1, 2, 1)));
        ka.setTapped(true);
        EffectEngine.record(room, GameEvent.TAP, "host", ka, null, null, null, null, "agota");
        Chain.advance(room);
        accept(ka);
        answer("m1", "m2");
        drain();
        assertThat(ka.isTapped()).isFalse();
        assertThat(ka.getMaterials()).isEmpty();
    }

    // ------------------------------------------------------------------ B05 Extheo

    @Test
    void extheoTributesMaterialsAndTakesAsManyAutomataFromTheTributos() {
        CardSlot summoner = onField(host, automata("s2", "Autómata Dos", 2, 2, 4));
        CardSlot material = automata("m2", "Material Dos", 2, 2, 4);
        host.getHand().add(material);
        CardSlot old = automata("ol", "Autómata Viejo", 1, 1, 1);
        host.getTributeZone().add(old);
        CardSlot ex = extheo("ex");
        materialize(ex, summoner, material);
        answer("s2", "m2");
        answer("ol", "s2");
        drain();
        assertThat(host.getHand()).contains(old, summoner);
        assertThat(host.getTributeZone()).containsExactly(material);
    }

    @Test
    void extheoReactivatesAnExhaustedStructureOfCostTwoOrLess() {
        CardSlot ex = onField(host, extheo("ex"));
        CardSlot structure = onField(host, structure("st", "Estructura", 2, "B"), BACK);
        structure.setTapped(true);
        String script = Chain.activatable(room, "host").stream().filter(a -> "ex".equals(a.instanceId())).map(Chain.Activatable::scriptId)
                .findFirst().orElseThrow();
        assertThat(Chain.activateByPlayer(room, "host", "ex", script)).isNull();
        answer("st");
        drain();
        assertThat(structure.isTapped()).isFalse();
        assertThat(ex).isNotNull();
    }

    // ------------------------------------------------------------------ B06 Núcleo De Energía

    @Test
    void nucleoTakesUpToTwoBlueTributes() {
        CardSlot a = card("ba", "Azul A", 1, 1, "B");
        CardSlot b = card("bb", "Azul B", 1, 1, "B");
        host.getTributeZone().addAll(List.of(a, b, card("rr", "Roja", 1, 1, "R")));
        play(structure("nu", "Núcleo De Energía", 2, "B"));
        answer("ba", "bb");
        drain();
        assertThat(host.getHand()).contains(a, b);
    }

    @Test
    void nucleoExhaustsToGiveASummonedAllyALowerMaterialAndAGem() {
        CardSlot nucleo = onField(host, structure("nu", "Núcleo De Energía", 2, "B"), BACK);
        CardSlot low = automata("lo", "Autómata Bajo", 1, 1, 1);
        host.getTributeZone().add(low);
        CardSlot summoned = automata("su", "Invocada", 0, 3, 6);
        summonByEffectToFront(summoned);
        untilOffered(nucleo);
        accept(nucleo);
        answer("lo");
        drain();
        assertThat(nucleo.isTapped()).isTrue();
        assertThat(summoned.getMaterials()).containsExactly(low);
        assertThat(gems()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ B07 Safikoe

    @Test
    void safikoeExhaustsToPlayABlueStructureFromTheTributosForTwoLess() {
        CardSlot structure = structure("st", "Estructura Azul", 3, "B");
        host.getTributeZone().add(structure);
        CardSlot safikoe = safikoe("sa");
        summon(safikoe);
        accept(safikoe);
        answer("st");
        drain();
        assertThat(safikoe.isTapped()).isTrue();
        assertThat(host.getField()).contains(structure);
        assertThat(gems()).isEqualTo(8 - 3 - 1);
    }

    @Test
    void safikoeInTheTributosGoesWithFourOthersToLowerAMaterializationByOne() {
        CardSlot safikoe = safikoe("sa");
        // Cards in the Tributos are face up
        safikoe.setFaceDown(false);
        host.getTributeZone().add(safikoe);
        List<CardSlot> others = new ArrayList<>();
        for (int i = 0; i < 4; i++) others.add(automata("o" + i, "Autómata " + i, 1, 1, 1));
        host.getTributeZone().addAll(others);
        CardSlot summoner = onField(host, automata("s2", "Autómata Dos", 2, 2, 4));
        CardSlot material = automata("m2", "Material Dos", 2, 2, 4);
        host.getHand().addAll(List.of(material, ax4("ax")));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ax", "s2")).isNull();
        answer("m2");
        Prompt pay = room.getPrompt();
        assertThat(pay.getValidIds()).contains("sa");
        assertThat(Answers.answer(room, "host", pay.getId(), List.of("o0", "o1", "o2", "o3", "m2"), List.of())).isNotNull();
        answer(pay, "sa", "o0", "o1", "o2", "o3");
        drain();
        assertThat(host.getDeck()).contains(safikoe);
        assertThat(host.getField()).anyMatch(c -> "ax".equals(c.getInstanceId()));
        assertThat(gems()).isEqualTo(8 - 1);
    }
}
