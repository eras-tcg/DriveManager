package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.actions.TurnActions;
import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1.1 (the edition laid out on 2026-11-17): one test (or more) per effect programmed from the printed text, and the designer's
 * answers of 2026-11-19 (Dendrovorax, Revitalización Floral, Dolicoquirus, Tory, Judeu, Orden de Búsqueda, Infección Mortal).
 */
@Tag("cards")
@Tag("e11")
class E11CardsTest extends CardBatchSupport {

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

    /** A card of the host destroys that enemy by effect. */
    private void destroyByHost(CardSlot enemy, String byCard) {
        Removals.destroy(room, enemy, guest, false, EffectEngine.CAUSE_EFFECT, "host", byCard, null);
        if (room.getPrompt() == null) Chain.advance(room);
    }

    private List<String> ids(List<CardSlot> cards) {
        return cards.stream().map(CardSlot::getInstanceId).toList();
    }

    // ------------------------------------------------------------------ Azul

    @Test
    void agenteTributedAsMaterialTakesABlueCreatureThenTributesAHandCard() {
        CardSlot agente = card("ag", "Agente V-1", 1, 1, "B");
        CardSlot blue = creature("bl", "Azul", 2, 2, "B", 4);
        CardSlot red = creature("rd", "Roja", 2, 2, "R", 4);
        host.getTributeZone().addAll(List.of(agente, blue, red));
        CardSlot pay = inHand("pg", "Pago", 1, 1, "R");
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", agente, "materials", "tributeZone", null, new java.util.HashMap<>(Map.of("byCard", "x")), null);
        Chain.advance(room);
        accept(agente);
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).contains("bl").doesNotContain("rd");
        answer(take, "bl");
        answer("pg");
        assertThat(host.getHand()).contains(blue).doesNotContain(pay);
        assertThat(host.getTributeZone()).contains(pay);
    }

    @Test
    void agenteTributesThreeFromTheTopThenTakesABlueOrAutomata() {
        host.getDeck().addAll(0, List.of(withClasses(card("a1", "Autómata Roja", 1, 1, "R"), "autómata"), card("r1", "Roja", 1, 1, "R"),
                card("r2", "Roja 2", 1, 1, "R")));
        summon(card("ag", "Agente V-1", 1, 1, "B"));
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).containsExactly("a1");
        answer(take, "a1");
        assertThat(ids(host.getHand())).contains("a1");
    }

    @Test
    void es101MaySendFiveBlueTributesToTheBottomToReactivate() {
        for (int i = 0; i < 5; i++) host.getTributeZone().add(card("t" + i, "Azul " + i, 1, 1, "B"));
        CardSlot es = creature("es", "ES-101", 3, 3, "B", 8, "autómata");
        summon(es);
        accept(es);
        answer("t0", "t1", "t2", "t3", "t4");
        assertThat(host.getTributeZone()).isEmpty();
        assertThat(es.isTapped()).isFalse();
    }

    @Test
    void es101WithMaterialsExhaustsAStructureInsteadOfBeingDestroyed() {
        CardSlot es = onField(host, creature("es", "ES-101", 3, 3, "B", 8, "autómata"));
        es.getMaterials().add(card("m", "Material", 2, 2, "B"));
        CardSlot tower = onField(host, structure("st", "Torre", 1, "B"), BACK);
        Removals.destroy(room, es, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        Prompt replace = room.getPrompt();
        assertThat(replace.getPurpose()).isEqualTo(PromptPurpose.REPLACE.id());
        answer(replace.getEffects().get(0).effectId());
        assertThat(room.getPrompt().getPurpose()).isEqualTo(PromptPurpose.REPLACE_TAP.id());
        answer("st");
        assertThat(host.getField()).contains(es);
        assertThat(tower.isTapped()).isTrue();
    }

    @Test
    void es101WithoutMaterialsIsDestroyed() {
        CardSlot es = onField(host, creature("es", "ES-101", 3, 3, "B", 8, "autómata"));
        onField(host, structure("st", "Torre", 1, "B"), BACK);
        Removals.destroy(room, es, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        drain();
        assertThat(host.getField()).doesNotContain(es);
    }

    @Test
    void esferaDeEnergiaReducesAnEnemyByThreeUntilTheEndOfTheTurn() {
        CardSlot foe = onField(guest, enemy("e", 2, 7));
        play(utility("ef", "Esfera de Energía", 1, "B"));
        answer("e");
        drain();
        assertThat(EffectEngine.strength(foe)).isEqualTo(4);
    }

    // ------------------------------------------------------------------ Estructuras (una por color)

    @Test
    void temploTributedByAGreenEffectIsPlayedWithoutPayingItsCost() {
        CardSlot templo = structure("to", "Templo Olvidado del Bosque", 1, "G");
        host.getTributeZone().add(templo);
        CardSlot green = onField(host, card("gr", "Verde", 1, 1, "G"));
        int before = gems();
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", templo, "hand", "tributeZone", null,
                new java.util.HashMap<>(Map.of("byCard", green.getInstanceId())), null).setEffectId("efecto-verde");
        Chain.advance(room);
        accept(templo);
        drain();
        assertThat(host.getField()).contains(templo);
        assertThat(gems()).isEqualTo(before);
    }

    @Test
    void laboratorioIsNotPlayedWhenARedCardTributedIt() {
        CardSlot lab = structure("lb", "Laboratorio Abandonado", 1, "P");
        host.getTributeZone().add(lab);
        CardSlot red = onField(host, card("rd", "Roja", 1, 1, "R"));
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", lab, "hand", "tributeZone", null,
                new java.util.HashMap<>(Map.of("byCard", red.getInstanceId())), null).setEffectId("efecto-rojo");
        Chain.advance(room);
        Prompt p = room.getPrompt();
        assertThat(p == null || !p.getValidIds().contains("lb")).isTrue();
    }

    @Test
    void campamentoExhaustsToDrawAndTributeWhenARedCreatureIsSummoned() {
        CardSlot camp = onField(host, structure("cn", "Campamento Nómada", 1, "R"), BACK);
        int hand = host.getHand().size();
        summon(card("rd", "Roja", 1, 1, "R"));
        accept(camp);
        Prompt tribute = room.getPrompt();
        answer(tribute, tribute.getValidIds().get(0));
        assertThat(camp.isTapped()).isTrue();
        assertThat(host.getHand()).hasSize(hand);
        assertThat(host.getTributeZone()).hasSize(1);
    }

    @Test
    void pilaresAreNotOfferedForACreatureOfAnotherColor() {
        onField(host, structure("pi", "Pilares de la Dimensión de las Entidades", 1, "B"), BACK);
        summon(card("rd", "Roja", 1, 1, "R"));
        Prompt p = room.getPrompt();
        assertThat(p == null || !p.getValidIds().contains("pi")).isTrue();
    }

    // ------------------------------------------------------------------ Verde

    @Test
    void gorgocarnisSendsAWeakerEnemyToItsOwnersHand() {
        CardSlot gorgo = onField(host, creature("go", "Gorgocarnis", 2, 2, "G", 6, "fauna"));
        CardSlot weak = onField(guest, enemy("w", 1, 5));
        onField(guest, enemy("s", 1, 6));
        assertThat(CombatRules.attackByPlayer(room, "host", "go", null)).isNull();
        accept(gorgo);
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("w");
        answer(target, "w");
        assertThat(guest.getHand()).contains(weak);
    }

    @Test
    void rutilocristalisMaySendFiveTributesForAGem() {
        for (int i = 0; i < 5; i++) host.getTributeZone().add(withClasses(card("t" + i, "Fauna " + i, 1, 1, "R"), "fauna"));
        CardSlot rut = creature("ru", "Rutilocristalis", 3, 3, "G", 7, "fauna");
        int before = gems();
        summon(rut);
        accept(rut);
        answer("t0", "t1", "t2", "t3", "t4");
        assertThat(gems()).isEqualTo(before - 3 + 1);
    }

    @Test
    void rutilocristalisGivesItsEvolutionAnExhaustOfAWeakerEnemyWhenItIsExhausted() {
        CardSlot holder = onField(host, creature("ho", "Portador", 4, 4, "G", 11));
        holder.getResources().add(creature("ru", "Rutilocristalis", 3, 3, "G", 7, "fauna"));
        CardSlot weak = onField(guest, enemy("w", 1, 5));
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        untilOffered(holder);
        accept(holder);
        answer("w");
        assertThat(weak.isTapped()).isTrue();
    }

    @Test
    void dendrovoraxGainsAGemForEveryTwoExhaustedCreaturesOrStructuresOnBothFields() {
        onField(host, card("a", "A", 1, 1, "G")).setTapped(true);
        onField(host, structure("s", "S", 1, "G"), BACK).setTapped(true);
        onField(guest, enemy("e1", 1, 2)).setTapped(true);
        onField(guest, enemy("e2", 1, 2)).setTapped(true);
        onField(guest, enemy("e3", 1, 2)).setTapped(true);
        int before = gems();
        summon(creature("dv", "Dendrovorax", 8, 5, "G", 13, "vegetación"));
        drain();
        assertThat(gems()).isEqualTo(before - 8 + 2);
    }

    @Test
    void dendrovoraxReactivatesItselfAndUpToTwoGreenAlliesOfLevelTwoOrLessAtTheEndOfTheTurn() {
        CardSlot dv = onField(host, creature("dv", "Dendrovorax", 8, 5, "G", 13, "vegetación"));
        dv.setTapped(true);
        CardSlot a = onField(host, card("a", "A", 1, 2, "G"));
        CardSlot b = onField(host, card("b", "B", 1, 1, "G"));
        CardSlot high = onField(host, card("c", "C", 1, 3, "G"));
        a.setTapped(true);
        b.setTapped(true);
        high.setTapped(true);
        TurnRules.turnEnding(room, "host");
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("dv")) answer("dv");
        Prompt pick = room.getPrompt();
        assertThat(pick.getValidIds()).containsExactlyInAnyOrder("a", "b");
        answer(pick, "a", "b");
        assertThat(dv.isTapped()).isFalse();
        assertThat(a.isTapped()).isFalse();
        assertThat(b.isTapped()).isFalse();
        assertThat(high.isTapped()).isTrue();
    }

    @Test
    void revitalizacionFloralReactivatesGreenCardsWhoseCostsAddUpToTwoOrLess() {
        for (int i = 0; i < 5; i++) host.getTributeZone().add(card("t" + i, "Verde " + i, 1, 1, "G"));
        CardSlot one = onField(host, card("a", "A", 1, 1, "G"));
        CardSlot st = onField(host, structure("s", "S", 1, "G"), BACK);
        CardSlot two = onField(host, card("b", "B", 2, 2, "G"));
        CardSlot three = onField(host, card("c", "C", 3, 3, "G"));
        for (CardSlot c : List.of(one, st, two, three)) c.setTapped(true);
        play(utility("rf", "Revitalización Floral", 1, "G"));
        answer("t0", "t1", "t2", "t3", "t4");
        Prompt pick = room.getPrompt();
        assertThat(pick.getValidIds()).containsExactlyInAnyOrder("a", "s", "b");
        assertThat(Answers.answer(room, "host", pick.getId(), List.of("a", "b"), List.of())).contains("2 o menos");
        answer(pick, "a", "s");
        assertThat(one.isTapped()).isFalse();
        assertThat(st.isTapped()).isFalse();
        assertThat(two.isTapped()).isTrue();
    }

    // ------------------------------------------------------------------ Violeta

    @Test
    void dolicoquirusSendsTwoCopiesToTheBottomToBePlacedInTheRetaguardia() {
        CardSlot doli = creature("dq", "Dolicoquirus", 1, 1, "P", 2, "mutante");
        CardSlot c1 = card("c1", "Copia", 1, 1, "P");
        CardSlot c2 = card("c2", "Copia", 1, 1, "P");
        CardSlot lone = card("ln", "Sola", 1, 1, "P");
        host.getTributeZone().addAll(List.of(doli, c1, c2, lone));
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "P"));
        destroyByHost(onField(guest, enemy("e", 1, 2)), ally.getInstanceId());
        accept(doli);
        Prompt pay = room.getPrompt();
        assertThat(pay.getValidIds()).containsExactlyInAnyOrder("c1", "c2");
        answer(pay, "c1", "c2");
        assertThat(host.getField()).contains(doli);
        assertThat(host.getDeck().subList(host.getDeck().size() - 2, host.getDeck().size())).containsExactlyInAnyOrder(c1, c2);
    }

    @Test
    void dolicoquirusIsNotOfferedWithoutTwoCopies() {
        CardSlot doli = creature("dq", "Dolicoquirus", 1, 1, "P", 2, "mutante");
        host.getTributeZone().addAll(List.of(doli, card("a", "Una", 1, 1, "P"), card("b", "Otra", 1, 1, "P")));
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "P"));
        destroyByHost(onField(guest, enemy("e", 1, 2)), ally.getInstanceId());
        Prompt p = room.getPrompt();
        assertThat(p == null || !p.getValidIds().contains("dq")).isTrue();
    }

    @Test
    void cinoplocusTributesUpToTwoFromTheHandAndTakesAsManyVioletTributes() {
        CardSlot cino = onField(host, creature("ci", "Cinoplocus", 2, 2, "P", 4, "mutante"));
        CardSlot h1 = inHand("h1", "Mano 1", 1, 1, "R");
        CardSlot h2 = inHand("h2", "Mano 2", 1, 1, "R");
        CardSlot v1 = card("v1", "Violeta 1", 1, 1, "P");
        CardSlot v2 = card("v2", "Violeta 2", 1, 1, "P");
        host.getTributeZone().addAll(List.of(v1, v2));
        Removals.destroy(room, cino, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        accept(cino);
        answer("h1", "h2");
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).contains("v1", "v2").doesNotContain("h1", "h2");
        answer(take, "v1", "v2");
        assertThat(host.getHand()).contains(v1, v2).doesNotContain(h1, h2);
    }

    @Test
    void hematofagoComehombresTributesThreeAndTakesAVioletWhenItAttacks() {
        host.getDeck().addAll(0, List.of(card("v", "Violeta", 1, 1, "P"), card("r1", "Roja", 1, 1, "R"), card("r2", "Roja 2", 1, 1, "R")));
        onField(host, creature("hc", "Hematófago Comehombres", 3, 3, "P", 8, "bestia"));
        assertThat(CombatRules.attackByPlayer(room, "host", "hc", null)).isNull();
        untilOffered(card("v", "Violeta", 1, 1, "P"));
        answer("v");
        assertThat(ids(host.getHand())).contains("v");
    }

    @Test
    void mastigoraxGainsProvocarAndTwoStrengthPerCopyInTheTributes() {
        for (int i = 0; i < 5; i++) host.getTributeZone().add(withClasses(card("t" + i, "Mutante " + i, 1, 1, "R"), "mutante"));
        host.getTributeZone().addAll(List.of(card("m1", "Mastigórax", 3, 3, "P"), card("m2", "Mastigórax", 3, 3, "P")));
        CardSlot masti = creature("ms", "Mastigórax", 3, 3, "P", 6, "mutante");
        summon(masti);
        accept(masti);
        answer("t0", "t1", "t2", "t3", "t4");
        drain();
        assertThat(EffectEngine.strength(masti)).isEqualTo(10);
        assertThat(EffectEngine.keywords(masti)).contains("Provocar");
    }

    @Test
    void gorgathHasLetalidadOnePerCopyInTheTributes() {
        CardSlot gorgath = onField(host, creature("gg", "Gorgath", 7, 5, "P", 13, "mutante"));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywordLevel(gorgath, "Letalidad")).isNull();
        host.getTributeZone().addAll(List.of(card("g1", "Gorgath", 7, 5, "P"), card("g2", "Gorgath", 7, 5, "P")));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywords(gorgath)).contains("Letalidad 2");
    }

    @Test
    void infeccionMortalSummonsALowerVioletFromTheTributesWhenTheChosenEnemyIsDestroyedThisTurn() {
        CardSlot foe = onField(guest, enemy("e", 3, 8));
        CardSlot low = creature("lw", "Violeta Baja", 5, 2, "P", 4);
        CardSlot same = creature("sm", "Violeta Igual", 1, 3, "P", 4);
        host.getTributeZone().addAll(List.of(low, same));
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "P"));
        CardSlot infeccion = utility("im", "Infección Mortal", 1, "P");
        play(infeccion);
        answer("e");
        drain();
        assertThat(host.getDiscardPile()).contains(infeccion);
        int before = gems();
        destroyByHost(foe, ally.getInstanceId());
        untilOffered(infeccion);
        accept(infeccion);
        Prompt pick = room.getPrompt();
        assertThat(pick.getValidIds()).containsExactly("lw");
        answer(pick, "lw");
        assertThat(host.getField()).contains(low);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void infeccionMortalDoesNothingWhenTheEnemyIsDestroyedOnALaterTurn() {
        CardSlot foe = onField(guest, enemy("e", 3, 8));
        host.getTributeZone().add(creature("lw", "Violeta Baja", 1, 2, "P", 4));
        play(utility("im", "Infección Mortal", 1, "P"));
        answer("e");
        drain();
        TurnActions.passTurn(room, "host");
        drain();
        Removals.destroy(room, foe, guest, false, EffectEngine.CAUSE_EFFECT, "host", "x", null);
        Chain.advance(room);
        Prompt p = room.getPrompt();
        assertThat(p == null || !p.getValidIds().contains("im")).isTrue();
    }

    // ------------------------------------------------------------------ Rojo

    @Test
    void judeuSendsAnAllyToTheHandToSummonARedOfAnotherNameForOneLess() {
        CardSlot judeu = onField(host, creature("jd", "Judeu", 1, 1, "R", 2, "mercenario"));
        CardSlot ally = onField(host, creature("al", "Caballero", 2, 2, "R", 5), BACK);
        CardSlot other = inHand("ot", "Mercenario Rojo", 2, 2, "R");
        int before = gems();
        assertThat(CombatRules.attackByPlayer(room, "host", "jd", null)).isNull();
        accept(judeu);
        answer("al");
        Prompt summon = room.getPrompt();
        assertThat(summon.getValidIds()).containsExactly("ot");
        answer(summon, "ot");
        assertThat(host.getHand()).contains(ally);
        assertThat(host.getField()).contains(other);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void ventoKeepsAnEnemyFromAttackingAndBlockingUntilTheEndOfTheOpponentsTurn() {
        onField(host, creature("vn", "Vento", 2, 2, "R", 5, "mercenario"));
        CardSlot foe = onField(guest, enemy("e", 1, 6));
        onField(guest, enemy("s", 1, 7));
        assertThat(CombatRules.attackByPlayer(room, "host", "vn", null)).isNull();
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("e");
        answer(target, "e");
        assertThat(EffectEngine.keywords(foe)).contains("No puede atacar", "No puede bloquear");
    }

    @Test
    void toryIsSummonedWhenAnAllysEffectSendsItToTheHand() {
        CardSlot judeu = onField(host, creature("jd", "Judeu", 1, 1, "R", 2, "mercenario"));
        CardSlot tory = onField(host, creature("ty", "Tory", 3, 3, "R", 7, "mercenario"), BACK);
        inHand("ot", "Mercenario Rojo", 2, 2, "R");
        for (int i = 0; i < 5; i++) host.getTributeZone().add(card("t" + i, "Roja " + i, 1, 1, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "jd", null)).isNull();
        accept(judeu);
        answer("ty");
        answer("ot");
        untilOffered(tory);
        accept(tory);
        answer("t0", "t1", "t2", "t3", "t4");
        drain();
        assertThat(host.getField()).contains(tory);
    }

    @Test
    void robinGainsTwoStrengthAndMayAttackWithoutBeingExhausted() {
        CardSlot robin = creature("rb", "Robin", 3, 3, "R", 6, "mercenario");
        summonByEffectToFront(robin);
        drain();
        assertThat(EffectEngine.strength(robin)).isEqualTo(8);
    }

    @Test
    void krugelGivesTwoStrengthToAnAllySummonedByEffect() {
        onField(host, creature("kg", "Krugel", 7, 5, "R", 13, "caballero"));
        CardSlot summoned = card("sm", "Invocado", 1, 1, "R");
        summoned.setStrength(3);
        summonByEffectToFront(summoned);
        drain();
        assertThat(EffectEngine.strength(summoned)).isEqualTo(5);
    }

    @Test
    void ordenDeBusquedaStaysUntilTheChosenEnemyIsRemovedThenGivesTwoGemsIfYourCardsRemovedIt() {
        CardSlot foe = onField(guest, enemy("e", 2, 6));
        CardSlot orden = utility("ob", "Orden de Busqueda", 1, "R");
        play(orden);
        answer("e");
        drain();
        assertThat(host.getField()).contains(orden);
        TurnActions.passTurn(room, "host");
        drain();
        TurnActions.passTurn(room, "guest");
        drain();
        assertThat(host.getField()).contains(orden);
        // Below the top of the Contador, so the 2 gems show
        room.setSharedCounter(room.getSharedCounter() - 4 * EffectEngine.direction(room, "host"));
        int before = gems();
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "R"));
        destroyByHost(foe, ally.getInstanceId());
        drain();
        assertThat(host.getDiscardPile()).contains(orden);
        assertThat(gems()).isEqualTo(before + 2);
    }

    @Test
    void ordenDeBusquedaGivesNoGemsWhenTheOpponentRemovesTheEnemy() {
        CardSlot foe = onField(guest, enemy("e", 2, 6));
        CardSlot orden = utility("ob", "Orden de Busqueda", 1, "R");
        play(orden);
        answer("e");
        drain();
        int before = gems();
        Removals.destroy(room, foe, guest, true, EffectEngine.CAUSE_EFFECT, "guest", "x", null);
        Chain.advance(room);
        drain();
        assertThat(host.getDiscardPile()).contains(orden);
        assertThat(gems()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ Requerimientos

    @Test
    void everySpecialSummonOfTheNewFilesHasItsRequirementSection() {
        for (String name : List.of("ES-101", "Gorgocarnis", "Rutilocristalis", "Dendrovorax", "Cinoplocus", "Hematófago Comehombres",
                "Mastigórax", "Gorgath", "Vento", "Tory", "Krugel")) {
            assertThat(CardEffects.requirementOf(card("x", name, 1, 1, "R"))).as(name).isNotNull();
        }
    }

    // ------------------------------------------------------------------ Blanco

    @Test
    void favorDeLaNaturalezaDrawsUntilTheHandHasFiveCards() {
        host.getHand().add(card("h1", "Mano 1", 1, 1, "W"));
        host.getHand().add(card("h2", "Mano 2", 1, 1, "W"));
        int deck = host.getDeck().size();
        play(utility("fn", "Favor de la Naturaleza", 0, "W"));
        if (room.getPrompt() != null) answer(room.getPrompt(), "fn");
        assertThat(host.getHand()).hasSize(5);
        assertThat(host.getDeck()).hasSize(deck - 3);
    }

    @Test
    void favorDeLaNaturalezaDrawsNothingWithFiveCardsInHand() {
        for (int i = 0; i < 5; i++) host.getHand().add(card("h" + i, "Mano " + i, 1, 1, "W"));
        int deck = host.getDeck().size();
        play(utility("fn", "Favor de la Naturaleza", 0, "W"));
        if (room.getPrompt() != null) answer(room.getPrompt(), "fn");
        assertThat(host.getHand()).hasSize(5);
        assertThat(host.getDeck()).hasSize(deck);
    }

    @Test
    void ultimaLineaCountsEveryLifeUpToThree() {
        // "si tienes 3 o menos vidas": the face-up ones count too
        host.getLifeStack().clear();
        for (int i = 0; i < 4; i++) {
            CardSlot life = card("hl" + i, "Vida", 1, 1, "R");
            life.setFaceDown(i > 0);
            host.getLifeStack().add(life);
        }
        host.getHand().add(utility("ul", "Última Línea De Defensa", 0, "W"));
        assertThat(Summons.playByPlayer(room, "host", "ul", 50.0, 75.0)).contains("condición");
        host.getLifeStack().remove(0);
        assertThat(Summons.playByPlayer(room, "host", "ul", 50.0, 75.0)).isNull();
    }
}
