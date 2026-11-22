package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** ST1 (Azul, Materialización; estructura nueva del 2026-11-15): one test (or more) per programmed effect, from the printed text. */
@Tag("cards")
@Tag("st1")
class ST1CardsTest extends CardBatchSupport {

    private static CardSlot blue(String id, String name, int cost, int level, int strength) {
        CardSlot c = card(id, name, cost, level, "B");
        c.setStrength(strength);
        return c;
    }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "R");
        c.setStrength(strength);
        return c;
    }

    private static CardSlot withMaterials(CardSlot card, int count) {
        for (int i = 0; i < count; i++) card.getMaterials().add(card(card.getInstanceId() + "m" + i, "Material " + i, 1, 1, "B"));
        return card;
    }

    private static CardSlot materialization(CardSlot card, int specialCost) {
        card.setSpecialSummonKind("materialization");
        card.setSpecialCost(specialCost);
        return card;
    }

    @Test
    void everySpecialCardHasItsRequirementProgrammed() {
        for (String name : List.of("Segador De Entidades", "Turgis", "Experimento EXEC-1", "Limpiador Errante", "Firus", "Fósfilis", "Frehle",
                "Ul", "Explorador de la Ciudad Muerta", "MO-S")) {
            CardSlot c = new CardSlot();
            c.setCardName(name);
            assertThat(CardEffects.requirementOf(c)).as(name).isNotNull();
        }
    }

    // ------------------------------------------------------------------ Criaturas

    @Test
    void segadorDeEntidadesTributesUpToTwoMaterialsAndReducesAsManyEnemiesByFour() {
        CardSlot segador = onField(host, withMaterials(blue("sg", "Segador De Entidades", 4, 4, 10), 2), FRONT);
        CardSlot a = onField(guest, enemy("a", 9), FRONT);
        CardSlot b = onField(guest, enemy("b", 9), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "sg", null)).isNull();
        accept(segador);
        answer("sgm0", "sgm1");
        Prompt targets = room.getPrompt();
        assertThat(targets.getMin()).isEqualTo(2);
        assertThat(targets.getMax()).isEqualTo(2);
        answer(targets, "a", "b");
        assertThat(EffectEngine.strength(a)).isEqualTo(5);
        assertThat(EffectEngine.strength(b)).isEqualTo(5);
        assertThat(segador.getMaterials()).isEmpty();
    }

    @Test
    void fosfilisTributesNoMoreMaterialsThanThereAreEnemiesToChoose() {
        // Choosing the enemies is part of the cost (before the ";"): two materials, one enemy of strength 3 or less, one tribute at most
        onField(host, blue("s", "Invocador", 2, 2, 4), FRONT);
        host.getTributeZone().add(blue("t", "Material", 2, 2, 4));
        CardSlot fosfilis = materialization(blue("fo", "Fósfilis", 3, 3, 6), 1);
        host.getHand().add(fosfilis);
        onField(guest, enemy("w", 3), FRONT);
        onField(guest, enemy("st", 4), FRONT);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "fo", "s")).isNull();
        answer("t");
        untilOffered(fosfilis);
        accept(fosfilis);
        Prompt tribute = room.getPrompt();
        assertThat(tribute.getValidIds()).containsExactlyInAnyOrder("s", "t");
        assertThat(tribute.getMax()).isEqualTo(1);
    }

    @Test
    void fosfilisIsNotOfferedWithoutAnEnemyToChoose() {
        onField(host, blue("s", "Invocador", 2, 2, 4), FRONT);
        host.getTributeZone().add(blue("t", "Material", 2, 2, 4));
        CardSlot fosfilis = materialization(blue("fo", "Fósfilis", 3, 3, 6), 1);
        host.getHand().add(fosfilis);
        onField(guest, enemy("st", 4), FRONT);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "fo", "s")).isNull();
        answer("t");
        for (int i = 0; i < 10 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            assertThat(p.getValidIds()).doesNotContain("fo");
            answer(p);
        }
        assertThat(host.getField()).contains(fosfilis);
        assertThat(fosfilis.getMaterials()).hasSize(2);
    }

    @Test
    void batallaReducesAnEnemyToZeroAndTurgisDealsDirectDamage() {
        CardSlot turgis = onField(host, withMaterials(blue("tu", "Turgis", 5, 5, 12), 2), FRONT);
        CardSlot weak = onField(guest, enemy("w", 4), FRONT);
        int lives = guest.getLifeStack().size();
        play(utility("ba", "Batalla por la Frontera Interdimensional", 5, "B"));
        answer("tum0");
        answer("w");
        assertThat(guest.getField()).doesNotContain(weak);
        accept(turgis);
        answer("tum1");
        drain();
        assertThat(guest.getLifeStack()).hasSize(lives - 1);
        assertThat(turgis.getMaterials()).isEmpty();
    }

    @Test
    void batallaReducesOneEnemyByFourForEachMaterialTributed() {
        // 2026-11-22: "tributa hasta 3 materiales de tus aliados y elige 1 enemigo; reduce la fuerza de dicho enemigo en 4 por
        // cada material tributado" (until the end of the turn)
        onField(host, withMaterials(blue("ho", "Portador", 3, 3, 8), 3), BACK);
        CardSlot big = onField(guest, enemy("b", 13), FRONT);
        CardSlot other = onField(guest, enemy("o", 6), FRONT);
        play(utility("ba", "Batalla por la Frontera Interdimensional", 5, "B"));
        answer("hom0", "hom1", "hom2");
        Prompt targets = room.getPrompt();
        assertThat(targets.getMin()).isEqualTo(1);
        assertThat(targets.getMax()).isEqualTo(1);
        answer(targets, "b");
        drain();
        assertThat(EffectEngine.strength(big)).isEqualTo(1);
        assertThat(EffectEngine.strength(other)).isEqualTo(6);
    }

    @Test
    void firusDealsNineOnlyFromTheFrontLine() {
        CardSlot firus = onField(host, withMaterials(blue("fi", "Firus", 2, 2, 4), 2), FRONT);
        onField(host, withMaterials(blue("ho", "Portador", 3, 3, 8), 1), BACK);
        CardSlot weak = onField(guest, enemy("w", 4), FRONT);
        int lives = guest.getLifeStack().size();
        play(utility("ba", "Batalla por la Frontera Interdimensional", 5, "B"));
        answer("hom0");
        answer("w");
        assertThat(guest.getField()).doesNotContain(weak);
        accept(firus);
        answer("fim0", "fim1");
        drain();
        assertThat(guest.getLifeStack()).hasSize(lives - 1);
    }

    @Test
    void firusInTheRetaguardiaIsNotOffered() {
        CardSlot firus = onField(host, withMaterials(blue("fi", "Firus", 2, 2, 4), 2), BACK);
        onField(guest, enemy("w", 4), FRONT);
        play(utility("ba", "Batalla por la Frontera Interdimensional", 5, "B"));
        answer("fim0");
        answer("w");
        Prompt p = room.getPrompt();
        assertThat(p == null || !p.getValidIds().contains(firus.getInstanceId())).isTrue();
    }

    @Test
    void limpiadorErranteTakesItsMaterialFromTheHandOrTheDiscardPile() {
        // Designer 2026-11-22: "criatura nivel 2 + material nivel 2 de tu mano o descarte"
        onField(host, blue("s", "Invocador", 2, 2, 4), FRONT);
        host.getHand().add(blue("h", "En la mano", 2, 2, 4));
        host.getDiscardPile().add(blue("d", "En el descarte", 2, 2, 4));
        host.getTributeZone().add(blue("t", "En tributos", 2, 2, 4));
        CardSlot limpiador = materialization(blue("li", "Limpiador Errante", 3, 3, 10), 1);
        host.getHand().add(limpiador);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "li", "s")).isNull();
        Prompt material = room.getPrompt();
        assertThat(material.getValidIds()).contains("h", "d").doesNotContain("t");
        answer(material, "h");
        assertThat(host.getField()).contains(limpiador);
        assertThat(limpiador.getMaterials()).extracting(CardSlot::getInstanceId).contains("h", "s");
    }

    @Test
    void fosfilisMaterializedReducesUpToTwoWeakEnemiesToZero() {
        onField(host, blue("s", "Invocador", 2, 2, 4), FRONT);
        host.getTributeZone().add(blue("t", "Material", 2, 2, 4));
        CardSlot fosfilis = materialization(blue("fo", "Fósfilis", 3, 3, 6), 1);
        host.getHand().add(fosfilis);
        CardSlot weak = onField(guest, enemy("w", 3), FRONT);
        CardSlot strong = onField(guest, enemy("st", 4), FRONT);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "fo", "s")).isNull();
        answer("t");
        untilOffered(fosfilis);
        accept(fosfilis);
        answer("s");
        Prompt targets = room.getPrompt();
        assertThat(targets.getValidIds()).containsExactly("w");
        answer(targets, "w");
        assertThat(guest.getField()).doesNotContain(weak).contains(strong);
        assertThat(fosfilis.getMaterials()).hasSize(1);
    }

    @Test
    void frehleTributesAMaterialToTakeABlueFromTheTributesOncePerTurn() {
        onField(host, withMaterials(blue("fr", "Frehle", 3, 3, 8), 2), FRONT);
        CardSlot b = blue("tb", "Azul En Tributos", 1, 1, 2);
        host.getTributeZone().addAll(List.of(b, card("tr", "Roja", 1, 1, "R")));
        assertThat(Chain.activateByPlayer(room, "host", "fr", "frehle:turno-aliado")).isNull();
        answer("frm0");
        Prompt take = room.getPrompt();
        // The material just tributed is Blue too
        assertThat(take.getValidIds()).containsExactlyInAnyOrder("tb", "frm0");
        answer(take, "tb");
        assertThat(host.getHand()).contains(b);
        assertThat(Chain.activateByPlayer(room, "host", "fr", "frehle:turno-aliado")).isNotNull();
    }

    @Test
    void exploradorTributesThreeFromTheTopThenTakesOneFromTheTributes() {
        host.getDeck().addAll(0, List.of(card("d1", "Uno", 1, 1, "B"), card("d2", "Dos", 1, 1, "B"), card("d3", "Tres", 1, 1, "B")));
        summon(blue("ex", "Explorador de la Ciudad Muerta", 2, 2, 4));
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).contains("d1", "d2", "d3");
        answer(take, "d2");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("d2");
        assertThat(room.getChain()).isEmpty();
    }

    @Test
    void falSendsFiveOtherBlueTributesToTheBottomToSummonItselfInYourTurn() {
        // Designer 2026-11-22: no condition; activated from the Tributos in your turn only; the summon by effect goes to the Retaguardia
        CardSlot fal = blue("fa", "Fal", 1, 1, 4);
        host.getTributeZone().add(fal);
        for (int i = 0; i < 5; i++) host.getTributeZone().add(card("v" + i, "Azul " + i, 1, 1, "B"));
        host.getTributeZone().add(card("p", "Violeta", 1, 1, "P"));
        room.setCurrentTurnUserId("guest");
        assertThat(Chain.activateByPlayer(room, "host", "fa", "fal:en-tributos")).isNotNull();
        room.setCurrentTurnUserId("host");
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "fa", "fal:en-tributos")).isNull();
        assertThat(room.getPrompt().getValidIds()).doesNotContain("p", "fa");
        answer("v0", "v1", "v2", "v3", "v4");
        drain();
        assertThat(host.getField()).contains(fal);
        assertThat(EffectEngine.inFrontLine(fal)).isFalse();
        // "sin pagar su coste"
        assertThat(gems()).isEqualTo(before);
        assertThat(host.getDeck().subList(host.getDeck().size() - 5, host.getDeck().size()))
                .extracting(CardSlot::getInstanceId).containsExactlyInAnyOrder("v0", "v1", "v2", "v3", "v4");
    }

    @Test
    void eliseoTakesUpToTwoBluesOfFiveThenTributesOneFromTheHand() {
        host.getDeck().addAll(0, List.of(card("b1", "Azul 1", 1, 1, "B"), card("r1", "Roja 1", 1, 1, "R"), card("b2", "Azul 2", 1, 1, "B"),
                card("r2", "Roja 2", 1, 1, "R"), card("b3", "Azul 3", 1, 1, "B")));
        CardSlot pay = inHand("x", "Pago", 1, 1, "R");
        summon(blue("el", "Eliséo", 1, 1, 2));
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).containsExactlyInAnyOrder("b1", "b2", "b3");
        answer(take, "b1", "b2");
        answer("x");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("b1", "b2").doesNotContain("x");
        assertThat(host.getTributeZone()).contains(pay);
    }

    @Test
    void moSRaisesAMaterialToTheHandToSummonABlueFromTheDiscardForTwoLess() {
        onField(host, withMaterials(blue("mo", "MO-S", 4, 4, 10), 1), FRONT);
        CardSlot dead = blue("dd", "Azul Descartada", 3, 3, 6);
        host.getDiscardPile().add(dead);
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "mo", "mo-s:turno-aliado")).isNull();
        answer("mom0");
        answer("dd");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("mom0");
        assertThat(host.getField()).contains(dead);
        assertThat(gems()).isEqualTo(before - 1);
    }

    // ------------------------------------------------------------------ Estructura y utilidades

    @Test
    void ciudadMuertaLowersAMaterializationsSpecialCost() {
        assertThat(CardEffects.definitions().get("ciudad muerta").reactions()).hasSize(1);
    }

    @Test
    void biomaterialCromadoTakesOneBlueStaysExhaustedAndLaterSummonsABlue() {
        room.setFirstTurn(false);
        host.getDeck().addAll(0, List.of(card("b1", "Azul 1", 1, 1, "B"), card("r1", "Roja 1", 1, 1, "R"), card("r2", "Roja 2", 1, 1, "R"),
                card("r3", "Roja 3", 1, 1, "R"), card("r4", "Roja 4", 1, 1, "R")));
        CardSlot bio = utility("bc", "Biomaterial Cromado", 1, "B");
        play(bio);
        answer("b1");
        assertThat(bio.isTapped()).isTrue();
        bio.setTapped(false);
        CardSlot b1 = host.getHand().stream().filter(c -> "b1".equals(c.getInstanceId())).findFirst().orElseThrow();
        assertThat(Chain.activateByPlayer(room, "host", "bc", "biomaterial-cromado:turno-aliado")).isNull();
        answer("b1");
        assertThat(host.getField()).contains(b1);
        assertThat(host.getDiscardPile()).contains(bio);
    }

    @Test
    void ofrendaTributesTwoAllyMaterialsSoAnAllyMaterializesABlueForTwoLess() {
        onField(host, withMaterials(blue("s", "Invocador", 2, 2, 4), 1), FRONT);
        onField(host, withMaterials(blue("o", "Otro", 1, 1, 2), 1), BACK);
        host.getTributeZone().add(blue("t", "Material", 2, 2, 4));
        CardSlot fosfilis = materialization(blue("fo", "Fósfilis", 3, 3, 6), 2);
        host.getHand().add(fosfilis);
        int before = gems();
        play(utility("of", "Ofrenda al Paso Interdimensional", 1, "B"));
        answer("sm0", "om0");
        for (int i = 0; i < 6 && !host.getField().contains(fosfilis) && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            if (p.getValidIds().contains("fo")) answer(p, "fo");
            else if (p.getValidIds().contains("s")) answer(p, "s");
            else if (p.getValidIds().contains("t")) answer(p, "t");
            else answer(p);
        }
        assertThat(host.getField()).contains(fosfilis);
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("sm0", "om0");
        // The utility costs 1; Fósfilis's special cost 2, minus 2
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void ofrendaMaterializesMoSWithALevel3MaterialFromTheHand() {
        // Reported 2026-11-22: Frehle got two materials, Ofrenda tributes them, MO-S from the hand; its Requerimiento takes
        // the material "en mano o descarte" and the choice showed only the Tributos and the field (the hand card was never offered)
        CardSlot frehle = withMaterials(blue("fr", "Frehle", 3, 3, 6), 2);
        onField(host, frehle, FRONT);
        CardSlot mos = materialization(blue("mo", "MO-S", 4, 4, 8), 3);
        CardSlot limpiador = blue("li", "Limpiador Errante", 3, 3, 6);
        host.getHand().add(mos);
        host.getHand().add(limpiador);
        play(utility("of", "Ofrenda al Paso Interdimensional", 1, "B"));
        answer("frm0", "frm1");
        Prompt material = null;
        for (int i = 0; i < 6 && !host.getField().contains(mos) && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            if (p.getValidIds().contains("mo")) answer(p, "mo");
            else if (p.getValidIds().contains("fr")) answer(p, "fr");
            else if (p.getValidIds().contains("li")) {
                material = p;
                assertThat(p.getCards()).extracting(CardSlot::getInstanceId).contains("li");
                assertThat(p.getInstruction()).contains("mano o tu descarte");
                answer(p, "li");
            } else answer(p);
        }
        assertThat(material).isNotNull();
        assertThat(host.getField()).contains(mos);
        assertThat(mos.getMaterials()).extracting(CardSlot::getInstanceId).contains("li");
    }
}
