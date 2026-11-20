package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cards in testing T6, archetype Soporte Hadas (2026-11-19): one test (or more) per programmed effect, from the printed text.
 */
@Tag("cards")
@Tag("t6")
class T6HadasCardsTest extends CardBatchSupport {

    private static CardSlot hada(String id, String name, Integer cost, int level, int strength) {
        CardSlot c = withClasses(card(id, name, cost, level, "G"), "hada");
        c.setStrength(strength);
        return c;
    }

    private static CardSlot evolution(CardSlot c, int specialCost) {
        c.setSpecialSummonKind("evolution");
        c.setSpecialCost(specialCost);
        return c;
    }

    private static CardSlot hadaPerdida(String id) {
        return hada(id, "Hada Perdida", 1, 1, 1);
    }

    private static CardSlot dela(String id) {
        return evolution(hada(id, "Dela", 2, 2, 4), 1);
    }

    private static CardSlot paladin(String id) {
        CardSlot c = evolution(hada(id, "Paladín de la Tierra Oculta", 3, 3, 7), 2);
        c.setClasses(new ArrayList<>(List.of("hada", "paladín")));
        return c;
    }

    private static CardSlot dianthe(String id) {
        return evolution(hada(id, "Dianthe", 5, 4, 10), 3);
    }

    private static CardSlot aine(String id) {
        return evolution(hada(id, "Aine", 6, 5, 13), 3);
    }

    private static CardSlot anima(String id) {
        CardSlot c = evolution(hada(id, "Ánima Feral", 2, 2, 4), 1);
        c.setClasses(new ArrayList<>(List.of("hada", "fauna")));
        return c;
    }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "R");
        c.setStrength(strength);
        return c;
    }

    private void evolve(CardSlot card, CardSlot summoner) {
        if (!host.getHand().contains(card)) host.getHand().add(card);
        assertThat(SpecialSummons.specialByPlayer(room, "host", card.getInstanceId(), summoner.getInstanceId())).isNull();
        Prompt p = room.getPrompt();
        if (p != null && PromptPurpose.SPECIAL_FRONT.is(p.getPurpose())) answer(p);
    }

    private void exhaust(CardSlot card) {
        card.setTapped(true);
        EffectEngine.record(room, GameEvent.TAP, "host", card, null, null, null, null, "agota");
        Chain.advance(room);
    }

    // ------------------------------------------------------------------ G01 Elida

    @Test
    void elidaTakesUpToTwoGreenOrHadaCardsThenTributesOneFromTheHand() {
        CardSlot green = card("gr", "Verde", 1, 1, "G");
        CardSlot fairy = withClasses(card("hd", "Hada Azul", 1, 1, "B"), "hada");
        host.getDeck().add(0, green);
        host.getDeck().add(1, fairy);
        host.getHand().add(card("hx", "Sobrante", 1, 1, "R"));
        summon(hada("el", "Elida", 1, 1, 1));
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("gr", "hd");
        answer("gr", "hd");
        answer("hx");
        drain();
        assertThat(host.getHand()).contains(green, fairy);
        assertThat(ids(host.getTributeZone())).containsExactly("hx");
    }

    @Test
    void elidaAsAResourceDrawsWhenItsCarrierIsExhausted() {
        CardSlot carrier = onField(host, hada("ca", "Portadora", 2, 2, 4));
        CardSlot elida = hada("el", "Elida", 1, 1, 1);
        carrier.getResources().add(elida);
        exhaust(carrier);
        accept(carrier);
        drain();
        assertThat(host.getTributeZone()).contains(elida);
        assertThat(host.getHand()).hasSize(1);
    }

    // ------------------------------------------------------------------ G02 Hada Perdida

    @Test
    void hadaPerdidaDrawsTwoOnSummon() {
        summon(hadaPerdida("hp"));
        drain();
        assertThat(host.getHand()).hasSize(2);
    }

    @Test
    void hadaPerdidaAddedAsResourceByEvolutionSendsFiveHadasToPlayAGreenStructureFromTheTributos() {
        CardSlot perdida = onField(host, hadaPerdida("hp"));
        List<CardSlot> fairies = new ArrayList<>();
        for (int i = 0; i < 5; i++) fairies.add(hada("f" + i, "Hada " + i, 1, 1, 1));
        host.getTributeZone().addAll(fairies);
        CardSlot forest = structure("bm", "Bosque Verde", 2, "G");
        host.getTributeZone().add(forest);
        CardSlot dela = dela("de");
        evolve(dela, perdida);
        untilOffered(perdida);
        accept(perdida);
        answer(ids(fairies).toArray(String[]::new));
        answer("bm");
        drain();
        assertThat(dela.getResources()).contains(perdida);
        assertThat(host.getField()).contains(forest);
        assertThat(host.getDeck()).containsAll(fairies);
        // Evolución 1 + Bosque 2 - 1
        assertThat(gems()).isEqualTo(6);
    }

    @Test
    void hadaPerdidaAsAResourceSavesItsHadaCarrierBySendingItToTheHand() {
        CardSlot carrier = onField(host, hada("ca", "Portadora", 2, 2, 4));
        CardSlot perdida = hadaPerdida("hp");
        CardSlot other = hada("ot", "Otro Recurso", 1, 1, 1);
        carrier.getResources().addAll(List.of(perdida, other));
        Removals.destroy(room, carrier, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        answer("ca");
        drain();
        assertThat(host.getHand()).contains(carrier);
        assertThat(host.getTributeZone()).containsExactly(perdida);
        assertThat(host.getDiscardPile()).contains(other);
    }

    @Test
    void hadaPerdidaDoesNotSaveACarrierThatIsNotHada() {
        CardSlot carrier = onField(host, card("ca", "Portadora Roja", 2, 2, "R"));
        carrier.getResources().add(hadaPerdida("hp"));
        Removals.destroy(room, carrier, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        drain();
        assertThat(host.getField()).doesNotContain(carrier);
        assertThat(host.getHand()).doesNotContain(carrier);
    }

    // ------------------------------------------------------------------ G03 Dela

    @Test
    void delaAddsALevelOneAllyToReactivateOneAndExhaustsToPlayAHadaStructure() {
        CardSlot summoner = onField(host, hada("s1", "Hada Uno", 1, 1, 1));
        CardSlot fodder = onField(host, hada("f1", "Hada Recurso", 1, 1, 1), BACK);
        CardSlot tired = onField(host, hada("t1", "Hada Cansada", 2, 2, 6), BACK);
        tired.setTapped(true);
        CardSlot forest = withClasses(structure("bh", "Bosque De Hadas", 2, "G"), "hada");
        host.getHand().add(forest);
        CardSlot dela = dela("de");
        evolve(dela, summoner);
        untilOffered(dela);
        accept(dela);
        answer("f1");
        answer("t1");
        accept(dela);
        answer("bh");
        drain();
        assertThat(dela.getResources()).contains(fodder);
        assertThat(tired.isTapped()).isFalse();
        assertThat(dela.isTapped()).isTrue();
        assertThat(host.getField()).contains(forest);
        // Evolución 1 + Bosque 2 - 1
        assertThat(gems()).isEqualTo(6);
    }

    @Test
    void delaAsAResourceSummonsAHadaWhenItsCarrierIsExhausted() {
        CardSlot carrier = onField(host, hada("ca", "Portadora", 2, 2, 4));
        carrier.getResources().add(dela("de"));
        CardSlot fairy = hada("hf", "Hada De Mano", 2, 1, 3);
        host.getHand().add(fairy);
        exhaust(carrier);
        accept(carrier);
        answer("hf");
        drain();
        assertThat(host.getField()).contains(fairy);
        assertThat(gems()).isEqualTo(7);
    }

    // ------------------------------------------------------------------ G04 Ánima Feral

    @Test
    void animaFeralKeepsAnAllyFromBeingDestroyedInAClash() {
        CardSlot ally = onField(host, hada("al", "Aliada", 2, 2, 5));
        CardSlot anima = anima("an");
        host.getHand().add(anima);
        summon(anima);
        answer("al");
        drain();
        CardSlot strong = onField(guest, enemy("st", 9));
        assertThat(CombatRules.attackByPlayer(room, "host", "al", null)).isNull();
        Prompt block = room.getPrompt();
        answer(block, "st");
        drain();
        assertThat(host.getField()).contains(ally);
        assertThat(guest.getField()).contains(strong);
    }

    @Test
    void animaFeralIsSummonedFromTheHandForOneLessWhenAnAllyClashes() {
        onField(host, hada("al", "Aliada", 2, 2, 5));
        CardSlot anima = anima("an");
        host.getHand().add(anima);
        onField(guest, enemy("bl", 3));
        assertThat(CombatRules.attackByPlayer(room, "host", "al", null)).isNull();
        answer(room.getPrompt(), "bl");
        untilOffered(anima);
        accept(anima);
        drain();
        assertThat(host.getField()).contains(anima);
        assertThat(gems()).isEqualTo(7);
    }

    // ------------------------------------------------------------------ G05 Paladín de la Tierra Oculta

    @Test
    void paladinTributesFromTheHandToReturnAnEnemyToTheHand() {
        CardSlot weak = onField(guest, enemy("we", 5));
        host.getHand().add(card("hx", "Sobrante", 1, 1, "R"));
        CardSlot paladin = paladin("pa");
        summonByEffectToFront(paladin);
        untilOffered(paladin);
        accept(paladin);
        answer("hx");
        answer("we");
        drain();
        assertThat(guest.getHand()).contains(weak);
    }

    @Test
    void paladinReactivatesWhenNoEnemyWasTargeted() {
        CardSlot summoner = onField(host, hada("s2", "Hada Dos", 2, 2, 4));
        summoner.setTapped(true);
        host.getHand().add(card("hx", "Sobrante", 1, 1, "R"));
        CardSlot paladin = paladin("pa");
        evolve(paladin, summoner);
        untilOffered(paladin);
        accept(paladin);
        answer("hx");
        drain();
        assertThat(paladin.isTapped()).isFalse();
    }

    @Test
    void paladinAsAResourceGivesAGemWhenAStrongAllyIsSummonedByEffect() {
        CardSlot carrier = onField(host, hada("ca", "Portadora", 2, 2, 4), BACK);
        carrier.getResources().add(paladin("pa"));
        CardSlot strong = hada("sg", "Hada Fuerte", 0, 2, 6);
        summonByEffectToFront(strong);
        untilOffered(carrier);
        accept(carrier);
        drain();
        assertThat(gems()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ G06 Dianthe

    @Test
    void diantheTributesFromTheHandToSummonAHadaForOneLess() {
        CardSlot fairy = hada("hf", "Hada De Mano", 2, 1, 3);
        host.getHand().addAll(List.of(fairy, card("hx", "Sobrante", 1, 1, "R")));
        CardSlot dianthe = dianthe("di");
        summon(dianthe);
        accept(dianthe);
        answer("hx");
        answer("hf");
        drain();
        assertThat(host.getField()).contains(fairy);
        // 5 + 2 - 1
        assertThat(gems()).isEqualTo(2);
    }

    @Test
    void diantheReactivatesWhenAStrongAllyIsSummonedByEffect() {
        CardSlot dianthe = onField(host, dianthe("di"), BACK);
        dianthe.setTapped(true);
        summonByEffectToFront(hada("sg", "Hada Fuerte", 0, 2, 6));
        untilOffered(dianthe);
        accept(dianthe);
        drain();
        assertThat(dianthe.isTapped()).isFalse();
    }

    @Test
    void diantheAsAResourceReactivatesItsCarrier() {
        CardSlot carrier = onField(host, hada("ca", "Portadora", 2, 2, 4), BACK);
        carrier.getResources().add(dianthe("di"));
        carrier.setTapped(true);
        summonByEffectToFront(hada("sg", "Hada Fuerte", 0, 2, 6));
        untilOffered(carrier);
        accept(carrier);
        drain();
        assertThat(carrier.isTapped()).isFalse();
    }

    // ------------------------------------------------------------------ G07 Aine

    @Test
    void aineSummonsAHadaForTwoLessAndThenGivesItALowerHadaToMoveItUpForAGem() {
        CardSlot fairy = hada("hf", "Hada De Mano", 3, 3, 7);
        CardSlot lower = hada("lo", "Hada Menor", 1, 1, 1);
        host.getHand().addAll(List.of(fairy, lower));
        CardSlot aine = aine("ai");
        summon(aine);
        accept(aine);
        answer("hf");
        untilOffered(aine);
        accept(aine);
        answer("lo");
        drain();
        assertThat(fairy.getResources()).contains(lower);
        assertThat(EffectEngine.inFrontLine(fairy)).isTrue();
        // 6 + 3 - 2, then 1 gem
        assertThat(gems()).isEqualTo(8 - 6 - 1 + 1);
    }

    // ------------------------------------------------------------------ G08 Bosque Mágico, G09 Bosque Perdido

    @Test
    void bosquesGiveTheirKeywordToHadaAlliesOnYourTurn() {
        onField(host, structure("bm", "Bosque Mágico", 1, "G"), BACK);
        onField(host, structure("bp", "Bosque Perdido", 1, "G"), BACK);
        CardSlot fairy = onField(host, hada("hf", "Hada", 1, 1, 1));
        CardSlot other = onField(host, card("ot", "Otra", 1, 1, "R"));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(fairy)).contains("Alcance", "Golpe rápido");
        assertThat(EffectEngine.keywords(other)).doesNotContain("Alcance");
        room.setCurrentTurnUserId("guest");
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(fairy)).doesNotContain("Alcance");
    }

    @Test
    void bosqueMagicoExhaustsToLowerTheCostOfASummonByEffect() {
        CardSlot forest = onField(host, structure("bm", "Bosque Mágico", 1, "G"), BACK);
        CardSlot fairy = hada("hf", "Hada De Mano", 4, 2, 4);
        host.getHand().addAll(List.of(fairy, card("hx", "Sobrante", 1, 1, "R")));
        // Dianthe's summon by effect: 4 - 1, and Bosque Mágico 1 more
        CardSlot dianthe = dianthe("di");
        summon(dianthe);
        accept(dianthe);
        answer("hx");
        answer("hf");
        drain();
        assertThat(host.getField()).contains(fairy);
        assertThat(forest.isTapped()).isTrue();
        // 5 (Dianthe) + 4 - 1 - 1
        assertThat(gems()).isEqualTo(8 - 5 - 2);
    }

    // ------------------------------------------------------------------ G10 Floración

    @Test
    void floracionWithoutASummonAndFewerLivesGoesToTheTopOfTheLivesFaceDown() {
        host.getLifeStack().remove(0);
        CardSlot floracion = utility("fl", "Floración", 1, "G");
        play(floracion);
        answer(room.getPrompt());
        drain();
        assertThat(host.getLifeStack().get(0)).isSameAs(floracion);
        assertThat(floracion.isFaceDown()).isTrue();
    }

    @Test
    void floracionSummonsAHadaForOneLessAndGoesToTheDiscard() {
        host.getLifeStack().remove(0);
        CardSlot fairy = hada("hf", "Hada De Mano", 2, 1, 3);
        host.getHand().add(fairy);
        CardSlot floracion = utility("fl", "Floración", 1, "G");
        play(floracion);
        answer(room.getPrompt());
        accept(floracion);
        answer("hf");
        drain();
        assertThat(host.getField()).contains(fairy);
        assertThat(host.getDiscardPile()).contains(floracion);
    }

    // ------------------------------------------------------------------ G11 Danza de Ensueño

    @Test
    void danzaCostsOneLessPerExhaustedEnemyAndReturnsTheExhaustedOnesUpToNine() {
        CardSlot a = onField(guest, enemy("ea", 9));
        a.setTapped(true);
        CardSlot b = onField(guest, enemy("eb", 4));
        b.setTapped(true);
        CardSlot c = onField(guest, enemy("ec", 10));
        c.setTapped(true);
        CardSlot d = onField(guest, enemy("ed", 3));
        play(utility("dz", "Danza de Ensueño", 4, "G"));
        drain();
        assertThat(guest.getHand()).contains(a, b).doesNotContain(c, d);
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void danzaIsPlayedFromTheHandWhenYouTakeDirectDamage() {
        CardSlot danza = utility("dz", "Danza de Ensueño", 4, "G");
        host.getHand().add(danza);
        room.setCurrentTurnUserId("guest");
        CardSlot life = host.getLifeStack().remove(0);
        host.getTributeZone().add(life);
        EffectEngine.record(room, GameEvent.LIFE_DAMAGE, "guest", life, "lifeStack", "tributeZone", null,
                new java.util.HashMap<>(java.util.Map.of("defenderId", "host")), "daño directo");
        Chain.advance(room);
        untilOffered(danza);
        accept(danza);
        drain();
        assertThat(host.getDiscardPile()).contains(danza);
        assertThat(gems()).isEqualTo(8 - 3);
    }

    private static List<String> ids(List<CardSlot> cards) {
        return cards.stream().map(CardSlot::getInstanceId).toList();
    }
}
