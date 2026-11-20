package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cards whose metadata was corrected on 2026-11-17 to follow the printed text (vault "13 Web/Migración de cartas - propuesta de
 * correcciones", "Motor: cartas a revisar"), reprogrammed where the behaviour changed: their Requerimientos and effects.
 */
@Tag("cards")
class PrintedTextCardsTest extends CardBatchSupport {

    /** A real card with a special summon: its Requerimiento comes from its card file. */
    private CardSlot special(CardSlot card, String kind, int cost, String requirement) {
        card.setSpecialSummonKind(kind);
        card.setSpecialCost(cost);
        card.setRequirement(requirement);
        Req.program(card);
        return card;
    }

    private CardSlot discard(String id, int level) {
        CardSlot c = card(id, "Descarte " + id, level, level, "B");
        host.getDiscardPile().add(c);
        return c;
    }

    // ------------------------------------------------------------------ Requerimientos

    @Test
    void cessEvolvesFromALevelOneCreature() {
        CardSlot cess = special(inHand("ce", "Cess", 1, 2, "G", "hada"), "evolution", 1, "criatura nivel 1.");
        onField(host, withClasses(card("s1", "Uno", 1, 1, "G"), "hada"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ce", "s1")).isNull();
        drain();
        assertThat(host.getField()).contains(cess);
    }

    @Test
    void criaDeDragonRunicoCannotEvolveFromACopyOfItself() {
        special(inHand("cr", "Cría De Dragón Rúnico", 1, 1, "G", "dragón"), "evolution", 0, "criatura nivel 1 que no sea una copia de esta carta.");
        onField(host, withClasses(card("c2", "Cría De Dragón Rúnico", 1, 1, "G"), "dragón"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "cr", "c2")).isNotNull();
        onField(host, withClasses(card("ot", "Otro Dragón", 1, 1, "G"), "dragón"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "cr", "ot")).isNull();
        drain();
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("cr");
    }

    @Test
    void arqueroMiticoErranteNeedsTheStrongestSummonerNotALoneOne() {
        special(inHand("ar", "Arquero Mítico Errante", 3, 3, "R", "retador"), "promotion", 1,
                "criatura nivel 2 con fuerza igual o mayor que la de cualquier otro aliado.");
        CardSlot summoner = onField(host, withClasses(card("s2", "Dos", 2, 2, "R"), "retador"));
        summoner.setStrength(5);
        CardSlot strong = onField(host, card("fu", "Fuerte", 2, 1, "R"));
        strong.setStrength(9);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ar", "s2")).isNotNull();
        strong.setStrength(3);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ar", "s2")).isNull();
        drain();
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("ar");
    }

    @Test
    void spentalosTakesItsLevelOneMaterialsFromTheFieldOnly() {
        special(inHand("sp", "Spentalos", 2, 2, "B", "ente"), "materialization", 1,
                "criatura nivel 1 + 1 o más materiales nivel 1 en el campo.");
        onField(host, withClasses(card("s1", "Invocador", 1, 1, "B"), "ente"));
        host.getTributeZone().add(withClasses(card("t1", "En Tributos", 1, 1, "B"), "ente"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "sp", "s1")).isNotNull();
        onField(host, withClasses(card("m1", "Material Uno", 1, 1, "B"), "ente"));
        onField(host, withClasses(card("m2", "Material Dos", 1, 1, "B"), "ente"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "sp", "s1")).isNull();
        assertThat(room.getPrompt().getValidIds()).contains("m1", "m2").doesNotContain("t1");
        answer("m1", "m2");
        drain();
        CardSlot spentalos = host.getField().stream().filter(c -> "sp".equals(c.getInstanceId())).findFirst().orElseThrow();
        assertThat(spentalos.getMaterials()).extracting(CardSlot::getInstanceId).contains("m1", "m2");
    }

    @Test
    void druidaAntiguoAlsoEvolvesFromAFaunaLevelThree() {
        special(inHand("dr", "Druida Antiguo", 8, 5, "G", "fauna", "treant"), "evolution", 5, "criatura nivel 4 o criatura [fauna] nivel 3.");
        onField(host, card("o3", "Otro Tres", 3, 3, "G"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "dr", "o3")).isNotNull();
        onField(host, withClasses(card("f3", "Fauna Tres", 3, 3, "G"), "fauna"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "dr", "f3")).isNull();
        drain();
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("dr").doesNotContain("f3");
    }

    @Test
    void infanteriaDePrimeraLineaAscendsFromALevelOneWithTwoCreatures() {
        special(inHand("in", "Infantería De Primera Línea", 2, 2, "R", "caballero", "armada"), "promotion", 1,
                "criatura nivel 1 mientras controlas al menos 2 criaturas.");
        onField(host, withClasses(card("s1", "Uno", 1, 1, "R"), "caballero"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "in", "s1")).isNotNull();
        onField(host, card("o1", "Otro", 1, 1, "R"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "in", "s1")).isNull();
        drain();
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("in");
    }

    @Test
    void caballeroDeLosLeonesDoradosAscendsWhenTheAlliesAddUpFourInCost() {
        special(inHand("cl", "Caballero de los Leones Dorados", 2, 2, "R", "caballero", "retador"), "promotion", 1,
                "criatura nivel 1, mientras tus aliados suman al menos 4 en coste.");
        onField(host, withClasses(card("s1", "Uno", 1, 1, "R"), "caballero"));
        CardSlot other = onField(host, card("o2", "Otro", 2, 2, "R"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "cl", "s1")).isNotNull();
        other.setCost(3);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "cl", "s1")).isNull();
        drain();
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).contains("cl");
    }

    @Test
    void armaduraDelAlmaImpasibleNowHasItsAscenso() {
        special(inHand("aa", "Armadura Del Alma Impasible", 2, 2, "R", "retador"), "promotion", 1,
                "criatura nivel 1, mientras tus aliados suman al menos 4 en coste.");
        onField(host, withClasses(card("s1", "Uno", 1, 1, "R"), "retador"));
        onField(host, card("o3", "Otro", 3, 3, "R"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "aa", "s1")).isNull();
        drain();
        // Its "si un aliado es invocado especialmente" sees its own summon: it goes to the top of the lives (doubt for the designer)
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.SPECIAL_SUMMON.equals(e.getType()) && "aa".equals(e.getCardInstanceId()));
    }

    @Test
    void velkeropTakesMaterialsOfTheDiscardThatAddUpToSixWithoutTheSummoner() {
        special(inHand("vk", "Velkerop-1", 5, 4, "B", "necrómata"), "materialization", 2,
                "criatura nivel 3 o 4 + materiales que sumen 6 en nivel en descarte.");
        onField(host, withClasses(card("s4", "Cuatro", 4, 4, "B"), "necrómata"));
        discard("d2", 2);
        discard("d4", 4);
        discard("d1", 1);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "vk", "s4")).isNull();
        assertThat(room.getPrompt().getValidIds()).contains("d1", "d2", "d4");
        // 2 + 1 = 3: with the summoner it would be 7, but the summoner does not count
        CardSlot summoner = host.getField().stream().filter(c -> "s4".equals(c.getInstanceId())).findFirst().orElseThrow();
        assertThat(SpecialRequirements.checkMaterials(SpecialRequirements.specialRequirement(host.getHand().stream()
                .filter(c -> "vk".equals(c.getInstanceId())).findFirst().orElseThrow()), summoner, List.of(host.getDiscardPile().get(0),
                host.getDiscardPile().get(2)))).isNotNull();
        answer("d2", "d4");
        drain();
        CardSlot velkerop = host.getField().stream().filter(c -> "vk".equals(c.getInstanceId())).findFirst().orElseThrow();
        assertThat(velkerop.getMaterials()).extracting(CardSlot::getInstanceId).contains("d2", "d4").doesNotContain("d1");
    }

    @Test
    void velkeropCannotBeSummonedWithoutSixLevelsInTheDiscard() {
        special(inHand("vk", "Velkerop-1", 5, 4, "B", "necrómata"), "materialization", 2,
                "criatura nivel 3 o 4 + materiales que sumen 6 en nivel en descarte.");
        onField(host, withClasses(card("s3", "Tres", 3, 3, "B"), "necrómata"));
        discard("d2", 2);
        discard("d1", 1);
        // Field allies do not count either
        onField(host, card("f3", "Tres En Campo", 3, 3, "B"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "vk", "s3")).isNotNull();
    }

    // ------------------------------------------------------------------ Effects

    @Test
    void bioPrototipoReactivatesAtTheEndOfAnAttackAndReducesOncePerDiscardedMaterial() {
        CardSlot bio = onField(host, withClasses(card("bp", "Bio-prototipo A0", 8, 5, "B"), "necrómata"), FRONT);
        bio.setStrength(13);
        bio.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B"), card("m3", "M3", 1, 1, "B")));
        CardSlot enemy = onField(guest, card("en", "Enemigo", 9, 4, "R"), FRONT);
        enemy.setStrength(20);
        CardSlot weak = onField(guest, card("wk", "Débil", 1, 1, "R"), FRONT);
        weak.setTapped(true);
        assertThat(CombatRules.attackByPlayer(room, "host", "bp", "wk")).isNull();
        untilOffered(bio);
        answer("bp");
        answer("m1", "m2", "m3");
        // "por cada carta removida": three reductions of 3, each one must be chosen
        for (int i = 0; i < 3 && room.getPrompt() != null && room.getPrompt().getValidIds().contains("en"); i++) {
            assertThat(room.getPrompt().getMin()).isEqualTo(1);
            answer("en");
        }
        drain();
        assertThat(bio.getMaterials()).isEmpty();
        assertThat(host.getDiscardPile()).extracting(CardSlot::getInstanceId).contains("m1", "m2", "m3");
        assertThat(bio.isTapped()).isFalse();
        assertThat(EffectEngine.strength(enemy)).isEqualTo(11);
    }

    @Test
    void berkelusSendsUpToTenAndOnlyTenGiveFuriaAndTheAttack() {
        for (int i = 0; i < 10; i++) host.getTributeZone().add(withClasses(card("b" + i, "Bestia " + i, 1, 1, "P"), "bestia"));
        CardSlot berkelus = withClasses(card("bk", "Berkelus El Cazarrecompensas Del Bajomundo", 6, 5, "P"), "bestia");
        summonByEffectToFront(berkelus);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("bk")) accept(berkelus);
        answer("b0", "b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8", "b9");
        assertThat(EffectEngine.keywords(berkelus)).contains("Furia 1");
        assertThat(room.getPrompt()).isNotNull();
        assertThat(room.getPrompt().getInstruction()).contains("declara ataque");
    }

    @Test
    void berkelusWithFewerThanTenStillReactivatesButGainsNothing() {
        for (int i = 0; i < 3; i++) host.getTributeZone().add(withClasses(card("b" + i, "Bestia " + i, 1, 1, "P"), "bestia"));
        CardSlot berkelus = withClasses(card("bk", "Berkelus El Cazarrecompensas Del Bajomundo", 6, 5, "P"), "bestia");
        summonByEffectToFront(berkelus);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("bk")) accept(berkelus);
        answer("b0", "b1", "b2");
        drain();
        assertThat(host.getDeck()).extracting(CardSlot::getInstanceId).contains("b0", "b1", "b2");
        assertThat(EffectEngine.keywords(berkelus)).doesNotContain("Furia 1");
        assertThat(berkelus.isTapped()).isFalse();
    }

    @Test
    void tacticasDeGuerraLooksAtFourTakesTwoRedsAndTributesOneOfTheHand() {
        host.getDeck().addAll(0, List.of(card("r1", "Roja 1", 1, 1, "R"), card("r2", "Roja 2", 1, 1, "R"),
                card("b1", "Azul", 1, 1, "B"), card("r3", "Roja 3", 1, 1, "R")));
        CardSlot tacticas = utility("tg", "Tácticas De Guerra", 1, "R");
        inHand("x", "Extra", 1, 1, "B");
        play(tacticas);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("tg")) accept(tacticas);
        assertThat(room.getPrompt().getValidIds()).contains("r1", "r2", "r3").doesNotContain("b1");
        answer("r1", "r3");
        answer("x");
        drain();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("r1", "r3").doesNotContain("x");
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("x");
        assertThat(host.getDeck().subList(host.getDeck().size() - 2, host.getDeck().size())).extracting(CardSlot::getInstanceId)
                .containsExactlyInAnyOrder("r2", "b1");
        assertThat(host.getField()).contains(tacticas);
        assertThat(tacticas.isTapped()).isTrue();
    }

    @Test
    void hydraColosalChoosesItsTargetBeforeTributing() {
        CardSlot enemy = onField(guest, card("en", "Enemigo", 3, 3, "R"), FRONT);
        enemy.setStrength(7);
        CardSlot hydra = withClasses(card("hy", "Hydra Colosal", 5, 4, "G"), "dragón", "hydra");
        summonByEffectToFront(hydra);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("hy")) accept(hydra);
        // The choice comes first, with the deck untouched
        assertThat(room.getPrompt().getValidIds()).contains("en");
        assertThat(host.getTributeZone()).isEmpty();
        answer("en");
        drain();
        assertThat(host.getTributeZone()).hasSize(3);
        assertThat(guest.getField()).doesNotContain(enemy);
    }

    @Test
    void hydraColosalStillTributesWithNoEnemy() {
        CardSlot hydra = withClasses(card("hy", "Hydra Colosal", 5, 4, "G"), "dragón", "hydra");
        summonByEffectToFront(hydra);
        drain();
        assertThat(host.getTributeZone()).hasSize(3);
    }

    @Test
    void dragonRunicoTributesTheCardsItDoesNotTake() {
        host.getDeck().addAll(0, List.of(card("g1", "Verde", 1, 1, "G"), card("r1", "Roja", 1, 1, "R"),
                card("r2", "Roja 2", 1, 1, "R"), card("b1", "Azul", 1, 1, "B")));
        int deck = host.getDeck().size();
        CardSlot dragon = withClasses(card("dr", "Dragón Rúnico", 3, 3, "G"), "dragón");
        summon(dragon);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("dr")) accept(dragon);
        answer("g1");
        drain();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("g1");
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactlyInAnyOrder("r1", "r2", "b1");
        assertThat(host.getDeck()).hasSize(deck - 4);
    }

    @Test
    void cofreMimicoDestroysOnlyWhenSummonedByItsOwnEffect() {
        CardSlot enemy = onField(guest, card("en", "Enemigo", 1, 1, "R"), FRONT);
        CardSlot cofre = withClasses(card("cm", "Cofre Mímico", 1, 1, "P"), "bestia", "mímico");
        // A normal summon does nothing
        summon(cofre);
        drain();
        assertThat(guest.getField()).contains(enemy);
        // Tributed from the lives: summoned, then the level 1 enemy may be destroyed
        host.getField().remove(cofre);
        host.getTributeZone().add(cofre);
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", cofre, "lifeStack", "tributeZone", null, new java.util.HashMap<>(), null);
        Chain.advance(room);
        accept(cofre);
        answer("en");
        drain();
        assertThat(host.getField()).contains(cofre);
        assertThat(guest.getField()).doesNotContain(enemy);
    }

    @Test
    void falisIsSummonedIntoTheFrontLine() {
        CardSlot falis = withClasses(card("fa", "Falis", 3, 3, "B"), "ente");
        host.getTributeZone().add(falis);
        CardSlot enemy = onField(guest, card("en", "Enemigo", 1, 1, "R"), FRONT);
        enemy.setStrength(2);
        PendingEffect reducer = new PendingEffect();
        reducer.setControllerId("host");
        reducer.setSourceName("Reductor");
        StepHelpers.reduceStrength(room, reducer, guest, enemy, 2, false);
        Chain.advance(room);
        accept(falis);
        drain();
        assertThat(host.getField()).contains(falis);
        assertThat(EffectEngine.inFrontLine(falis)).isTrue();
    }

    @Test
    void duregonNoLongerLowersAnEvolutionCost() {
        for (int i = 0; i < 5; i++) host.getTributeZone().add(withClasses(card("d" + i, "Dragón " + i, 1, 1, "G"), "dragón"));
        onField(host, withClasses(card("du", "Duregón", 1, 1, "G"), "dragón"));
        CardSlot evo = special(card("ev", "Evolución", 2, 2, "G"), "evolution", 2, "criatura nivel 1.");
        host.getHand().add(evo);
        int before = gems();
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ev", "du")).isNull();
        drain();
        assertThat(host.getField()).contains(evo);
        assertThat(gems()).isEqualTo(before - 2);
    }

    @Test
    void cylvausIsFoundByItsPrintedName() {
        assertThat(CardEffects.programmedNames()).contains(CardEffects.nameOf("cylvaus"));
        assertThat(CardEffects.programmedNames()).contains(CardEffects.nameOf("cess"));
    }
}
