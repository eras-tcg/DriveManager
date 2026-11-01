package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** E1.3: one test (or more) per card programmed on 2026-10-31, from the printed text. */
class E13CardsTest extends CardBatchSupport {

    private void destroyByOpponent(CardSlot card) {
        Removals.destroy(room, card, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
    }

    // ------------------------------------------------------------------ Azul

    @Test
    void vigilanteAntiorganicoDiscardsUpToTwoAndTakesANecromataBack() {
        host.getDeck().add(0, withClasses(card("n1", "Necro", 1, 1, "B"), "necrómata"));
        summon(withClasses(card("va", "Vigilante Antiorgánico", 1, 1, "B"), "necrómata"));
        answer("n1");
        answer("n1");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("n1");
    }

    @Test
    void vigilanteAntiorganicoGoesToTheTopOfTheLivesFaceUpWhenDestroyed() {
        CardSlot va = onField(host, withClasses(card("va", "Vigilante Antiorgánico", 1, 1, "B"), "necrómata"));
        destroyByOpponent(va);
        accept(va);
        assertThat(host.getLifeStack().get(0)).isSameAs(va);
        assertThat(va.isFaceDown()).isFalse();
    }

    @Test
    void residuoBiomecanicoReturnsWhenSentToTheDiscardAsAMaterialWithThreeLivesOrLess() {
        CardSlot residuo = card("rb", "Residuo Biomecánico", 1, 1, "B");
        CardSlot holder = onField(host, card("ho", "Portador", 2, 2, "B"));
        holder.getMaterials().add(residuo);
        destroyByOpponent(holder);
        accept(residuo);
        assertThat(host.getField()).contains(residuo);
    }

    @Test
    void necrosimbionteComesBackFromTheDiscardWhenYouDestroyAnEnemy() {
        CardSlot necro = card("ns", "Necrosimbionte", 1, 1, "B");
        host.getDiscardPile().add(necro);
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        Removals.destroy(room, enemy, guest, false, EffectEngine.CAUSE_EFFECT, "host", "src", null);
        Chain.advance(room);
        accept(necro);
        assertThat(host.getField()).contains(necro);
    }

    @Test
    void h0110wAttacksWithoutExhaustingAndGainsThreeWithThreeLivesOrLess() {
        CardSlot h = card("hw", "H 0110 W", 2, 2, "B");
        summonByEffectToFront(h);
        accept(h);
        drain();
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.ATTACK_DECLARED.equals(e.getType()) && "hw".equals(e.getCardInstanceId()));
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.GAIN.equals(e.getType()) && "hw".equals(e.getCardInstanceId()));
        assertThat(h.isTapped()).isFalse();
        assertThat(guest.getLifeStack()).hasSize(2);
    }

    @Test
    void recolectorTakesFromTheDiscardAndWithMaterialsSummonsANecromataLevelTwo() {
        CardSlot two = withClasses(card("n2", "Necro Dos", 2, 2, "B"), "necrómata");
        host.getDiscardPile().add(two);
        CardSlot recolector = onField(host, withClasses(card("rc", "Recolector De Biomateria", 2, 2, "B"), "necrómata"));
        recolector.getMaterials().add(card("m", "Material", 1, 1, "B"));
        inHand("x", "Pago", 1, 1, "R");
        destroyByOpponent(recolector);
        accept(recolector);
        answer("x");
        answer("n2");
        assertThat(host.getField()).contains(two);
    }

    @Test
    void doctorNuliberPlacesADiscardedLevelOneNecromataExhaustedInTheFrontLine() {
        host.getDeck().add(0, withClasses(card("n1", "Necro Uno", 1, 1, "B"), "necrómata"));
        summon(withClasses(card("dn", "Doctor Nuliber", 2, 2, "B"), "necrómata"));
        answer("n1", "hd1");
        answer("n1");
        CardSlot placed = host.getField().stream().filter(c -> "n1".equals(c.getInstanceId())).findFirst().orElseThrow();
        assertThat(placed.isTapped()).isTrue();
        assertThat(EffectEngine.inFrontLine(placed)).isTrue();
    }

    @Test
    void srlEx8PutsASmallCreatureOfTheDiscardOnTopOfTheLivesFaceUp() {
        CardSlot small = card("sm", "Pequeña", 1, 1, "B");
        host.getDiscardPile().add(small);
        CardSlot srl = onField(host, card("sr", "SRL EX8", 3, 3, "B"));
        destroyByOpponent(srl);
        accept(srl);
        answer("sm");
        assertThat(host.getLifeStack().get(0)).isSameAs(small);
        assertThat(small.isFaceDown()).isFalse();
    }

    @Test
    void freudSendsFiveNecromatasForAGemThenDestroysAnAllyToSummonSpecially() {
        for (int i = 0; i < 5; i++) host.getTributeZone().add(withClasses(card("t" + i, "Necro " + i, 1, 1, "B"), "necrómata"));
        CardSlot victim = onField(host, card("vi", "Víctima", 1, 1, "B"));
        CardSlot evo = card("ev", "Evolución Azul", 3, 3, "B");
        evo.setSpecialSummonKind("evolution");
        evo.setSpecialCost(1);
        evo.setRequirement("criatura nivel 3.");
        host.getHand().add(evo);
        CardSlot freud = withClasses(card("fr", "Freud", 3, 3, "B"), "necrómata");
        int before = gems();
        summon(freud);
        answer("fr");
        answer("t0", "t1", "t2", "t3", "t4");
        answer("fr");
        answer("vi");
        answer("ev");
        assertThat(host.getField()).contains(evo).doesNotContain(victim, freud);
        assertThat(gems()).isEqualTo(before - 3 + 1);
    }

    @Test
    void velkeropPlacesABlueLevelOneFromTheDiscardWhenALifeIsRemovedOnTheOpponentsTurn() {
        CardSlot velkerop = onField(host, withClasses(card("vk", "Velkerop 1", 5, 4, "B"), "necrómata"));
        velkerop.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        CardSlot one = withClasses(card("n1", "Necro Uno", 1, 1, "B"), "necrómata");
        host.getDiscardPile().add(one);
        room.setCurrentTurnUserId("guest");
        CardSlot life = host.getLifeStack().remove(0);
        host.getHand().add(life);
        EffectEngine.record(room, GameEvent.LIFE_DAMAGE, "guest", life, "lifeStack", "hand", null, java.util.Map.of("defenderId", "host"), "daño");
        Chain.advance(room);
        accept(velkerop);
        answer("m1", "m2");
        answer("n1");
        assertThat(host.getField()).contains(one);
        assertThat(EffectEngine.inFrontLine(one)).isTrue();
    }

    @Test
    void biokGainsReactivarUntilTheOpponentsTurnEnds() {
        CardSlot biok = withClasses(card("bk", "Biok", 5, 4, "B"), "necrómata");
        summon(biok);
        assertThat(EffectEngine.keywords(biok)).contains("Reactivar");
    }

    @Test
    void bioPrototipoTakesTheRemovedLifeAsAMaterialToReduceAnEnemy() {
        CardSlot bio = onField(host, withClasses(card("bp", "Bio prototipo A0", 8, 5, "B"), "necrómata"));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setStrength(5);
        CardSlot life = host.getLifeStack().remove(0);
        host.getHand().add(life);
        EffectEngine.record(room, GameEvent.LIFE_DAMAGE, "guest", life, "lifeStack", "hand", null, java.util.Map.of("defenderId", "host"), "daño");
        Chain.advance(room);
        untilOffered(bio);
        answer("bp");
        answer("e");
        assertThat(bio.getMaterials()).contains(life);
        assertThat(EffectEngine.strength(enemy)).isEqualTo(2);
    }

    @Test
    void ingenieriaNeurobionicaDestroysAnAllyToSummonALowerNecromataFree() {
        onField(host, withClasses(card("n3", "Necro Tres", 3, 3, "B"), "necrómata"));
        CardSlot two = inHand("n2", "Necro Dos", 2, 2, "B", "necrómata");
        inHand("n3b", "Necro Tres B", 3, 3, "B", "necrómata");
        int before = gems();
        play(utility("in", "Ingeniería Neurobiónica", 1, "B"));
        answer("n3");
        assertThat(room.getPrompt().getValidIds()).containsExactly("n2");
        answer("n2");
        assertThat(host.getField()).contains(two);
        assertThat(EffectEngine.inFrontLine(two)).isTrue();
        assertThat(gems()).isEqualTo(before - 1);
    }

    // ------------------------------------------------------------------ Verde

    @Test
    void cervalyraTakesAGreenStructureThenDraws() {
        CardSlot estr = structure("es", "Estructura Verde", 1, "G");
        host.getDeck().add(0, estr);
        summon(withClasses(card("cv", "Cervalyra", 1, 1, "G"), "fauna", "treant"));
        answer("es");
        // The rest to the bottom, in one choice
        answer();
        assertThat(host.getHand()).contains(estr).hasSize(2);
    }

    @Test
    void monoDePantanoAsAResourceLetsTheAttackerEvolve() {
        CardSlot holder = onField(host, card("ho", "Portador", 1, 1, "G"));
        holder.getResources().add(withClasses(card("mp", "Mono De Pantano", 1, 1, "G"), "fauna"));
        CardSlot evo = card("ev", "Evolución", 2, 2, "G");
        evo.setSpecialSummonKind("evolution");
        evo.setSpecialCost(1);
        evo.setRequirement("criatura nivel 1.");
        host.getHand().add(evo);
        assertThat(CombatRules.attackByPlayer(room, "host", "ho", null)).isNull();
        untilOffered(holder);
        answer("ho");
        answer("ev");
        assertThat(host.getField()).contains(evo);
    }

    @Test
    void veleafEvolvesWhenItMovesUpAndThenDraws() {
        CardSlot veleaf = onField(host, withClasses(card("vl", "Veleaf", 1, 1, "G"), "fauna"), BACK);
        CardSlot evo = card("ev", "Evolución", 2, 2, "G");
        evo.setSpecialSummonKind("evolution");
        evo.setSpecialCost(1);
        evo.setRequirement("criatura nivel 1.");
        host.getHand().add(evo);
        veleaf.setY(25.0);
        EffectEngine.record(room, GameEvent.MOVE, "host", veleaf, null, "frontLine", null, null, "sube");
        Chain.advance(room);
        accept(veleaf);
        answer("ev");
        assertThat(host.getField()).contains(evo);
        // The regulatory draw of the special summon, and "Si lo haces: roba 1 carta"
        assertThat(host.getHand()).hasSize(2);
    }

    @Test
    void fiorvoPlaysAGreenStructureFromTheTributes() {
        CardSlot estr = structure("es", "Estructura Verde", 2, "G");
        host.getTributeZone().add(estr);
        CardSlot fiorvo = withClasses(card("fi", "Fiorvo", 2, 2, "G"), "fauna");
        summon(fiorvo);
        accept(fiorvo);
        answer("es");
        assertThat(host.getField()).contains(estr);
    }

    @Test
    void fiorvoAsAResourceAddsALevelThreeFauna() {
        CardSlot holder = onField(host, card("ho", "Portador", 3, 3, "G"));
        holder.getResources().add(withClasses(card("fi", "Fiorvo", 2, 2, "G"), "fauna"));
        CardSlot fauna = withClasses(card("f3", "Fauna Tres", 3, 3, "G"), "fauna");
        CardSlot other3 = card("o3", "Otra Tres", 3, 3, "G");
        host.getTributeZone().addAll(List.of(fauna, other3));
        assertThat(Chain.activateByPlayer(room, "host", "ho", "fiorvo:hereda-turno-aliado")).isNull();
        assertThat(room.getPrompt().getValidIds()).contains("f3").doesNotContain("o3");
        answer("f3");
        assertThat(holder.getResources()).contains(fauna);
    }

    @Test
    void cylvausExhaustsAGreenCardToLowerTheEvolutionCost() {
        CardSlot cylvaus = onField(host, withClasses(card("cy", "Cylvarus", 2, 2, "G"), "fauna"));
        CardSlot green = onField(host, structure("gr", "Verde", 1, "G"), BACK);
        CardSlot evo = card("ev", "Evolución", 3, 3, "G");
        evo.setSpecialSummonKind("evolution");
        evo.setSpecialCost(2);
        evo.setRequirement("criatura nivel 2.");
        host.getHand().add(evo);
        int before = gems();
        assertThat(Summons.specialByPlayer(room, "host", "ev", "cy")).isNull();
        answer("gr");
        drain();
        assertThat(host.getField()).contains(evo).doesNotContain(cylvaus);
        assertThat(green.isTapped()).isTrue();
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void leshyTakesAGreenResourceAndDraws() {
        CardSlot green = card("g3", "Verde Tres", 3, 3, "G");
        host.getTributeZone().add(green);
        CardSlot leshy = withClasses(card("le", "Leshy", 3, 3, "G"), "fauna", "hada");
        summon(leshy);
        accept(leshy);
        answer("g3");
        assertThat(leshy.getResources()).contains(green);
        assertThat(host.getHand()).hasSize(1);
    }

    @Test
    void filyhoAsAResourceGivesFuriaTwoWhenTheHolderIsSummoned() {
        CardSlot holder = card("ho", "Portador", 3, 3, "G");
        holder.getResources().add(withClasses(card("fy", "Filyho", 3, 3, "G"), "fauna"));
        summon(holder);
        assertThat(EffectEngine.keywords(holder)).contains("Furia 2");
    }

    @Test
    void tharnokCostsLessForEachFaceDownLifeTheOpponentHasMore() {
        guest.getLifeStack().add(card("gl3", "Vida G3", 1, 1, "R"));
        guest.getLifeStack().add(card("gl4", "Vida G4", 1, 1, "R"));
        CardSlot tharnok = card("th", "Tharnok", 5, 4, "G");
        tharnok.setSpecialSummonKind("evolution");
        tharnok.setSpecialCost(3);
        tharnok.setRequirement("criatura nivel 3.");
        host.getHand().add(tharnok);
        onField(host, card("su", "Invocador", 3, 3, "G"));
        int before = gems();
        assertThat(Summons.specialByPlayer(room, "host", "th", "su")).isNull();
        drain();
        assertThat(host.getField()).contains(tharnok);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void druidaAntiguoReactivatesTwoCheapGreenStructuresWhenItAttacks() {
        CardSlot druida = onField(host, withClasses(card("dr", "Druida Antiguo", 8, 5, "G"), "fauna", "treant"));
        CardSlot s1 = onField(host, structure("s1", "E1", 1, "G"), BACK);
        CardSlot s2 = onField(host, structure("s2", "E2", 2, "G"), BACK);
        s1.setTapped(true);
        s2.setTapped(true);
        assertThat(CombatRules.attackByPlayer(room, "host", "dr", null)).isNull();
        untilOffered(druida);
        answer("dr");
        answer("s1", "s2");
        assertThat(s1.isTapped()).isFalse();
        assertThat(s2.isTapped()).isFalse();
    }

    @Test
    void mensajeDeLosEspiritusTributesFaunaResourcesToLowerASpecialSummon() {
        CardSlot holder = onField(host, card("ho", "Portador", 2, 2, "G"));
        holder.getResources().addAll(List.of(withClasses(card("r1", "Fauna 1", 1, 1, "G"), "fauna"),
                withClasses(card("r2", "Fauna 2", 1, 1, "G"), "fauna")));
        CardSlot evo = card("ev", "Evolución", 3, 3, "G");
        evo.setSpecialSummonKind("evolution");
        evo.setSpecialCost(2);
        evo.setRequirement("criatura nivel 2.");
        host.getHand().add(evo);
        int before = gems();
        play(utility("me", "Mensaje De Los Espiritus Protectores", 1, "G"));
        answer("ho");
        answer("r1", "r2");
        answer("ev");
        answer("ho");
        assertThat(host.getField()).contains(evo);
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void praderaSalvajeReactivatesItselfAndAFaunaAtTheStartOfTheOpponentsTurn() {
        CardSlot pradera = onField(host, structure("ps", "Pradera Salvaje", 2, "G"), BACK);
        pradera.setTapped(true);
        CardSlot fauna = onField(host, withClasses(card("fa", "Fauna", 1, 1, "G"), "fauna"));
        fauna.setTapped(true);
        room.setCurrentTurnUserId("guest");
        TurnRules.turnStarted(room, "guest");
        Prompt untap = room.getPrompt();
        if (untap != null && untap.getValidIds().contains("fa")) answer(untap, "fa");
        drain();
        assertThat(pradera.isTapped()).isFalse();
        assertThat(fauna.isTapped()).isFalse();
    }

    // ------------------------------------------------------------------ Violeta

    @Test
    void hulSacrificesItselfWhenACreatureIsDestroyedAndPlaysAUtilityByEffect() {
        CardSlot hul = onField(host, withClasses(card("hu", "Hul", 1, 1, "P"), "espectro", "interdimensional"));
        CardSlot other = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        Removals.destroy(room, other, guest, false, EffectEngine.CAUSE_EFFECT, "host", "src", null);
        Chain.advance(room);
        drain();
        assertThat(host.getTributeZone()).contains(hul);
    }

    @Test
    void brujaMensajeraTributesBrujosAndTakesAVioletCard() {
        host.getDeck().add(0, withClasses(card("bj", "Brujo", 1, 1, "P"), "brujo"));
        summon(withClasses(card("bm", "Bruja Mensajera", 1, 1, "P"), "brujo"));
        answer("bj");
        // The rest to the bottom, in one choice
        answer();
        answer("bj");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("bj");
    }

    @Test
    void piromagoDestroyedToTheDiscardDealsDirectDamage() {
        CardSlot piro = onField(host, withClasses(card("pm", "Piromago", 2, 2, "P"), "pyro", "brujo"));
        destroyByOpponent(piro);
        drain();
        assertThat(guest.getLifeStack()).hasSize(2);
    }

    @Test
    void espantapajarosComesFromTheTributesAndIsTributedAtTheEndOfTheTurn() {
        CardSlot espanta = withClasses(card("ec", "Espantapajaros Carroñero", 2, 2, "P"), "brujo");
        host.getTributeZone().add(espanta);
        for (int i = 0; i < 10; i++) host.getTributeZone().add(card("t" + i, "T" + i, 1, 1, "P"));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        int before = gems();
        CardSlot src = onField(host, card("src", "Fuente", 1, 1, "P"));
        PendingEffect byEffect = Chain.pending(room, CardEffects.of("Dragón Goloso").get(0), "host", src, room.getEventSeq());
        Removals.destroy(room, enemy, guest, false, EffectEngine.CAUSE_EFFECT, "host", "src", byEffect);
        Chain.advance(room);
        accept(espanta);
        assertThat(host.getField()).contains(espanta);
        assertThat(gems()).isEqualTo(before - 1);
        drain();
        TurnRules.turnEnding(room, "host");
        drain();
        assertThat(host.getTributeZone()).contains(espanta);
    }

    @Test
    void necrobrujoPlaysAUtilityFromTheTributesIfItTributedOne() {
        CardSlot util = utility("ut", "Utilidad", 1, "P");
        CardEffects.register("utilidad", new EffectScript("utilidad:en-juego", EffectScript.Moment.ON_PLAY, true, "En Juego", "Gana 1 gema.",
                List.of(Compose.gainGems(1))));
        host.getHand().add(util);
        summon(withClasses(card("nb", "Necrobrujo", 5, 4, "P"), "brujo"));
        answer("ut", "hd1");
        answer("nb");
        answer("ut");
        drain();
        assertThat(host.getTributeZone()).doesNotContain(util);
    }

    @Test
    void apostolDeLaInfamiaSummonsAVioletEspectroToTheFrontLineWhenDestroyed() {
        CardSlot apostol = onField(host, withClasses(card("ai", "Apostol De La Infamia", 7, 5, "P"), "espectro"));
        CardSlot espectro = withClasses(card("es", "Espectro", 7, 4, "P"), "espectro");
        host.getTributeZone().add(espectro);
        destroyByOpponent(apostol);
        accept(apostol);
        answer("es");
        assertThat(host.getField()).contains(espectro);
        assertThat(EffectEngine.inFrontLine(espectro)).isTrue();
    }

    @Test
    void khaanExhaustedSummonsASmallVioletFromTheTributes() {
        CardSlot khaan = onField(host, withClasses(card("kh", "Khaan", 7, 5, "P"), "brujo"));
        CardSlot small = card("sm", "Pequeña", 2, 2, "P");
        host.getTributeZone().add(small);
        assertThat(CombatRules.attackByPlayer(room, "host", "kh", null)).isNull();
        untilOffered(khaan);
        answer("kh");
        answer("sm");
        assertThat(host.getField()).contains(small);
    }

    @Test
    void furiaAlmicaReachesHigherLevelsWithBrujeriaTributes() {
        for (int i = 0; i < 5; i++) host.getTributeZone().add(withClasses(utility("b" + i, "Brujería " + i, 1, "P"), "brujería"));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 2, 2, "R"));
        play(withClasses(utility("fa", "Furia Álmica", 1, "P"), "brujería"));
        answer("e");
        assertThat(guest.getField()).doesNotContain(enemy);
    }

    @Test
    void materializacionInterdimensionalDrawsWithFewTributesOrGainsAGemWithMany() {
        play(utility("mi", "Materialización Interdimensional", 0, "P"));
        answer(room.getPrompt().getValidIds().subList(0, 2).toArray(String[]::new));
        assertThat(host.getHand()).hasSize(1);
    }

    @Test
    void conjuroDeOscuridadCostsOneLessWithABrujoOfLevelFive() {
        onField(host, withClasses(card("bj", "Brujo Cinco", 7, 5, "P"), "brujo"));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        int before = gems();
        play(withClasses(utility("co", "Conjuro De Oscuridad Infinita", 4, "P"), "brujería"));
        answer("e");
        assertThat(guest.getField()).doesNotContain(enemy);
        assertThat(gems()).isEqualTo(before - 3);
    }

    @Test
    void conjuroRevealedAsALifeIsPlayedPayingTwoGems() {
        assertThat(CardEffects.lifePlayCost(withClasses(utility("co", "Conjuro De Oscuridad Infinita", 4, "P"), "brujería"))).isEqualTo(2);
    }

    // ------------------------------------------------------------------ Roja

    @Test
    void peonDelCastilloAscendedSummonsAPrimeraLineaFromTheHand() {
        CardSlot peon = onField(host, withClasses(card("pc", "Peón Del Castillo", 1, 1, "R"), "armada"));
        CardSlot ascended = card("as", "Ascendido", 2, 2, "R");
        ascended.setSpecialSummonKind("promotion");
        ascended.setSpecialCost(1);
        ascended.setRequirement("criatura nivel 1.");
        host.getHand().add(ascended);
        CardSlot pl = inHand("pl", "Infantería De Primera Línea", 2, 2, "R", "armada");
        inHand("x", "Pago", 1, 1, "R");
        assertThat(Summons.specialByPlayer(room, "host", "as", "pc")).isNull();
        untilOffered(peon);
        answer("pc");
        answer("x");
        answer("pl");
        assertThat(host.getField()).contains(pl);
    }

    @Test
    void guerreroDelAlmaGoesBackToTheHandWhenAnAllyIsSpecialSummoned() {
        CardSlot guerrero = onField(host, withClasses(card("ga", "Guerrero Del Alma Imbatible", 2, 1, "R"), "retador"));
        EffectEngine.record(room, GameEvent.SPECIAL_SUMMON, "host", guerrero, "hand", "field", null, java.util.Map.of("summonType", "promotion"), "x");
        Chain.advance(room);
        drain();
        assertThat(host.getHand()).contains(guerrero);
    }

    @Test
    void caballeroVersatilTakesARedCardWhenExhaustedInCombat() {
        CardSlot versatil = onField(host, withClasses(card("cv", "Caballero Versátil", 1, 1, "R"), "armada"));
        host.getTributeZone().add(card("rd", "Roja", 1, 1, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "cv", null)).isNull();
        untilOffered(versatil);
        answer("cv");
        answer("rd");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("rd");
    }

    @Test
    void agruparGivesTheStrengthOfTheExhaustedAllyAndOneMoreHit() {
        CardSlot infanteria = onField(host, withClasses(card("ip", "Infantería De Primera Línea", 2, 2, "R"), "armada"));
        infanteria.setKeywords(new java.util.ArrayList<>(List.of("Agrupar 1")));
        onField(host, card("al", "Aliado", 1, 1, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "ip", null)).isNull();
        untilOffered(infanteria);
        Prompt offer = room.getPrompt();
        // Agrupar is offered (its own effect when exhausted too): take Agrupar
        answer(offer, "ip");
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("al")) answer("al");
        drain();
        assertThat(guest.getLifeStack().size()).isLessThan(3);
    }

    @Test
    void caballeroLeonDoradoTributesAnotherAllyForAGem() {
        onField(host, card("o2", "Otro", 2, 2, "R"));
        CardSlot leon = withClasses(card("cl", "Caballero León Dorado", 2, 2, "R"), "caballero", "retador");
        int before = gems();
        summon(leon);
        accept(leon);
        answer("o2");
        assertThat(gems()).isEqualTo(before - 2 + 1);
    }

    @Test
    void caballeroDeApoyoGivesTwoStrengthUntilTheEndOfTheCombat() {
        CardSlot apoyo = onField(host, withClasses(card("ca", "Caballero De Apoyo", 2, 2, "R"), "armada"));
        assertThat(CombatRules.attackByPlayer(room, "host", "ca", null)).isNull();
        untilOffered(apoyo);
        answer("ca");
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.GAIN.equals(e.getType()) && "ca".equals(e.getCardInstanceId())
                && Integer.valueOf(2).equals(e.getData().get("strength")));
        drain();
        assertThat(EffectEngine.strength(apoyo)).isEqualTo(3);
    }

    @Test
    void armaduraDelAlmaGoesToTheLivesWhenAnAllyIsSpecialSummoned() {
        CardSlot armadura = onField(host, withClasses(card("aa", "Armadura Del Alma Impasible", 2, 2, "R"), "retador"));
        EffectEngine.record(room, GameEvent.SPECIAL_SUMMON, "host", armadura, "hand", "field", null, java.util.Map.of("summonType", "promotion"), "x");
        Chain.advance(room);
        drain();
        assertThat(host.getLifeStack().get(0)).isSameAs(armadura);
        assertThat(armadura.isFaceDown()).isTrue();
    }

    @Test
    void filoDeSangreGainsOnePerLifeTheOpponentHasMore() {
        host.getLifeStack().remove(0);
        host.getLifeStack().remove(0);
        CardSlot filo = withClasses(card("fs", "Filo De Sangre", 3, 3, "R"), "retador");
        summon(filo);
        assertThat(EffectEngine.strength(filo)).isEqualTo(5);
    }

    @Test
    void heroeDePrimeraLineaReactivatesThreePrimeraLineaAtTheEndOfTheTurn() {
        onField(host, card("hp", "Heroe De Primera Línea", 5, 4, "R"));
        CardSlot a = onField(host, card("a", "Infantería De Primera Línea", 2, 2, "R"));
        a.setTapped(true);
        TurnRules.turnEnding(room, "host");
        Prompt p = room.getPrompt();
        while (p != null && !p.getValidIds().contains("a")) {
            answer(p);
            p = room.getPrompt();
        }
        answer(p, "a");
        assertThat(a.isTapped()).isFalse();
    }

    @Test
    void fervorSummonsAPrimeraLineaToTheFrontLine() {
        CardSlot pl = inHand("pl", "Caballería De Primera Línea", 3, 3, "R", "armada");
        CardSlot fervor = utility("fv", "Fervor De La Primera Línea", 1, "R");
        play(fervor);
        answer("fv");
        answer("pl");
        assertThat(host.getField()).contains(pl);
        assertThat(EffectEngine.inFrontLine(pl)).isTrue();
    }

    @Test
    void investiduraReturnsAnAllyToSummonADifferentLeonDoradoOfItsLevelOrLess() {
        onField(host, card("al", "Aliado", 2, 2, "R"));
        CardSlot leon = inHand("ld", "Arco León Dorado", 2, 2, "R", "retador");
        play(utility("iv", "Investidura De Los Leones Dorados", 0, "R"));
        answer("al");
        answer("ld");
        assertThat(host.getField()).contains(leon);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("al");
    }

    // ------------------------------------------------------------------ Blanca

    @Test
    void viajeExtensoDrawsThreeAndAtTheEndOfTheTurnKeepsFour() {
        room.setFirstTurn(false);
        for (int i = 0; i < 3; i++) host.getHand().add(card("hh" + i, "Mano " + i, 1, 1, "R"));
        play(utility("ve", "Viaje Extenso", 0, "W"));
        assertThat(host.getHand()).hasSize(6);
        TurnRules.turnEnding(room, "host");
        Prompt down = room.getPrompt();
        answer(down, down.getValidIds().subList(0, 2).toArray(String[]::new));
        assertThat(host.getHand()).hasSize(4);
    }

    @Test
    void suenoPremonitorioSendsOneOfTheRevealedCardsToTheBottom() {
        room.setFirstTurn(false);
        for (int i = 0; i < 4; i++) guest.getHand().add(card("gh" + i, "Mano G" + i, 1, 1, "R"));
        play(utility("sp", "Sueño Premonitorio", 0, "W"));
        Prompt pick = room.getPrompt();
        assertThat(pick.getValidIds()).hasSize(3);
        answer(pick, pick.getValidIds().get(0));
        assertThat(guest.getHand()).hasSize(3);
    }

    @Test
    void promesaRotaOnlyAfterAnAllyWasDestroyedByTheOpponentTheTurnBefore() {
        room.setFirstTurn(false);
        CardSlot promesa = utility("pr", "Promesa Rota", 0, "W");
        host.getHand().add(promesa);
        assertThat(Summons.unplayable(room, "host")).contains("pr");
        room.getUsedThisTurn().add(EffectEngine.ALLY_DESTROYED_LAST_TURN + "host");
        CardSlot creature = inHand("cr", "Criatura", 2, 2, "R");
        play(promesa);
        answer("pr");
        answer("cr");
        assertThat(host.getField()).contains(creature);
    }

    @Test
    void condensadorTurnsAllLivesFaceDown() {
        room.setFirstTurn(false);
        host.getLifeStack().forEach(l -> l.setFaceDown(false));
        play(utility("cm", "Condensador De Materia", 0, "W"));
        assertThat(host.getLifeStack()).allMatch(CardSlot::isFaceDown);
    }

    @Test
    void magiaIncontrolableTakesANonWhiteUtilityFromTheDiscard() {
        room.setFirstTurn(false);
        host.getDiscardPile().addAll(List.of(utility("w", "Blanca", 0, "W"), utility("p", "Violeta", 0, "P")));
        play(utility("mg", "Magia Incontrolable", 0, "W"));
        assertThat(room.getPrompt().getValidIds()).containsExactly("p");
        answer("p");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("p");
    }
}
