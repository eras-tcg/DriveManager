package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sub-edition E3.1 (2026-11-19): one test (or more) per programmed effect, from the printed text.
 */
@Tag("cards")
@Tag("e31")
class E31CardsTest extends CardBatchSupport {

    private static CardSlot creature(String id, String name, Integer cost, int level, int strength, String color, String... classes) {
        CardSlot c = withClasses(card(id, name, cost, level, color), classes);
        c.setStrength(strength);
        return c;
    }

    private static CardSlot enemy(String id, int strength) {
        return creature(id, "Enemigo " + id, 1, 1, strength, "B");
    }

    private void activate(CardSlot card, String idStart) {
        String script = Chain.activatable(room, "host").stream()
                .filter(a -> card.getInstanceId().equals(a.instanceId()) && a.scriptId().startsWith(idStart))
                .map(Chain.Activatable::scriptId).findFirst().orElse(null);
        assertThat(script).as("se puede activar " + idStart).isNotNull();
        assertThat(Chain.activateByPlayer(room, "host", card.getInstanceId(), script)).isNull();
    }

    private boolean canActivate(CardSlot card, String idStart) {
        return Chain.activatable(room, "host").stream().anyMatch(a -> card.getInstanceId().equals(a.instanceId()) && a.scriptId().startsWith(idStart));
    }

    private void settle() {
        for (int i = 0; i < 40; i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                if (room.getCombat() == null) break;
                Chain.advance(room);
                continue;
            }
            if (PromptPurpose.ORDER.is(p.getPurpose())) answer(p, p.getValidIds().get(0));
            else Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
    }

    private void stayBack() {
        Prompt p = room.getPrompt();
        if (p != null && PromptPurpose.SPECIAL_FRONT.is(p.getPurpose())) answer(p);
    }

    // ------------------------------------------------------------------ R01 Atalaya

    @Test
    void atalayaIsPlayedFromTheHandForFreeWhenAnotherStructureIsPlayed() {
        CardSlot atalaya = structure("at", "Atalaya", 1, "R");
        host.getHand().add(atalaya);
        play(structure("ot", "Otra Estructura", 1, "R"));
        untilOffered(atalaya);
        accept(atalaya);
        settle();
        assertThat(host.getField()).contains(atalaya);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("hd1");
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void atalayaIsNotOfferedForACopyOfItself() {
        CardSlot atalaya = structure("at", "Atalaya", 1, "R");
        host.getHand().add(atalaya);
        play(structure("a2", "Atalaya", 1, "R"));
        settle();
        assertThat(host.getHand()).contains(atalaya);
    }

    @Test
    void atalayaExhaustsToSendTheTopCardToTheBottom() {
        CardSlot atalaya = onField(host, structure("at", "Atalaya", 1, "R"), BACK);
        activate(atalaya, "atalaya:turno-aliado");
        assertThat(room.getPrompt().getCards()).extracting(CardSlot::getInstanceId).containsExactly("hd1");
        answer(room.getPrompt());
        settle();
        assertThat(atalaya.isTapped()).isTrue();
        assertThat(host.getDeck().get(host.getDeck().size() - 1).getInstanceId()).isEqualTo("hd1");
    }

    // ------------------------------------------------------------------ R02 Guardia Legionario

    @Test
    void guardiaExhaustsWithAnAllyOfTheSameTypeToSummonARedCreatureForTwoLess() {
        CardSlot guardia = onField(host, creature("gl", "Guardia Legionario", 1, 1, 2, "R", "armada", "legionario"), BACK);
        CardSlot partner = onField(host, creature("pa", "Compañero", 1, 1, 2, "R", "legionario"), BACK);
        onField(host, creature("ot", "Otro", 1, 1, 2, "R", "bestia"), BACK);
        CardSlot red = creature("rd", "Roja", 3, 2, 5, "R");
        host.getHand().add(red);
        activate(guardia, "guardia-legionario:turno-aliado");
        assertThat(room.getPrompt().getValidIds()).containsExactly("pa");
        answer("pa");
        answer("rd");
        settle();
        assertThat(guardia.isTapped()).isTrue();
        assertThat(partner.isTapped()).isTrue();
        assertThat(host.getField()).contains(red);
        assertThat(gems()).isEqualTo(8 - 1);
    }

    // ------------------------------------------------------------------ R03 Atelea de Viresia

    private static CardSlot atelea(String id) {
        CardSlot c = creature(id, "Atelea de Viresia", 2, 2, 5, "R", "retador", "legionario");
        c.setSpecialSummonKind("promotion");
        c.setSpecialCost(1);
        return c;
    }

    @Test
    void ateleaAscendsWhileTheAlliesAddUpToTwoInCostAndPlacesALegionario() {
        CardSlot summoner = onField(host, creature("s1", "Uno", 2, 1, 2, "R"), BACK);
        CardSlot legion = creature("lg", "Legionario", 1, 1, 2, "R", "legionario");
        host.getHand().add(legion);
        CardSlot atelea = atelea("av");
        host.getHand().add(atelea);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "av", "s1")).isNull();
        stayBack();
        untilOffered(atelea);
        accept(atelea);
        answer("lg");
        settle();
        assertThat(host.getField()).contains(atelea, legion);
        assertThat(EffectEngine.inFrontLine(legion)).isTrue();
    }

    @Test
    void ateleaCannotAscendWhenTheAlliesCostLessThanTwo() {
        onField(host, creature("s1", "Uno", 1, 1, 2, "R"), BACK);
        host.getHand().add(atelea("av"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "av", "s1")).isNotNull();
    }

    @Test
    void ateleaAtTheEndOfTheAttackSummonsSpeciallyAndReactivatesIt() {
        CardSlot atelea = onField(host, atelea("av"));
        CardSlot evo = creature("ev", "Evolución Tres", 4, 3, 9, "R");
        evo.setSpecialSummonKind("evolution");
        evo.setSpecialCost(2);
        host.getHand().addAll(List.of(evo, card("h1", "Mano 1", 1, 1, "R"), card("h2", "Mano 2", 1, 1, "R")));
        CardEffects.registerRequirement("evolución tres", Req.summoner(2));
        try {
            assertThat(CombatRules.attackByPlayer(room, "host", "av", null)).isNull();
            untilOffered(atelea);
            accept(atelea);
            answer("h1", "h2");
            answer("ev");
            stayBack();
            settle();
            assertThat(host.getField()).contains(evo);
            assertThat(evo.getResources()).contains(atelea);
            assertThat(evo.isTapped()).isFalse();
        } finally {
            CardEffects.registerRequirement("evolución tres", null);
        }
    }

    // ------------------------------------------------------------------ R04 Golth

    private static CardSlot golth(String id) {
        CardSlot c = creature(id, "Golth el Ejecutor de Novatos", 3, 2, 8, "R", "retador");
        c.setSpecialSummonKind("promotion");
        c.setSpecialCost(1);
        return c;
    }

    @Test
    void golthAscendsFromACostTwoCreatureWhileTheOpponentHasMoreCreatures() {
        onField(host, creature("s2", "Coste Dos", 2, 1, 2, "R"), BACK);
        host.getHand().add(golth("go"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "go", "s2")).isNotNull();
        onField(guest, enemy("e1", 2));
        onField(guest, enemy("e2", 2));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "go", "s2")).isNull();
        stayBack();
        settle();
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("go");
    }

    @Test
    void golthReactivatesAfterWinningTheClashForTwoHandCards() {
        CardSlot golth = onField(host, golth("go"));
        host.getHand().addAll(List.of(card("h1", "Mano 1", 1, 1, "R"), card("h2", "Mano 2", 1, 1, "R")));
        CardSlot victim = onField(guest, enemy("en", 3));
        victim.setTapped(true);
        assertThat(CombatRules.attackByPlayer(room, "host", "go", "en")).isNull();
        untilOffered(golth);
        accept(golth);
        answer("h1", "h2");
        settle();
        assertThat(guest.getDiscardPile()).contains(victim);
        assertThat(golth.isTapped()).isFalse();
    }

    // ------------------------------------------------------------------ G01 Florrión

    private static CardSlot florrion(String id) {
        return creature(id, "Florrión", 1, 1, 1, "G", "hada", "vegetación");
    }

    @Test
    void florrionTakesAFaunaVegetationOrStructure() {
        host.getDeck().addAll(0, List.of(creature("f1", "Fauna", 1, 1, 1, "G", "fauna"), card("r1", "Roja", 1, 1, "R"),
                structure("st", "Estructura", 1, "R")));
        summon(florrion("fl"));
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("f1", "st");
        answer("st");
        settle();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("st");
    }

    @Test
    void florrionIsPlacedInTheRetaguardiaWhenAnAllyIsSummonedByEffect() {
        CardSlot florrion = florrion("fl");
        host.getHand().add(florrion);
        summonByEffectToFront(creature("x", "Invocada", 1, 1, 2, "R"));
        untilOffered(florrion);
        accept(florrion);
        settle();
        assertThat(host.getField()).contains(florrion);
        assertThat(EffectEngine.inFrontLine(florrion)).isFalse();
    }

    @Test
    void florrionAsAResourceCanBeTributed() {
        CardSlot summoner = onField(host, creature("s1", "Uno", 1, 1, 2, "G"), BACK);
        CardSlot florrion = florrion("fl");
        summoner.getResources().add(florrion);
        CardSlot evo = creature("ev", "Evolución", 2, 2, 5, "G");
        evo.setSpecialSummonKind("evolution");
        evo.setSpecialCost(1);
        host.getHand().add(evo);
        CardEffects.registerRequirement("evolución", Req.summoner(1));
        try {
            assertThat(SpecialSummons.specialByPlayer(room, "host", "ev", "s1")).isNull();
            stayBack();
            untilOffered(evo);
            accept(evo);
            settle();
            assertThat(host.getTributeZone()).contains(florrion);
            assertThat(evo.getResources()).doesNotContain(florrion);
        } finally {
            CardEffects.registerRequirement("evolución", null);
        }
    }

    // ------------------------------------------------------------------ G02 Tinflarus

    private static CardSlot tinflarus(String id) {
        return creature(id, "Tinflarus", 1, 1, 1, "G", "fauna", "treant");
    }

    @Test
    void tinflarusOnYourTurnBecomesAResourceAndLetsYouSummonFromTheHand() {
        CardSlot tin = tinflarus("tf");
        host.getHand().add(tin);
        CardSlot ally = onField(host, creature("al", "Verde", 2, 2, 4, "G"), BACK);
        CardSlot other = creature("ot", "Otra", 1, 1, 2, "R");
        host.getHand().add(other);
        activate(tin, "tinflarus:en-mano");
        answer("al");
        answer(room.getPrompt(), room.getPrompt().getValidIds().get(0));
        answer("ot");
        settle();
        assertThat(ally.getResources()).containsExactly(tin);
        assertThat(host.getField()).contains(other);
        // Once per turn between copies: the trigger and the activation share it
        CardSlot second = tinflarus("t2");
        host.getHand().add(second);
        assertThat(canActivate(second, "tinflarus")).isFalse();
    }

    @Test
    void tinflarusAsAResourcePlaysAStructureForOneLess() {
        CardSlot summoner = onField(host, creature("s1", "Uno", 1, 1, 2, "G"), BACK);
        summoner.getResources().add(tinflarus("tf"));
        CardSlot evo = creature("ev", "Evolución", 2, 2, 5, "G");
        evo.setSpecialSummonKind("evolution");
        evo.setSpecialCost(1);
        host.getHand().add(evo);
        CardSlot st = structure("st", "Estructura", 2, "G");
        host.getHand().add(st);
        CardEffects.registerRequirement("evolución", Req.summoner(1));
        try {
            assertThat(SpecialSummons.specialByPlayer(room, "host", "ev", "s1")).isNull();
            stayBack();
            untilOffered(evo);
            accept(evo);
            answer("st");
            settle();
            assertThat(host.getField()).contains(st);
            assertThat(gems()).isEqualTo(8 - 1 - 1);
        } finally {
            CardEffects.registerRequirement("evolución", null);
        }
    }

    // ------------------------------------------------------------------ B01 AX-3L

    private static CardSlot ax3l(String id) {
        CardSlot c = creature(id, "AX-3L Modo Asalto Aereo", 4, 5, 14, "B", "mecha");
        c.setSpecialSummonKind("materialization");
        c.setSpecialCost(2);
        return c;
    }

    @Test
    void ax3lIsSpeciallySummonedFromTheHandWhenAnAllyIsSummonedByEffect() {
        CardSlot ax = ax3l("ax");
        host.getHand().add(ax);
        CardSlot summoner = onField(host, creature("s3", "Tres", 3, 3, 6, "B"), BACK);
        CardSlot material = creature("m3", "Material Tres", 3, 3, 6, "B");
        host.getHand().add(material);
        summonByEffectToFront(creature("x", "Invocada", 1, 1, 2, "R"));
        untilOffered(ax);
        accept(ax);
        answer("ax");
        answer("s3");
        answer("m3");
        stayBack();
        settle();
        assertThat(host.getField()).contains(ax);
        assertThat(ax.getMaterials()).contains(summoner, material);
    }

    @Test
    void ax3lReturnsAlliesToTheHandToReduceOneEnemyEachByThree() {
        CardSlot summoner = onField(host, creature("s3", "Tres", 3, 3, 6, "B"));
        CardSlot material = creature("m3", "Material Tres", 3, 3, 6, "B");
        host.getHand().add(material);
        CardSlot a1 = onField(host, creature("a1", "Aliado 1", 1, 1, 2, "B"), BACK);
        CardSlot a2 = onField(host, creature("a2", "Aliado 2", 1, 1, 2, "B"), BACK);
        CardSlot e1 = onField(guest, enemy("e1", 8));
        CardSlot e2 = onField(guest, enemy("e2", 8));
        CardSlot ax = ax3l("ax");
        host.getHand().add(ax);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ax", "s3")).isNull();
        answer("m3");
        stayBack();
        untilOffered(ax);
        accept(ax);
        answer("a1", "a2");
        answer("e1", "e2");
        settle();
        assertThat(host.getHand()).contains(a1, a2);
        assertThat(EffectEngine.strength(e1)).isEqualTo(5);
        assertThat(EffectEngine.strength(e2)).isEqualTo(5);
    }
}
