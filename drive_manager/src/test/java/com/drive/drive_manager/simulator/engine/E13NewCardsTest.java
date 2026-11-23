package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.actions.TurnActions;
import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** E1.3 (new E1, sub-edition 3, programmed 2026-11-22): one test (or more) per card, from the printed text. */
@Tag("cards")
@Tag("e13")
class E13NewCardsTest extends CardBatchSupport {

    /** A creature of that strength and classes. */
    private static CardSlot creature(String id, String name, int cost, int level, String color, int strength, String... classes) {
        CardSlot c = withClasses(card(id, name, cost, level, color), classes);
        c.setStrength(strength);
        return c;
    }

    private static CardSlot special(CardSlot c, String kind, int specialCost) {
        c.setSpecialSummonKind(kind);
        c.setSpecialCost(specialCost);
        return c;
    }

    /** The script id of an effect the player can activate now on that card, by the start of its id. */
    private String activatable(String instanceId, String idStart) {
        return Chain.activatable(room, "host").stream()
                .filter(a -> instanceId.equals(a.instanceId()) && a.scriptId().startsWith(idStart))
                .map(Chain.Activatable::scriptId).findFirst().orElse(null);
    }

    private void activate(String instanceId, String idStart) {
        String script = activatable(instanceId, idStart);
        assertThat(script).as("se puede activar " + idStart).isNotNull();
        assertThat(Chain.activateByPlayer(room, "host", instanceId, script)).isNull();
    }

    /** Summoned by an effect into the Retaguardia (a test card that summons it). */
    private void summonByEffectToBack(CardSlot card) {
        CardEffects.register("invocador trasero", new EffectScript("invocador-trasero:en-invocacion", EffectScript.Moment.ON_SUMMON, true,
                "En Invocación", "Invoca 1 criatura de tu mano sin pagar su coste.",
                List.of(Compose.summonFromZone("hand", CardFilter.creatures().named(card.getCardName()), Compose.FREE, false))));
        host.getHand().addAll(List.of(card, TestTable.card("it", "Invocador Trasero", 0, 1, "R")));
        assertThat(Summons.normalSummon(room, "host", "it", 50.0, 75.0)).isNull();
        answer(room.getPrompt(), card.getInstanceId());
    }

    // ------------------------------------------------------------------ Azul

    @Test
    void protoL8TributesTwoMaterialsAtTheStartOfTheOpponentsTurnToReactivate() {
        CardSlot proto = onField(host, creature("pl", "Proto-L8 Soldado Invernal", 6, 5, "B", 13, "autómata", "retador"));
        proto.getMaterials().addAll(List.of(card("m1", "Material Uno", 1, 4, "B"), card("m2", "Material Dos", 1, 4, "B")));
        proto.setTapped(true);
        TurnActions.passTurn(room, "host");
        untilOffered(proto);
        accept(proto);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("m1")) answer("m1", "m2");
        assertThat(proto.isTapped()).isFalse();
        assertThat(proto.getMaterials()).isEmpty();
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("m1", "m2");
    }

    @Test
    void protoL8IsNotOfferedAtTheStartOfItsOwnTurnNorWithoutTwoMaterials() {
        CardSlot proto = onField(host, creature("pl", "Proto-L8 Soldado Invernal", 6, 5, "B", 13));
        proto.getMaterials().add(card("m1", "Material Uno", 1, 4, "B"));
        proto.setTapped(true);
        TurnActions.passTurn(room, "host");
        drain();
        assertThat(proto.isTapped()).isTrue();
        assertThat(proto.getMaterials()).hasSize(1);
    }

    @Test
    void protoL8IsMaterializedWithALevelFourAndALevelFourMaterialFromTheHand() {
        CardSlot proto = special(creature("pl", "Proto-L8 Soldado Invernal", 6, 5, "B", 13), "materialization", 2);
        host.getHand().add(proto);
        onField(host, card("s4", "Nivel Cuatro", 4, 4, "B"));
        assertThat(SpecialRequirements.canSpecial(room, "host", proto)).isFalse();
        host.getHand().add(card("h4", "Mano Cuatro", 4, 4, "B"));
        assertThat(SpecialRequirements.canSpecial(room, "host", proto)).isTrue();
    }

    @Test
    void nexoEnergeticoExhaustsItselfAndTakesABlueCardFromTheTributes() {
        host.getTributeZone().addAll(List.of(card("b1", "Azul", 1, 1, "B"), card("r1", "Roja", 1, 1, "R")));
        CardSlot nexo = structure("nx", "Nexo Energético Abandonado", 1, "B");
        play(nexo);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("nx") && !room.getPrompt().getValidIds().contains("b1")) accept(nexo);
        assertThat(room.getPrompt().getValidIds()).containsExactly("b1");
        answer("b1");
        assertThat(nexo.isTapped()).isTrue();
        assertThat(host.getField()).contains(nexo);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("b1");
    }

    @Test
    void nexoEnergeticoIsTributedToPlayABlueStructureOneCheaper() {
        CardSlot nexo = onField(host, structure("nx", "Nexo Energético Abandonado", 1, "B"), BACK);
        nexo.setTapped(true);
        CardSlot blue = structure("bs", "Estructura Azul", 3, "B");
        host.getHand().addAll(List.of(blue, structure("gs", "Estructura Verde", 1, "G")));
        int before = gems();
        activate("nx", "nexo-energetico-abandonado:turno-aliado");
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("bs")) {
            assertThat(room.getPrompt().getValidIds()).doesNotContain("gs");
            answer("bs");
        }
        drain();
        assertThat(host.getTributeZone()).contains(nexo);
        assertThat(host.getField()).contains(blue);
        assertThat(gems()).isEqualTo(before - 2);
    }

    // ------------------------------------------------------------------ Verde

    @Test
    void leucothiExhaustsAnEnemyOfStrengthSixOrLess() {
        CardSlot weak = onField(guest, creature("w", "Débil", 2, 2, "R", 6));
        onField(guest, creature("s", "Fuerte", 3, 3, "R", 7));
        CardSlot leucothi = creature("lc", "Leucothi", 1, 1, "G", 4, "vegetación");
        summonByEffectToFront(leucothi);
        assertThat(room.getPrompt().getValidIds()).containsExactly("w");
        answer("w");
        drain();
        assertThat(weak.isTapped()).isTrue();
    }

    @Test
    void lettugonCostsOneLessPerExhaustedCardInTheFrontLines() {
        CardSlot summoner = onField(host, creature("s2", "Nivel Dos", 2, 2, "G", 4));
        summoner.setTapped(true);
        onField(guest, creature("e1", "Enemigo", 1, 1, "R", 3)).setTapped(true);
        onField(guest, creature("e2", "Enemigo Activo", 1, 1, "R", 3));
        CardSlot lettugon = special(creature("lt", "Lettugón", 3, 3, "G", 6, "dragón", "vegetación"), "evolution", 3);
        host.getHand().add(lettugon);
        int before = gems();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "lt", "s2")).isNull();
        drain();
        assertThat(host.getField()).contains(lettugon);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void lettugonGivesAsARecursoItsAlAtacarThatDestroysAnExhaustedEnemyOfStrengthEightOrLess() {
        CardSlot attacker = onField(host, creature("at", "Atacante", 4, 4, "G", 10));
        attacker.getResources().add(creature("lt", "Lettugón", 3, 3, "G", 6));
        CardSlot exhausted = onField(guest, creature("ex", "Agotado", 3, 3, "R", 8));
        exhausted.setTapped(true);
        onField(guest, creature("ac", "Activo", 3, 3, "R", 5));
        assertThat(CombatRules.attackByPlayer(room, "host", "at", null)).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("ex");
        answer("ex");
        drain();
        assertThat(guest.getField()).doesNotContain(exhausted);
    }

    @Test
    void escorpionReactivatesWhenItAttacksOncePerTurn() {
        CardSlot escorpion = onField(host, creature("es", "Escorpión de Roble Torcido", 6, 5, "G", 13));
        assertThat(CombatRules.attackByPlayer(room, "host", "es", null)).isNull();
        drain();
        assertThat(escorpion.isTapped()).isFalse();
        assertThat(guest.getLifeStack()).hasSize(2);
        // Once per turn: the second attack leaves it exhausted
        assertThat(CombatRules.attackByPlayer(room, "host", "es", null)).isNull();
        drain();
        assertThat(escorpion.isTapped()).isTrue();
    }

    @Test
    void escorpionExhaustsTwoGreenCardsInsteadOfBeingRemoved() {
        CardSlot escorpion = onField(host, creature("es", "Escorpión de Roble Torcido", 6, 5, "G", 13));
        CardSlot g1 = onField(host, creature("g1", "Verde Uno", 1, 1, "G", 3), BACK);
        CardSlot g2 = onField(host, structure("g2", "Verde Dos", 1, "G"), BACK);
        onField(host, creature("r1", "Roja", 1, 1, "R", 3), BACK);
        Removals.destroy(room, escorpion, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        Prompt offer = room.getPrompt();
        assertThat(offer).isNotNull();
        answer(offer, offer.getValidIds().get(0));
        Prompt tap = room.getPrompt();
        assertThat(tap.getMin()).isEqualTo(2);
        assertThat(tap.getValidIds()).containsExactlyInAnyOrder("es", "g1", "g2");
        answer("g1", "g2");
        assertThat(host.getField()).contains(escorpion);
        assertThat(g1.isTapped()).isTrue();
        assertThat(g2.isTapped()).isTrue();
        assertThat(escorpion.isTapped()).isFalse();
    }

    @Test
    void escorpionIsRemovedWithoutTwoActiveGreenCards() {
        CardSlot escorpion = onField(host, creature("es", "Escorpión de Roble Torcido", 6, 5, "G", 13));
        escorpion.setTapped(true);
        onField(host, creature("g1", "Verde Uno", 1, 1, "G", 3), BACK);
        Removals.destroy(room, escorpion, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        drain();
        assertThat(host.getField()).doesNotContain(escorpion);
    }

    @Test
    void broteVigorosoExhaustsItselfAndMayTakeAGreenCardFromTheTributes() {
        host.getTributeZone().addAll(List.of(card("g1", "Verde", 1, 1, "G"), card("b1", "Azul", 1, 1, "B")));
        CardSlot brote = structure("bv", "Brote Vigoroso", 0, "G");
        play(brote);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("bv") && !room.getPrompt().getValidIds().contains("g1")) accept(brote);
        assertThat(room.getPrompt().getValidIds()).containsExactly("g1");
        // "puedes": none taken is fine
        answer();
        assertThat(brote.isTapped()).isTrue();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).doesNotContain("g1");
    }

    @Test
    void broteVigorosoActiveGoesToTheDiscardToPlayAGreenStructurePayingItsCost() {
        CardSlot brote = onField(host, structure("bv", "Brote Vigoroso", 0, "G"), BACK);
        CardSlot green = structure("gs", "Estructura Verde", 2, "G");
        host.getHand().add(green);
        brote.setTapped(true);
        assertThat(activatable("bv", "brote-vigoroso:turno-aliado")).isNull();
        brote.setTapped(false);
        int before = gems();
        activate("bv", "brote-vigoroso:turno-aliado");
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("gs")) answer("gs");
        drain();
        assertThat(host.getDiscardPile()).contains(brote);
        assertThat(host.getField()).contains(green);
        assertThat(gems()).isEqualTo(before - 2);
    }

    // ------------------------------------------------------------------ Violeta

    @Test
    void parasitoCerebralSacrificesAnAllyToBeSummonedFreeInTheFrontLineOncePerTurnBetweenCopies() {
        CardSlot ally = onField(host, creature("al", "Aliado", 1, 1, "P", 3), BACK);
        onField(host, creature("a2", "Aliado Dos", 1, 1, "P", 3), BACK);
        CardSlot parasito = inHand("pc", "Parásito Cerebral", 1, 1, "P", "bestia", "mutante");
        CardSlot copy = inHand("pc2", "Parásito Cerebral", 1, 1, "P", "bestia", "mutante");
        int before = gems();
        activate("pc", "parasito-cerebral:en-mano");
        answer("al");
        drain();
        assertThat(host.getField()).contains(parasito).doesNotContain(ally);
        assertThat(host.getTributeZone()).contains(ally);
        assertThat(EffectEngine.inFrontLine(parasito)).isTrue();
        assertThat(gems()).isEqualTo(before);
        assertThat(activatable(copy.getInstanceId(), "parasito-cerebral:en-mano")).isNull();
    }

    @Test
    void gumxoGainsThreeWhenSpecialSummonedOnly() {
        onField(host, creature("s2", "Nivel Dos", 2, 2, "P", 4));
        CardSlot gumxo = special(creature("gx", "Gumxo", 3, 3, "P", 9), "ritual", 2);
        host.getHand().add(gumxo);
        assertThat(Rituals.ritualByPlayer(room, "host", "gx", "s2")).isNull();
        drain();
        assertThat(host.getField()).contains(gumxo);
        assertThat(EffectEngine.strength(gumxo)).isEqualTo(12);
    }

    @Test
    void gumxoNormalSummonedDoesNotGainStrength() {
        CardSlot gumxo = creature("gx", "Gumxo", 3, 3, "P", 9);
        summon(gumxo);
        drain();
        assertThat(EffectEngine.strength(gumxo)).isEqualTo(9);
    }

    @Test
    void comehombresSacrificesAnAllyToSummonAVioletLevelOneFromTheTributesOneCheaper() {
        CardSlot ally = onField(host, creature("al", "Aliado", 2, 2, "R", 3), BACK);
        CardSlot violet = creature("v1", "Violeta Uno", 1, 1, "P", 3);
        host.getTributeZone().addAll(List.of(violet, creature("v2", "Violeta Dos", 2, 2, "P", 3)));
        CardSlot comehombres = creature("ch", "Comehombres", 3, 3, "P", 8, "bestia");
        summon(comehombres);
        accept(comehombres);
        // the ally to sacrifice (Comehombres itself may be it: "1 aliado")
        assertThat(room.getPrompt().getValidIds()).contains("al");
        answer("al");
        assertThat(room.getPrompt().getValidIds()).containsExactly("v1");
        int before = gems();
        answer("v1");
        drain();
        assertThat(host.getField()).contains(violet, comehombres).doesNotContain(ally);
        assertThat(gems()).isEqualTo(before);
    }

    @Test
    void abominacionZombieTakesTheNameOfACreatureUsedForItsRitualAndCountsItsCopiesInTheTributes() {
        host.getTributeZone().add(creature("t0", "Zombi Raso", 1, 1, "P", 3));
        onField(host, creature("s2", "Zombi Raso", 2, 2, "P", 4));
        onField(host, creature("o1", "Zombi Raso", 1, 1, "P", 3), BACK);
        onField(host, creature("o2", "Otro Muerto", 1, 1, "P", 3), BACK);
        CardSlot abominacion = special(creature("ab", "Abominación Zombie", 5, 4, "P", 11, "muerto viviente", "mutante"), "ritual", 3);
        host.getHand().add(abominacion);
        assertThat(Rituals.ritualByPlayer(room, "host", "ab", "s2")).isNull();
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("o1")) answer("o1", "o2");
        untilOffered(abominacion);
        accept(abominacion);
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("s2", "o1", "o2");
        answer("o2");
        drain();
        assertThat(NameChanges.name(abominacion)).isEqualTo("Otro Muerto");
        assertThat(CardEffects.isNamed(abominacion, "Abominación Zombie")).isFalse();
        assertThat(CardEffects.isNamed(abominacion, "otro muerto")).isTrue();
        // Its own effects stay its own: the Al Atacar counts the copies of its new name (1 "Otro Muerto" in the Tributos: none can be chosen)
        CardSlot two = onField(guest, creature("e2", "Nivel Dos", 2, 2, "R", 3));
        two.setTapped(true);
        abominacion.setY(FRONT);
        host.getTributeZone().add(creature("t1", "Otro Muerto", 1, 1, "P", 3));
        host.getTributeZone().add(creature("t2", "Otro Muerto", 1, 1, "P", 3));
        // 3 copies now: a level 2 enemy can be chosen
        assertThat(CombatRules.attackByPlayer(room, "host", "ab", null)).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("e2");
        answer("e2");
        drain();
        assertThat(guest.getField()).doesNotContain(two);
    }

    @Test
    void abominacionZombieWithoutRenamingChoosesBelowItsOwnCopies() {
        CardSlot abominacion = onField(host, creature("ab", "Abominación Zombie", 5, 4, "P", 11));
        host.getTributeZone().addAll(List.of(creature("c1", "Abominación Zombie", 5, 4, "P", 11), creature("c2", "Abominación Zombie", 5, 4, "P", 11)));
        CardSlot one = onField(guest, creature("e1", "Nivel Uno", 1, 1, "R", 3));
        onField(guest, creature("e2", "Nivel Dos", 2, 2, "R", 3));
        assertThat(CombatRules.attackByPlayer(room, "host", "ab", null)).isNull();
        // 2 copies: only level 1 or less
        assertThat(room.getPrompt().getValidIds()).containsExactly("e1");
        answer("e1");
        drain();
        assertThat(guest.getField()).doesNotContain(one);
    }

    @Test
    void abominacionZombieRitualNeedsALevelTwoOrThreeSummonerAndTwoMoreCreatures() {
        CardSlot abominacion = special(creature("ab", "Abominación Zombie", 5, 4, "P", 11), "ritual", 3);
        host.getHand().add(abominacion);
        onField(host, creature("s1", "Nivel Uno", 1, 1, "P", 3));
        onField(host, creature("o1", "Otro Uno", 1, 1, "P", 3), BACK);
        onField(host, creature("o2", "Otro Dos", 1, 1, "P", 3), BACK);
        assertThat(Rituals.canRitual(room, "host", abominacion)).isFalse();
        onField(host, creature("s3", "Nivel Tres", 3, 3, "P", 3), BACK);
        assertThat(Rituals.canRitual(room, "host", abominacion)).isTrue();
    }

    // ------------------------------------------------------------------ Rojo

    @Test
    void peleadorSummonedByEffectSummonsARedLevelOneAndMovesUpWithThreeMore() {
        CardSlot red = inHand("r1", "Roja Uno", 2, 1, "R");
        inHand("r2", "Roja Dos", 2, 2, "R");
        CardSlot peleador = creature("pp", "Peleador de la Primera Línea", 2, 2, "R", 5, "caballero");
        summonByEffectToBack(peleador);
        untilOffered(peleador);
        accept(peleador);
        // (the regulatory draw added a red level 1 of the deck too)
        assertThat(room.getPrompt().getValidIds()).contains("r1").doesNotContain("r2");
        int before = gems();
        answer("r1");
        drain();
        assertThat(host.getField()).contains(red, peleador);
        assertThat(gems()).isEqualTo(before - 1);
        assertThat(EffectEngine.inFrontLine(peleador)).isTrue();
        assertThat(EffectEngine.strength(peleador)).isEqualTo(8);
    }

    @Test
    void peleadorNormalSummonedDoesNothing() {
        inHand("r1", "Roja Uno", 2, 1, "R");
        CardSlot peleador = creature("pp", "Peleador de la Primera Línea", 2, 2, "R", 5);
        summon(peleador);
        drain();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("r1");
        assertThat(EffectEngine.strength(peleador)).isEqualTo(5);
    }

    @Test
    void peonExperimentadoTributesTwoFromTheDeckAndTakesARedOrWhiteCardFromTheTributes() {
        host.getDeck().addAll(0, List.of(card("b1", "Azul", 1, 1, "B"), card("w1", "Blanca", 1, 1, "W")));
        host.getTributeZone().add(card("g1", "Verde", 1, 1, "G"));
        CardSlot peon = creature("pe", "Peón Experimentado", 1, 1, "R", 3);
        summon(peon);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().equals(List.of("pe"))) accept(peon);
        assertThat(room.getPrompt().getValidIds()).containsExactly("w1");
        answer("w1");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("w1");
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("b1", "g1");
    }

    @Test
    void sableTemplarioMayReactivateWhenItDestroysAnEnemyOncePerTurn() {
        CardSlot sable = onField(host, creature("sb", "Sable Templario", 3, 3, "R", 8, "caballero", "retador"));
        sable.setTapped(true);
        CardSlot enemy = onField(guest, creature("e1", "Enemigo", 1, 1, "R", 3));
        Removals.destroy(room, enemy, guest, false, EffectEngine.CAUSE_EFFECT, "host", "sb", null);
        Chain.advance(room);
        accept(sable);
        drain();
        assertThat(sable.isTapped()).isFalse();
        sable.setTapped(true);
        CardSlot other = onField(guest, creature("e2", "Otro", 1, 1, "R", 3));
        Removals.destroy(room, other, guest, false, EffectEngine.CAUSE_EFFECT, "host", "sb", null);
        Chain.advance(room);
        drain();
        assertThat(sable.isTapped()).isTrue();
    }

    @Test
    void sableTemplarioIsPromotedWhileYourAlliesAddUpToFourInCost() {
        CardSlot sable = special(creature("sb", "Sable Templario", 3, 3, "R", 8), "promotion", 2);
        host.getHand().add(sable);
        onField(host, creature("s2", "Nivel Dos", 2, 2, "R", 3));
        assertThat(SpecialRequirements.canSpecial(room, "host", sable)).isFalse();
        onField(host, creature("o2", "Otro", 2, 1, "R", 3), BACK);
        assertThat(SpecialRequirements.canSpecial(room, "host", sable)).isTrue();
    }

    @Test
    void hachaTemplariaCountsAsHavingPrimeraLineaInItsName() {
        CardSlot hacha = creature("hc", "Hacha Templaria", 3, 3, "R", 9);
        assertThat(CardFilter.any().nameContains("Primera Línea").matches(hacha)).isTrue();
        assertThat(CardFilter.any().nameContains("Primera Línea").matches(creature("o", "Otra", 1, 1, "R", 3))).isFalse();
    }

    @Test
    void hachaTemplariaReturnsToTheHandAtTheEndOfItsAttackToSummonATemplarioOrPrimeraLineaTwoCheaper() {
        CardSlot hacha = onField(host, creature("hc", "Hacha Templaria", 3, 3, "R", 9, "caballero", "retador"));
        CardSlot sable = inHand("sb", "Sable Templario", 3, 3, "R");
        inHand("pp", "Peleador de la Primera Línea", 2, 2, "R");
        inHand("h2", "Hacha Templaria", 3, 3, "R");
        inHand("ot", "Otra Roja", 1, 1, "R");
        assertThat(CombatRules.attackByPlayer(room, "host", "hc", null)).isNull();
        untilOffered(hacha);
        accept(hacha);
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("sb", "pp");
        int before = gems();
        answer("sb");
        drain();
        assertThat(host.getHand()).contains(hacha);
        assertThat(host.getField()).contains(sable);
        assertThat(gems()).isEqualTo(before - 1);
    }

    // ------------------------------------------------------------------ Blanco

    @Test
    void pesadillaDeMaleficioStopsAnEnemyFromActivatingEffectsUntilTheEndOfTheTurn() {
        CardSlot enemy = onField(guest, creature("e1", "Enemigo", 2, 2, "R", 5));
        CardSlot pesadilla = utility("pm", "Pesadilla de Maleficio", 1, "W");
        play(pesadilla);
        answer("e1");
        drain();
        assertThat(EffectEngine.cannotActivate(enemy)).isTrue();
        assertThat(host.getDiscardPile()).contains(pesadilla);
        TurnActions.passTurn(room, "host");
        drain();
        assertThat(EffectEngine.cannotActivate(enemy)).isFalse();
    }

    @Test
    void desgarroDiscardsTheEnemysAttachedCardsAndThenMayPayFourGemsToDestroyIt() {
        CardSlot enemy = onField(guest, creature("e1", "Enemigo", 3, 3, "R", 9));
        CardSlot material = card("m1", "Material", 1, 1, "R");
        CardSlot resource = card("r1", "Recurso", 1, 1, "R");
        enemy.getMaterials().add(material);
        enemy.getResources().add(resource);
        CardSlot desgarro = utility("dc", "Desgarro de la Corporalidad", 1, "W");
        int before = gems();
        play(desgarro);
        answer("e1");
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("m1", "r1");
        answer("m1", "r1");
        assertThat(guest.getDiscardPile()).contains(material, resource);
        // "puedes pagar 4 gemas para destruirlo"
        Prompt confirm = room.getPrompt();
        assertThat(confirm).isNotNull();
        answer(confirm, confirm.getValidIds().get(0));
        drain();
        assertThat(guest.getField()).doesNotContain(enemy);
        assertThat(gems()).isEqualTo(before - 5);
    }

    @Test
    void desgarroDoesNotOfferTheDestructionWhileTheEnemyKeepsAttachedCards() {
        CardSlot enemy = onField(guest, creature("e1", "Enemigo", 3, 3, "R", 9));
        enemy.getMaterials().addAll(new ArrayList<>(List.of(card("m1", "M1", 1, 1, "R"), card("m2", "M2", 1, 1, "R"), card("m3", "M3", 1, 1, "R"))));
        CardSlot desgarro = utility("dc", "Desgarro de la Corporalidad", 1, "W");
        int before = gems();
        play(desgarro);
        answer("e1");
        answer("m1", "m2");
        drain();
        assertThat(guest.getField()).contains(enemy);
        assertThat(enemy.getMaterials()).extracting(CardSlot::getInstanceId).containsExactly("m3");
        assertThat(gems()).isEqualTo(before - 1);
    }
}
