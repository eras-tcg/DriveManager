package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cards in testing T10, archetype Chamán (2026-11-19): one test (or more) per programmed effect, from the printed text.
 */
@Tag("cards")
@Tag("t10")
class T10ChamanCardsTest extends CardBatchSupport {

    private static CardSlot blue(String id, String name, Integer cost, int level, int strength, String... classes) {
        CardSlot c = withClasses(card(id, name, cost, level, "B"), classes);
        c.setStrength(strength);
        return c;
    }

    private static CardSlot materialization(CardSlot c, int specialCost) {
        c.setSpecialSummonKind("materialization");
        c.setSpecialCost(specialCost);
        return c;
    }

    private static CardSlot geo(String id) {
        return blue(id, "Geo Chamán", 1, 1, 1, "chamán", "neogeo");
    }

    private static CardSlot archnikek(String id) {
        return materialization(blue(id, "Archnikek", 2, 2, 4, "ente", "neogeo"), 1);
    }

    private static CardSlot nehiel(String id) {
        return materialization(blue(id, "Nehiel", 3, 3, 8, "ente", "neogeo"), 2);
    }

    private static CardSlot filushtie(String id) {
        return materialization(blue(id, "Filushtie", 4, 4, 10, "ente", "neogeo"), 2);
    }

    private static CardSlot novikhe(String id) {
        return materialization(blue(id, "Novikhé", 7, 4, 13, "chamán"), 3);
    }

    private static CardSlot enemy(String id, int strength) {
        CardSlot c = card(id, "Enemigo " + id, 1, 1, "R");
        c.setStrength(strength);
        return c;
    }

    private void materialize(CardSlot card, CardSlot summoner, CardSlot material) {
        if (!host.getHand().contains(card)) host.getHand().add(card);
        assertThat(SpecialSummons.specialByPlayer(room, "host", card.getInstanceId(), summoner.getInstanceId())).isNull();
        answer(material.getInstanceId());
        Prompt p = room.getPrompt();
        if (p != null && PromptPurpose.SPECIAL_FRONT.is(p.getPurpose())) answer(p);
    }

    private void activate(CardSlot card, String idStart) {
        String script = Chain.activatable(room, "host").stream()
                .filter(a -> card.getInstanceId().equals(a.instanceId()) && a.scriptId().startsWith(idStart))
                .map(Chain.Activatable::scriptId).findFirst().orElse(null);
        assertThat(script).as("se puede activar " + idStart).isNotNull();
        assertThat(Chain.activateByPlayer(room, "host", card.getInstanceId(), script)).isNull();
    }

    private void settle() {
        for (int i = 0; i < 40; i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                if (room.getCombat() == null) break;
                Chain.advance(room);
                continue;
            }
            if (PromptPurpose.ORDER.is(p.getPurpose())) answer(p, p.getValidIds().get(0));
            else Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
    }

    // ------------------------------------------------------------------ B01 Geo Chamán

    @Test
    void geoChamanTakesUpToThreeAndTributesOneFromTheHandForEach() {
        host.getDeck().add(0, blue("x1", "Ente Uno", 1, 1, 1, "ente"));
        host.getDeck().add(1, blue("x2", "Neogeo Dos", 1, 1, 1, "neogeo"));
        host.getHand().add(card("h1", "Mano Uno", 1, 1, "R"));
        host.getHand().add(card("h2", "Mano Dos", 1, 1, "R"));
        summon(geo("gc"));
        answer("x1", "x2");
        assertThat(room.getPrompt().getMin()).isEqualTo(2);
        answer("h1", "h2");
        settle();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactlyInAnyOrder("x1", "x2");
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactlyInAnyOrder("h1", "h2");
    }

    @Test
    void geoChamanFromTheTributosBecomesAMaterialWhenAnEnteIsSummonedByEffect() {
        CardSlot geo = geo("gc");
        host.getTributeZone().add(geo);
        CardSlot shaman = onField(host, blue("sh", "Chamán", 2, 2, 3, "chamán"), BACK);
        summonByEffectToFront(blue("en", "Ente", 1, 1, 2, "ente"));
        untilOffered(geo);
        accept(geo);
        answer("sh");
        settle();
        assertThat(shaman.getMaterials()).containsExactly(geo);
    }

    // ------------------------------------------------------------------ B02 Archnikek

    @Test
    void archnikekTributesTwoMaterialsToMillFourAndTakeOne() {
        CardSlot arch = onField(host, archnikek("ar"));
        arch.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        activate(arch, "archnikek:turno-aliado");
        answer("m1", "m2");
        assertThat(host.getTributeZone()).hasSize(6);
        answer("hd2");
        settle();
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("hd2");
        assertThat(arch.getMaterials()).isEmpty();
    }

    @Test
    void archnikekGoesBackWithFourEntesForAGemWhenAnEnemyIsReducedToZero() {
        CardSlot arch = archnikek("ar");
        host.getTributeZone().add(arch);
        for (int i = 0; i < 4; i++) host.getTributeZone().add(blue("e" + i, "Ente " + i, 1, 1, 1, "ente"));
        CardSlot summoner = onField(host, blue("s3", "Tres", 3, 3, 6));
        CardSlot material = blue("m3", "Material Tres", 3, 3, 6);
        host.getTributeZone().add(material);
        CardSlot target = onField(guest, enemy("en", 5));
        CardSlot fi = filushtie("fi");
        materialize(fi, summoner, material);
        untilOffered(fi);
        accept(fi);
        answer("s3", "m3");
        answer("en");
        untilOffered(arch);
        accept(arch);
        answer("e0", "e1", "e2", "e3");
        settle();
        assertThat(guest.getDiscardPile()).contains(target);
        assertThat(host.getDeck()).extracting(CardSlot::getInstanceId).contains("ar", "e3");
        assertThat(gems()).isEqualTo(8 - 2 + 1);
    }

    // ------------------------------------------------------------------ B03 Nehiel

    @Test
    void nehielPutsTheTopCardOnTheLivesAfterDirectDamageWithFiveOrFewerLives() {
        onField(host, nehiel("ne"));
        assertThat(CombatRules.attackByPlayer(room, "host", "ne", null)).isNull();
        settle();
        assertThat(guest.getLifeStack()).hasSize(2);
        assertThat(host.getLifeStack()).hasSize(4);
        assertThat(host.getLifeStack().get(0).getInstanceId()).isEqualTo("hd1");
    }

    @Test
    void nehielIsMaterializedWithAMaterialFromTheTributos() {
        CardSlot summoner = onField(host, blue("s2", "Dos", 2, 2, 4), BACK);
        CardSlot material = blue("m2", "Material Dos", 2, 2, 4);
        host.getTributeZone().add(material);
        CardSlot nehiel = nehiel("ne");
        materialize(nehiel, summoner, material);
        settle();
        assertThat(host.getField()).contains(nehiel);
        assertThat(nehiel.getMaterials()).contains(summoner, material);
    }

    // ------------------------------------------------------------------ B04 Filushtie

    @Test
    void filushtieTributesTwoMaterialsAndAnEnemyLosesFive() {
        CardSlot summoner = onField(host, blue("s3", "Tres", 3, 3, 6));
        CardSlot material = blue("m3", "Material Tres", 3, 3, 6);
        host.getTributeZone().add(material);
        CardSlot target = onField(guest, enemy("en", 8));
        CardSlot fi = filushtie("fi");
        materialize(fi, summoner, material);
        untilOffered(fi);
        accept(fi);
        answer("s3", "m3");
        answer("en");
        settle();
        assertThat(EffectEngine.strength(target)).isEqualTo(3);
        assertThat(gems()).isEqualTo(8 - 2);
    }

    @Test
    void filushtieGainsAGemWhenNoEnemyCouldBeChosen() {
        CardSlot summoner = onField(host, blue("s3", "Tres", 3, 3, 6));
        CardSlot material = blue("m3", "Material Tres", 3, 3, 6);
        host.getTributeZone().add(material);
        CardSlot fi = filushtie("fi");
        materialize(fi, summoner, material);
        untilOffered(fi);
        accept(fi);
        answer("s3", "m3");
        settle();
        assertThat(gems()).isEqualTo(8 - 2 + 1);
    }

    @Test
    void filushtieInTheTributosIsSpeciallySummonedAsIfInTheHand() {
        CardSlot summoner = onField(host, blue("s3", "Tres", 3, 3, 6), BACK);
        CardSlot material = blue("m3", "Material Tres", 3, 3, 6);
        host.getTributeZone().add(material);
        CardSlot fi = filushtie("fi");
        host.getTributeZone().add(fi);
        assertThat(SpecialSummons.specialByPlayer(room, "host", "fi", "s3")).isNull();
        answer("m3");
        settle();
        assertThat(host.getField()).contains(fi);
    }

    // ------------------------------------------------------------------ B05 Novikhé

    @Test
    void novikheSummonsAnEnteFromTheTributosThatDealsItsStrengthAsDamage() {
        CardSlot nov = onField(host, novikhe("nv"));
        nov.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        CardSlot ente = blue("en", "Ente Tres", 3, 3, 7, "ente");
        host.getTributeZone().add(ente);
        // Lives of strength 3: 7 damage takes 2 of them (3 + 3) and the third survives with 1
        activate(nov, "novikhe:turno-aliado");
        answer("m1", "m2");
        answer("en");
        settle();
        assertThat(host.getField()).contains(ente);
        assertThat(EffectEngine.inFrontLine(ente)).isTrue();
        assertThat(guest.getLifeStack().size()).isLessThan(3);
    }

    @Test
    void novikheTributesAMaterialAndAnEnteInsteadOfBeingDestroyed() {
        CardSlot nov = onField(host, novikhe("nv"));
        CardSlot material = card("m1", "M1", 1, 1, "B");
        nov.getMaterials().add(material);
        CardSlot ente = onField(host, blue("en", "Ente", 1, 1, 2, "ente"), BACK);
        Removals.destroy(room, nov, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        Prompt p = room.getPrompt();
        answer(p, p.getValidIds().stream().filter(id -> id.startsWith("novikhe")).findFirst().orElse(p.getValidIds().get(0)));
        answer("m1", "en");
        settle();
        assertThat(host.getField()).contains(nov).doesNotContain(ente);
        assertThat(host.getTributeZone()).contains(material, ente);
    }

    // ------------------------------------------------------------------ B06 Material Biológico

    private static CardSlot material(String id) {
        CardSlot c = utility(id, "Material Biológico", 0, "B");
        c.setLevel(2);
        return c;
    }

    @Test
    void materialBiologicoDestroysANecromataToSummonALowerCreatureFromTheDiscard() {
        CardSlot necro = onField(host, blue("nc", "Necrómata", 3, 3, 5, "necrómata"), BACK);
        CardSlot low = blue("lo", "Baja", 3, 2, 4);
        CardSlot same = blue("sa", "Igual", 3, 3, 4);
        host.getDiscardPile().addAll(List.of(low, same));
        play(material("mb"));
        accept(host.getField().stream().filter(c -> c.getInstanceId().equals("mb")).findFirst().orElse(host.getField().get(0)));
        answer("nc");
        assertThat(room.getPrompt().getValidIds()).containsExactly("lo");
        answer("lo");
        settle();
        assertThat(host.getField()).contains(low).doesNotContain(necro);
        assertThat(EffectEngine.inFrontLine(low)).isTrue();
        assertThat(gems()).isEqualTo(8 - 1);
    }

    @Test
    void materialBiologicoIsPlayedFromTheHandAfterDirectDamage() {
        CardSlot mb = material("mb");
        host.getHand().add(mb);
        onField(host, blue("nc", "Necrómata", 3, 3, 5, "necrómata"), BACK);
        CardSlot attacker = onField(guest, enemy("at", 2));
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(0);
        assertThat(CombatRules.attackByPlayer(room, "guest", "at", null)).isNull();
        untilOffered(mb);
        accept(mb);
        assertThat(host.getHand()).doesNotContain(mb);
    }
}
