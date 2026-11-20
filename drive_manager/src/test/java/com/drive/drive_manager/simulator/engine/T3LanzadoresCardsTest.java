package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Cards in testing T3, archetype Lanzadores de Conjuros (2026-11-19): one test (or more) per programmed effect, from the printed text. */
@Tag("cards")
@Tag("t3")
class T3LanzadoresCardsTest extends CardBatchSupport {

    private static CardSlot lz(String id, String name, Integer cost, int level, int strength, String color) {
        CardSlot c = withClasses(card(id, name, cost, level, color), "lanzador de conjuros");
        c.setStrength(strength);
        return c;
    }

    private static CardSlot promotion(CardSlot card, int specialCost) {
        card.setSpecialSummonKind("promotion");
        card.setSpecialCost(specialCost);
        return card;
    }

    private static CardSlot lux(String id) { return lz(id, "Lux", 1, 1, 1, "B"); }
    private static CardSlot lyra(String id) { return promotion(lz(id, "Lyra", 2, 2, 4, "B"), 2); }
    private static CardSlot bonny(String id) { return lz(id, "Bonny", 1, 1, 1, "G"); }
    private static CardSlot ignis(String id) { return promotion(lz(id, "Ignis", 2, 2, 4, "G"), 2); }
    private static CardSlot velmora(String id) { return promotion(lz(id, "Velmora", 2, 2, 4, "P"), 2); }
    private static CardSlot freiha(String id) { return lz(id, "Freiha", 1, 1, 1, "R"); }
    private static CardSlot luccy(String id) { return promotion(lz(id, "Luccy", 2, 2, 4, "W"), 2); }

    private static CardSlot marrow(String id) {
        CardSlot c = lz(id, "Marrow", 5, 4, 3, "P");
        c.setSpecialSummonKind("ritual");
        c.setSpecialCost(2);
        return c;
    }

    private static CardSlot agata(String id) { return promotion(lz(id, "Ágata", 3, 3, 5, "R"), 1); }
    private static CardSlot solaris(String id) { return promotion(lz(id, "Solaris", 5, 4, 12, "R"), 2); }

    private static CardSlot lumisdragon(String id) {
        CardSlot c = promotion(withClasses(card(id, "Lumisdragon Arcano", null, 6, "G"), "dragón", "lanzador de conjuros"), 10);
        c.setColors(new ArrayList<>(List.of("G", "B")));
        c.setStrength(20);
        return c;
    }

    private static CardSlot eiris(String id) {
        CardSlot c = lz(id, "Eiris", 4, 4, 10, "B");
        c.setSpecialSummonKind("materialization");
        c.setSpecialCost(2);
        return c;
    }

    private static CardSlot lummy(String id) {
        CardSlot c = utility(id, "Lummy", 1, "B");
        c.setClasses(new ArrayList<>(List.of("lanzador de conjuros")));
        return c;
    }

    private static CardSlot noth(String id) {
        CardSlot c = structure(id, "Tierra de Noth", 1, "R");
        c.setLevel(2);
        return c;
    }

    private List<String> ids(List<CardSlot> cards) {
        return cards.stream().map(CardSlot::getInstanceId).toList();
    }

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

    private void faceUp(CardSlot life) {
        life.setFaceDown(false);
    }

    private long revealed() {
        return room.getTimeline().stream().filter(e -> GameEvent.LIFE_REVEALED.equals(e.getType())).count();
    }

    /** Freiha from the hand: tributes 2 [lanzador de conjuros] of the hand, is summoned and reveals the host's first life. */
    private void freihaReveals() {
        host.getHand().addAll(List.of(freiha("fr"), lz("h1", "Mano Uno", 1, 1, 1, "R"), lz("h2", "Mano Dos", 1, 1, 1, "R")));
        activate("fr", "freiha:en-mano");
        answer("h1", "h2");
        answer("hl0");
    }

    // ------------------------------------------------------------------ B01 Lux

    @Test
    void luxTributedAsTheSummonerOfAnAscensoPutsALanzadorInTheLivesAndRevealsOne() {
        onField(host, lux("lx"));
        CardSlot lyra = lyra("ly");
        CardSlot hidden = lz("h1", "En Mano", 1, 1, 1, "B");
        host.getHand().addAll(List.of(lyra, hidden));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ly", "lx")).isNull();
        accept(host.getTributeZone().get(0));
        answer("h1");
        assertThat(host.getLifeStack().get(0)).isSameAs(hidden);
        assertThat(hidden.isFaceDown()).isTrue();
        answer("hl1");
        assertThat(host.getLifeStack().get(2).isFaceDown()).isFalse();
        assertThat(revealed()).isEqualTo(1);
    }

    @Test
    void luxTakesACardOfLevelTwoOrLessAndTributesTheRest() {
        host.getDeck().addAll(0, List.of(card("l3", "Nivel 3", 1, 3, "R"), card("l2", "Nivel 2", 1, 2, "R"), card("l4", "Nivel 4", 1, 4, "R")));
        summon(lux("lx"));
        assertThat(room.getPrompt().getValidIds()).containsExactly("l2");
        answer("l2");
        assertThat(ids(host.getHand())).contains("l2");
        assertThat(ids(host.getTributeZone())).containsExactlyInAnyOrder("l3", "l4");
    }

    // ------------------------------------------------------------------ B02 Lyra

    @Test
    void lyraReducesTheChosenEnemyByOneForEachFaceUpLife() {
        faceUp(host.getLifeStack().get(0));
        faceUp(guest.getLifeStack().get(1));
        onField(host, lyra("ly"));
        CardSlot enemy = onField(guest, card("en", "Enemigo", 1, 1, "R"));
        enemy.setStrength(5);
        assertThat(CombatRules.attackByPlayer(room, "host", "ly", null)).isNull();
        answer("en");
        assertThat(EffectEngine.strength(enemy)).isEqualTo(3);
    }

    @Test
    void lyraFromTheTributosTakesALifeBelowTheTopAfterALifeIsRevealed() {
        CardSlot lyra = lyra("ly");
        CardSlot other = lz("ot", "Otro Lanzador", 1, 1, 1, "B");
        host.getTributeZone().addAll(List.of(lyra, other));
        freihaReveals();
        List<String> top = ids(host.getDeck().subList(0, 1));
        accept(lyra);
        answer("ot");
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("hl1", "hl2");
        answer("hl2");
        assertThat(ids(host.getHand())).contains("hl2");
        assertThat(ids(host.getLifeStack())).containsExactly("hl0", "hl1", top.get(0));
        assertThat(host.getDeck().subList(host.getDeck().size() - 2, host.getDeck().size())).containsExactly(lyra, other);
    }

    @Test
    void lyraIsNotOfferedWhenSomethingElseHappenedAfterTheReveal() {
        CardSlot lyra = lyra("ly");
        host.getTributeZone().addAll(List.of(lyra, lz("ot", "Otro Lanzador", 1, 1, 1, "B")));
        // Eiris reveals and then tributes a life: the reveal is not the last thing
        guest.getLifeStack().get(0).setFaceDown(false);
        CardSlot eiris = onField(host, eiris("ei"));
        assertThat(CombatRules.attackByPlayer(room, "host", "ei", null)).isNull();
        answer("hl0");
        answer("gl1");
        answer(guest.getLifeStack().stream().filter(l -> !l.isFaceDown()).findFirst().orElseThrow().getInstanceId());
        assertThat(room.getPrompt() == null || !room.getPrompt().getValidIds().contains("ly")).isTrue();
        assertThat(eiris).isNotNull();
    }

    // ------------------------------------------------------------------ B03 Eiris

    @Test
    void eirisTributesItsMaterialsAndTurnsOverALifeForEachLanzador() {
        CardSlot eiris = onField(host, eiris("ei"));
        eiris.getMaterials().addAll(List.of(lz("m1", "Material Lanzador", 1, 2, 1, "B"), card("m2", "Otro Material", 1, 1, "R")));
        EffectEngine.record(room, GameEvent.SPECIAL_SUMMON, "host", eiris, "hand", "field", null, java.util.Map.of("summonType", "materialization"), "invoca");
        Chain.advance(room);
        assertThat(room.getPrompt().getMax()).isEqualTo(1);
        answer("hl1");
        assertThat(host.getLifeStack().get(1).isFaceDown()).isFalse();
        assertThat(ids(host.getTributeZone())).containsExactlyInAnyOrder("m1", "m2");
    }

    @Test
    void eirisMaterializationTakesOneOrTwoAllies() {
        onField(host, lz("s2", "Invocador", 2, 2, 3, "B"));
        onField(host, card("a1", "Aliado Uno", 1, 1, "B"));
        onField(host, card("a2", "Aliado Dos", 1, 1, "B"));
        onField(host, card("a3", "Aliado Tres", 1, 1, "B"));
        host.getHand().add(eiris("ei"));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ei", "s2")).isNull();
        assertThat(room.getPrompt().getMax()).isEqualTo(2);
    }

    @Test
    void eirisRevealsALifeOfEachPlayerAndTributesAFaceUpOpponentLife() {
        onField(host, eiris("ei"));
        assertThat(CombatRules.attackByPlayer(room, "host", "ei", null)).isNull();
        answer("hl0");
        answer("gl2");
        answer("gl2");
        assertThat(host.getLifeStack().get(0).isFaceDown()).isFalse();
        assertThat(ids(guest.getTributeZone()).get(0)).isEqualTo("gl2");
    }

    // ------------------------------------------------------------------ B04 Lummy

    @Test
    void lummyBecomesACreatureOfStrengthOneAndMaySummonALanzadorFromTheTributos() {
        onField(guest, card("e1", "Enemigo Uno", 1, 1, "R"));
        onField(guest, card("e2", "Enemigo Dos", 1, 1, "R"));
        CardSlot fromTributes = lz("tr", "En Tributos", 2, 2, 3, "B");
        host.getTributeZone().add(fromTributes);
        CardSlot lummy = lummy("lu");
        play(lummy);
        answer("lu");
        answer("tr");
        assertThat(host.getField()).contains(lummy, fromTributes);
        assertThat(lummy.getCardType()).isEqualTo("creature");
        assertThat(EffectEngine.strength(lummy)).isEqualTo(1);
        assertThat(EffectEngine.inFrontLine(lummy)).isFalse();
        // 1 for Lummy, 1 for the creature (2 - 1)
        assertThat(gems()).isEqualTo(6);
        Removals.destroy(room, lummy, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        assertThat(lummy.getCardType()).isEqualTo("utility");
    }

    @Test
    void lummySummoningADragonLowersItsSpecialCostByThree() {
        CardSlot lummy = lummy("lu");
        play(lummy);
        CardSlot dragon = lumisdragon("ld");
        host.getHand().add(dragon);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ld", "lu")).isNull();
        drain();
        assertThat(host.getField()).contains(dragon);
        // 8 - 1 (Lummy) - (10 - 3)
        assertThat(gems()).isEqualTo(0);
    }

    // ------------------------------------------------------------------ G01 Bonny

    @Test
    void bonnyIsSummonedForFreeWhenYourEffectRevealsOneOfYourLives() {
        CardSlot bonny = bonny("bo");
        host.getHand().add(bonny);
        host.getHand().add(card("hk", "Para Tributar", 1, 1, "R"));
        freihaReveals();
        accept(bonny);
        answer("hk");
        drain();
        assertThat(host.getField()).contains(bonny);
        assertThat(gems()).isEqualTo(8);
    }

    @Test
    void bonnyDrawsTwoAndTributesTwo() {
        int deck = host.getDeck().size();
        summon(bonny("bo"));
        assertThat(host.getDeck()).hasSize(deck - 2);
        List<String> two = ids(host.getHand().subList(0, 2));
        answer(two.toArray(String[]::new));
        assertThat(ids(host.getTributeZone())).containsExactlyInAnyOrderElementsOf(two);
    }

    // ------------------------------------------------------------------ G02 Ignis

    @Test
    void ignisDestroysAnEnemyUpToOneStrengthPlusOnePerFaceUpLife() {
        faceUp(host.getLifeStack().get(2));
        onField(host, ignis("ig"));
        onField(guest, card("e2", "Fuerza Dos", 1, 1, "R")).setStrength(2);
        onField(guest, card("e3", "Fuerza Tres", 1, 1, "R")).setStrength(3);
        assertThat(CombatRules.attackByPlayer(room, "host", "ig", null)).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("e2");
        answer("e2");
        assertThat(ids(guest.getDiscardPile())).contains("e2");
    }

    @Test
    void ignisFromTheTributosDrawsTwoAndTributesOne() {
        CardSlot ignis = ignis("ig");
        host.getTributeZone().addAll(List.of(ignis, lz("ot", "Otro Lanzador", 1, 1, 1, "G")));
        freihaReveals();
        accept(ignis);
        answer("ot");
        int hand = host.getHand().size();
        answer(host.getHand().get(0).getInstanceId());
        assertThat(host.getHand()).hasSize(hand - 1);
        assertThat(host.getDeck()).contains(ignis);
    }

    // ------------------------------------------------------------------ G03 Lumisdragon Arcano

    @Test
    void lumisdragonGivesTwentyToTheCreaturesInYourLivesFromTheFrontLine() {
        CardSlot life = host.getLifeStack().get(0);
        CardSlot dragon = onField(host, lumisdragon("ld"));
        assertThat(CardEffects.lifeStrength(room, "host", life)).isEqualTo(23);
        dragon.setY(BACK);
        assertThat(CardEffects.lifeStrength(room, "host", life)).isEqualTo(3);
    }

    @Test
    void lumisdragonCostsOneLessPerFaceUpLifeAndTurnsTheLivesFaceDown() {
        faceUp(host.getLifeStack().get(0));
        faceUp(host.getLifeStack().get(1));
        onField(host, lz("su", "Invocador", 1, 1, 1, "G"));
        CardSlot dragon = lumisdragon("ld");
        host.getHand().add(dragon);
        room.setSharedCounter(-10);
        host.setResourceCount(10);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "ld", "su")).isNull();
        drain();
        assertThat(host.getField()).contains(dragon);
        assertThat(gems()).isEqualTo(2);
        assertThat(host.getLifeStack()).allMatch(CardSlot::isFaceDown);
    }

    @Test
    void lumisdragonDealsNoDirectDamage() {
        onField(host, lumisdragon("ld"));
        assertThat(CombatRules.attackByPlayer(room, "host", "ld", null)).isNull();
        drain();
        assertThat(guest.getLifeStack()).hasSize(3);
    }

    // ------------------------------------------------------------------ P01 Velmora

    @Test
    void velmoraDestroysAnEnemyOfLevelZeroPlusOnePerTwoFaceUpLives() {
        faceUp(host.getLifeStack().get(0));
        faceUp(guest.getLifeStack().get(0));
        onField(host, velmora("ve"));
        onField(guest, card("e1", "Nivel Uno", 1, 1, "R"));
        onField(guest, card("e2", "Nivel Dos", 1, 2, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "ve", null)).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("e1");
        answer("e1");
        assertThat(ids(guest.getDiscardPile())).contains("e1");
    }

    @Test
    void velmoraFromTheTributosTakesALanzadorAndTributesOne() {
        CardSlot velmora = velmora("ve");
        CardSlot taken = lz("tk", "Para Tomar", 1, 1, 1, "P");
        host.getTributeZone().addAll(List.of(velmora, lz("ot", "Otro Lanzador", 1, 1, 1, "P"), taken));
        freihaReveals();
        accept(velmora);
        answer("ot");
        answer("tk");
        answer("tk");
        assertThat(host.getTributeZone()).contains(taken);
        assertThat(host.getDeck()).contains(velmora);
    }

    // ------------------------------------------------------------------ P02 Marrow

    @Test
    void marrowPutsALevelOneEnemyAtTheBottomOfTheOpponentsLivesAndRevealsOne() {
        onField(host, marrow("ma"));
        CardSlot enemy = onField(guest, card("e1", "Nivel Uno", 1, 1, "R"));
        onField(guest, card("e2", "Nivel Dos", 1, 2, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "ma", null)).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("e1");
        answer("e1");
        assertThat(guest.getLifeStack().get(guest.getLifeStack().size() - 1)).isSameAs(enemy);
        answer("gl0");
        assertThat(guest.getLifeStack().get(0).isFaceDown()).isFalse();
    }

    @Test
    void marrowTributesAFaceUpOpponentLifeAtTheEndOfTheTurnFromTheFrontLine() {
        onField(host, marrow("ma"));
        faceUp(guest.getLifeStack().get(1));
        EffectEngine.record(room, GameEvent.TURN_END, "host", null, null, null, null, null, "fin");
        Chain.advance(room);
        assertThat(room.getPrompt().getValidIds()).containsExactly("gl1");
        answer("gl1");
        assertThat(ids(guest.getTributeZone())).containsExactly("gl1");
    }

    @Test
    void marrowsRitualTakesALevelTwoCreatureAndAnyOther() {
        onField(host, lz("l2", "Nivel Dos", 2, 2, 3, "P"));
        onField(host, lz("l1", "Nivel Uno", 1, 1, 3, "P"));
        host.getHand().add(marrow("ma"));
        assertThat(Rituals.ritualByPlayer(room, "host", "ma", null)).isNull();
        answer("l2");
        answer("l1");
        assertThat(ids(host.getField())).contains("ma");
    }

    // ------------------------------------------------------------------ R01 Freiha

    @Test
    void freihaIsSummonedTributingTwoLanzadoresAndRevealsALife() {
        freihaReveals();
        drain();
        assertThat(ids(host.getField())).contains("fr");
        assertThat(host.getLifeStack().get(0).isFaceDown()).isFalse();
        assertThat(gems()).isEqualTo(8);
        assertThat(activatable("fr", "freiha")).isNull();
    }

    @Test
    void freihaAsSummonerLowersTheSpecialCostSendingFiveLanzadoresToTheDeck() {
        CardSlot freiha = onField(host, freiha("fr"));
        host.getTributeZone().addAll(List.of(lz("t1", "T1", 1, 1, 1, "R"), lz("t2", "T2", 1, 1, 1, "R"), lz("t3", "T3", 1, 1, 1, "R"),
                lz("t4", "T4", 1, 1, 1, "R")));
        CardSlot lyra = lyra("ly");
        assertThat(CostModifiers.available(room, host, CostModifiers.Event.SPECIAL_SUMMON, "promotion", lyra, freiha)).isZero();
        host.getTributeZone().add(lz("t5", "T5", 1, 1, 1, "R"));
        assertThat(CostModifiers.available(room, host, CostModifiers.Event.SPECIAL_SUMMON, "promotion", lyra, freiha)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ R02 Ágata

    @Test
    void agataGainsStrengthPerFaceUpLifeAndFacesActiveEnemiesOfLowerLevel() {
        CardSlot agata = onField(host, agata("ag"));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.keywordLevel(agata, "Enfrentar")).isNull();
        faceUp(host.getLifeStack().get(0));
        faceUp(host.getLifeStack().get(1));
        faceUp(guest.getLifeStack().get(0));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(agata)).isEqualTo(8);
        assertThat(EffectEngine.keywordLevel(agata, "Enfrentar")).isEqualTo(1);
    }

    @Test
    void agataComesFromTheTributosByAscensoForFreeAndRevealsALife() {
        onField(host, lyra("ly"));
        CardSlot agata = agata("ag");
        host.getTributeZone().add(agata);
        activate("ag", "agata:en-tributos");
        answer("ag");
        answer("ly");
        answer("hl0");
        assertThat(host.getField()).contains(agata);
        assertThat(gems()).isEqualTo(8);
        assertThat(host.getLifeStack().get(0).isFaceDown()).isFalse();
    }

    // ------------------------------------------------------------------ R03 Solaris

    @Test
    void solarisRevealsAnOpponentLifeAndTributesAllTheFaceUpOnes() {
        faceUp(guest.getLifeStack().get(2));
        onField(host, solaris("so"));
        assertThat(CombatRules.attackByPlayer(room, "host", "so", null)).isNull();
        answer("gl0");
        // gl1 is then lost to the direct damage
        assertThat(ids(guest.getTributeZone())).contains("gl0", "gl2");
    }

    @Test
    void solarisAscensoNeedsTwoOrLessFaceDownLives() {
        onField(host, lz("l3", "Nivel Tres", 3, 3, 3, "R"));
        CardSlot solaris = solaris("so");
        host.getHand().add(solaris);
        assertThat(SpecialRequirements.canSpecial(room, "host", solaris)).isFalse();
        faceUp(host.getLifeStack().get(0));
        assertThat(SpecialRequirements.canSpecial(room, "host", solaris)).isTrue();
    }

    @Test
    void solarisLeavingTheLivesPutsACreatureOfTheHandAtTheirBottomFaceUp() {
        CardSlot solaris = solaris("so");
        solaris.setFaceDown(false);
        host.getLifeStack().add(0, solaris);
        CardSlot placed = card("pl", "Colocada", 1, 1, "R");
        host.getHand().add(placed);
        EffectEngine.loseTopLife(room, host, 1, "guest", null, "atacante", "Beto ataca: ");
        Chain.advance(room);
        accept(solaris);
        answer("pl");
        assertThat(host.getLifeStack().get(host.getLifeStack().size() - 1)).isSameAs(placed);
        assertThat(placed.isFaceDown()).isFalse();
    }

    // ------------------------------------------------------------------ R04 Tierra de Noth

    @Test
    void tierraDeNothIsPlayedFromTheHandForFreeAndRevealsALife() {
        CardSlot noth = noth("tn");
        host.getHand().add(noth);
        activate("tn", "tierra-de-noth:en-mano");
        answer("hl0");
        assertThat(host.getField()).contains(noth);
        assertThat(gems()).isEqualTo(8);
        assertThat(host.getLifeStack().get(0).isFaceDown()).isFalse();
    }

    @Test
    void tierraDeNothExhaustsToGiveAlcanceToAnAttackingLanzador() {
        CardSlot noth = onField(host, noth("tn"), BACK);
        CardSlot attacker = onField(host, lz("at", "Atacante", 1, 1, 3, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "at", null)).isNull();
        untilOffered(noth);
        accept(noth);
        assertThat(noth.isTapped()).isTrue();
        // Until the end of the attack (it went on to the direct damage and ended)
        assertThat(room.getEventLog()).anyMatch(l -> l.getText().contains("Alcance") && l.getText().contains("Atacante"));
        assertThat(EffectEngine.keywords(attacker)).doesNotContain("Alcance");
    }

    // ------------------------------------------------------------------ W01 Luccy

    @Test
    void luccySendsALevelFiveEnemyItClashesWithToTheTributosAndGoesToTheDeck() {
        CardSlot luccy = onField(host, luccy("lc"));
        luccy.setKeywords(new ArrayList<>(List.of("Enfrentar")));
        CardSlot enemy = onField(guest, card("e5", "Nivel Cinco", 1, 5, "R"));
        enemy.setStrength(9);
        assertThat(CombatRules.attackByPlayer(room, "host", "lc", "e5")).isNull();
        drain();
        assertThat(guest.getTributeZone()).contains(enemy);
        assertThat(host.getDeck().get(host.getDeck().size() - 1)).isSameAs(luccy);
    }
}
