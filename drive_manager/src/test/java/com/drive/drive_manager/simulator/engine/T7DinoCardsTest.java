package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cards in testing T7, archetype Dinosaurios - soporte lateral (2026-11-19): one test (or more) per programmed effect, from the printed text.
 * Ophidrax and Dragmorasaurus enter by a modified normal summon, as Carnirex (designer, 2026-10-25: no regulatory draw).
 */
@Tag("cards")
@Tag("t7")
class T7DinoCardsTest extends CardBatchSupport {

    private static CardSlot dino(String id, String name, Integer cost, int level, int strength) {
        CardSlot c = withClasses(card(id, name, cost, level, "G"), "dinosaurio");
        c.setColors(new ArrayList<>(List.of("G", "P")));
        c.setStrength(strength);
        return c;
    }

    private static CardSlot evolution(CardSlot c, int specialCost) {
        c.setSpecialSummonKind("evolution");
        c.setSpecialCost(specialCost);
        return c;
    }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "R");
        c.setStrength(strength);
        return c;
    }

    private void destroyedBy(CardSlot card, String by) {
        Removals.destroy(room, card, host, false, EffectEngine.CAUSE_EFFECT, by, "src", null);
        Chain.advance(room);
    }

    // ------------------------------------------------------------------ G01 Arapterax

    @Test
    void arapteraxSacrificesALevelTwoAllyToTakeATributeAndGainAGem() {
        CardSlot big = onField(host, dino("bg", "Dino Grande", 2, 2, 5), BACK);
        CardSlot old = card("ol", "Tributo", 1, 1, "R");
        host.getTributeZone().add(old);
        CardSlot arapterax = dino("ar", "Arapterax", 1, 1, 1);
        summon(arapterax);
        accept(arapterax);
        answer("bg");
        answer("ol");
        drain();
        assertThat(host.getTributeZone()).contains(big);
        assertThat(host.getHand()).contains(old);
        assertThat(gems()).isEqualTo(8 - 1 + 1);
    }

    @Test
    void arapteraxAsAResourceIsTributedForAGemWhenItsCarrierIsDestroyedByYourEffect() {
        CardSlot carrier = onField(host, dino("ca", "Portador", 2, 2, 5), BACK);
        CardSlot arapterax = dino("ar", "Arapterax", 1, 1, 1);
        carrier.getResources().add(arapterax);
        destroyedBy(carrier, "host");
        untilOffered(carrier);
        accept(carrier);
        drain();
        assertThat(host.getTributeZone()).contains(arapterax);
        assertThat(gems()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ G02 Cruorax

    @Test
    void cruoraxSacrificesAStrongerAllyForAGem() {
        CardSlot summoner = onField(host, dino("s1", "Dino Uno", 1, 1, 1));
        CardSlot strong = onField(host, dino("st", "Dino Fuerte", 3, 2, 6), BACK);
        onField(host, dino("wk", "Dino Débil", 1, 1, 2), BACK);
        CardSlot cruorax = evolution(dino("cr", "Cruorax", 2, 2, 5), 1);
        host.getHand().add(cruorax);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "cr", "s1")).isNull();
        untilOffered(cruorax);
        accept(cruorax);
        assertThat(room.getPrompt().getValidIds()).containsExactly("st");
        answer("st");
        drain();
        assertThat(host.getTributeZone()).contains(strong);
        assertThat(gems()).isEqualTo(8 - 1 + 1);
    }

    // ------------------------------------------------------------------ G03 Diceratodón

    @Test
    void diceratodonDestroyedByYourEffectPlacesALevelOneDinosaurFromTheTributos() {
        CardSlot dice = onField(host, evolution(dino("di", "Diceratodón", 3, 2, 6), 2), BACK);
        CardSlot one = dino("d1", "Dino Uno", 1, 1, 1);
        CardSlot two = dino("d2", "Dino Dos", 2, 2, 4);
        host.getTributeZone().addAll(List.of(one, two));
        destroyedBy(dice, "host");
        untilOffered(dice);
        accept(dice);
        assertThat(room.getPrompt().getValidIds()).containsExactly("d1");
        answer("d1");
        drain();
        assertThat(host.getField()).contains(one);
        assertThat(EffectEngine.inFrontLine(one)).isFalse();
    }

    @Test
    void diceratodonDestroyedByTheOpponentPlacesALevelTwoDinosaurInstead() {
        CardSlot dice = onField(host, evolution(dino("di", "Diceratodón", 3, 2, 6), 2), BACK);
        CardSlot one = dino("d1", "Dino Uno", 1, 1, 1);
        CardSlot two = dino("d2", "Dino Dos", 2, 2, 4);
        host.getTributeZone().addAll(List.of(one, two));
        destroyedBy(dice, "guest");
        untilOffered(dice);
        accept(dice);
        assertThat(room.getPrompt().getValidIds()).containsExactly("d2");
        answer("d2");
        drain();
        assertThat(host.getField()).contains(two);
    }

    // ------------------------------------------------------------------ G04 Bikeratops

    @Test
    void bikeratopsDestroyedByTheOpponentSummonsADinosaurFromTheTributosForTwoLess() {
        CardSlot bike = onField(host, evolution(dino("bk", "Bikeratops", 4, 3, 8), 2), BACK);
        CardSlot back = dino("bb", "Dino De Vuelta", 3, 2, 5);
        host.getTributeZone().add(back);
        host.getHand().add(card("hx", "Sobrante", 1, 1, "R"));
        destroyedBy(bike, "guest");
        untilOffered(bike);
        accept(bike);
        answer("hx");
        answer("bb");
        drain();
        assertThat(host.getField()).contains(back);
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void bikeratopsDestroyedByYourEffectReducesOnlyOne() {
        CardSlot bike = onField(host, evolution(dino("bk", "Bikeratops", 4, 3, 8), 2), BACK);
        CardSlot back = dino("bb", "Dino De Vuelta", 3, 2, 5);
        host.getTributeZone().add(back);
        host.getHand().add(card("hx", "Sobrante", 1, 1, "R"));
        destroyedBy(bike, "host");
        untilOffered(bike);
        accept(bike);
        answer("hx");
        answer("bb");
        drain();
        assertThat(gems()).isEqualTo(8 - 2);
    }

    // ------------------------------------------------------------------ G05 Laniapalus

    @Test
    void laniapalusSacrificesAnActiveAllyAndSummonsALevelTwoDinosaurForTwoLess() {
        CardSlot summoner = onField(host, dino("s2", "Dino Dos", 2, 2, 4));
        CardSlot victim = onField(host, dino("vi", "Dino Víctima", 1, 1, 3), BACK);
        CardSlot next = dino("nx", "Dino Siguiente", 3, 2, 5);
        host.getHand().addAll(List.of(next, card("hx", "Sobrante", 1, 1, "R")));
        CardSlot lania = evolution(dino("la", "Laniapalus", 4, 3, 9), 3);
        host.getHand().add(lania);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "la", "s2")).isNull();
        untilOffered(lania);
        accept(lania);
        answer("vi");
        accept(lania);
        answer("hx");
        answer("nx");
        drain();
        assertThat(host.getTributeZone()).contains(victim);
        assertThat(host.getField()).contains(next);
        // Evolución 3 + 3 - 2
        assertThat(gems()).isEqualTo(8 - 3 - 1);
        assertThat(summoner).isNotNull();
    }

    @Test
    void laniapalusSacrificingAnEnemyDoesNotSummon() {
        CardSlot weak = onField(guest, enemy("we", 3));
        CardSlot next = dino("nx", "Dino Siguiente", 3, 2, 5);
        host.getHand().add(next);
        CardSlot lania = dino("la", "Laniapalus", 4, 3, 9);
        summonByEffectToFront(lania);
        untilOffered(lania);
        accept(lania);
        answer("we");
        drain();
        assertThat(guest.getTributeZone()).contains(weak);
        assertThat(host.getHand()).contains(next);
    }

    // ------------------------------------------------------------------ G06 Ophidrax

    @Test
    void ophidraxSacrificesAnActiveDinosaurToEnterTheFrontLineForLess() {
        CardSlot fodder = onField(host, dino("fo", "Dino Sacrificio", 4, 3, 8), BACK);
        CardSlot ophidrax = dino("op", "Ophidrax", 6, 4, 12);
        host.getHand().add(ophidrax);
        assertThat(Summons.normalSummon(room, "host", "op", 50.0, 75.0)).isNull();
        answer("fo");
        drain();
        assertThat(host.getField()).contains(ophidrax);
        assertThat(EffectEngine.inFrontLine(ophidrax)).isTrue();
        assertThat(host.getTributeZone()).contains(fodder);
        assertThat(gems()).isEqualTo(8 - 2);
    }

    @Test
    void ophidraxDestroyedBecomesAResourceThatGivesReactivarToALevelFiveDinosaur() {
        CardSlot ophidrax = onField(host, dino("op", "Ophidrax", 6, 4, 12), BACK);
        CardSlot big = onField(host, dino("bg", "Dino Cinco", 8, 5, 13), BACK);
        destroyedBy(ophidrax, "guest");
        untilOffered(ophidrax);
        accept(ophidrax);
        answer("bg");
        drain();
        assertThat(big.getResources()).contains(ophidrax);
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(big)).contains("Reactivar");
    }

    // ------------------------------------------------------------------ G07 Dragmorasaurus

    @Test
    void dragmorasaurusSacrificesUpToThreeActiveDinosaursForTheSumOfTheirCosts() {
        onField(host, dino("a", "Dino A", 4, 3, 8), BACK);
        onField(host, dino("b", "Dino B", 3, 2, 6), BACK);
        onField(host, dino("c", "Dino C", 3, 2, 6), BACK);
        CardSlot dragmo = dino("dm", "Dragmorasaurus", 11, 5, 13);
        host.getHand().add(dragmo);
        assertThat(Summons.normalSummon(room, "host", "dm", 50.0, 75.0)).isNull();
        assertThat(room.getPrompt().getMax()).isEqualTo(3);
        answer("a", "b", "c");
        drain();
        assertThat(host.getField()).containsExactly(dragmo);
        assertThat(EffectEngine.inFrontLine(dragmo)).isTrue();
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void dragmorasaurusAttackingSacrificesUpToFiveLevelsAndReactivatesWithTwoActiveAllies() {
        CardSlot dragmo = onField(host, dino("dm", "Dragmorasaurus", 11, 5, 13));
        onField(host, dino("a", "Dino A", 2, 2, 5), BACK);
        onField(host, dino("b", "Dino B", 2, 2, 5), BACK);
        CardSlot enemy = onField(guest, enemy("en", 3));
        onField(guest, enemy("e2", 3));
        assertThat(CombatRules.attackByPlayer(room, "host", "dm", null)).isNull();
        Prompt choose = room.getPrompt();
        assertThat(choose.getMaxLevelSum()).isEqualTo(5);
        assertThat(Answers.answer(room, "host", choose.getId(), List.of("a", "b", "en", "e2"), List.of())).isNotNull();
        answer(room.getPrompt(), "a", "b", "en");
        assertThat(dragmo.isTapped()).isFalse();
        drain();
        assertThat(guest.getTributeZone()).contains(enemy);
    }
}
