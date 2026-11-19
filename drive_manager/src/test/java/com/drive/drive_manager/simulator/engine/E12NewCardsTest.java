package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.actions.TurnActions;
import com.drive.drive_manager.simulator.engine.effects.model.Action;
import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1.2 cards programmed on 2026-11-19 (the ones without a file until then) and the cards whose printed text the designer changed (Dragaour,
 * Lyna, Fervor de Guerra): one test (or more) per behaviour.
 */
@Tag("cards")
@Tag("e12")
class E12NewCardsTest extends CardBatchSupport {

    private static CardSlot creature(String id, String name, int cost, int level, String color, int strength, String... classes) {
        CardSlot c = withClasses(card(id, name, cost, level, color), classes);
        c.setStrength(strength);
        return c;
    }

    private static CardSlot enemy(String id, int level, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, level, "R");
        c.setStrength(strength);
        return c;
    }

    /** The guest's effect destroys that card of the host. */
    private void destroyByGuest(CardSlot card) {
        Removals.destroy(room, card, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        if (room.getPrompt() == null) Chain.advance(room);
    }

    /** Uses the first replacement offered for the removal that waits. */
    private void useReplacement() {
        Prompt replace = room.getPrompt();
        assertThat(replace.getPurpose()).isEqualTo(PromptPurpose.REPLACE.id());
        answer(replace, replace.getEffects().get(0).effectId());
    }

    /** Confirms a lone mandatory effect of that card if the room asks for it. */
    private void confirmIfAsked(CardSlot source) {
        Prompt p = room.getPrompt();
        if (p != null && p.getPurpose() != null && p.getValidIds().equals(List.of(source.getInstanceId()))) answer(p, source.getInstanceId());
    }

    // ------------------------------------------------------------------ Azul

    @Test
    void joelDestroysAnAllyToEnterTheFrontLineWithoutPayingItsCost() {
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "B"), BACK);
        CardSlot joel = inHand("jo", "Joel", 2, 2, "B", "necrómata");
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "jo", "joel:en-mano")).isNull();
        answer("al");
        drain();
        assertThat(host.getDiscardPile()).contains(ally);
        assertThat(host.getField()).contains(joel);
        assertThat(EffectEngine.inFrontLine(joel)).isTrue();
        assertThat(gems()).isEqualTo(before);
    }

    @Test
    void joelCannotBeActivatedWithoutAnAlly() {
        inHand("jo", "Joel", 2, 2, "B", "necrómata");
        assertThat(Chain.activateByPlayer(room, "host", "jo", "joel:en-mano")).isNotNull();
    }

    @Test
    void vFlyerDestroysAnotherAllyToGainLetalidadUntilTheEndOfTheAttack() {
        CardSlot flyer = onField(host, creature("vf", "V-Flyer", 3, 3, "B", 8, "necrómata"));
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "B"), BACK);
        // An active enemy that may block: the attack waits for its answer
        onField(guest, enemy("bk", 1, 2));
        assertThat(CombatRules.attackByPlayer(room, "host", "vf", null)).isNull();
        accept(flyer);
        assertThat(room.getPrompt().getValidIds()).containsExactly("al");
        answer("al");
        assertThat(host.getDiscardPile()).contains(ally);
        assertThat(EffectEngine.keywords(flyer)).contains("Letalidad 1");
    }

    @Test
    void vFlyerDiscardsAMaterialInsteadOfBeingDestroyedOnTheOpponentsTurnOnly() {
        CardSlot flyer = onField(host, creature("vf", "V-Flyer", 3, 3, "B", 8, "necrómata"));
        CardSlot m1 = card("m1", "Material 1", 2, 2, "B");
        CardSlot m2 = card("m2", "Material 2", 2, 2, "B");
        flyer.getMaterials().addAll(List.of(m1, m2));
        room.setCurrentTurnUserId("guest");
        destroyByGuest(flyer);
        useReplacement();
        assertThat(room.getPrompt().getPurpose()).isEqualTo(PromptPurpose.REPLACE_DISCARD.id());
        answer("m2");
        assertThat(host.getField()).contains(flyer);
        assertThat(flyer.getMaterials()).containsExactly(m1);
        assertThat(host.getDiscardPile()).contains(m2);
        // Once per turn
        destroyByGuest(flyer);
        drain();
        assertThat(host.getField()).doesNotContain(flyer);
    }

    @Test
    void vFlyerIsDestroyedOnItsOwnersTurn() {
        CardSlot flyer = onField(host, creature("vf", "V-Flyer", 3, 3, "B", 8, "necrómata"));
        flyer.getMaterials().add(card("m1", "Material 1", 2, 2, "B"));
        destroyByGuest(flyer);
        drain();
        assertThat(host.getField()).doesNotContain(flyer);
    }

    @Test
    void cazadorAntibiologicoDiscardsTwoMaterialsToSummonABlueCreatureFromTheDiscardForTwoLess() {
        CardSlot cazador = onField(host, creature("cz", "Cazador Antibiológico", 3, 4, "B", 11, "necrómata"));
        cazador.getMaterials().addAll(List.of(card("m1", "Material 1", 2, 2, "R"), card("m2", "Material 2", 2, 2, "R")));
        CardSlot blue = creature("bd", "Azul", 3, 3, "B", 6);
        host.getDiscardPile().addAll(List.of(blue, creature("rd", "Roja", 1, 1, "R", 3)));
        CardSlot foe = onField(guest, enemy("e", 2, 5));
        int before = gems();
        Removals.destroy(room, foe, guest, false, EffectEngine.CAUSE_BATTLE, "host", "cz", null);
        Chain.advance(room);
        accept(cazador);
        if (room.getPrompt().getValidIds().contains("m1")) answer("m1", "m2");
        assertThat(cazador.getMaterials()).isEmpty();
        assertThat(room.getPrompt().getValidIds()).containsExactly("bd");
        answer("bd");
        drain();
        assertThat(host.getField()).contains(blue);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void cazadorAntibiologicoIsNotOfferedForADestructionByEffect() {
        CardSlot cazador = onField(host, creature("cz", "Cazador Antibiológico", 3, 4, "B", 11, "necrómata"));
        cazador.getMaterials().addAll(List.of(card("m1", "Material 1", 2, 2, "B"), card("m2", "Material 2", 2, 2, "B")));
        CardSlot foe = onField(guest, enemy("e", 2, 5));
        Removals.destroy(room, foe, guest, false, EffectEngine.CAUSE_EFFECT, "host", "cz", null);
        Chain.advance(room);
        Prompt p = room.getPrompt();
        assertThat(p == null || !p.getValidIds().contains("cz")).isTrue();
    }

    @Test
    void reciclajeBiologicoSendsTenBlueCardsToTheBottomForTwoGems() {
        room.setSharedCounter(room.getSharedCounter() - 4 * EffectEngine.direction(room, "host"));
        for (int i = 0; i < 10; i++) host.getDiscardPile().add(card("b" + i, "Azul " + i, 1, 1, "B"));
        CardSlot reciclaje = utility("rb", "Reciclaje Biológico", 1, "B");
        int before = gems();
        play(reciclaje);
        for (int i = 0; i < 3 && room.getPrompt() != null && room.getPrompt().getValidIds().equals(List.of("rb")); i++) answer("rb");
        answer("b0", "b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8", "b9");
        drain();
        assertThat(host.getDiscardPile()).doesNotContainAnyElementsOf(host.getDeck());
        assertThat(host.getDeck().subList(host.getDeck().size() - 10, host.getDeck().size())).extracting(CardSlot::getInstanceId)
                .containsExactlyInAnyOrder("b0", "b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8", "b9");
        assertThat(gems()).isEqualTo(before - 1 + 2);
    }

    @Test
    void reciclajeBiologicoDoesNothingWithNineBlueCards() {
        for (int i = 0; i < 9; i++) host.getDiscardPile().add(card("b" + i, "Azul " + i, 1, 1, "B"));
        int deck = host.getDeck().size();
        play(utility("rb", "Reciclaje Biológico", 1, "B"));
        drain();
        assertThat(host.getDeck()).hasSize(deck);
    }

    // ------------------------------------------------------------------ Verde

    @Test
    void ciervoFrondosoExhaustsAGreenStructureToBeSummonedForOneLess() {
        CardSlot tower = onField(host, structure("st", "Torre Verde", 1, "G"), BACK);
        onField(host, structure("sr", "Torre Roja", 1, "R"), BACK);
        CardSlot ciervo = inHand("cf", "Ciervo Frondoso", 2, 2, "G", "fauna", "vegetación");
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "cf", "ciervo-frondoso:en-mano")).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("st");
        answer("st");
        drain();
        assertThat(tower.isTapped()).isTrue();
        assertThat(host.getField()).contains(ciervo);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void ciervoFrondosoGivesItsEvolutionAReactivationOfAGreenStructureOfCostOne() {
        CardSlot holder = onField(host, creature("ho", "Portador", 3, 3, "G", 8));
        holder.getResources().add(creature("cf", "Ciervo Frondoso", 2, 2, "G", 4));
        CardSlot tower = onField(host, structure("st", "Torre Verde", 1, "G"), BACK);
        tower.setTapped(true);
        CardSlot expensive = onField(host, structure("sx", "Torre Cara", 2, "G"), BACK);
        expensive.setTapped(true);
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        untilOffered(holder);
        accept(holder);
        assertThat(room.getPrompt().getValidIds()).containsExactly("st");
        answer("st");
        assertThat(tower.isTapped()).isFalse();
        assertThat(expensive.isTapped()).isTrue();
    }

    @Test
    void dragonVelaGivesPerforanteToALevelFiveHolderOnItsTurn() {
        CardSlot five = onField(host, creature("h5", "Portador 5", 5, 5, "G", 12));
        five.getResources().add(creature("dv", "Dragón Vela", 3, 3, "G", 7));
        CardSlot four = onField(host, creature("h4", "Portador 4", 4, 4, "G", 10));
        four.getResources().add(creature("dw", "Dragón Vela", 3, 3, "G", 7));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(five)).contains("Perforante");
        assertThat(EffectEngine.keywords(four)).doesNotContain("Perforante");
        room.setCurrentTurnUserId("guest");
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(five)).doesNotContain("Perforante");
    }

    @Test
    void dracoverdisSendsAnEnemyNoStrongerThanYourStructuresToTheHand() {
        CardSlot holder = onField(host, creature("ho", "Portador", 4, 4, "G", 10));
        holder.getResources().add(creature("dc", "Dracoverdis", 3, 3, "G", 7));
        onField(host, structure("s1", "Torre 1", 1, "G"), BACK);
        onField(host, structure("s2", "Torre 2", 1, "G"), BACK);
        CardSlot weak = onField(guest, enemy("w", 1, 2));
        onField(guest, enemy("s", 1, 3));
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        confirmIfAsked(holder);
        assertThat(room.getPrompt().getValidIds()).containsExactly("w");
        answer("w");
        assertThat(guest.getHand()).contains(weak);
    }

    @Test
    void stromperPlaysAGreenStructureFromTheTributesForOneLess() {
        CardSlot fromTributes = structure("ts", "Torre Tributos", 1, "G");
        host.getTributeZone().add(fromTributes);
        host.getHand().add(structure("rs", "Torre Roja", 1, "R"));
        CardSlot stromper = creature("sp", "Stromper", 5, 4, "G", 11, "fauna", "vegetación");
        int before = gems();
        summon(stromper);
        accept(stromper);
        assertThat(room.getPrompt().getValidIds()).containsExactly("ts");
        answer("ts");
        drain();
        assertThat(host.getField()).contains(fromTributes);
        assertThat(gems()).isEqualTo(before - 5);
    }

    @Test
    void stromperGivesItsEvolutionImmunityToLevelTwoOrLessEnemyCards() {
        CardSlot holder = onField(host, creature("ho", "Portador", 5, 5, "G", 12));
        holder.getResources().add(creature("sp", "Stromper", 5, 4, "G", 11));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(holder)).contains("Inmune 2");
        assertThat(EffectEngine.immuneTo(holder, 2)).isTrue();
        assertThat(EffectEngine.immuneTo(holder, 3)).isFalse();
    }

    // ------------------------------------------------------------------ Violeta

    @Test
    void bokLooksAtBothTopCardsSendsEachToTopOrBottomThenDraws() {
        CardSlot bok = onField(host, creature("bk", "Bok", 1, 1, "P", 2, "interdimensional"));
        String hostTop = host.getDeck().get(0).getInstanceId();
        String hostSecond = host.getDeck().get(1).getInstanceId();
        String guestTop = guest.getDeck().get(0).getInstanceId();
        destroyByGuest(bok);
        confirmIfAsked(bok);
        // Its own card: to the bottom
        Prompt own = room.getPrompt();
        assertThat(own.getValidIds()).containsExactly(hostTop);
        answer(own);
        // The opponent's card: back on top of the opponent's deck
        Prompt theirs = room.getPrompt();
        assertThat(theirs.getValidIds()).containsExactly(guestTop);
        assertThat(theirs.getInstruction()).contains("mazo de tu oponente");
        answer(theirs, guestTop);
        drain();
        assertThat(host.getDeck().get(host.getDeck().size() - 1).getInstanceId()).isEqualTo(hostTop);
        assertThat(guest.getDeck().get(0).getInstanceId()).isEqualTo(guestTop);
        assertThat(guest.getDeck()).hasSize(8);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains(hostSecond);
    }

    @Test
    void coxTributesAHandCardToDestroyALevelOneEnemy() {
        CardSlot foe = onField(guest, enemy("e1", 1, 3));
        onField(guest, enemy("e2", 2, 3));
        CardSlot pay = inHand("h", "Pago", 1, 1, "R");
        CardSlot cox = creature("cx", "Cox", 1, 1, "P", 1, "interdimensional");
        summonByEffectToFront(cox);
        untilOffered(cox);
        accept(cox);
        answer("h");
        assertThat(room.getPrompt().getValidIds()).containsExactly("e1");
        answer("e1");
        drain();
        assertThat(host.getTributeZone()).contains(pay);
        assertThat(guest.getDiscardPile()).contains(foe);
    }

    @Test
    void minSummonsAVioletFromTheTributesThenTheOpponentMaySummonOneOfTheirs() {
        CardSlot min = onField(host, creature("mn", "Min", 2, 2, "P", 3, "interdimensional"));
        CardSlot violet = creature("vt", "Violeta", 2, 2, "P", 5);
        host.getTributeZone().addAll(List.of(violet, creature("rt", "Roja", 1, 1, "R", 3)));
        CardSlot theirs = creature("gt", "Del Rival", 1, 1, "R", 4);
        guest.getTributeZone().add(theirs);
        destroyByGuest(min);
        accept(min);
        assertThat(room.getPrompt().getValidIds()).containsExactly("vt");
        answer("vt");
        assertThat(host.getField()).contains(violet);
        assertThat(EffectEngine.inFrontLine(violet)).isTrue();
        Prompt rival = room.getPrompt();
        assertThat(rival.getPlayerId()).isEqualTo("guest");
        assertThat(rival.getValidIds()).containsExactly("gt");
        answer(rival, "gt");
        drain();
        assertThat(guest.getField()).contains(theirs);
        assertThat(EffectEngine.inFrontLine(theirs)).isTrue();
    }

    @Test
    void minLetsTheOpponentDecline() {
        CardSlot min = onField(host, creature("mn", "Min", 2, 2, "P", 3, "interdimensional"));
        host.getTributeZone().add(creature("vt", "Violeta", 2, 2, "P", 5));
        CardSlot theirs = creature("gt", "Del Rival", 1, 1, "R", 4);
        guest.getTributeZone().add(theirs);
        destroyByGuest(min);
        accept(min);
        answer("vt");
        answer(room.getPrompt());
        drain();
        assertThat(guest.getTributeZone()).contains(theirs);
    }

    @Test
    void mhoIsSummonedFromTheTributesWhenTheOpponentHasFiveTributes() {
        CardSlot mho = creature("mh", "Mho", 3, 3, "P", 7, "interdimensional");
        host.getTributeZone().add(mho);
        for (int i = 0; i < 4; i++) guest.getTributeZone().add(TestTable.utility("g" + i, "Tributo " + i));
        assertThat(Chain.activateByPlayer(room, "host", "mh", "mho:en-tributos")).isNotNull();
        CardSlot theirs = creature("gt", "Del Rival", 2, 2, "R", 4);
        guest.getTributeZone().add(theirs);
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "mh", "mho:en-tributos")).isNull();
        assertThat(host.getField()).contains(mho);
        assertThat(EffectEngine.inFrontLine(mho)).isTrue();
        assertThat(gems()).isEqualTo(before - 1);
        Prompt rival = room.getPrompt();
        assertThat(rival.getPlayerId()).isEqualTo("guest");
        assertThat(rival.getValidIds()).containsExactly("gt");
        answer(rival, "gt");
        drain();
        assertThat(guest.getField()).contains(theirs);
    }

    @Test
    void gorgusSummonsAVioletWhenAnEnemyEntersTheFrontLineByEffect() {
        CardSlot gorgus = onField(host, creature("gs", "Gorgus", 5, 4, "P", 12, "interdimensional"));
        CardSlot violet = creature("vt", "Violeta", 2, 2, "P", 5);
        host.getTributeZone().add(violet);
        CardSlot foe = onField(guest, enemy("e", 2, 3));
        EffectEngine.record(room, GameEvent.MOVE, "guest", foe, null, "frontLine", null, null, null).setEffectId("efecto-rival");
        Chain.advance(room);
        accept(gorgus);
        answer("vt");
        drain();
        assertThat(host.getField()).contains(violet);
    }

    @Test
    void gorgusIsNotOfferedWhenTheEnemyMovesUpByTheRules() {
        onField(host, creature("gs", "Gorgus", 5, 4, "P", 12, "interdimensional"));
        host.getTributeZone().add(creature("vt", "Violeta", 2, 2, "P", 5));
        CardSlot foe = onField(guest, enemy("e", 2, 3));
        EffectEngine.record(room, GameEvent.MOVE, "guest", foe, null, "frontLine", null, null, null);
        Chain.advance(room);
        Prompt p = room.getPrompt();
        assertThat(p == null || !p.getValidIds().contains("gs")).isTrue();
    }

    @Test
    void gorgusRitualNeedsALevelThreeSummonerAmongTheLevelsAddingUpToFive() {
        CardSlot three = onField(host, creature("l3", "Violeta 3", 3, 3, "P", 7), BACK);
        CardSlot two = onField(host, creature("l2", "Violeta 2", 2, 2, "P", 5), BACK);
        CardSlot gorgus = creature("gs", "Gorgus", 5, 4, "P", 12, "interdimensional");
        gorgus.setSpecialSummonKind("ritual");
        gorgus.setSpecialCost(1);
        host.getHand().add(gorgus);
        assertThat(Rituals.ritualByPlayer(room, "host", "gs", null)).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("l3");
        // The level 2 one cannot be the summoner
        room.setPrompt(null);
        assertThat(Rituals.ritualByPlayer(room, "host", "gs", "l2")).isNotNull();
        assertThat(Rituals.ritualByPlayer(room, "host", "gs", "l3")).isNull();
        answer("l2");
        drain();
        assertThat(host.getField()).contains(gorgus).doesNotContain(three, two);
    }

    // ------------------------------------------------------------------ Rojo

    @Test
    void filoLegionarioSummonedByEffectGainsThreeAndCeleridadUntilTheEndOfTheOpponentsTurn() {
        CardSlot filo = creature("fl", "Filo Legionario", 1, 1, "R", 3, "legionario");
        summonByEffectToFront(filo);
        drain();
        assertThat(EffectEngine.strength(filo)).isEqualTo(6);
        assertThat(EffectEngine.keywords(filo)).contains("Celeridad");
        TurnActions.passTurn(room, "host");
        drain();
        assertThat(EffectEngine.strength(filo)).isEqualTo(6);
        TurnActions.passTurn(room, "guest");
        drain();
        assertThat(EffectEngine.strength(filo)).isEqualTo(3);
        assertThat(EffectEngine.keywords(filo)).doesNotContain("Celeridad");
    }

    @Test
    void filoLegionarioNormallySummonedGainsNothing() {
        CardSlot filo = creature("fl", "Filo Legionario", 1, 1, "R", 3, "legionario");
        summon(filo);
        drain();
        assertThat(EffectEngine.strength(filo)).isEqualTo(3);
    }

    @Test
    void escuadraLegionariaSacrificesAnotherAllyInsteadOfBeingRemoved() {
        CardSlot escuadra = onField(host, creature("el", "Escuadra Legionaria", 3, 3, "R", 7, "legionario"));
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "B"), BACK);
        destroyByGuest(escuadra);
        useReplacement();
        assertThat(room.getPrompt().getPurpose()).isEqualTo(PromptPurpose.REPLACE_SACRIFICE.id());
        answer("al");
        drain();
        assertThat(host.getField()).contains(escuadra).doesNotContain(ally);
        assertThat(host.getTributeZone()).contains(ally);
    }

    @Test
    void escuadraLegionariaGivesRedOrLegionarioAlliesOneStrengthOnItsTurn() {
        CardSlot escuadra = onField(host, creature("el", "Escuadra Legionaria", 3, 3, "R", 7, "legionario"));
        CardSlot red = onField(host, creature("rd", "Roja", 1, 1, "R", 3), BACK);
        CardSlot blue = onField(host, creature("bl", "Azul", 1, 1, "B", 3), BACK);
        CardSlot legion = onField(host, creature("lg", "Legionaria Azul", 1, 1, "B", 3, "legionario"), BACK);
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(red)).isEqualTo(4);
        assertThat(EffectEngine.strength(legion)).isEqualTo(4);
        assertThat(EffectEngine.strength(blue)).isEqualTo(3);
        assertThat(EffectEngine.strength(escuadra)).isEqualTo(8);
        room.setCurrentTurnUserId("guest");
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(red)).isEqualTo(3);
    }

    @Test
    void campeonDeLaLegionSummonsARedLevelOneFromTheHandForFreeWhenItAttacks() {
        CardSlot campeon = onField(host, creature("cl", "Campeón de la Legión", 6, 4, "R", 12, "legionario"));
        CardSlot red = inHand("r1", "Roja 1", 2, 1, "R");
        inHand("r2", "Roja 2", 2, 2, "R");
        int before = gems();
        assertThat(CombatRules.attackByPlayer(room, "host", "cl", null)).isNull();
        accept(campeon);
        assertThat(room.getPrompt().getValidIds()).containsExactly("r1");
        answer("r1");
        assertThat(host.getField()).contains(red);
        assertThat(gems()).isEqualTo(before);
    }

    @Test
    void campeonDeLaLegionTributesAHandCardInsteadOfARedAllyDestroyedTwicePerOpponentTurn() {
        onField(host, creature("cl", "Campeón de la Legión", 6, 4, "R", 12, "legionario"));
        CardSlot ally = onField(host, creature("r2", "Roja 2", 2, 2, "R", 4), BACK);
        CardSlot h1 = inHand("h1", "Mano 1", 1, 1, "B");
        CardSlot h2 = inHand("h2", "Mano 2", 1, 1, "B");
        inHand("h3", "Mano 3", 1, 1, "B");
        room.setCurrentTurnUserId("guest");
        for (CardSlot pay : List.of(h1, h2)) {
            destroyByGuest(ally);
            useReplacement();
            assertThat(room.getPrompt().getPurpose()).isEqualTo(PromptPurpose.REPLACE_HAND.id());
            answer(pay.getInstanceId());
            assertThat(host.getField()).contains(ally);
            assertThat(host.getTributeZone()).contains(pay);
        }
        // A third time this turn: destroyed
        destroyByGuest(ally);
        drain();
        assertThat(host.getField()).doesNotContain(ally);
    }

    @Test
    void campeonDeLaLegionDoesNotGuardOnItsOwnersTurnNorHigherOrOtherColourAllies() {
        onField(host, creature("cl", "Campeón de la Legión", 6, 4, "R", 12, "legionario"));
        CardSlot ally = onField(host, creature("r2", "Roja 2", 2, 2, "R", 4), BACK);
        CardSlot high = onField(host, creature("r3", "Roja 3", 3, 3, "R", 6), BACK);
        inHand("h1", "Mano 1", 1, 1, "B");
        destroyByGuest(ally);
        drain();
        assertThat(host.getField()).doesNotContain(ally);
        room.setCurrentTurnUserId("guest");
        destroyByGuest(high);
        drain();
        assertThat(host.getField()).doesNotContain(high);
    }

    // ------------------------------------------------------------------ Blanco

    @Test
    void lunaExhaustsItselfToDrawTwoThenTributesAHandCard() {
        CardSlot luna = onField(host, structure("lu", "Luna de la Dimensión Alterna", 1, "W"), BACK);
        int hand = host.getHand().size();
        assertThat(Chain.activateByPlayer(room, "host", "lu", "luna-de-la-dimension-alterna:turno-aliado")).isNull();
        Prompt tribute = room.getPrompt();
        answer(tribute, tribute.getValidIds().get(0));
        drain();
        assertThat(luna.isTapped()).isTrue();
        assertThat(host.getHand()).hasSize(hand + 1);
        assertThat(host.getTributeZone()).hasSize(1);
        assertThat(Chain.activateByPlayer(room, "host", "lu", "luna-de-la-dimension-alterna:turno-aliado")).isNotNull();
    }

    @Test
    void lunaMayGoToTheDiscardForAGemAtTheStartOfItsOwnersTurn() {
        CardSlot luna = onField(host, structure("lu", "Luna de la Dimensión Alterna", 1, "W"), BACK);
        TurnActions.passTurn(room, "host");
        Prompt p = room.getPrompt();
        assertThat(p == null || !p.getValidIds().contains("lu")).isTrue();
        drain();
        TurnActions.passTurn(room, "guest");
        untilOffered(luna);
        accept(luna);
        drain();
        assertThat(host.getDiscardPile()).contains(luna);
    }

    @Test
    void conocimientoOcultoSendsTheHandToTheBottomAndDrawsUpToFive() {
        List<CardSlot> hand = List.of(inHand("h1", "Mano 1", 1, 1, "W"), inHand("h2", "Mano 2", 1, 1, "W"), inHand("h3", "Mano 3", 1, 1, "W"));
        play(utility("co", "Conocimiento Oculto", 1, "W"));
        if (room.getPrompt() != null) answer(room.getPrompt(), "co");
        drain();
        assertThat(host.getHand()).hasSize(5).doesNotContainAnyElementsOf(hand);
        assertThat(host.getDeck().subList(host.getDeck().size() - 3, host.getDeck().size())).containsExactlyElementsOf(hand);
    }

    @Test
    void selloDeContencionKeepsTheChosenEnemyFromActivatingEffectsThisTurn() {
        CardSlot muerte = onField(guest, creature("mc", "Muerte Caminante", 4, 4, "P", 10));
        muerte.setTapped(true);
        CardSlot other = onField(guest, enemy("e", 1, 2));
        play(utility("sc", "Sello de Contención", 2, "W"));
        answer("mc");
        drain();
        assertThat(EffectEngine.keywords(muerte)).contains(EffectEngine.NO_EFFECTS);
        Removals.destroy(room, other, guest, false, EffectEngine.CAUSE_BATTLE, "host", "x", null);
        Chain.advance(room);
        drain();
        assertThat(muerte.isTapped()).isTrue();
    }

    @Test
    void withoutTheSelloTheEnemyActivatesItsEffect() {
        CardSlot muerte = onField(guest, creature("mc", "Muerte Caminante", 4, 4, "P", 10));
        muerte.setTapped(true);
        CardSlot other = onField(guest, enemy("e", 1, 2));
        Removals.destroy(room, other, guest, false, EffectEngine.CAUSE_BATTLE, "host", "x", null);
        Chain.advance(room);
        drain();
        assertThat(muerte.isTapped()).isFalse();
    }

    // ------------------------------------------------------------------ Cards the designer changed

    @Test
    void dragaourGivesItsEvolutionTheDestructionOfAnEnemyOfStrengthSixOrLess() {
        CardSlot holder = onField(host, creature("ho", "Portador", 5, 5, "G", 12));
        holder.getResources().add(creature("dg", "Dragaour", 4, 4, "G", 11));
        CardSlot six = onField(guest, enemy("e6", 2, 6));
        onField(guest, enemy("e7", 2, 7));
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        confirmIfAsked(holder);
        assertThat(room.getPrompt().getValidIds()).containsExactly("e6");
        answer("e6");
        assertThat(guest.getDiscardPile()).contains(six);
    }

    @Test
    void lynaReactivatesAStructureOfAnyColourOfCostOneOrLess() {
        CardSlot red = onField(host, structure("sr", "Torre Roja", 1, "R"), BACK);
        red.setTapped(true);
        CardSlot lyna = creature("ly", "Lyna", 1, 1, "G", 3, "hada");
        summon(lyna);
        confirmIfAsked(lyna);
        // No ally of level 1 to add: the first choice is skipped; then "¿Agotas a Lyna…?"
        Prompt exhaust = room.getPrompt();
        assertThat(exhaust.getValidIds()).containsExactly("ly");
        answer(exhaust, "ly");
        assertThat(room.getPrompt().getValidIds()).containsExactly("sr");
        answer("sr");
        assertThat(red.isTapped()).isFalse();
    }

    @Test
    void fervorDeGuerraCostsOneLessWithFewerLivesThanTheOpponent() {
        CardSlot fervor = utility("fg", "Fervor de Guerra", 2, "R");
        EffectScript script = CardEffects.of(fervor).stream().filter(s -> "fervor-de-guerra:en-mano".equals(s.id())).findFirst().orElseThrow();
        Action.Play play = (Action.Play) ((EffectStep.Do) script.steps().get(0)).action();
        assertThat(play.reduce().of(room, "host")).isZero();
        host.getLifeStack().remove(0);
        assertThat(play.reduce().of(room, "host")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ Requerimientos

    @Test
    void everySpecialSummonOfTheNewFilesHasItsRequirementSection() {
        for (String name : List.of("V-Flyer", "Unit V", "Cazador Antibiológico", "Ciervo Frondoso", "Dragón Vela", "Dracoverdis", "Stromper",
                "Min", "Mho", "Gorgus", "Escuadra Legionaria", "Campeón de la Legión")) {
            assertThat(CardEffects.requirementOf(card("x", name, 1, 1, "R"))).as(name).isNotNull();
        }
    }
}
