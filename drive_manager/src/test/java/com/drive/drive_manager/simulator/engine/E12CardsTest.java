package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** E1.2: one test (or more) per card programmed on 2026-10-31, from the printed text. */
class E12CardsTest extends CardBatchSupport {

    // ------------------------------------------------------------------ Azul

    @Test
    void corredorTakesABlueCardOfTheTopThreeThenTributesOneFromTheHand() {
        host.getDeck().add(0, card("az", "Azul", 1, 1, "B"));
        inHand("x", "Otra", 1, 1, "R");
        CardSlot corredor = withClasses(card("co", "Corredor A1001", 1, 1, "B"), "autómata");
        summon(corredor);
        answer("az");
        answer("x");
        assertThat(host.getTributeZone()).hasSize(1);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("az");
    }

    @Test
    void b2GuardianDrawsAgainWhenItTributedAStructure() {
        CardSlot b2 = onField(host, card("b2", "B2 Guardián", 1, 1, "B"));
        CardSlot estr = structure("es", "Estructura", 1, "B");
        host.getHand().add(estr);
        assertThat(CombatRules.attackByPlayer(room, "host", "b2", null)).isNull();
        answer("es");
        assertThat(host.getTributeZone()).contains(estr);
        // Drew 1, tributed the structure, drew 1 more
        assertThat(host.getHand()).hasSize(2);
        assertThat(b2).isNotNull();
    }

    @Test
    void homorfoErranteSummonsABlueLevelOneWithFourLivesOrPlaysAStructureWithThree() {
        host.getLifeStack().add(card("hl3", "Vida H3", 1, 1, "R"));
        CardSlot homorfo = onField(host, card("ho", "Homorfo Errante", 1, 1, "B"));
        CardSlot blue = inHand("bl", "Azul Uno", 1, 1, "B");
        Removals.destroy(room, homorfo, host, false, EffectEngine.CAUSE_EFFECT, "guest", null, null);
        Chain.advance(room);
        accept(homorfo);
        answer("bl");
        assertThat(host.getField()).contains(blue);
    }

    @Test
    void homorfoErrantePlaysABlueStructureFromTheTributesWithThreeLives() {
        CardSlot homorfo = onField(host, card("ho", "Homorfo Errante", 1, 1, "B"));
        CardSlot estr = structure("es", "Estructura Azul", 2, "B");
        host.getTributeZone().add(estr);
        inHand("bl", "Azul Uno", 1, 1, "B");
        Removals.destroy(room, homorfo, host, false, EffectEngine.CAUSE_EFFECT, "guest", null, null);
        Chain.advance(room);
        accept(homorfo);
        answer("es");
        assertThat(host.getField()).contains(estr);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("bl");
    }

    @Test
    void vs1JoinsAsAMaterialOfALevelThreeAllySummonedWithoutLevelTwoMaterials() {
        CardSlot vs1 = card("vs", "VS 1", 2, 2, "B");
        host.getTributeZone().add(vs1);
        CardSlot big = card("bg", "Grande", 3, 3, "B");
        summon(big);
        accept(vs1);
        assertThat(big.getMaterials()).contains(vs1);
        assertThat(host.getTributeZone()).doesNotContain(vs1);
    }

    @Test
    void vs1TributesAMaterialToPlayABlueStructureFromTheTributes() {
        CardSlot estr = structure("es", "Estructura Azul", 2, "B");
        host.getTributeZone().add(estr);
        CardSlot vs1 = card("vs", "VS 1", 2, 2, "B");
        vs1.getMaterials().add(card("m", "Material", 1, 1, "B"));
        summon(vs1);
        accept(vs1);
        answer("m");
        answer("es");
        assertThat(host.getField()).contains(estr);
    }

    @Test
    void vv3TributesTwoBetweenTheHandAndItsMaterials() {
        CardSlot vv3 = card("vv", "VV 3", 2, 2, "B");
        vv3.getMaterials().add(card("m", "Material", 1, 1, "B"));
        CardSlot handCard = inHand("h1", "Mano", 1, 1, "R");
        summon(vv3);
        answer();
        answer("m", "h1");
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("m", "h1");
        assertThat(handCard).isNotNull();
    }

    @Test
    void exo91TributesTwoMaterialsToPlayAStructureFromTheTributesForTwoLess() {
        CardSlot estr = structure("es", "Estructura", 2, "G");
        host.getTributeZone().add(estr);
        CardSlot exo = card("ex", "EXO 91", 3, 3, "B");
        exo.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        summon(exo);
        int before = gems();
        accept(exo);
        answer("m1", "m2");
        answer("es");
        assertThat(host.getField()).contains(estr);
        assertThat(gems()).isEqualTo(before);
    }

    @Test
    void sars2ReducesAsManyEnemiesAsMaterialsTributed() {
        CardSlot e1 = onField(guest, card("e1", "Enemigo 1", 1, 1, "R"));
        onField(guest, card("e2", "Enemigo 2", 1, 1, "R"));
        CardSlot sars = card("sa", "SARS 2", 3, 3, "B");
        sars.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        summonByEffectToFront(sars);
        accept(sars);
        answer("m1");
        assertThat(room.getPrompt().getMax()).isEqualTo(1);
        answer("e1");
        assertThat(EffectEngine.strength(e1)).isEqualTo(1);
    }

    @Test
    void fibraxReducesToZeroOnlyWithThreeStructures() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setStrength(9);
        for (int i = 0; i < 3; i++) onField(host, structure("s" + i, "Estructura " + i, 1, "B"), BACK);
        CardSlot fibrax = card("fx", "Fibrax 1", 4, 4, "B");
        summonByEffectToFront(fibrax);
        answer("e");
        assertThat(guest.getField()).doesNotContain(enemy);
    }

    @Test
    void geoVigilanteExhaustsToLowerTheCostOfABlueLevelThreeWhenItIsNeeded() {
        CardSlot geo = onField(host, structure("gv", "Geo Vigilante", 1, "B"), BACK);
        room.setSharedCounter(-2);
        host.setResourceCount(2);
        CardSlot big = card("bg", "Azul Tres", 3, 3, "B");
        summon(big);
        // "puedes agotar…" is asked before the summon
        answer("gv");
        assertThat(geo.isTapped()).isTrue();
        assertThat(host.getField()).contains(big);
        assertThat(gems()).isEqualTo(0);
    }

    @Test
    void geoVigilanteCanBeExhaustedEvenWhenTheCostIsAlreadyZero() {
        CardSlot geo = onField(host, structure("gv", "Geo Vigilante", 1, "B"), BACK);
        CardSlot free = card("fr", "Azul Gratis", 0, 3, "B");
        int before = gems();
        summon(free);
        answer("gv");
        assertThat(geo.isTapped()).isTrue();
        assertThat(host.getField()).contains(free);
        assertThat(gems()).isEqualTo(before);
    }

    @Test
    void geoVigilanteNotExhaustedWhenThePlayerSaysNo() {
        CardSlot geo = onField(host, structure("gv", "Geo Vigilante", 1, "B"), BACK);
        CardSlot big = card("bg", "Azul Tres", 3, 3, "B");
        int before = gems();
        summon(big);
        answer();
        assertThat(geo.isTapped()).isFalse();
        assertThat(gems()).isEqualTo(before - 3);
    }

    private static CardSlot exoLike(CardSlot c) {
        c.setSpecialSummonKind("materialization");
        c.setSpecialCost(2);
        c.setRequirement("criatura nivel 2 o 3 + material nivel 2 en campo o mano.");
        return c;
    }

    @Test
    void exo91InTheTributesIsSpecialSummonedInsteadOfTheAllysDestructionWithTheAllyAsSummoner() {
        CardSlot exo = exoLike(withClasses(card("ex", "EXO 91", 3, 3, "B"), "ente", "autómata"));
        host.getTributeZone().add(exo);
        CardSlot ally = onField(host, card("al", "Aliado Dos", 2, 2, "B"));
        CardSlot material = inHand("ma", "Material Dos", 2, 2, "B");
        int before = gems();
        Removals.destroy(room, ally, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        Prompt replace = room.getPrompt();
        assertThat(replace.getValidIds()).contains("ex");
        answer(replace, "ex");
        answer("ma");
        assertThat(host.getField()).contains(exo).doesNotContain(ally);
        assertThat(exo.getMaterials()).contains(ally, material);
        assertThat(host.getDiscardPile()).doesNotContain(ally);
        // Its special cost (2) reduced in 1
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void exo91IsNotOfferedWithoutTheMaterialItsRequirementAsks() {
        host.getTributeZone().add(exoLike(withClasses(card("ex", "EXO 91", 3, 3, "B"), "ente", "autómata")));
        CardSlot ally = onField(host, card("al", "Aliado Dos", 2, 2, "B"));
        Removals.destroy(room, ally, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        assertThat(host.getDiscardPile()).contains(ally);
    }

    @Test
    void sars2InTheHandSavesAnAutomataLevelThree() {
        CardSlot sars = exoLike(withClasses(card("sa", "SARS 2", 3, 3, "B"), "autómata"));
        host.getHand().add(sars);
        CardSlot ally = onField(host, withClasses(card("al", "Autómata Tres", 3, 3, "B"), "autómata"));
        CardSlot material = inHand("ma", "Material Dos", 2, 2, "B");
        int before = gems();
        Removals.destroy(room, ally, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        answer("sa");
        answer("ma");
        assertThat(host.getField()).contains(sars).doesNotContain(ally);
        assertThat(sars.getMaterials()).contains(ally, material);
        assertThat(gems()).isEqualTo(before - 2);
    }

    @Test
    void canonGaussReducesAnEnemyUntilTheEndOfTheAttackOfABlueAlly() {
        CardSlot canon = onField(host, structure("cg", "Cañón Gauss", 2, "B"), BACK);
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        onField(host, card("at", "Atacante Azul", 1, 1, "B"));
        assertThat(CombatRules.attackByPlayer(room, "host", "at", null)).isNull();
        accept(canon);
        answer("e");
        assertThat(EffectEngine.strength(enemy)).isEqualTo(1);
        drain();
        assertThat(EffectEngine.strength(enemy)).isEqualTo(3);
        assertThat(canon.isTapped()).isTrue();
    }

    @Test
    void canonGaussGainsAGemWhenItsAlliesAddUpToLessLevel() {
        onField(host, structure("cg", "Cañón Gauss", 2, "B"), BACK);
        onField(guest, card("e", "Enemigo", 3, 3, "R"));
        int before = gems();
        TurnRules.turnStarted(room, "host");
        drain();
        assertThat(gems()).isEqualTo(before + 1);
    }

    // ------------------------------------------------------------------ Verde

    @Test
    void annaTributedAsAResourceSummonsAHada() {
        CardSlot anna = withClasses(card("an", "Anna", 1, 1, "G"), "hada");
        CardSlot holder = onField(host, card("ho", "Portador", 2, 2, "G"));
        holder.getResources().add(anna);
        CardSlot hada = inHand("hd", "Hada", 2, 2, "G", "hada");
        inHand("x", "Otra", 1, 1, "R");
        CardEffects.register("tributador de recursos", new EffectScript("tributador:en-invocacion", EffectScript.Moment.ON_SUMMON, true,
                "En Invocación", "Tributa Anna de los recursos de 1 aliado.", List.of(Compose.tributeResources(1, CardFilter.any()))));
        // The holder tributes its own resource (as its inherited cost would)
        PendingEffect e = Chain.pending(room, java.util.Objects.requireNonNull(CardEffects.byId("anna:hereda-todos-los-turnos")), "host", holder, room.getEventSeq());
        Resolution.run(room, e, Compose.tributeResource("Anna"));
        Chain.advance(room);
        accept(anna);
        answer("x");
        answer("hd");
        assertThat(host.getField()).contains(hada);
    }

    @Test
    void annaGivesTheHolderADrawWhenItIsExhausted() {
        CardSlot anna = withClasses(card("an", "Anna", 1, 1, "G"), "hada");
        CardSlot holder = onField(host, card("ho", "Portador", 2, 2, "G"));
        holder.getResources().add(anna);
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        untilOffered(holder);
        answer("ho");
        assertThat(host.getTributeZone()).contains(anna);
        assertThat(host.getHand()).isNotEmpty();
    }

    @Test
    void clavisExhaustsAnEnemyOfStrengthFiveOrLess() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        summonByEffectToFront(withClasses(card("cl", "Clavis", 1, 1, "G"), "hada"));
        answer("e");
        assertThat(enemy.isTapped()).isTrue();
    }

    @Test
    void hadaAprendizReactivatesACheapGreenCardOrDraws() {
        CardSlot cheap = onField(host, card("gr", "Verde Barata", 1, 1, "G"), BACK);
        cheap.setTapped(true);
        summon(withClasses(card("ha", "Hada Aprendiz", 1, 1, "G"), "hada"));
        answer("gr");
        assertThat(cheap.isTapped()).isFalse();
        assertThat(host.getHand()).isEmpty();
    }

    @Test
    void hadaAprendizDrawsWhenThereIsNothingToReactivate() {
        summon(withClasses(card("ha", "Hada Aprendiz", 1, 1, "G"), "hada"));
        assertThat(host.getHand()).hasSize(1);
    }

    @Test
    void celiusByEffectTakesAnAllyAsResourceMovesUpAndTributesAnExhaustedEnemy() {
        CardSlot small = onField(host, card("sm", "Pequeño", 1, 1, "G"), BACK);
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setTapped(true);
        inHand("x", "Descarte", 1, 1, "R");
        CardSlot celius = withClasses(card("ce", "Celius", 2, 2, "G"), "hada");
        CardEffects.register("invocador de prueba", new EffectScript("invocador-prueba:en-invocacion", EffectScript.Moment.ON_SUMMON, true,
                "En Invocación", "Invoca Celius.", List.of(Compose.summonFromZone("hand", CardFilter.creatures().named("Celius"), 2, false))));
        host.getHand().addAll(List.of(celius, card("iv", "Invocador De Prueba", 0, 1, "R")));
        assertThat(Summons.normalSummon(room, "host", "iv", 50.0, 70.0)).isNull();
        answer("ce");
        accept(celius);
        answer("sm");
        answer("ce");
        answer("x");
        answer("e");
        assertThat(celius.getResources()).contains(small);
        assertThat(EffectEngine.inFrontLine(celius)).isTrue();
        assertThat(guest.getTributeZone()).contains(enemy);
    }

    @Test
    void calaTakesALevelOneAllyAsResourceToReactivateAnother() {
        CardSlot small = onField(host, card("sm", "Pequeño", 1, 1, "G"), BACK);
        CardSlot tired = onField(host, card("ti", "Cansado", 2, 2, "G"));
        tired.setTapped(true);
        CardSlot cala = withClasses(card("ca", "Cala", 2, 2, "G"), "hada");
        summon(cala);
        accept(cala);
        answer("sm");
        answer("ti");
        assertThat(cala.getResources()).contains(small);
        assertThat(tired.isTapped()).isFalse();
    }

    @Test
    void calaAsAResourceSummonsAHadaWhenTheHolderIsExhausted() {
        CardSlot holder = onField(host, card("ho", "Portador", 2, 2, "G"));
        holder.getResources().add(withClasses(card("ca", "Cala", 2, 2, "G"), "hada"));
        CardSlot hada = inHand("hd", "Hada", 2, 2, "G", "hada");
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        untilOffered(holder);
        answer("ho");
        answer("hd");
        assertThat(host.getField()).contains(hada);
    }

    @Test
    void raflesiaByEffectExhaustsACreatureAndThenMayReactivateAnAlly() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        CardSlot tired = onField(host, card("ti", "Cansado", 2, 2, "G"));
        tired.setTapped(true);
        CardSlot raflesia = withClasses(card("rf", "Raflesia", 2, 2, "G"), "hada");
        summonByEffectToFront(raflesia);
        accept(raflesia);
        answer("e");
        answer("rf");
        answer("ti");
        assertThat(enemy.isTapped()).isTrue();
        assertThat(tired.isTapped()).isFalse();
    }

    @Test
    void violetMovesAnAllyOfTheRetaguardiaUp() {
        CardSlot back = onField(host, card("bk", "Atrás", 1, 1, "G"), BACK);
        CardSlot violet = withClasses(card("vi", "Violet", 3, 3, "G"), "hada");
        summon(violet);
        accept(violet);
        answer("bk");
        assertThat(EffectEngine.inFrontLine(back)).isTrue();
    }

    @Test
    void leimsExhaustedOnItsTurnSummonsAHadaPayingACardOfTheHand() {
        CardSlot leims = onField(host, withClasses(card("le", "Leims", 3, 3, "G"), "hada"));
        inHand("x", "Pago", 1, 1, "R");
        CardSlot hada = inHand("hd", "Hada", 2, 2, "G", "hada");
        assertThat(CombatRules.attackByPlayer(room, "host", "le", null)).isNull();
        untilOffered(leims);
        answer("le");
        answer("x");
        answer("hd");
        assertThat(host.getField()).contains(hada);
    }

    @Test
    void leimsAsAResourceGainsAGemWhenAStrongAllyIsSummonedByEffect() {
        CardSlot holder = onField(host, card("ho", "Portador", 2, 2, "G"));
        holder.getResources().add(withClasses(card("le", "Leims", 3, 3, "G"), "hada"));
        int before = gems();
        CardSlot strong = card("st", "Fuerte", 1, 1, "G");
        strong.setStrength(5);
        summonByEffectToFront(strong);
        accept(holder);
        assertThat(gems()).isEqualTo(before + 1);
    }

    @Test
    void juleusReactivatesItselfWhenNoEnemyWasExhausted() {
        inHand("x", "Pago", 1, 1, "R");
        CardSlot juleus = withClasses(card("ju", "Juleus El Principe Olvidado", 4, 4, "G"), "hada");
        summonByEffectToFront(juleus);
        juleus.setTapped(true);
        untilOffered(juleus);
        answer("ju");
        answer("x");
        assertThat(juleus.isTapped()).isFalse();
    }

    @Test
    void trampaVegetalStaysAndExhaustsAWeakEnemyThatEntersTheFrontLine() {
        CardSlot trampa = utility("tv", "Trampa Vegetal", 1, "G");
        play(trampa);
        assertThat(host.getField()).contains(trampa);
        // The opponent's turn: an enemy moves up
        room.setCurrentTurnUserId("guest");
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"), BACK);
        enemy.setY(25.0);
        EffectEngine.record(room, GameEvent.MOVE, "guest", enemy, null, "frontLine", null, null, "sube");
        Chain.advance(room);
        assertThat(enemy.isTapped()).isTrue();
        assertThat(host.getDiscardPile()).contains(trampa);
    }

    @Test
    void trampaVegetalReturnsToTheHandAtTheEndOfTheOpponentsTurn() {
        CardSlot trampa = onField(host, utility("tv", "Trampa Vegetal", 1, "G"), BACK);
        room.setCurrentTurnUserId("guest");
        TurnRules.turnEnding(room, "guest");
        drain();
        assertThat(host.getHand()).contains(trampa);
    }

    @Test
    void poderAntiguoEvolvesAnActiveAllyReducingByItsLevelThenExhaustsIt() {
        CardSlot ally = onField(host, card("al", "Aliado", 2, 2, "G"));
        CardSlot evo = card("ev", "Evolución Verde", 3, 3, "G");
        evo.setSpecialSummonKind("evolution");
        evo.setSpecialCost(2);
        evo.setRequirement("criatura nivel 2.");
        host.getHand().add(evo);
        int before = gems();
        play(utility("pa", "Poder Antiguo", 1, "G"));
        answer("al");
        answer("ev");
        assertThat(host.getField()).contains(evo);
        assertThat(evo.isTapped()).isTrue();
        assertThat(gems()).isEqualTo(before - 1);
        assertThat(ally).isNotNull();
    }

    // ------------------------------------------------------------------ Violeta

    @Test
    void abrazaAlmasCostsOneWithoutCardsOnTheField() {
        int before = gems();
        summon(withClasses(card("aa", "Abraza Almas", 2, 1, "P"), "espectro"));
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void zokolokPlaysACheapVioletUtilityFromTheTributesForFree() {
        CardSlot zokolok = onField(host, card("zk", "Zokolok", 1, 1, "P"));
        CardSlot util = utility("ut", "Utilidad Violeta", 1, "P");
        CardEffects.register("utilidad violeta", new EffectScript("utilidad-violeta:en-juego", EffectScript.Moment.ON_PLAY, true, "En Juego",
                "Gana 1 gema.", List.of(Compose.gainGems(1))));
        host.getTributeZone().add(util);
        Removals.destroy(room, zokolok, host, false, EffectEngine.CAUSE_EFFECT, "guest", null, null);
        Chain.advance(room);
        accept(zokolok);
        answer("ut");
        drain();
        assertThat(host.getTributeZone()).doesNotContain(util);
    }

    @Test
    void phogSacrificesALevelTwoToBeSummonedForFree() {
        CardSlot two = onField(host, card("tw", "Dos", 2, 2, "P"));
        CardSlot phog = inHand("ph", "Phog", 1, 1, "P", "espectro");
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "ph", null)).isNull();
        answer("tw");
        assertThat(host.getField()).contains(phog).doesNotContain(two);
        assertThat(gems()).isEqualTo(before);
    }

    @Test
    void caloxGainsStrengthAndProvocarWithFiveInterdimensionalOnTheField() {
        CardSlot calox = onField(host, withClasses(card("cx", "Calox", 2, 2, "P"), "bestia", "interdimensional"));
        calox.setStrength(5);
        for (int i = 0; i < 2; i++) onField(host, withClasses(card("h" + i, "Inter H" + i, 1, 1, "P"), "interdimensional"));
        for (int i = 0; i < 2; i++) onField(guest, withClasses(card("g" + i, "Inter G" + i, 1, 1, "P"), "interdimensional"));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(calox)).isEqualTo(8);
        assertThat(EffectEngine.keywords(calox)).contains("Provocar");
    }

    @Test
    void augurioFlorecienteDestroyedByAnEffectSummonsALevelThreeOrLessFromTheTributes() {
        CardSlot augurio = onField(host, withClasses(card("af", "Augurio Floreciente", 3, 3, "P"), "espectro"));
        CardSlot copy = card("af2", "Augurio Floreciente", 3, 3, "P");
        CardSlot other = card("ot", "Otro", 3, 3, "P");
        host.getTributeZone().addAll(List.of(copy, other));
        inHand("x", "Pago", 1, 1, "R");
        Removals.destroy(room, augurio, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        accept(augurio);
        answer("x");
        assertThat(room.getPrompt().getValidIds()).contains("ot").doesNotContain("af2");
        answer("ot");
        assertThat(host.getField()).contains(other);
    }

    @Test
    void cadaverCorruptoPlacesTwoSmallEspectrosAfterSacrificingALevelThree() {
        onField(host, card("l3", "Nivel 3", 3, 3, "P"));
        CardSlot e1 = withClasses(card("e1", "Espectro 1", 1, 1, "P"), "espectro");
        CardSlot e2 = withClasses(card("e2", "Espectro 2", 2, 2, "P"), "espectro");
        host.getTributeZone().addAll(List.of(e1, e2));
        CardSlot cadaver = withClasses(card("cc", "Cadaver Corrupto", 5, 4, "P"), "espectro");
        summon(cadaver);
        accept(cadaver);
        answer("l3");
        answer("e1", "e2");
        assertThat(host.getField()).contains(e1, e2);
    }

    @Test
    void apostolDeLaRuinaDealsDirectDamageAtTheEndOfTheTurnWithTwentyTributes() {
        CardSlot apostol = onField(host, withClasses(card("ar", "Apostol De La Ruina", 6, 5, "P"), "espectro"));
        for (int i = 0; i < 20; i++) host.getTributeZone().add(card("t" + i, "Tributo " + i, 1, 1, "P"));
        TurnRules.turnEnding(room, "host");
        untilOffered(apostol);
        answer("ar");
        answer(room.getPrompt().getValidIds().subList(0, 10).toArray(String[]::new));
        assertThat(guest.getLifeStack()).hasSize(2);
    }

    @Test
    void asedioDelInframundoSummonsALevelOneThenGoesToTheTributos() {
        CardSlot one = card("o1", "Uno", 3, 1, "P");
        host.getTributeZone().add(one);
        CardSlot asedio = utility("as", "Asedio Del Inframundo", 1, "P");
        play(asedio);
        // "invoca en tu línea frontal 1 criatura Violeta o [espectro] nivel 1 de tus tributos": not optional
        answer("o1");
        assertThat(host.getField()).contains(one);
        assertThat(host.getTributeZone()).contains(asedio);
    }

    @Test
    void reclamoDelInframundoRitualsWithAReductionPerTenTributes() {
        CardSlot summoner = onField(host, card("su", "Invocador", 1, 1, "P"));
        CardSlot ritual = withClasses(card("ri", "Ritual Espectro", 3, 2, "P"), "espectro");
        ritual.setSpecialSummonKind("ritual");
        ritual.setSpecialCost(2);
        ritual.setRequirement("criatura nivel 1.");
        host.getTributeZone().add(ritual);
        for (int i = 0; i < 10; i++) host.getTributeZone().add(card("t" + i, "Tributo " + i, 1, 1, "P"));
        int before = gems();
        CardSlot reclamo = utility("rc", "Reclamo Del Inframundo", 1, "P");
        play(reclamo);
        answer("rc");
        answer("ri");
        answer("su");
        assertThat(host.getField()).contains(ritual);
        assertThat(gems()).isEqualTo(before - 1 - 1);
        assertThat(host.getTributeZone()).contains(reclamo);
    }

    // ------------------------------------------------------------------ Roja

    @Test
    void estoqueTakesTwoCardsOfDifferentLevels() {
        host.getDeck().add(0, withClasses(card("r1", "Retador 1", 1, 1, "R"), "retador"));
        host.getDeck().add(0, withClasses(card("r2", "Retador 1b", 1, 1, "R"), "retador"));
        summon(withClasses(card("es", "Estoque De Los Guerreros Jurados", 1, 1, "R"), "retador"));
        Prompt take = room.getPrompt();
        // The board locks a level already chosen
        assertThat(take.isDistinctLevels()).isTrue();
        assertThat(Answers.answer(room, "host", take.getId(), List.of("r1", "r2"), List.of())).contains("distinto nivel");
        answer(take, "r1");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("r1");
    }


    private void estoqueInHand() {
        inHand("es", "Estoque De Los Guerreros Jurados", 1, 1, "R", "retador");
        inHand("r2", "Retador Dos", 2, 2, "R", "retador");
        inHand("r3", "Retador Tres", 3, 3, "R", "retador");
    }

    @Test
    void estoqueTributedWithTwoRetadoresPreventsTheHitAndLetalidadsNextOne() {
        estoqueInHand();
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(4);
        CardSlot attacker = onField(guest, card("at", "Atacante", 1, 3, "R"));
        attacker.getTurnKeywords().add("Letalidad 1");
        assertThat(CombatRules.attackByPlayer(room, "guest", "at", null)).isNull();
        for (int i = 0; i < 6 && room.getPrompt() != null && !room.getPrompt().getValidIds().contains("es"); i++) answer(room.getPrompt());
        Prompt offer = room.getPrompt();
        assertThat(offer.isDistinctLevels()).isTrue();
        assertThat(offer.isExactOrNone()).isTrue();
        // "esta carta más 2 [retador]": the board asks for Estoque first
        assertThat(offer.getRequiredIds()).containsExactly("es");
        assertThat(Answers.answer(room, "host", offer.getId(), List.of("es", "r2"), List.of())).isNotNull();
        answer(offer, "es", "r2", "r3");
        drain();
        assertThat(host.getLifeStack()).hasSize(3);
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("es", "r2", "r3");
    }

    @Test
    void estoqueCanAlsoPreventTheDamageOfAnEffect() {
        estoqueInHand();
        CardSlot src = onField(guest, card("gs", "Fuente", 1, 1, "R"));
        PendingEffect theirs = Chain.pending(room, CardEffects.of("Dragón Goloso").get(0), "guest", src, room.getEventSeq());
        room.getChain().add(theirs);
        StepHelpers.damageByEffect(room, theirs, guest, 5);
        Prompt offer = room.getPrompt();
        assertThat(offer.getPlayerId()).isEqualTo("host");
        // Declined: the damage happens
        answer(offer);
        assertThat(host.getLifeStack()).hasSize(2);
    }

    @Test
    void peleadorDelCastilloIsStrongerAndImmuneToLevelOneOnTheOpponentsTurn() {
        CardSlot peleador = onField(host, card("pc", "Peleador Del Castillo", 2, 1, "R"));
        room.setCurrentTurnUserId("guest");
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(peleador)).isEqualTo(6);
        assertThat(EffectEngine.immuneTo(peleador, 1)).isTrue();
        assertThat(EffectEngine.immuneTo(peleador, 2)).isFalse();
    }

    @Test
    void magoArmadoRevealsARetadorTwoAndThreeToBeSummonedFree() {
        onField(host, card("al", "Aliado", 1, 1, "R"));
        CardSlot mago = inHand("ma", "Mago Armado del Ocaso", 2, 1, "R", "retador");
        inHand("r2", "Retador Dos", 2, 2, "R", "retador");
        inHand("r3", "Retador Tres", 3, 3, "R", "retador");
        assertThat(Chain.activateByPlayer(room, "host", "ma", null)).isNull();
        assertThat(room.getPrompt().isDistinctLevels()).isTrue();
        answer("r2", "r3");
        assertThat(host.getField()).contains(mago);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("r2", "r3");
    }

    @Test
    void guerreroForjadoTributedWithNegativeGemsMakesAnActiveAllyAttack() {
        room.setSharedCounter(1);
        CardSlot forjado = card("gf", "Guerrero Forjado", 2, 2, "R");
        host.getTributeZone().add(forjado);
        onField(host, card("at", "Atacante", 1, 1, "R"));
        EffectEngine.record(room, GameEvent.TRIBUTE, "host", forjado, "field", "tributeZone", null, null, "tributa");
        Chain.advance(room);
        accept(forjado);
        answer("at");
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.ATTACK_DECLARED.equals(e.getType()) && "at".equals(e.getCardInstanceId()));
    }

    @Test
    void guerreroForjadoCannotBeAscendedWhileAnotherAllyIsLevelOne() {
        onField(host, card("s1", "Uno", 1, 1, "R"));
        CardSlot other = onField(host, card("s2", "Otro Uno", 1, 1, "R"));
        CardSlot forjado = card("gf", "Guerrero Forjado", 2, 5, "R");
        forjado.setSpecialSummonKind("promotion");
        forjado.setSpecialCost(1);
        forjado.setRequirement("criatura nivel 1, mientras no controlas otros aliados nivel 1.");
        host.getHand().add(forjado);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "gf", "s1")).isNotNull();
        host.getField().remove(other);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "gf", "s1")).isNull();
    }

    @Test
    void guardiaDelMuroTributesAnotherAllyToReactivateAtTheEndOfTheTurn() {
        CardSlot guardia = onField(host, card("gm", "Guardia Del Muro", 2, 2, "R"));
        guardia.setTapped(true);
        onField(host, card("ot", "Otro", 1, 1, "R"));
        TurnRules.turnEnding(room, "host");
        untilOffered(guardia);
        answer("gm");
        answer("ot");
        assertThat(guardia.isTapped()).isFalse();
    }

    @Test
    void arcoLeonDoradoStopsAnEnemyOfTheChosenLevelFromBlocking() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 2, 2, "R"));
        CardSlot arco = withClasses(card("ar", "Arco de los Leones Dorados", 2, 2, "R"), "retador");
        summon(arco);
        // "Puedes subir esta carta a tu línea frontal. Si lo haces: elige 1 aliado y 1 enemigo de nivel igual o menor…"
        untilOffered(arco);
        answer("ar");
        assertThat(EffectEngine.inFrontLine(arco)).isTrue();
        answer("ar");
        answer("e");
        assertThat(EffectEngine.keywords(enemy)).contains(EffectEngine.NO_BLOCK);
        assertThat(CombatRules.blockers(guest)).doesNotContain(enemy);
    }

    @Test
    void espadaDeLosLeonesDoradosGivesFuriaTwoToWhatAscendsIt() {
        CardSlot espada = onField(host, withClasses(card("ed", "Espada De Los Leones Dorados", 3, 3, "R"), "retador"));
        CardSlot ascended = card("as", "Ascendido", 4, 4, "R");
        ascended.setSpecialSummonKind("promotion");
        ascended.setSpecialCost(1);
        ascended.setRequirement("criatura nivel 3.");
        host.getHand().add(ascended);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "as", "ed")).isNull();
        // A mandatory Reacción shown to be activated before the Ascenso
        assertThat(room.getPrompt().getPurpose()).isEqualTo(PromptPurpose.ASCEND_GAIN.id());
        drain();
        assertThat(EffectEngine.keywords(ascended)).contains("Furia 2");
        assertThat(espada).isNotNull();
    }

    @Test
    void caballeroJuradoWithFewerLivesHasEnfrentarAndTwoMoreStrength() {
        host.getLifeStack().remove(0);
        CardSlot jurado = onField(host, withClasses(card("cj", "Caballero Jurado", 3, 3, "R"), "retador"));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(jurado)).isEqualTo(5);
        assertThat(EffectEngine.keywords(jurado)).contains("Enfrentar 3");
    }

    @Test
    void comandanteDeLosLeonesDoradosTributesTheOthersAndItsRetadorGoesBackAtTheEndOfTheTurn() {
        onField(host, card("o1", "Otro 1", 1, 1, "R"));
        onField(host, card("o2", "Otro 2", 1, 1, "R"));
        CardSlot retador = inHand("rt", "Retador", 2, 2, "R", "retador");
        CardSlot comandante = withClasses(card("cl", "Comandante De Los Leones Dorados", 0, 4, "R"), "retador");
        summon(comandante);
        // "tributa todos tus otros aliados" (mandatory again since 2026-11-10)
        drain();
        assertThat(EffectEngine.strength(comandante)).isEqualTo(3 + 4);
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("o1", "o2");
        assertThat(retador).isNotNull();
    }

    @Test
    void comandanteAtAttackSummonsUpToTwoRetadoresThatStay() {
        CardSlot comandante = onField(host, withClasses(card("cl", "Comandante De Los Leones Dorados", 0, 4, "R"), "retador"));
        CardSlot retador = inHand("rt", "Retador", 2, 2, "R", "retador");
        CardSlot other = inHand("r2", "Otro Retador", 2, 2, "R", "retador");
        assertThat(CombatRules.attackByPlayer(room, "host", "cl", null)).isNull();
        // Mandatory: straight to "invoca hasta 2 [retadores] de tu mano reduciendo sus costes en 2"
        untilOffered(retador);
        assertThat(room.getPrompt().getMax()).isEqualTo(2);
        answer("rt", "r2");
        assertThat(host.getField()).contains(retador, other);
        drain();
        TurnRules.turnEnding(room, "host");
        drain();
        // They no longer go back to the hand at the end of the turn (2026-11-10)
        assertThat(host.getField()).contains(retador, other);
    }

    @Test
    void mensajeDeUnCompaneroDrawsTwoAndItsTributosEffectHasItsOwnLimit() {
        inHand("rt", "Retador", 1, 1, "R", "retador");
        CardSlot mensaje = utility("mc", "Mensaje De Un Compañero", 0, "R");
        play(mensaje);
        answer("rt");
        assertThat(host.getTributeZone()).contains(mensaje);
        assertThat(host.getHand()).hasSize(2);
        // Each effect is "una vez por turno entre copias" on its own (2026-11-10): the En Tributos one is still there
        assertThat(Chain.activatable(room, "host")).anyMatch(a -> "mc".equals(a.instanceId()));
    }

    @Test
    void coliseoGainsAGemWithOneAllyOrLessAndTurnsALifeFaceDown() {
        CardSlot coliseo = onField(host, structure("cs", "Coliseo", 1, "R"), BACK);
        int before = gems();
        TurnRules.turnStarted(room, "host");
        drain();
        assertThat(gems()).isEqualTo(before + 1);
        host.getLifeStack().get(0).setFaceDown(false);
        summonByEffectToFront(card("x", "Invocado", 1, 1, "R"));
        accept(coliseo);
        answer("hl0");
        assertThat(host.getLifeStack().get(0).isFaceDown()).isTrue();
        assertThat(coliseo.isTapped()).isTrue();
    }

    // ------------------------------------------------------------------ Blanca

    @Test
    void regresoDeLaPenumbraTakesANonWhiteCreatureFromTheDiscard() {
        room.setFirstTurn(false);
        host.getDiscardPile().addAll(List.of(card("w", "Blanca", 1, 1, "W"), card("r", "Roja", 1, 1, "R")));
        play(utility("rp", "Regreso De La Penumbra", 0, "W"));
        assertThat(room.getPrompt().getValidIds()).containsExactly("r");
        answer("r");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("r");
    }

    @Test
    void circuloDevoradorStaysExhaustedAndFourCopiesGiveFourGems() {
        room.setFirstTurn(false);
        for (int i = 0; i < 3; i++) {
            CardSlot c = onField(host, utility("c" + i, "Circulo Devorador De Muerte", 0, "W"), BACK);
            c.setTapped(true);
        }
        CardSlot last = utility("c3", "Circulo Devorador De Muerte", 0, "W");
        play(last);
        assertThat(host.getField()).contains(last);
        assertThat(last.isTapped()).isTrue();
        room.setSharedCounter(-2);
        // Its own player's effects do nothing to it either (only its copies affect it)
        PendingEffect other = Chain.pending(room, CardEffects.of("Dragón Goloso").get(0), "host", onField(host, card("src", "Fuente", 1, 1, "R")), 0);
        Removals.destroy(room, last, host, false, EffectEngine.CAUSE_EFFECT, "host", "src", other);
        assertThat(host.getField()).contains(last);
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "c3", "circulo-devorador:turno-aliado")).isNull();
        answer("c0", "c1", "c2", "c3");
        assertThat(gems()).isEqualTo(before + 4);
        assertThat(host.getDiscardPile()).hasSize(4);
    }

    @Test
    void exploracionTerritorialFromTheTributesTakesACardWhenAStructureIsPlayed() {
        CardSlot exploracion = utility("et", "Exploración Territorial", 0, "W");
        host.getTributeZone().addAll(List.of(exploracion, card("tk", "Tomar", 1, 1, "R")));
        play(structure("es", "Estructura", 0, "R"));
        accept(exploracion);
        answer("tk");
        assertThat(host.getDiscardPile()).contains(exploracion);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("tk");
    }

    @Test
    void tratoSucioMakesBothDiscardThreeAndDrawThree() {
        room.setFirstTurn(false);
        for (int i = 0; i < 3; i++) {
            host.getHand().add(card("hh" + i, "Mano H" + i, 1, 1, "R"));
            guest.getHand().add(card("gh" + i, "Mano G" + i, 1, 1, "R"));
        }
        play(utility("ts", "Trato Sucio", 0, "W"));
        answer("hh0", "hh1", "hh2");
        Prompt theirs = room.getPrompt();
        assertThat(theirs.getPlayerId()).isEqualTo("guest");
        answer(theirs, "gh0", "gh1", "gh2");
        assertThat(host.getHand()).hasSize(3);
        assertThat(guest.getHand()).hasSize(3);
        assertThat(guest.getDiscardPile()).hasSize(3);
    }

    @Test
    void ejecucionDespiadadaGivesTheOpponentItsGemsAgainAndDestroysAnEnemyIgnoringImmunity() {
        room.setFirstTurn(false);
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.getTurnKeywords().add(EffectEngine.IMMUNE);
        room.setSharedCounter(4);
        if (EffectEngine.gemsOf(room, "host") < 0) room.setSharedCounter(-4);
        play(utility("ed", "Ejecución Despiadada", 1, "W"));
        answer("e");
        assertThat(guest.getField()).doesNotContain(enemy);
        // Paid 1 (3 left, the opponent's counter says 3): "tu oponente gana tantas gemas como su contador de gemas diga"
        assertThat(EffectEngine.gemsOf(room, "host")).isZero();
    }

    private CardSlot ritualCard(String id, String name, int level, int specialCost, String requirement) {
        CardSlot c = withClasses(card(id, name, 3, level, "P"), "espectro");
        c.setSpecialSummonKind("ritual");
        c.setSpecialCost(specialCost);
        c.setRequirement(requirement);
        host.getHand().add(c);
        return c;
    }

    @Test
    void augurioFlorecienteIsRitualSummonedOnALevelThreeToo() {
        CardSlot augurio = ritualCard("au", "Augurio Floreciente", 3, 2, "criatura nivel 2 o 3.");
        onField(host, withClasses(card("s3", "Nivel Tres", 3, 3, "P"), "espectro"));
        assertThat(Rituals.ritualByPlayer(room, "host", "au", "s3")).isNull();
        assertThat(host.getField()).contains(augurio);
    }

    @Test
    void apostolDeLaRuinaNeedsALevelFourAndOneOrMoreAlliesMore() {
        CardSlot apostol = ritualCard("ap", "Apostol De La Ruina", 5, 2, "criatura nivel 4 + 1 o más aliados.");
        CardSlot four = onField(host, withClasses(card("s4", "Nivel Cuatro", 4, 4, "P"), "espectro"));
        // Alone, the level 4 does not meet it
        assertThat(Rituals.canRitual(room, "host", apostol)).isFalse();
        onField(host, withClasses(card("o1", "Otro", 1, 1, "P"), "espectro"));
        assertThat(Rituals.ritualByPlayer(room, "host", "ap", "s4")).isNull();
        assertThat(room.getPrompt().getMin()).isEqualTo(1);
        answer("o1");
        assertThat(host.getField()).contains(apostol);
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("s4", "o1");
        assertThat(four).isNotNull();
    }

    @Test
    void geoVigilanteReactivatesWithReactivar() {
        CardSlot geo = onField(host, structure("gv", "Geo Vigilante", 1, "B"), BACK);
        geo.setKeywords(new java.util.ArrayList<>(List.of("Reactivar")));
        geo.setTapped(true);
        TurnRules.turnStarted(room, "guest");
        assertThat(geo.isTapped()).isFalse();
    }
}
