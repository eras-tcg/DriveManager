package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cards in testing T4, archetype Kuwal (2026-11-19): one test (or more) per programmed effect, from the printed text. Excavar: vault
 * {@code 01 Reglas/03 Mecánicas/Excavar - regla de desarrollo} (the rest goes to the bottom of the deck in random order).
 */
@Tag("cards")
@Tag("t4")
class T4KuwalCardsTest extends CardBatchSupport {

    /** A Kuwal card as printed: green and red unless the colors say otherwise. */
    private static CardSlot kuwal(String id, String name, Integer cost, int level, int strength, String... colors) {
        CardSlot c = withClasses(card(id, name, cost, level, colors.length == 0 ? "G" : colors[0]), "kuwal");
        c.setColors(new ArrayList<>(colors.length == 0 ? List.of("G", "R") : List.of(colors)));
        c.setStrength(strength);
        return c;
    }

    /** A Kuwal with a Ritual (its Requerimiento is its card file's). */
    private static CardSlot ritual(CardSlot card, int specialCost) {
        card.setSpecialSummonKind("ritual");
        card.setSpecialCost(specialCost);
        return card;
    }

    private static CardSlot esbirro(String id) {
        return kuwal(id, "Esbirro Kuwal", 2, 1, 5);
    }

    private static CardSlot guerrero(String id) {
        return ritual(kuwal(id, "Guerrero Kuwal", 2, 2, 5), 0);
    }

    private static CardSlot demoledor(String id) {
        return ritual(kuwal(id, "Demoledor Kuwal", 2, 2, 5), 1);
    }

    private static CardSlot centurion(String id) {
        return ritual(kuwal(id, "Kuwal Centurión", 3, 2, 6), 1);
    }

    private static CardSlot campeon(String id) {
        return kuwal(id, "Campeón Kuwal", 3, 3, 9);
    }

    private static CardSlot primus(String id) {
        return ritual(kuwal(id, "Primus Kuwal", 3, 3, 8), 1);
    }

    private static CardSlot ngakpa(String id) {
        return kuwal(id, "Ngakpa Kuwal", null, 3, 6, "G", "B");
    }

    private static CardSlot comandante(String id) {
        return ritual(kuwal(id, "Comandante Kuwal", 5, 4, 10), 2);
    }

    private static CardSlot chaman(String id) {
        return ritual(kuwal(id, "Chamán Kuwal", 5, 4, 7, "G"), 2);
    }

    private static CardSlot coloso(String id) {
        return kuwal(id, "Coloso Milenario Kuwal", null, 5, 10, "B");
    }

    private static CardSlot ejercito(String id) {
        CardSlot c = utility(id, "Ejército Kuwal", 2, "G");
        c.setColors(new ArrayList<>(List.of("G", "R")));
        c.setClasses(new ArrayList<>(List.of("kuwal")));
        return c;
    }

    /** Plain Kuwal creatures for the zones that pay costs ("5 de tus tributos [kuwal]"): levels 1, 2, 3… when `distinct`. */
    private List<CardSlot> fillers(String prefix, int n, boolean distinct) {
        List<CardSlot> cards = new ArrayList<>();
        for (int i = 1; i <= n; i++) cards.add(kuwal(prefix + i, "Kuwal Común " + i, 1, distinct ? i : 1, 3));
        return cards;
    }

    private List<String> ids(List<CardSlot> cards) {
        return cards.stream().map(CardSlot::getInstanceId).toList();
    }

    private boolean offered(CardSlot source) {
        Prompt p = room.getPrompt();
        return p != null && p.getValidIds().contains(source.getInstanceId());
    }

    /** The player excavated (what Demoledor's or Ejército's excavation records). */
    private void excavated() {
        EffectEngine.record(room, GameEvent.EXCAVATE, "host", null, "deck", null, null, Map.of("count", 1), "excava");
        Chain.advance(room);
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

    private void sacrificeByHost(CardSlot card) {
        Removals.destroy(room, card, host, true, EffectEngine.CAUSE_EFFECT, "host", "src", null);
        Chain.advance(room);
    }

    // ------------------------------------------------------------------ G01 Esbirro Kuwal

    @Test
    void esbirroSummonsItselfFromTheHandForOneLessWithOnlyKuwalCreatures() {
        onField(host, guerrero("gu"));
        CardSlot esbirro = esbirro("es");
        host.getHand().add(esbirro);
        activate("es", "esbirro-kuwal:en-mano");
        drain();
        assertThat(host.getField()).contains(esbirro);
        assertThat(gems()).isEqualTo(7);
    }

    @Test
    void esbirroCannotSummonItselfWithAnotherCreatureAndOnlyOncePerTurnBetweenCopies() {
        CardSlot other = onField(host, card("ot", "Otra", 1, 1, "R"));
        host.getHand().add(esbirro("e1"));
        assertThat(activatable("e1", "esbirro-kuwal:en-mano")).isNull();
        host.getField().remove(other);
        // From the Tributos too, with no creatures at all
        host.getTributeZone().add(esbirro("e2"));
        activate("e2", "esbirro-kuwal:en-mano#tributos");
        drain();
        assertThat(activatable("e1", "esbirro-kuwal:en-mano")).isNull();
    }

    @Test
    void esbirroTakesAKuwalGreenOrWhiteCardAndTributesOneFromTheHand() {
        host.getDeck().addAll(0, List.of(card("r1", "Roja", 1, 1, "R"), card("w1", "Blanca", 1, 1, "W"), card("r2", "Otra Roja", 1, 1, "R")));
        CardSlot kept = card("hk", "En Mano", 1, 1, "R");
        host.getHand().add(kept);
        summon(esbirro("es"));
        assertThat(room.getPrompt().getValidIds()).containsExactly("w1");
        answer("w1");
        answer("hk");
        assertThat(ids(host.getHand())).contains("w1").doesNotContain("hk");
        assertThat(host.getTributeZone()).containsExactly(kept);
        assertThat(ids(host.getDeck().subList(host.getDeck().size() - 2, host.getDeck().size()))).containsExactlyInAnyOrder("r1", "r2");
    }

    // ------------------------------------------------------------------ G02 Guerrero Kuwal

    @Test
    void guerreroGivesTwoStrengthToKuwalAlliesDuringYourTurn() {
        CardSlot ally = onField(host, esbirro("es"));
        CardSlot plain = onField(host, card("pl", "Otra", 1, 1, "R"));
        onField(host, guerrero("gu"));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(ally)).isEqualTo(7);
        assertThat(EffectEngine.strength(plain)).isEqualTo(3);
        room.setCurrentTurnUserId("guest");
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(ally)).isEqualTo(5);
    }

    @Test
    void guerreroGivesPerforanteOnceATurnWhenYouExcavate() {
        CardSlot ally = onField(host, esbirro("es"));
        onField(host, guerrero("gu"));
        excavated();
        answer("es");
        assertThat(EffectEngine.keywords(ally)).contains("Perforante");
        excavated();
        assertThat(room.getPrompt()).isNull();
    }

    @Test
    void guerrerosRitualTakesACreatureOfCostOrLevelTwoThatIsNotACopy() {
        CardSlot guerrero = guerrero("gu");
        host.getHand().add(guerrero);
        onField(host, guerrero("g2"));
        assertThat(Rituals.canRitual(room, "host", guerrero)).isFalse();
        // Esbirro: level 1, cost 2
        onField(host, esbirro("es"));
        assertThat(Rituals.ritualByPlayer(room, "host", "gu", null)).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly("es");
        answer("es");
        assertThat(host.getField()).contains(guerrero);
        assertThat(ids(host.getTributeZone())).contains("es");
    }

    // ------------------------------------------------------------------ G03 Demoledor Kuwal

    @Test
    void demoledorSacrificedExcavatesUntilAKuwalTakesItAndTributesFromTheHand() {
        CardSlot found = esbirro("k1");
        host.getDeck().addAll(0, List.of(card("r1", "Roja", 1, 1, "R"), card("r2", "Otra Roja", 1, 1, "R"), found, card("r3", "Tercera", 1, 1, "R")));
        CardSlot kept = card("hk", "En Mano", 1, 1, "R");
        host.getHand().add(kept);
        CardSlot demoledor = onField(host, demoledor("de"));
        sacrificeByHost(demoledor);
        answer("hk");
        assertThat(host.getHand()).contains(found).doesNotContain(kept);
        assertThat(ids(host.getDeck()).get(0)).isEqualTo("r3");
        assertThat(ids(host.getDeck().subList(host.getDeck().size() - 2, host.getDeck().size()))).containsExactlyInAnyOrder("r1", "r2");
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.EXCAVATE.equals(e.getType()));
    }

    @Test
    void demoledorExcavatesFiveCardsAtMostAndTakesNothingWithoutAKuwal() {
        int before = host.getDeck().size();
        CardSlot demoledor = onField(host, demoledor("de"));
        host.getHand().add(card("hk", "En Mano", 1, 1, "R"));
        sacrificeByHost(demoledor);
        answer("hk");
        assertThat(host.getHand()).isEmpty();
        assertThat(host.getDeck()).hasSize(before);
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.EXCAVATE.equals(e.getType()) && Integer.valueOf(5).equals(e.getData().get("count")));
    }

    @Test
    void demoledorTributesThreeFromTheTopAndMayTakeOneFromTheTributos() {
        List<String> top = ids(host.getDeck().subList(0, 3));
        summon(demoledor("de"));
        assertThat(ids(host.getTributeZone())).containsExactlyElementsOf(top);
        answer(top.get(1));
        assertThat(ids(host.getHand())).contains(top.get(1));
        assertThat(host.getTributeZone()).hasSize(2);
    }

    // ------------------------------------------------------------------ G04 Kuwal Centurión

    @Test
    void centurionComesBackByRitualFromTheTributosWhenYouExcavateForOneLessOnYourTurn() {
        CardSlot summoner = onField(host, esbirro("es"));
        CardSlot centurion = centurion("ce");
        host.getTributeZone().add(centurion);
        host.getHand().add(card("hk", "En Mano", 1, 1, "R"));
        excavated();
        accept(centurion);
        answer("hk");
        answer("ce");
        answer("es");
        assertThat(host.getField()).contains(centurion);
        assertThat(host.getTributeZone()).contains(summoner);
        // Special cost 1, 1 less on your turn
        assertThat(gems()).isEqualTo(8);
    }

    @Test
    void centurionSacrificesAKuwalOfTheHandAndReactivatesAKuwalAlly() {
        CardSlot tired = onField(host, esbirro("es"));
        tired.setTapped(true);
        CardSlot fromHand = demoledor("dh");
        host.getHand().add(fromHand);
        summon(centurion("ce"));
        accept(host.getField().get(1));
        answer("dh");
        answer("es");
        assertThat(tired.isTapped()).isFalse();
        assertThat(host.getTributeZone()).contains(fromHand);
        // Sacrificed from the hand: Demoledor's own effect triggers (it excavates)
        drain();
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.EXCAVATE.equals(e.getType()));
    }

    // ------------------------------------------------------------------ G05 Campeón Kuwal

    @Test
    void campeonIsPlacedInTheFrontLinePayingTwoGemsAndFiveKuwalOfTheTributos() {
        CardSlot campeon = campeon("ca");
        host.getTributeZone().add(campeon);
        List<CardSlot> paid = fillers("f", 5, false);
        host.getTributeZone().addAll(paid);
        excavated();
        accept(campeon);
        answer(ids(paid).toArray(String[]::new));
        assertThat(host.getField()).contains(campeon);
        assertThat(EffectEngine.inFrontLine(campeon)).isTrue();
        assertThat(host.getDiscardPile()).containsAll(paid);
        assertThat(gems()).isEqualTo(6);
        // Placed: not summoned
        assertThat(room.getTimeline()).noneMatch(e -> "ca".equals(e.getCardInstanceId()) && GameEvent.SPECIAL_SUMMON.equals(e.getType()));
    }

    @Test
    void campeonIsNotOfferedWhileYouControlACopy() {
        onField(host, campeon("c1"));
        CardSlot campeon = campeon("ca");
        host.getTributeZone().add(campeon);
        host.getTributeZone().addAll(fillers("f", 5, false));
        excavated();
        assertThat(offered(campeon)).isFalse();
    }

    // ------------------------------------------------------------------ G06 Primus Kuwal

    @Test
    void primusDiscardedFromTheTributosComesBackByRitual() {
        CardSlot summoner = onField(host, centurion("ce"));
        CardSlot primus = primus("pr");
        host.getDiscardPile().add(primus);
        host.getHand().add(card("hk", "En Mano", 1, 1, "R"));
        EffectEngine.record(room, GameEvent.SEND, "host", primus, "tributeZone", "discardPile", null, Map.of("ownerId", "host"), "descarta");
        Chain.advance(room);
        accept(primus);
        answer("hk");
        answer("pr");
        answer("ce");
        assertThat(host.getField()).contains(primus);
        assertThat(host.getTributeZone()).contains(summoner);
        assertThat(gems()).isEqualTo(7);
    }

    @Test
    void primusPlacesACostTwoKuwalOfTheTributosAndThenSacrificesAnAlly() {
        CardSlot placed = esbirro("es");
        host.getTributeZone().addAll(List.of(placed, centurion("ce")));
        summon(primus("pr"));
        accept(host.getField().get(0));
        answer("es");
        assertThat(host.getField()).contains(placed);
        assertThat(EffectEngine.inFrontLine(placed)).isTrue();
        answer("pr");
        assertThat(ids(host.getTributeZone())).contains("pr");
    }

    // ------------------------------------------------------------------ G07 Ngakpa Kuwal

    @Test
    void ngakpaIsPlacedPayingTwoGemsAndFiveKuwalOfDifferentLevels() {
        CardSlot ngakpa = ngakpa("ng");
        host.getTributeZone().add(ngakpa);
        List<CardSlot> sameLevel = fillers("s", 5, false);
        host.getTributeZone().addAll(sameLevel);
        assertThat(activatable("ng", "ngakpa-kuwal:en-tributos")).isNull();
        List<CardSlot> distinct = fillers("d", 5, true);
        host.getTributeZone().addAll(distinct);
        activate("ng", "ngakpa-kuwal:en-tributos");
        assertThat(room.getPrompt().isDistinctLevels()).isTrue();
        assertThat(Answers.answer(room, "host", room.getPrompt().getId(), List.of("s1", "s2", "d3", "d4", "d5"), List.of())).isNotNull();
        answer(ids(distinct).toArray(String[]::new));
        drain();
        assertThat(host.getField()).contains(ngakpa);
        assertThat(host.getDiscardPile()).containsAll(distinct);
        assertThat(gems()).isEqualTo(6);
    }

    @Test
    void ngakpaOnTheOpponentsTurnSummonsACostTwoKuwalFromTheTributosForFree() {
        onField(host, ngakpa("ng"), BACK);
        CardSlot summoned = esbirro("es");
        host.getTributeZone().add(summoned);
        List<CardSlot> paid = fillers("f", 5, false);
        host.getTributeZone().addAll(paid);
        host.getHand().add(card("hk", "En Mano", 1, 1, "R"));
        room.setCurrentTurnUserId("guest");
        EffectEngine.record(room, GameEvent.TURN_START, "guest", null, null, null, null, null, "turno");
        Chain.advance(room);
        accept(host.getField().get(0));
        answer(ids(paid).toArray(String[]::new));
        answer("es");
        answer("hk");
        assertThat(host.getField()).contains(summoned);
        assertThat(host.getDiscardPile()).containsAll(paid);
        assertThat(ids(host.getTributeZone())).contains("hk");
        assertThat(gems()).isEqualTo(8);
    }

    // ------------------------------------------------------------------ G08 Comandante Kuwal

    @Test
    void comandanteComesBackByRitualWhenYouSacrificeAnAllyWithCostsOrLevelsAddingUpToThree() {
        CardSlot comandante = comandante("co");
        host.getTributeZone().add(comandante);
        // Esbirro counts as 2 (its cost), the other as 1
        CardSlot first = onField(host, esbirro("es"));
        CardSlot second = onField(host, kuwal("k1", "Kuwal Común", 1, 1, 3));
        CardSlot sacrificed = onField(host, kuwal("k9", "Sacrificado", 1, 1, 3));
        host.getHand().add(card("hk", "En Mano", 1, 1, "R"));
        sacrificeByHost(sacrificed);
        accept(comandante);
        answer("hk");
        answer("co");
        answer("es");
        answer("k1");
        assertThat(host.getField()).contains(comandante);
        assertThat(host.getTributeZone()).contains(first, second);
        // Special cost 2, 1 less on your turn
        assertThat(gems()).isEqualTo(7);
    }

    @Test
    void comandanteExhaustsToAttackAnActiveEnemyWhenYouSacrificeAnAlly() {
        CardSlot comandante = onField(host, comandante("co"));
        CardSlot enemy = onField(guest, card("en", "Enemigo", 1, 1, "R"));
        onField(guest, card("ex", "Agotado", 1, 1, "R")).setTapped(true);
        sacrificeByHost(onField(host, esbirro("es")));
        accept(comandante);
        assertThat(room.getPrompt().getValidIds()).containsExactly("en");
        answer("en");
        assertThat(comandante.isTapped()).isTrue();
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.ATTACK_DECLARED.equals(e.getType()) && "co".equals(e.getCardInstanceId())
                && "en".equals(e.getData().get("targetId")));
        // The combat went on: Comandante (10) destroyed it in the clash
        assertThat(guest.getDiscardPile()).contains(enemy);
    }

    // ------------------------------------------------------------------ G09 Chamán Kuwal

    @Test
    void chamanComesBackByRitualDiscardingFiveTributosWhenYouExcavate() {
        CardSlot chaman = chaman("ch");
        host.getTributeZone().add(chaman);
        List<CardSlot> paid = fillers("f", 5, false);
        host.getTributeZone().addAll(paid);
        onField(host, esbirro("es"));
        onField(host, kuwal("k2", "Kuwal Dos", 1, 2, 3));
        excavated();
        accept(chaman);
        answer(ids(paid).toArray(String[]::new));
        answer("ch");
        answer("es");
        // Esbirro counts 1 or 2 (level or cost), the other 2 or 1: together 3 or 4
        answer("k2");
        assertThat(host.getField()).contains(chaman);
        assertThat(gems()).isEqualTo(6);
    }

    @Test
    void chamanSacrificesACardOfTheHandInsteadOfAKuwalAllysDestruction() {
        onField(host, chaman("ch"));
        CardSlot ally = onField(host, esbirro("es"));
        CardSlot paid = card("hk", "En Mano", 1, 1, "R");
        host.getHand().add(paid);
        Removals.destroy(room, ally, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        assertThat(PromptPurpose.REPLACE.is(room.getPrompt().getPurpose())).isTrue();
        answer("ch");
        assertThat(PromptPurpose.REPLACE_SACRIFICE.is(room.getPrompt().getPurpose())).isTrue();
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("ch", "hk");
        answer("hk");
        assertThat(host.getField()).contains(ally);
        assertThat(host.getTributeZone()).containsExactly(paid);
        // Once per turn between copies: the next destruction happens
        Removals.destroy(room, ally, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        assertThat(host.getDiscardPile()).contains(ally);
    }

    @Test
    void chamanMaySacrificeAnotherAllyInstead() {
        onField(host, chaman("ch"));
        CardSlot ally = onField(host, esbirro("es"));
        CardSlot other = onField(host, card("ot", "Otra", 1, 1, "R"));
        Removals.destroy(room, ally, host, false, EffectEngine.CAUSE_BATTLE, "guest", null, null);
        Chain.advance(room);
        answer("ch");
        answer("ot");
        assertThat(host.getField()).contains(ally).doesNotContain(other);
        assertThat(host.getTributeZone()).contains(other);
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.SACRIFICE.equals(e.getType()) && "ot".equals(e.getCardInstanceId()));
    }

    // ------------------------------------------------------------------ B01 Coloso Milenario Kuwal

    @Test
    void colosoDiscardedFromTheTributosSendsTenKuwalToTheBottomAndIsPlaced() {
        CardSlot coloso = coloso("cl");
        host.getDiscardPile().add(coloso);
        List<CardSlot> paid = fillers("f", 10, false);
        host.getDiscardPile().addAll(paid);
        EffectEngine.record(room, GameEvent.SEND, "host", coloso, "tributeZone", "discardPile", null, Map.of("ownerId", "host"), "descarta");
        Chain.advance(room);
        accept(coloso);
        answer(ids(paid).toArray(String[]::new));
        assertThat(host.getField()).contains(coloso);
        assertThat(EffectEngine.inFrontLine(coloso)).isTrue();
        assertThat(host.getDeck().subList(host.getDeck().size() - 10, host.getDeck().size())).containsExactlyInAnyOrderElementsOf(paid);
    }

    @Test
    void colosoBouncesAnEnemyNoStrongerThanTheRemovedAllyWas() {
        onField(host, coloso("cl"));
        CardSlot ally = onField(host, esbirro("es"));
        // 5 printed + 2 this turn: 7 as it leaves
        ally.setTurnStrength(2);
        CardSlot weak = onField(guest, card("e7", "Siete", 1, 1, "R"));
        weak.setStrength(7);
        CardSlot strong = onField(guest, card("e8", "Ocho", 1, 1, "R"));
        strong.setStrength(8);
        Removals.destroy(room, ally, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        assertThat(room.getPrompt().getValidIds()).containsExactly("e7");
        answer("e7");
        assertThat(guest.getHand()).contains(weak);
        assertThat(guest.getField()).contains(strong);
    }

    // ------------------------------------------------------------------ G10 Ejército Kuwal

    @Test
    void ejercitoExcavatesUntilAGreenOrRedCostOrLevelTwoCreatureAndSummonsItForTwoLess() {
        CardSlot found = kuwal("k2", "Encontrada", 3, 2, 4);
        host.getDeck().addAll(0, List.of(card("w1", "Blanca", 2, 2, "W"), card("r1", "Roja Nivel 1", 1, 1, "R"), found));
        play(ejercito("ej"));
        answer("ej");
        answer("k2");
        assertThat(host.getField()).contains(found);
        // 2 for Ejército, 1 for the creature (3 - 2)
        assertThat(gems()).isEqualTo(5);
        assertThat(ids(host.getDeck().subList(host.getDeck().size() - 2, host.getDeck().size()))).containsExactlyInAnyOrder("w1", "r1");
    }

    @Test
    void ejercitoMayAddTheExcavatedCreatureToTheHandInstead() {
        CardSlot found = card("r2", "Roja Coste 2", 2, 1, "R");
        host.getDeck().add(0, found);
        play(ejercito("ej"));
        answer();
        answer("r2");
        assertThat(host.getHand()).contains(found);
    }

    @Test
    void ejercitoSacrificedFromTheHandIsPlayedForOneLess() {
        CardSlot army = ejercito("ej");
        host.getHand().add(army);
        summon(centurion("ce"));
        accept(host.getField().get(0));
        answer("ej");
        accept(army);
        drain();
        assertThat(host.getTributeZone()).doesNotContain(army);
        // 3 for Centurión, 1 for Ejército
        assertThat(gems()).isEqualTo(4);
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.PLAY.equals(e.getType()) && "ej".equals(e.getCardInstanceId()));
    }
}
