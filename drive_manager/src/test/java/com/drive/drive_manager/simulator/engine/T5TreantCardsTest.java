package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cards in testing T5, archetype Treant (2026-11-19): one test (or more) per programmed effect, from the printed text. The Evoluciones of the
 * archetype accept a [treant] structure as their summoner ("criatura o estructura [treant] nivel N").
 */
@Tag("cards")
@Tag("t5")
class T5TreantCardsTest extends CardBatchSupport {

    private static CardSlot treant(String id, String name, Integer cost, int level, int strength) {
        CardSlot c = withClasses(card(id, name, cost, level, "G"), "treant");
        c.setStrength(strength);
        return c;
    }

    private static CardSlot evolution(CardSlot c, int specialCost) {
        c.setSpecialSummonKind("evolution");
        c.setSpecialCost(specialCost);
        return c;
    }

    private static CardSlot treantStructure(String id, String name, int cost, int level) {
        CardSlot c = withClasses(structure(id, name, cost, "G"), "treant");
        c.setLevel(level);
        return c;
    }

    private static CardSlot pequeno(String id) {
        CardSlot c = treant(id, "Pequeño Treant", 1, 1, 1);
        c.setStarter(true);
        return c;
    }

    private static CardSlot selvatico(String id) {
        return treantStructure(id, "Bosque Selvático", 1, 1);
    }

    private static CardSlot runico(String id) {
        return treantStructure(id, "Bosque Rúnico", 2, 2);
    }

    private static CardSlot bonewillow(String id) {
        CardSlot c = evolution(treant(id, "Bonewillow", 2, 2, 6), 1);
        c.setColors(new ArrayList<>(List.of("G", "P")));
        return c;
    }

    private static CardSlot coloso(String id) {
        return evolution(treant(id, "Coloso Enraizado", 2, 2, 5), 1);
    }

    private static CardSlot courser(String id) {
        return evolution(treant(id, "Treant Courser", 3, 2, 8), 2);
    }

    private static CardSlot pantanoso(String id) {
        return evolution(treant(id, "Treant Pantanoso", 3, 2, 9), 3);
    }

    private static CardSlot otonal(String id) {
        CardSlot c = evolution(treant(id, "Treant Otoñal", 5, 4, 12), 3);
        c.setKeywords(new ArrayList<>(List.of("Reactivar")));
        return c;
    }

    private static CardSlot thalaesk(String id) {
        CardSlot c = evolution(treant(id, "Thalaesk el Alarido del Bosque Podrido", null, 5, 14), 12);
        c.setColors(new ArrayList<>(List.of("P", "G")));
        return c;
    }

    private static CardSlot embrujado(String id) {
        CardSlot c = withClasses(card(id, "Treant Embrujado", 3, 3, "G"), "ángel");
        c.setStrength(8);
        return c;
    }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "R");
        c.setStrength(strength);
        return c;
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

    /** Evolves `card` from the hand with that summoner; it stays in the Retaguardia (the move to the Línea frontal is declined). */
    private void evolve(CardSlot card, CardSlot summoner) {
        if (!host.getHand().contains(card)) host.getHand().add(card);
        assertThat(SpecialSummons.specialByPlayer(room, "host", card.getInstanceId(), summoner.getInstanceId())).isNull();
        stayBack();
    }

    /** Declines moving a creature special summoned up to the Línea frontal. */
    private void stayBack() {
        Prompt p = room.getPrompt();
        if (p != null && PromptPurpose.SPECIAL_FRONT.is(p.getPurpose())) answer(p);
    }

    // ------------------------------------------------------------------ G01 Pequeño Treant

    @Test
    void pequenoTreantLooksAtThreeAndTakesATreant() {
        CardSlot wanted = treant("tw", "Treant Buscado", 1, 1, 1);
        host.getDeck().add(1, wanted);
        summon(pequeno("pt"));
        assertThat(room.getPrompt().getValidIds()).containsExactly("tw");
        answer("tw");
        drain();
        assertThat(host.getHand()).contains(wanted);
        assertThat(host.getDeck()).hasSize(8);
    }

    @Test
    void pequenoTreantBecomesAResourceOfAStructureToPlayATreantStructure() {
        CardSlot pequeno = onField(host, pequeno("pt"), BACK);
        CardSlot structure = onField(host, structure("st", "Estructura", 1, "G"), BACK);
        CardSlot runico = runico("br");
        host.getHand().add(runico);
        activate(pequeno, "pequeno-treant:turno-aliado");
        answer("st");
        answer("br");
        drain();
        assertThat(structure.getResources()).containsExactly(pequeno);
        assertThat(host.getField()).contains(runico).doesNotContain(pequeno);
        assertThat(gems()).isEqualTo(6);
    }

    @Test
    void pequenoTreantGivesItsEvolutionATreantResourceForItself() {
        CardSlot pequeno = onField(host, pequeno("pt"));
        CardSlot coloso = coloso("co");
        CardSlot extra = treant("tx", "Treant Extra", 1, 1, 1);
        host.getHand().add(extra);
        evolve(coloso, pequeno);
        untilOffered(coloso);
        accept(coloso);
        answer("tx");
        drain();
        assertThat(coloso.getResources()).containsExactly(extra);
        assertThat(host.getTributeZone()).contains(pequeno);
    }

    // ------------------------------------------------------------------ G02 Bosque Selvático

    @Test
    void bosqueSelvaticoIsPlayedForFreeWhenATreantIsSummonedAlone() {
        CardSlot bosque = selvatico("bs");
        host.getHand().add(bosque);
        summon(pequeno("pt"));
        untilOffered(bosque);
        accept(bosque);
        drain();
        assertThat(host.getField()).contains(bosque);
        assertThat(gems()).isEqualTo(7);
    }

    @Test
    void bosqueSelvaticoIsNotOfferedWithAnotherCreatureOnTheField() {
        onField(host, card("ot", "Otra", 1, 1, "R"), BACK);
        CardSlot bosque = selvatico("bs");
        host.getHand().add(bosque);
        summon(pequeno("pt"));
        drain();
        assertThat(host.getHand()).contains(bosque);
    }

    @Test
    void bosqueSelvaticoAddsATreantAsResourceToDrawTwo() {
        CardSlot bosque = selvatico("bs");
        CardSlot fodder = treant("tf", "Treant Recurso", 1, 1, 1);
        host.getHand().add(fodder);
        play(bosque);
        accept(bosque);
        answer("tf");
        drain();
        assertThat(bosque.getResources()).containsExactly(fodder);
        assertThat(host.getHand()).hasSize(2);
    }

    @Test
    void bosqueSelvaticoAsAResourcePlaysAGreenStructureFromTheTributosForOneLess() {
        CardSlot bosque = onField(host, selvatico("bs"), BACK);
        CardSlot coloso = coloso("co");
        CardSlot fromTributes = runico("br");
        host.getTributeZone().add(fromTributes);
        evolve(coloso, bosque);
        untilOffered(coloso);
        accept(coloso);
        answer("br");
        drain();
        assertThat(coloso.getResources()).contains(bosque);
        assertThat(host.getField()).contains(fromTributes);
        // Evolución 1 + Bosque Rúnico 2 - 1
        assertThat(gems()).isEqualTo(6);
    }

    // ------------------------------------------------------------------ G03 Renacimiento Vital

    @Test
    void renacimientoVitalTributesFourTakesAGreenTributeAndStaysExhausted() {
        CardSlot green = card("gr", "Verde", 1, 1, "G");
        host.getTributeZone().add(green);
        CardSlot renacimiento = utility("rv", "Renacimiento Vital", 1, "G");
        play(renacimiento);
        answer("gr");
        drain();
        assertThat(host.getTributeZone()).hasSize(4);
        assertThat(host.getHand()).contains(green);
        assertThat(host.getField()).contains(renacimiento);
        assertThat(renacimiento.isTapped()).isTrue();
    }

    @Test
    void renacimientoVitalGoesToTheDiscardToSummonAResourceOfATreant() {
        CardSlot renacimiento = onField(host, utility("rv", "Renacimiento Vital", 1, "G"), BACK);
        CardSlot host1 = onField(host, treant("th", "Treant Anfitrión", 2, 2, 5), BACK);
        CardSlot resource = treant("tr", "Treant Recurso", 2, 1, 3);
        host1.getResources().add(resource);
        activate(renacimiento, "renacimiento-vital:turno-aliado");
        answer("th");
        answer("tr");
        drain();
        assertThat(host.getDiscardPile()).contains(renacimiento);
        assertThat(host.getField()).contains(resource);
        assertThat(host1.getResources()).isEmpty();
        assertThat(gems()).isEqualTo(6);
    }

    @Test
    void renacimientoVitalCannotBeActivatedExhausted() {
        CardSlot renacimiento = onField(host, utility("rv", "Renacimiento Vital", 1, "G"), BACK);
        renacimiento.setTapped(true);
        CardSlot host1 = onField(host, treant("th", "Treant Anfitrión", 2, 2, 5), BACK);
        host1.getResources().add(treant("tr", "Treant Recurso", 2, 1, 3));
        assertThat(canActivate(renacimiento, "renacimiento-vital:turno-aliado")).isFalse();
    }

    // ------------------------------------------------------------------ G04 Bonewillow

    @Test
    void bonewillowEvolvesFromItsHandWhenACreatureIsDestroyedWithAStructureAsSummoner() {
        CardSlot structure = onField(host, selvatico("bs"), BACK);
        CardSlot bonewillow = bonewillow("bw");
        host.getHand().add(bonewillow);
        CardSlot victim = onField(guest, enemy("en", 3));
        Removals.destroy(room, victim, guest, false, EffectEngine.CAUSE_EFFECT, "host", "src", null);
        Chain.advance(room);
        accept(bonewillow);
        answer("bw");
        answer("bs");
        drain();
        assertThat(host.getField()).contains(bonewillow);
        assertThat(bonewillow.getResources()).contains(structure);
        assertThat(gems()).isEqualTo(7);
    }

    @Test
    void bonewillowDestroyedPlaysAStructureFromTheDiscardForOneLess() {
        CardSlot bonewillow = onField(host, bonewillow("bw"), BACK);
        CardSlot runico = runico("br");
        host.getDiscardPile().add(runico);
        Removals.destroy(room, bonewillow, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        accept(bonewillow);
        answer("br");
        drain();
        assertThat(host.getField()).contains(runico);
        assertThat(gems()).isEqualTo(7);
    }

    // ------------------------------------------------------------------ G05 Coloso Enraizado

    @Test
    void colosoGainsOneStrengthPerStructureUntilTheEndOfTheAttack() {
        CardSlot coloso = onField(host, coloso("co"));
        onField(host, structure("s1", "Estructura 1", 1, "G"), BACK);
        onField(host, structure("s2", "Estructura 2", 1, "G"), BACK);
        onField(guest, enemy("bl", 1));
        assertThat(CombatRules.attackByPlayer(room, "host", "co", null)).isNull();
        Chain.advance(room);
        // The opponent is asked whether it blocks: the attack is going on
        assertThat(room.getPrompt().getPlayerId()).isEqualTo("guest");
        assertThat(EffectEngine.strength(coloso)).isEqualTo(7);
        drain();
        assertThat(EffectEngine.strength(coloso)).isEqualTo(5);
    }

    @Test
    void colosoAtTheStartOfTheOpponentsTurnSpecialSummonsATreantFromTheHandForOneLess() {
        CardSlot coloso = onField(host, coloso("co"), BACK);
        CardSlot courser = courser("cr");
        host.getHand().add(courser);
        room.setCurrentTurnUserId("guest");
        EffectEngine.record(room, GameEvent.TURN_START, "guest", null, null, null, null, null, "turno");
        Chain.advance(room);
        accept(coloso);
        answer("cr");
        drain();
        assertThat(host.getField()).contains(courser);
        assertThat(courser.getResources()).contains(coloso);
        assertThat(gems()).isEqualTo(7);
    }

    @Test
    void colosoAsAResourceLetsItsCarrierSummonATreantAtTheStartOfYourTurn() {
        CardSlot carrier = onField(host, treant("ca", "Treant Portador", 2, 2, 5), BACK);
        carrier.getResources().add(coloso("co"));
        CardSlot courser = courser("cr");
        host.getHand().add(courser);
        EffectEngine.record(room, GameEvent.TURN_START, "host", null, null, null, null, null, "turno");
        Chain.advance(room);
        accept(carrier);
        answer("cr");
        drain();
        assertThat(host.getField()).contains(courser);
        assertThat(courser.getResources()).contains(carrier);
    }

    // ------------------------------------------------------------------ G06 Bosque Rúnico

    @Test
    void bosqueRunicoLooksAtThreeAndTakesATreant() {
        CardSlot wanted = treant("tw", "Treant Buscado", 1, 1, 1);
        host.getDeck().add(0, wanted);
        play(runico("br"));
        answer("tw");
        drain();
        assertThat(host.getHand()).contains(wanted);
    }

    // ------------------------------------------------------------------ G07 Treant Courser

    @Test
    void courserReactivatesOnSummonOnlyWithALevelFiveTreant() {
        CardSlot summoner = onField(host, treant("t2", "Treant Dos", 2, 2, 5));
        summoner.setTapped(true);
        onField(host, treant("t5", "Treant Cinco", 6, 5, 12), BACK);
        CardSlot courser = courser("cr");
        evolve(courser, summoner);
        drain();
        assertThat(courser.isTapped()).isFalse();
    }

    @Test
    void courserStaysExhaustedWithoutALevelFiveTreant() {
        CardSlot summoner = onField(host, treant("t2", "Treant Dos", 2, 2, 5));
        summoner.setTapped(true);
        CardSlot courser = courser("cr");
        evolve(courser, summoner);
        drain();
        assertThat(courser.isTapped()).isTrue();
    }

    @Test
    void courserAttackingAddsATreantAsResourceToReactivate() {
        CardSlot courser = onField(host, courser("cr"));
        CardSlot other = onField(host, treant("to", "Treant Otro", 1, 1, 1), BACK);
        assertThat(CombatRules.attackByPlayer(room, "host", "cr", null)).isNull();
        untilOffered(courser);
        accept(courser);
        answer("to");
        assertThat(courser.getResources()).contains(other);
        assertThat(courser.isTapped()).isFalse();
        drain();
    }

    @Test
    void courserAsAResourceIsSpecialSummonedForOneLess() {
        CardSlot carrier = onField(host, treant("ca", "Treant Portador", 2, 2, 5), BACK);
        CardSlot courser = courser("cr");
        carrier.getResources().add(courser);
        activate(carrier, "treant-courser:hereda-turno-aliado");
        answer("cr");
        answer("ca");
        drain();
        assertThat(host.getField()).contains(courser);
        assertThat(courser.getResources()).contains(carrier);
        assertThat(gems()).isEqualTo(7);
    }

    // ------------------------------------------------------------------ G08 Treant Pantanoso

    @Test
    void pantanosoReactivatesAddsATreantAndSummonsOneFromTheTributosForOneLess() {
        CardSlot summoner = onField(host, treant("t2", "Treant Dos", 2, 2, 5));
        summoner.setTapped(true);
        CardSlot other = onField(host, treant("to", "Treant Otro", 1, 1, 1), BACK);
        CardSlot fromTributes = treant("tt", "Treant Tributo", 2, 1, 3);
        host.getTributeZone().add(fromTributes);
        CardSlot pantanoso = pantanoso("tp");
        evolve(pantanoso, summoner);
        answer("to");
        accept(pantanoso);
        answer("tt");
        drain();
        assertThat(pantanoso.isTapped()).isFalse();
        assertThat(pantanoso.getResources()).contains(other);
        assertThat(host.getField()).contains(fromTributes);
        // Evolución 3 + 2 - 1
        assertThat(gems()).isEqualTo(4);
    }

    @Test
    void pantanosoMayPlayATreantStructureInsteadOfSummoning() {
        CardSlot summoner = onField(host, treant("t2", "Treant Dos", 2, 2, 5));
        onField(host, treant("to", "Treant Otro", 1, 1, 1), BACK);
        CardSlot structure = runico("br");
        host.getHand().add(structure);
        CardSlot pantanoso = pantanoso("tp");
        evolve(pantanoso, summoner);
        answer("to");
        accept(pantanoso);
        answer("br");
        drain();
        assertThat(host.getField()).contains(structure);
    }

    @Test
    void pantanosoAsAResourceAddsATreantAtTheStartOfYourTurnForAGem() {
        CardSlot carrier = onField(host, treant("ca", "Treant Portador", 2, 2, 5), BACK);
        carrier.getResources().add(pantanoso("tp"));
        CardSlot fodder = treant("tf", "Treant Recurso", 1, 1, 1);
        host.getHand().add(fodder);
        EffectEngine.record(room, GameEvent.TURN_START, "host", null, null, null, null, null, "turno");
        Chain.advance(room);
        accept(carrier);
        answer("tf");
        drain();
        assertThat(carrier.getResources()).contains(fodder);
        assertThat(gems()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ G09 Treant Embrujado

    @Test
    void embrujadoSendsGreenTributesToTheDeckBottomToLowerItsCost() {
        for (int i = 0; i < 11; i++) host.getTributeZone().add(card("g" + i, "Verde " + i, 1, 1, "G"));
        CardSlot embrujado = embrujado("te");
        host.getHand().add(embrujado);
        assertThat(Summons.normalSummon(room, "host", "te", 50.0, 75.0)).isNull();
        Prompt pay = room.getPrompt();
        assertThat(pay.getMax()).isEqualTo(11);
        answer(pay, "g0", "g1", "g2", "g3", "g4", "g5", "g6", "g7", "g8", "g9", "g10");
        drain();
        assertThat(host.getField()).contains(embrujado);
        // 11 sent: 2 times 5, cost 3 - 2
        assertThat(gems()).isEqualTo(7);
        assertThat(host.getTributeZone()).isEmpty();
        assertThat(host.getDeck()).hasSize(8 + 11);
    }

    @Test
    void embrujadoDestroysAnEnemyUpToFourStrengthPlusOnePerTreant() {
        onField(host, treant("t1", "Treant Uno", 1, 1, 1), BACK);
        onField(host, treant("t2", "Treant Dos", 1, 1, 1), BACK);
        onField(guest, enemy("e6", 6));
        onField(guest, enemy("e7", 7));
        summonByEffectToFront(embrujado("te"));
        drain();
        Prompt choose = room.getPrompt();
        assertThat(choose.getValidIds()).containsExactly("e6");
        answer("e6");
        drain();
        assertThat(guest.getField()).extracting(CardSlot::getInstanceId).containsExactly("e7");
    }

    // ------------------------------------------------------------------ G10 Treant Otoñal

    @Test
    void otonalExhaustedPlaysATreantStructureFromItsResourcesForTwoLess() {
        CardSlot otonal = onField(host, otonal("ot"));
        CardSlot structure = runico("br");
        otonal.getResources().add(structure);
        otonal.setTapped(true);
        EffectEngine.record(room, GameEvent.TAP, "host", otonal, null, null, null, null, "agota");
        Chain.advance(room);
        accept(otonal);
        answer("br");
        drain();
        assertThat(host.getField()).contains(structure);
        assertThat(gems()).isEqualTo(8);
    }

    // ------------------------------------------------------------------ P01 Thalaesk

    @Test
    void thalaeskCostsOneLessPerTreantAndTakesThreeTreantsWithTheirResources() {
        CardSlot summoner = onField(host, treant("t1", "Treant Uno", 1, 1, 1));
        CardSlot a = onField(host, treant("ta", "Treant A", 1, 1, 1), BACK);
        CardSlot b = onField(host, treant("tb", "Treant B", 1, 1, 1), BACK);
        CardSlot c = onField(host, treant("tc", "Treant C", 1, 1, 1), BACK);
        CardSlot stuck = treant("rs", "Recurso De A", 1, 1, 1);
        a.getResources().add(stuck);
        onField(host, treantStructure("bs", "Bosque", 1, 1), BACK);
        CardSlot thalaesk = thalaesk("th");
        // 12 - 5 [treant] (summoner, three treants and the structure)
        room.setSharedCounter(-7);
        evolve(thalaesk, summoner);
        answer("ta", "tb", "tc");
        drain();
        assertThat(host.getField()).contains(thalaesk);
        assertThat(thalaesk.getResources()).contains(summoner, a, b, c, stuck);
        assertThat(gems()).isZero();
    }

    @Test
    void thalaeskReactivatesWhenATreantOfYoursDestroysACreature() {
        CardSlot thalaesk = onField(host, thalaesk("th"));
        thalaesk.setTapped(true);
        CardSlot attacker = onField(host, treant("ta", "Treant Atacante", 1, 1, 9));
        CardSlot blocker = onField(guest, enemy("en", 3));
        blocker.setTapped(true);
        assertThat(CombatRules.attackByPlayer(room, "host", "ta", "en")).isNull();
        drain();
        assertThat(guest.getField()).doesNotContain(blocker);
        assertThat(thalaesk.isTapped()).isFalse();
        assertThat(attacker.isTapped()).isTrue();
    }
}
