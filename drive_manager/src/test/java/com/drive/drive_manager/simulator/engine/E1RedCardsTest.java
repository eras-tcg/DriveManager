package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** E1 Rojo and the 21st cards of Azul, Verde and Violeta (licuado del 2026-11-17), from the printed text. */
class E1RedCardsTest extends CardBatchSupport {

    private static CardSlot creature(String id, String name, int cost, int level, String color, int strength) {
        CardSlot c = card(id, name, cost, level, color);
        c.setStrength(strength);
        return c;
    }

    private static CardSlot exhausted(CardSlot c) {
        c.setTapped(true);
        return c;
    }

    @Test
    void everySpecialCardHasItsRequirementProgrammed() {
        for (String name : List.of("Solfaris", "Madame Feris", "Campeón de los Muertos Vivientes", "Hacha de Primera Línea",
                "Peón de Espadas Dobles", "Espada de la Legión de Primera Línea", "Caballería Acorazada", "El Ejercito de un Hombre",
                "Caballería Abanderada")) {
            CardSlot c = new CardSlot();
            c.setCardName(name);
            assertThat(CardEffects.requirementOf(c)).as(name).isNotNull();
        }
    }

    // ------------------------------------------------------------------ 21

    @Test
    void solfarisTributesAMaterialAndACardToSummonABlueFromTheTributesForTwoLess() {
        CardSlot solfaris = creature("so", "Solfaris", 5, 5, "B", 10);
        solfaris.getMaterials().add(card("som0", "Material", 1, 1, "B"));
        onField(host, solfaris, FRONT);
        inHand("fd", "Descarte", 1, 1, "R");
        CardSlot blue = creature("tb", "Azul En Tributos", 3, 3, "B", 7);
        host.getTributeZone().add(blue);
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "so", "solfaris:turno-aliado")).isNull();
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("som0")) answer("som0");
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("fd")) answer("fd");
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("tb")) answer("tb");
        drain();
        assertThat(host.getField()).contains(blue);
        assertThat(EffectEngine.inFrontLine(blue)).isTrue();
        assertThat(gems()).isEqualTo(before - 1);
        assertThat(Chain.activateByPlayer(room, "host", "so", "solfaris:turno-aliado")).isNotNull();
    }

    @Test
    void solfarisInTheRetaguardiaCannotBeActivated() {
        CardSlot solfaris = creature("so", "Solfaris", 5, 5, "B", 10);
        solfaris.getMaterials().add(card("som0", "Material", 1, 1, "B"));
        onField(host, solfaris, BACK);
        inHand("fd", "Descarte", 1, 1, "R");
        host.getTributeZone().add(creature("tb", "Azul En Tributos", 3, 3, "B", 7));
        assertThat(Chain.activateByPlayer(room, "host", "so", "solfaris:turno-aliado")).isNotNull();
    }

    @Test
    void madameFerisReturnsAnExhaustedAllyAndAWeakerExhaustedEnemyToTheHand() {
        CardSlot feris = onField(host, creature("mf", "Madame Feris", 7, 5, "G", 13), FRONT);
        CardSlot ally = onField(host, exhausted(creature("al", "Aliado", 3, 3, "G", 6)), FRONT);
        CardSlot weak = onField(guest, exhausted(creature("w", "Débil", 1, 1, "R", 5)), FRONT);
        onField(guest, exhausted(creature("s", "Fuerte", 3, 3, "R", 6)), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "mf", null)).isNull();
        untilOffered(feris);
        accept(feris);
        answer("al");
        Prompt foe = room.getPrompt();
        assertThat(foe.getValidIds()).containsExactly("w");
        answer(foe, "w");
        drain();
        assertThat(host.getHand()).contains(ally);
        assertThat(guest.getHand()).contains(weak);
    }

    @Test
    void campeonDeLosMuertosVivientesComesBackWithAVioletLevelOneAtTheEndOfTheTurn() {
        CardSlot campeon = onField(host, creature("cm", "Campeón de los Muertos Vivientes", 7, 5, "P", 13), FRONT);
        inHand("f1", "Mano 1", 1, 1, "R");
        inHand("f2", "Mano 2", 1, 1, "R");
        CardSlot one = creature("v1", "Violeta Nivel 1", 1, 1, "P", 2);
        host.getDiscardPile().add(one);
        onField(guest, exhausted(creature("big", "Enemigo", 7, 5, "R", 20)), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "cm", "big")).isNull();
        untilOffered(campeon);
        accept(campeon);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("f1")) answer("f1", "f2");
        drain();
        assertThat(host.getField()).doesNotContain(campeon);
        TurnRules.turnEnding(room, "host");
        for (int i = 0; i < 10 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            answer(p, p.getValidIds().contains("v1") ? new String[] {"v1"} : new String[0]);
        }
        assertThat(host.getField()).contains(campeon, one);
        assertThat(EffectEngine.inFrontLine(campeon)).isTrue();
        assertThat(campeon.isTapped()).isTrue();
        assertThat(one.isTapped()).isTrue();
    }

    // ------------------------------------------------------------------ Rojo

    @Test
    void legionarioDeEspadasDoblesGivesOneToRedOrArmadaAllies() {
        onField(host, withClasses(creature("le", "Legionario De Espadas Dobles", 1, 1, "R", 3), "armada"), FRONT);
        CardSlot red = onField(host, creature("r", "Roja", 1, 1, "R", 2), FRONT);
        CardSlot armada = onField(host, withClasses(creature("a", "Armada Azul", 1, 1, "B", 2), "armada"), FRONT);
        CardSlot other = onField(host, creature("b", "Azul", 1, 1, "B", 2), FRONT);
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(red)).isEqualTo(3);
        assertThat(EffectEngine.strength(armada)).isEqualTo(3);
        assertThat(EffectEngine.strength(other)).isEqualTo(2);
    }

    @Test
    void armaduraErranteDiscardsOneToTakeARedFromTheDiscard() {
        CardSlot armadura = inHand("ae", "Armadura Errante", 1, 1, "R");
        inHand("bh", "Azul De Mano", 1, 1, "B");
        CardSlot red = card("dr", "Roja En Descarte", 1, 1, "R");
        host.getDiscardPile().add(red);
        summon(armadura);
        accept(armadura);
        answer("bh");
        if (room.getPrompt() != null) answer("dr");
        assertThat(host.getHand()).contains(red);
    }

    @Test
    void espadasSinNombreTributesACopyToGoToTheRetaguardia() {
        CardSlot espadas = inHand("e1", "Espadas sin Nombre", 1, 1, "R");
        CardSlot copy = inHand("e2", "Espadas sin Nombre", 1, 1, "R");
        assertThat(Chain.activateByPlayer(room, "host", "e1", "espadas-sin-nombre:en-mano")).isNull();
        if (room.getPrompt() != null) answer("e2");
        drain();
        assertThat(host.getField()).contains(espadas);
        assertThat(host.getTributeZone()).contains(copy);
    }

    @Test
    void espadaDeLaLegionSummonsARedOfCostOneToTheFrontAndGainsTwo() {
        CardSlot espada = creature("el", "Espada de la Legión de Primera Línea", 3, 3, "R", 9);
        CardSlot cheap = inHand("c1", "Roja Coste 1", 1, 1, "R");
        inHand("c2", "Roja Coste 2", 2, 2, "R");
        summon(espada);
        accept(espada);
        Prompt pick = room.getPrompt();
        if (pick != null) {
            assertThat(pick.getValidIds()).containsExactly("c1");
            answer(pick, "c1");
        }
        drain();
        assertThat(host.getField()).contains(cheap);
        assertThat(EffectEngine.inFrontLine(cheap)).isTrue();
        assertThat(EffectEngine.strength(espada)).isEqualTo(11);
    }

    @Test
    void elEjercitoDeUnHombreForcesTheOpponentToBlock() {
        onField(host, creature("ej", "El Ejercito de un Hombre", 6, 5, "R", 14), FRONT);
        CardSlot a = onField(guest, creature("a", "Activa A", 1, 1, "B", 2), FRONT);
        onField(guest, creature("b", "Activa B", 1, 1, "B", 3), FRONT);
        EffectEngine.refreshContinuous(room);
        assertThat(CombatRules.attackByPlayer(room, "host", "ej", null)).isNull();
        Prompt block = null;
        for (int i = 0; i < 10; i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                Chain.advance(room);
                continue;
            }
            if ("guest".equals(p.getPlayerId())) {
                block = p;
                break;
            }
            Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
        assertThat(block).isNotNull();
        assertThat(block.getMin()).isEqualTo(1);
        assertThat(Answers.answer(room, "guest", block.getId(), List.of(), List.of())).isNotNull();
        answer(block, "a");
        drain();
        assertThat(guest.getField()).doesNotContain(a);
    }

    @Test
    void caballeriaAbanderadaReactivatesItselfAndUpToTwoAlliesAtTheEndOfTheTurn() {
        CardSlot caballeria = onField(host, exhausted(creature("ca", "Caballería Abanderada", 8, 5, "R", 13)), FRONT);
        CardSlot a = onField(host, exhausted(creature("a", "A", 1, 1, "R", 2)), FRONT);
        CardSlot b = onField(host, exhausted(creature("b", "B", 1, 1, "R", 2)), FRONT);
        TurnRules.turnEnding(room, "host");
        for (int i = 0; i < 10 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            answer(p, p.getValidIds().contains("a") ? new String[] {"a", "b"} : new String[0]);
        }
        assertThat(caballeria.isTapped()).isFalse();
        assertThat(a.isTapped()).isFalse();
        assertThat(b.isTapped()).isFalse();
    }

    @Test
    void caballeriaAbanderadaPromotesFromTheHandForTwoLess() {
        onField(host, creature("ca", "Caballería Abanderada", 8, 5, "R", 13), FRONT);
        CardSlot summoner = onField(host, creature("s1", "Invocador", 1, 1, "R", 2), FRONT);
        CardSlot promo = inHand("pr", "Ascenso Rojo", 2, 2, "R");
        promo.setStrength(6);
        promo.setSpecialSummonKind("promotion");
        promo.setSpecialCost(2);
        promo.setRequirement("criatura nivel 1.");
        Req.program(promo);
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "ca", "caballeria-abanderada:turno-aliado")).isNull();
        for (int i = 0; i < 6 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            if (p.getValidIds().contains("pr")) answer(p, "pr");
            else if (p.getValidIds().contains("s1")) answer(p, "s1");
            else answer(p);
        }
        assertThat(host.getField()).contains(promo);
        assertThat(host.getField()).doesNotContain(summoner);
        assertThat(gems()).isEqualTo(before);
    }

    @Test
    void dueloDisparejoTheChosenAllyAttacksTheChosenWeakerEnemy() {
        CardSlot ally = onField(host, creature("al", "Aliado", 3, 3, "R", 7), FRONT);
        CardSlot weak = onField(guest, creature("w", "Débil", 1, 1, "B", 5), FRONT);
        CardSlot other = onField(guest, creature("o", "Otro", 1, 1, "B", 4), FRONT);
        play(utility("dd", "Duelo Disparejo", 2, "R"));
        answer("al");
        Prompt foe = room.getPrompt();
        assertThat(foe.getValidIds()).containsExactlyInAnyOrder("w", "o");
        answer(foe, "w");
        drain();
        assertThat(guest.getField()).doesNotContain(weak).contains(other);
        assertThat(ally.isTapped()).isFalse();
    }
}
