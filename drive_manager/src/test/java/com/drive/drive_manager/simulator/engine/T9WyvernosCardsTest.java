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
 * Cards in testing T9, archetype Wyvernos (2026-11-19): one test (or more) per programmed effect, from the printed text.
 */
@Tag("cards")
@Tag("t9")
class T9WyvernosCardsTest extends CardBatchSupport {

    private static CardSlot dragon(String id, String name, Integer cost, int level, int strength, String... classes) {
        CardSlot c = withClasses(card(id, name, cost, level, "G"), classes.length == 0 ? new String[]{"dragón"} : classes);
        c.setStrength(strength);
        return c;
    }

    private static CardSlot evolution(CardSlot c, int specialCost) {
        c.setSpecialSummonKind("evolution");
        c.setSpecialCost(specialCost);
        return c;
    }

    private static CardSlot dirugon(String id) {
        return dragon(id, "Dirugón", 1, 1, 3);
    }

    private static CardSlot pteroragon(String id) {
        return evolution(dragon(id, "Pteroragón", 2, 2, 5), 1);
    }

    private static CardSlot elegante(String id) {
        return evolution(dragon(id, "Wyverno Elegante", 2, 2, 5, "dragón", "wyverno"), 1);
    }

    private static CardSlot ddrake(String id) {
        return evolution(dragon(id, "Ddrake", 3, 2, 7, "dragón", "muerto viviente"), 1);
    }

    private static CardSlot fresnagon(String id) {
        return evolution(dragon(id, "Fresnagón", 3, 3, 7), 1);
    }

    private static CardSlot metaldrake(String id) {
        return evolution(dragon(id, "Metaldrake", 3, 3, 8, "dragón", "wyverno"), 2);
    }

    private static CardSlot pluma(String id) {
        return evolution(dragon(id, "Dragón Pluma Cristal", 7, 4, 11, "dragón", "paladín"), 4);
    }

    private static CardSlot valanidris(String id) {
        return evolution(dragon(id, "Valanidris", 4, 4, 10, "dragón", "wyverno"), 3);
    }

    private static CardSlot dracotyrantus(String id) {
        return evolution(dragon(id, "Dracotyrantus Magna", 9, 5, 15), 4);
    }

    private static CardSlot falanis(String id) {
        return evolution(dragon(id, "Falanis el Wyverno de la Tormenta", 8, 5, 13, "dragón", "wyverno", "eléctrico"), 4);
    }

    private static CardSlot gaeos(String id) {
        return evolution(dragon(id, "Gaeos el Ala de la Tierra", 10, 6, 16, "dragón", "deidad"), 10);
    }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "R");
        c.setStrength(strength);
        return c;
    }

    private void evolve(CardSlot card, CardSlot summoner) {
        if (!host.getHand().contains(card)) host.getHand().add(card);
        assertThat(SpecialSummons.specialByPlayer(room, "host", card.getInstanceId(), summoner.getInstanceId())).isNull();
        stayBack();
    }

    private void stayBack() {
        Prompt p = room.getPrompt();
        if (p != null && PromptPurpose.SPECIAL_FRONT.is(p.getPurpose())) answer(p);
    }

    private void activate(CardSlot card, String idStart) {
        String script = Chain.activatable(room, "host").stream()
                .filter(a -> card.getInstanceId().equals(a.instanceId()) && a.scriptId().startsWith(idStart))
                .map(Chain.Activatable::scriptId).findFirst().orElse(null);
        assertThat(script).as("se puede activar " + idStart).isNotNull();
        assertThat(Chain.activateByPlayer(room, "host", card.getInstanceId(), script)).isNull();
    }

    /** Answers "none" to what is asked, and the first option of an order of mandatory effects. */
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

    private void tributes(int n, String prefix, String... classes) {
        for (int i = 0; i < n; i++) host.getTributeZone().add(dragon(prefix + i, "Tributo " + prefix + i, 1, 1, 1, classes));
    }

    // ------------------------------------------------------------------ G01 Dirugón

    @Test
    void dirugonBecomesAResourceOfAStrongAllySummonedForAGemAndATribute() {
        CardSlot dirugon = onField(host, dirugon("di"), BACK);
        CardSlot taken = card("tk", "Tomada", 1, 1, "G");
        host.getTributeZone().add(taken);
        CardSlot big = dragon("bg", "Grande", 2, 2, 5);
        summon(big);
        untilOffered(dirugon);
        accept(dirugon);
        answer("tk");
        settle();
        assertThat(big.getResources()).containsExactly(dirugon);
        assertThat(host.getHand()).contains(taken);
        assertThat(gems()).isEqualTo(8 - 2 + 1);
    }

    @Test
    void dirugonIsNotOfferedForAWeakAlly() {
        onField(host, dirugon("di"), BACK);
        summon(dragon("sm", "Chico", 1, 1, 4));
        settle();
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("di");
    }

    @Test
    void dirugonAsAResourceMakesTheDragonsEffectsSeeStrengthOneLess() {
        // Fresnagón destroys an enemy of strength 4: with Dirugón as a Recurso it counts as 3
        CardSlot summoner = onField(host, dragon("s2", "Dragón Dos", 2, 2, 4));
        summoner.getResources().add(dirugon("di"));
        CardSlot target = onField(guest, enemy("en", 4));
        evolve(fresnagon("fr"), summoner);
        untilOffered(target);
        answer("en");
        settle();
        assertThat(guest.getDiscardPile()).contains(target);
    }

    @Test
    void withoutDirugonFresnagonCannotDestroyStrengthFour() {
        CardSlot summoner = onField(host, dragon("s2", "Dragón Dos", 2, 2, 4));
        CardSlot target = onField(guest, enemy("en", 4));
        evolve(fresnagon("fr"), summoner);
        settle();
        assertThat(guest.getField()).contains(target);
    }

    // ------------------------------------------------------------------ G02 Pteroragón

    @Test
    void pteroragonMillsThreeAndPlaysADragonStructureFromTheTributosForOneLess() {
        CardSlot cave = structure("cv", "Cueva del Dragón", 2, "G");
        host.getTributeZone().add(cave);
        CardSlot summoner = onField(host, dragon("s1", "Dragón Uno", 1, 1, 3), BACK);
        evolve(pteroragon("pt"), summoner);
        assertThat(host.getTributeZone()).hasSize(4);
        // "¿Juegas…?"
        answer(room.getPrompt(), room.getPrompt().getValidIds().get(0));
        answer("cv");
        settle();
        assertThat(host.getField()).contains(cave);
        assertThat(gems()).isEqualTo(8 - 1 - 1);
    }

    // ------------------------------------------------------------------ G03 Wyverno Elegante

    @Test
    void eleganteTributedFromTheDeckActivatesItsSummonEffectForAGem() {
        CardSlot elegante = elegante("we");
        host.getDeck().add(1, elegante);
        tributes(5, "w", "wyverno");
        CardSlot summoner = onField(host, dragon("s1", "Dragón Uno", 1, 1, 3), BACK);
        evolve(pteroragon("pt"), summoner);
        untilOffered(elegante);
        accept(elegante);
        answer("w0", "w1", "w2", "w3", "w4");
        settle();
        assertThat(host.getDeck()).extracting(CardSlot::getInstanceId).contains("w0", "w4");
        assertThat(gems()).isEqualTo(8 - 1 + 1);
    }

    // ------------------------------------------------------------------ G04 Ddrake

    @Test
    void ddrakeSummonsSpeciallyACreatureOfTheTributosForOneLess() {
        CardSlot fresnagon = fresnagon("fr");
        host.getTributeZone().add(fresnagon);
        CardSlot summoner = onField(host, dragon("s2", "Dragón Dos", 2, 2, 4), BACK);
        CardSlot ddrake = ddrake("dd");
        evolve(ddrake, summoner);
        untilOffered(ddrake);
        accept(ddrake);
        answer("fr");
        settle();
        assertThat(host.getField()).contains(fresnagon).doesNotContain(ddrake);
        assertThat(fresnagon.getResources()).contains(ddrake);
        // Ddrake 1, Fresnagón 1 - 1
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void ddrakeFromTheHandEvolvesFromAnAllyAboutToBeDestroyed() {
        CardSlot ddrake = ddrake("dd");
        host.getHand().add(ddrake);
        CardSlot ally = onField(host, dragon("a2", "Aliado Dos", 2, 2, 4));
        Removals.destroy(room, ally, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        Prompt p = room.getPrompt();
        assertThat(p).isNotNull();
        answer(p, p.getValidIds().stream().filter(id -> id.contains("dd")).findFirst().orElseThrow());
        settle();
        assertThat(host.getField()).contains(ddrake);
        assertThat(ddrake.getResources()).contains(ally);
    }

    // ------------------------------------------------------------------ G05 Fresnagón

    @Test
    void fresnagonAsAResourceDestroysAWeakEnemyWhenItsHolderAttacks() {
        CardSlot holder = onField(host, dragon("ho", "Portador", 3, 3, 7));
        holder.getResources().add(fresnagon("fr"));
        CardSlot weak = onField(guest, enemy("en", 3));
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        untilOffered(weak);
        answer("en");
        settle();
        assertThat(guest.getDiscardPile()).contains(weak);
    }

    // ------------------------------------------------------------------ G06 Metaldrake

    @Test
    void metaldrakeTributedFromTheHandIsEvolvedByAnAlly() {
        CardSlot metaldrake = metaldrake("md");
        CardSlot ally = onField(host, dragon("a2", "Aliado Dos", 2, 2, 4), BACK);
        host.getTributeZone().add(metaldrake);
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", metaldrake, "hand", "tributeZone", null,
                new HashMap<>(Map.of("ownerId", "host")), "tributa");
        Chain.advance(room);
        untilOffered(metaldrake);
        accept(metaldrake);
        answer("md");
        answer("a2");
        stayBack();
        // Its own En Invocación: a card of the hand on top (none), then 4 tributed
        settle();
        assertThat(host.getField()).contains(metaldrake);
        assertThat(metaldrake.getResources()).contains(ally);
        assertThat(host.getTributeZone()).hasSize(4);
    }

    @Test
    void metaldrakeIsNotOfferedWhenTributedFromTheDeck() {
        CardSlot metaldrake = metaldrake("md");
        host.getDeck().add(1, metaldrake);
        onField(host, dragon("a2", "Aliado Dos", 2, 2, 4), BACK);
        CardSlot summoner = onField(host, dragon("s1", "Dragón Uno", 1, 1, 3), BACK);
        evolve(pteroragon("pt"), summoner);
        settle();
        assertThat(host.getTributeZone()).contains(metaldrake);
    }

    @Test
    void metaldrakeAsAResourcePutsATributeOnTopToDestroyAnEnemy() {
        CardSlot holder = onField(host, dragon("ho", "Portador", 3, 3, 7));
        holder.getResources().add(metaldrake("md"));
        CardSlot back = card("tb", "Del Tributo", 1, 1, "G");
        host.getTributeZone().add(back);
        CardSlot target = onField(guest, enemy("en", 5));
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        untilOffered(holder);
        accept(holder);
        answer("tb");
        answer(room.getPrompt(), room.getPrompt().getValidIds().get(0));
        answer("en");
        settle();
        assertThat(host.getDeck().get(0)).isSameAs(back);
        assertThat(guest.getDiscardPile()).contains(target);
    }

    // ------------------------------------------------------------------ G07 Dragón Pluma Cristal

    @Test
    void plumaCristalReturnsWeakEnemiesToTheHandForAGemEach() {
        onField(guest, enemy("e1", 5));
        onField(guest, enemy("e2", 4));
        CardSlot strong = onField(guest, enemy("e3", 6));
        CardSlot summoner = onField(host, dragon("s3", "Dragón Tres", 3, 3, 6));
        evolve(pluma("pc"), summoner);
        settle();
        assertThat(guest.getHand()).extracting(CardSlot::getInstanceId).containsExactlyInAnyOrder("e1", "e2");
        assertThat(guest.getField()).containsExactly(strong);
        assertThat(gems()).isEqualTo(8 - 4 + 2);
    }

    @Test
    void plumaCristalReactivatesWhenACardIsAddedToTheOpponentsHandByEffect() {
        CardSlot first = onField(host, pluma("p1"));
        first.setTapped(true);
        onField(guest, enemy("e1", 2));
        CardSlot summoner = onField(host, dragon("s3", "Dragón Tres", 3, 3, 6));
        evolve(pluma("p2"), summoner);
        settle();
        assertThat(guest.getHand()).extracting(CardSlot::getInstanceId).contains("e1");
        assertThat(first.isTapped()).isFalse();
    }

    // ------------------------------------------------------------------ G08 Valanidris

    @Test
    void valanidrisPutsThreeTributesOnTopToGainAGemBeforeEvolving() {
        CardSlot valanidris = onField(host, valanidris("va"));
        host.getTributeZone().addAll(List.of(card("t1", "T1", 1, 1, "G"), card("t2", "T2", 1, 1, "G"), card("t3", "T3", 1, 1, "G")));
        CardSlot falanis = falanis("fa");
        host.getHand().add(falanis);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "fa", "va")).isNull();
        Prompt p = room.getPrompt();
        assertThat(PromptPurpose.SUMMONER_DISCOUNT.is(p.getPurpose())).isTrue();
        answer(p, "t1", "t2", "t3");
        stayBack();
        // The three went on top: the regulatory draw and Falanis's mill of 5 took them
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsAnyOf("t1", "t2", "t3");
        assertThat(host.getField()).contains(falanis);
        // +1 gem, then the special cost 4
        assertThat(gems()).isEqualTo(8 + 1 - 4);
    }

    @Test
    void valanidrisAsAResourceMillsThreeAndTakesOneWhenItsHolderAttacks() {
        CardSlot holder = onField(host, dragon("ho", "Portador", 5, 5, 13));
        holder.getResources().add(valanidris("va"));
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        assertThat(host.getTributeZone()).hasSize(3);
        answer("hd3");
        settle();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("hd3");
    }

    // ------------------------------------------------------------------ G09 Dracotyrantus Magna

    @Test
    void dracotyrantusSendsTenDragonsToDestroyTwoEnemies() {
        tributes(10, "d");
        CardSlot e1 = onField(guest, enemy("e1", 9));
        CardSlot e2 = onField(guest, enemy("e2", 2));
        CardSlot summoner = onField(host, dragon("s4", "Dragón Cuatro", 4, 4, 10));
        CardSlot draco = dracotyrantus("dm");
        evolve(draco, summoner);
        untilOffered(draco);
        accept(draco);
        answer("d0", "d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9");
        answer("e1", "e2");
        settle();
        assertThat(guest.getDiscardPile()).contains(e1, e2);
        assertThat(host.getDeck()).hasSize(17);
    }

    @Test
    void dracotyrantusDestroysAStrongerEnemyWhenNoneWasDestroyedAndTenWereSent() {
        tributes(10, "d");
        CardSlot e1 = onField(guest, enemy("e1", 12));
        CardSlot summoner = onField(host, dragon("s4", "Dragón Cuatro", 4, 4, 10));
        CardSlot draco = dracotyrantus("dm");
        evolve(draco, summoner);
        untilOffered(draco);
        accept(draco);
        answer("d0", "d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9");
        untilOffered(e1);
        answer("e1");
        settle();
        assertThat(guest.getDiscardPile()).contains(e1);
    }

    @Test
    void dracotyrantusCostsTwoLessFromALevelFiveSummoner() {
        CardSlot summoner = onField(host, dragon("s5", "Dragón Cinco", 5, 5, 12), BACK);
        evolve(dracotyrantus("dm"), summoner);
        settle();
        assertThat(gems()).isEqualTo(8 - 2);
    }

    @Test
    void dracotyrantusAsAResourceGivesDragonsCeleridad() {
        CardSlot holder = onField(host, dragon("ho", "Portador", 5, 5, 13));
        holder.getResources().add(dracotyrantus("dm"));
        CardSlot other = onField(host, dragon("ot", "Otro Dragón", 1, 1, 2), BACK);
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(other)).contains("Celeridad");
    }

    // ------------------------------------------------------------------ G10 Falanis

    @Test
    void falanisMillsFiveAndOnAttackSortsThreeTributesAndStopsAnEnemyFromExhausting() {
        CardSlot summoner = onField(host, dragon("s4", "Dragón Cuatro", 4, 4, 10));
        CardSlot falanis = falanis("fa");
        evolve(falanis, summoner);
        settle();
        assertThat(host.getTributeZone()).hasSize(5);
        CardSlot target = onField(guest, enemy("en", 2));
        assertThat(CombatRules.attackByPlayer(room, "host", "fa", null)).isNull();
        // "¿Colocas…?" yes
        answer(room.getPrompt(), room.getPrompt().getValidIds().get(0));
        answer("hd2", "hd3", "hd4");
        // The three, to the bottom
        answer(room.getPrompt());
        answer("en");
        settle();
        assertThat(EffectEngine.keywords(target)).contains("No puede agotarse");
        assertThat(host.getTributeZone()).hasSize(2);
    }

    // ------------------------------------------------------------------ G11 Gaeos

    @Test
    void gaeosCostsOneLessForEachResourceOfTheSummonerTributed() {
        CardSlot summoner = onField(host, dragon("s5", "Dragón Cinco", 5, 5, 12), BACK);
        for (int i = 0; i < 4; i++) summoner.getResources().add(card("r" + i, "Recurso " + i, 1, 1, "G"));
        host.setResourceCount(8);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ga", "s5")).isNotNull();
        CardSlot gaeos = gaeos("ga");
        host.getHand().add(gaeos);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ga", "s5")).isNull();
        Prompt p = room.getPrompt();
        assertThat(PromptPurpose.SUMMONER_DISCOUNT.is(p.getPurpose())).isTrue();
        answer(p, "r0", "r1", "r2", "r3");
        stayBack();
        assertThat(host.getField()).contains(gaeos);
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("r0", "r3");
        assertThat(gems()).isEqualTo(8 - 6);
    }

    @Test
    void gaeosSummonsADragonFromTheHandAndThenDestroysTheWeakestEnemy() {
        CardSlot gaeos = onField(host, gaeos("ga"));
        tributes(5, "d");
        CardSlot small = dragon("sm", "Dragón Pequeño", 4, 2, 4);
        host.getHand().add(small);
        CardSlot weak = onField(guest, enemy("e1", 2));
        CardSlot strong = onField(guest, enemy("e2", 6));
        activate(gaeos, "gaeos:turno-aliado");
        answer("d0", "d1", "d2", "d3", "d4");
        answer("sm");
        settle();
        assertThat(host.getField()).contains(small);
        assertThat(guest.getDiscardPile()).contains(weak);
        assertThat(guest.getField()).contains(strong);
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void gaeosDestroysTheTopLifeWhenThereIsNoEnemy() {
        onField(host, gaeos("ga"));
        tributes(5, "d");
        host.getHand().add(dragon("sm", "Dragón Pequeño", 4, 2, 4));
        activate(host.getField().get(0), "gaeos:turno-aliado");
        answer("d0", "d1", "d2", "d3", "d4");
        answer("sm");
        settle();
        assertThat(guest.getLifeStack()).hasSize(2);
    }

    // ------------------------------------------------------------------ G12 Montañas Volcánicas

    @Test
    void montanasTributedFromTheDeckArePlayedForFree() {
        CardSlot montanas = structure("mv", "Montañas Volcánicas", 1, "G");
        host.getDeck().add(1, montanas);
        CardSlot summoner = onField(host, dragon("s1", "Dragón Uno", 1, 1, 3), BACK);
        evolve(pteroragon("pt"), summoner);
        untilOffered(montanas);
        accept(montanas);
        settle();
        assertThat(host.getField()).contains(montanas);
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void montanasExhaustToTributeTheTopCardWhenAnEnemyIsDestroyedByEffect() {
        CardSlot montanas = onField(host, structure("mv", "Montañas Volcánicas", 1, "G"), BACK);
        CardSlot summoner = onField(host, dragon("s2", "Dragón Dos", 2, 2, 4));
        onField(guest, enemy("en", 2));
        evolve(fresnagon("fr"), summoner);
        answer("en");
        untilOffered(montanas);
        accept(montanas);
        settle();
        assertThat(montanas.isTapped()).isTrue();
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("hd2");
    }

    // ------------------------------------------------------------------ G13 Dragón Templado

    private static CardSlot templado(String id) {
        CardSlot c = utility(id, "Dragón Templado", 1, "G");
        c.setLevel(2);
        return c;
    }

    @Test
    void dragonTempladoBecomesAResourceOfAnAllyWithoutOne() {
        CardSlot with = onField(host, dragon("a1", "Con Templado", 2, 2, 4), BACK);
        with.getResources().add(templado("old"));
        CardSlot without = onField(host, dragon("a2", "Sin Templado", 2, 2, 4), BACK);
        CardSlot templado = templado("dt");
        play(templado);
        assertThat(room.getPrompt().getValidIds()).containsExactly("a2");
        answer("a2");
        settle();
        assertThat(without.getResources()).containsExactly(templado);
        assertThat(host.getDiscardPile()).doesNotContain(templado);
    }

    @Test
    void dragonTempladoTributedFromTheDeckIsPlayedForFiveDragons() {
        CardSlot templado = templado("dt");
        host.getDeck().add(1, templado);
        tributes(5, "d");
        CardSlot ally = onField(host, dragon("a2", "Aliado", 2, 2, 4), BACK);
        CardSlot summoner = onField(host, dragon("s1", "Dragón Uno", 1, 1, 3), BACK);
        evolve(pteroragon("pt"), summoner);
        untilOffered(templado);
        accept(templado);
        answer("d0", "d1", "d2", "d3", "d4");
        Prompt p = room.getPrompt();
        answer(p, "a2");
        settle();
        assertThat(ally.getResources()).contains(templado);
    }

    @Test
    void dragonTempladoAsAResourceLetsAnActiveHolderAttackAtTheEndOfTheTurn() {
        CardSlot holder = onField(host, dragon("ho", "Portador", 2, 2, 6));
        holder.getResources().add(templado("dt"));
        EffectEngine.record(room, GameEvent.TURN_END, "host", null, null, null, null, null, "fin del turno");
        Chain.advance(room);
        untilOffered(holder);
        accept(holder);
        settle();
        assertThat(guest.getLifeStack()).hasSize(2);
    }

    // ------------------------------------------------------------------ G14 Embestida Aérea

    @Test
    void embestidaTributesItselfAndSortsTheTopFiveCards() {
        onField(host, dragon("dr", "Dragón", 2, 2, 4), BACK);
        CardSlot embestida = withClasses(utility("ea", "Embestida Aérea", 0, "G"), "dragón");
        play(embestida);
        assertThat(room.getPrompt().getCards()).hasSize(5);
        answer(room.getPrompt());
        settle();
        assertThat(host.getTributeZone()).contains(embestida);
        assertThat(host.getDeck()).hasSize(8);
        assertThat(host.getDeck().get(0).getInstanceId()).isEqualTo("hd6");
    }

    @Test
    void embestidaCannotBePlayedWithoutADragon() {
        CardSlot embestida = withClasses(utility("ea", "Embestida Aérea", 0, "G"), "dragón");
        host.getHand().add(embestida);
        assertThat(Summons.playByPlayer(room, "host", "ea", 50.0, 75.0)).isNotNull();
    }

    // ------------------------------------------------------------------ G15 Renacido de las Llamas

    @Test
    void renacidoSummonsADragonFromTheTributosAndGivesItALowerDragonOfEachLevel() {
        CardSlot big = dragon("b4", "Dragón Cuatro", 4, 4, 10);
        CardSlot l1 = dragon("l1", "Nivel Uno", 1, 1, 2);
        CardSlot l2 = dragon("l2", "Nivel Dos", 1, 2, 2);
        CardSlot l2b = dragon("l2b", "Nivel Dos B", 1, 2, 2);
        host.getTributeZone().addAll(List.of(big, l1, l2, l2b));
        CardSlot renacido = withClasses(utility("rl", "Renacido de las Llamas", 3, "G"), "dragón");
        play(renacido);
        answer("b4");
        answer("l1", "l2");
        settle();
        assertThat(host.getField()).contains(big);
        assertThat(big.getResources()).containsExactlyInAnyOrder(l1, l2);
        assertThat(gems()).isEqualTo(8 - 3 - 1);
    }
}
