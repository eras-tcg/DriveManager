package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** E1 Azul y Verde (licuado del 2026-11-17): one test (or more) per effect programmed for the new cards, from the printed text. */
@Tag("cards")
@Tag("e1")
class E1BlueGreenCardsTest extends CardBatchSupport {

    private static CardSlot creature(String id, String name, int cost, int level, String color, int strength) {
        CardSlot c = card(id, name, cost, level, color);
        c.setStrength(strength);
        return c;
    }

    private static CardSlot enemy(String id, int strength) {
        return creature(id, "Enemigo " + id, 1, 1, "R", strength);
    }

    private static CardSlot exhaustedEnemy(String id, int strength) {
        CardSlot c = enemy(id, strength);
        c.setTapped(true);
        return c;
    }

    private static CardSlot withMaterials(CardSlot card, int count) {
        for (int i = 0; i < count; i++) card.getMaterials().add(card(card.getInstanceId() + "m" + i, "Material " + i, 1, 1, "B"));
        return card;
    }

    private CardSlot evolutionInHand() {
        CardSlot evo = creature("ev", "Evolución Verde", 2, 2, "G", 7);
        evo.setSpecialSummonKind("evolution");
        evo.setSpecialCost(1);
        evo.setRequirement("criatura nivel 1.");
        Req.program(evo);
        host.getHand().add(evo);
        return evo;
    }

    @Test
    void everySpecialCardHasItsRequirementProgrammed() {
        for (String name : List.of("Fogler", "Kholec", "Mhot", "Comando E7", "Cess", "Behowl", "Cylvaus", "Elefante Anciano", "Thalia",
                "Umbrielge", "Carnotaurus Rex")) {
            CardSlot c = new CardSlot();
            c.setCardName(name);
            assertThat(CardEffects.requirementOf(c)).as(name).isNotNull();
        }
    }

    // ------------------------------------------------------------------ Azul

    @Test
    void zenoDiscardsOneToTakeABlueFromTheDiscard() {
        CardSlot zeno = inHand("ze", "Zeno", 1, 1, "B");
        CardSlot red = inHand("rh", "Roja De Mano", 1, 1, "R");
        CardSlot blue = card("db", "Azul En Descarte", 1, 1, "B");
        host.getDiscardPile().addAll(List.of(blue, card("dr", "Roja En Descarte", 1, 1, "R")));
        summon(zeno);
        accept(zeno);
        answer("rh");
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).containsExactly("db");
        answer(take, "db");
        assertThat(host.getHand()).contains(blue).doesNotContain(red);
        assertThat(host.getDiscardPile()).contains(red);
    }

    @Test
    void hultemTributesACopyInTheHandToGoToTheRetaguardia() {
        CardSlot hultem = inHand("h1", "Hultem", 1, 1, "B");
        CardSlot copy = inHand("h2", "Hultem", 1, 1, "B");
        inHand("ot", "Otra", 1, 1, "B");
        assertThat(Chain.activateByPlayer(room, "host", "h1", "hultem:en-mano")).isNull();
        if (room.getPrompt() != null) answer("h2");
        drain();
        assertThat(host.getField()).contains(hultem);
        assertThat(EffectEngine.inFrontLine(hultem)).isFalse();
        assertThat(host.getTributeZone()).contains(copy);
    }

    @Test
    void hultemWithoutACopyCannotBeActivated() {
        inHand("h1", "Hultem", 1, 1, "B");
        assertThat(Chain.activateByPlayer(room, "host", "h1", "hultem:en-mano")).isNotNull();
    }

    @Test
    void foglerReactivatesAtTheEndOfItsTurn() {
        CardSlot fogler = onField(host, creature("fo", "Fogler", 2, 2, "B", 5), FRONT);
        fogler.setTapped(true);
        TurnRules.turnEnding(room, "host");
        drain();
        assertThat(fogler.isTapped()).isFalse();
    }

    @Test
    void kholecTributesAMaterialToReduceTheEnemyItClashesWithByThree() {
        CardSlot kholec = onField(host, withMaterials(creature("kh", "Kholec", 3, 3, "B", 7), 1), FRONT);
        CardSlot foe = onField(guest, exhaustedEnemy("e", 9), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "kh", "e")).isNull();
        untilOffered(kholec);
        accept(kholec);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("khm0")) answer("khm0");
        drain();
        assertThat(kholec.getMaterials()).isEmpty();
        assertThat(guest.getField()).doesNotContain(foe);
        assertThat(host.getField()).contains(kholec);
    }

    @Test
    void lueYBonzSendsFiveBluesFromTheDiscardToSummonItselfToTheFrontForOneLess() {
        CardSlot lue = inHand("lb", "Lue y BONZ", 4, 4, "B");
        lue.setStrength(11);
        for (int i = 0; i < 5; i++) host.getDiscardPile().add(card("d" + i, "Azul " + i, 1, 1, "B"));
        host.getDiscardPile().add(card("dr", "Roja", 1, 1, "R"));
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "lb", "lue-y-bonz:en-mano")).isNull();
        Prompt send = room.getPrompt();
        assertThat(send.getValidIds()).doesNotContain("dr");
        answer(send, "d0", "d1", "d2", "d3", "d4");
        drain();
        assertThat(host.getField()).contains(lue);
        assertThat(EffectEngine.inFrontLine(lue)).isTrue();
        assertThat(host.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("dr");
        assertThat(gems()).isEqualTo(before - 3);
    }

    @Test
    void lueYBonzNeedsFiveBlues() {
        inHand("lb", "Lue y BONZ", 4, 4, "B");
        for (int i = 0; i < 4; i++) host.getDiscardPile().add(card("d" + i, "Azul " + i, 1, 1, "B"));
        assertThat(Chain.activateByPlayer(room, "host", "lb", "lue-y-bonz:en-mano")).isNotNull();
    }

    @Test
    void kliusPlacesABlueCreatureOfCostThreeFromTheTributesInTheFrontLine() {
        CardSlot klius = inHand("kl", "Klius", 4, 4, "B");
        CardSlot three = creature("t3", "Azul Coste 3", 3, 3, "B", 7);
        host.getTributeZone().addAll(List.of(three, creature("t2", "Azul Coste 2", 2, 2, "B", 4), creature("r3", "Roja Coste 3", 3, 3, "R", 7)));
        summon(klius);
        accept(klius);
        Prompt pick = room.getPrompt();
        if (pick != null) {
            assertThat(pick.getValidIds()).containsExactly("t3");
            answer(pick, "t3");
        }
        assertThat(host.getField()).contains(three);
        assertThat(EffectEngine.inFrontLine(three)).isTrue();
    }

    @Test
    void comandoE7TributesMaterialsToReactivateAsManyBlueStructuresThenItself() {
        CardSlot a = onField(host, structure("sa", "Estructura A", 1, "B"), BACK);
        CardSlot b = onField(host, structure("sb", "Estructura B", 1, "B"), BACK);
        CardSlot c = onField(host, structure("sc", "Estructura C", 1, "B"), BACK);
        for (CardSlot s : List.of(a, b, c)) s.setTapped(true);
        CardSlot comando = withMaterials(creature("ce", "Comando E7", 4, 4, "B", 12), 2);
        summon(comando);
        answer("cem0", "cem1");
        Prompt structures = room.getPrompt();
        assertThat(structures.getMin()).isEqualTo(2);
        answer(structures, "sa", "sb");
        drain();
        assertThat(a.isTapped()).isFalse();
        assertThat(b.isTapped()).isFalse();
        assertThat(c.isTapped()).isTrue();
        assertThat(comando.isTapped()).isFalse();
    }

    @Test
    void vigilanteDeLaFronteraStrengthensTheStrongestAllyUntilTheEndOfTheOpponentsTurn() {
        CardSlot weak = onField(host, creature("w", "Débil", 2, 2, "B", 5), FRONT);
        CardSlot strong = onField(host, creature("s", "Fuerte", 3, 3, "B", 8), FRONT);
        play(utility("vg", "Vigilante de la Frontera", 1, "B"));
        if (room.getPrompt() != null) {
            assertThat(room.getPrompt().getValidIds()).doesNotContain("w");
            answer("s");
        }
        drain();
        assertThat(EffectEngine.strength(strong)).isEqualTo(11);
        assertThat(EffectEngine.keywords(strong)).contains("Reactivar");
        assertThat(EffectEngine.strength(weak)).isEqualTo(5);
    }

    // ------------------------------------------------------------------ Verde

    @Test
    void mollyDiscardsOneToTakeAGreenFromTheDiscard() {
        CardSlot molly = inHand("mo", "Molly", 1, 1, "G");
        inHand("rh", "Roja De Mano", 1, 1, "R");
        CardSlot green = card("dg", "Verde En Descarte", 1, 1, "G");
        host.getDiscardPile().add(green);
        summon(molly);
        accept(molly);
        answer("rh");
        if (room.getPrompt() != null) answer("dg");
        assertThat(host.getHand()).contains(green);
    }

    @Test
    void cianopterixTributesACopyInTheHandToGoToTheRetaguardia() {
        CardSlot ciano = inHand("c1", "Cianoptérix", 1, 1, "G");
        CardSlot copy = inHand("c2", "Cianoptérix", 1, 1, "G");
        assertThat(Chain.activateByPlayer(room, "host", "c1", "cianopterix:en-mano")).isNull();
        if (room.getPrompt() != null) answer("c2");
        drain();
        assertThat(host.getField()).contains(ciano);
        assertThat(host.getTributeZone()).contains(copy);
    }

    @Test
    void behowlEvolvesWhenItAttacksAnEnemyAndGivesTwoOnItsTurnAsResource() {
        CardSlot behowl = onField(host, creature("bh", "Behowl", 2, 1, "G", 5), FRONT);
        onField(guest, exhaustedEnemy("e", 3), FRONT);
        CardSlot evo = evolutionInHand();
        assertThat(CombatRules.attackByPlayer(room, "host", "bh", "e")).isNull();
        untilOffered(behowl);
        answer("bh");
        answer("ev");
        assertThat(host.getField()).contains(evo);
        assertThat(evo.getResources()).contains(behowl);
        // Heredado: "Turno Aliado: esta carta gana fuerza 2"
        assertThat(EffectEngine.strength(evo)).isEqualTo(9);
    }

    @Test
    void behowlAttackingTheRivalDoesNotEvolve() {
        CardSlot behowl = onField(host, creature("bh", "Behowl", 2, 1, "G", 5), FRONT);
        evolutionInHand();
        assertThat(CombatRules.attackByPlayer(room, "host", "bh", null)).isNull();
        for (int i = 0; i < 30 && (room.getPrompt() != null || room.getCombat() != null); i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                Chain.advance(room);
                continue;
            }
            assertThat(p.getValidIds()).doesNotContain(behowl.getInstanceId());
            Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
        }
    }

    @Test
    void umbrielgeAsResourceGivesTwoOnlyOnItsControllersTurn() {
        CardSlot holder = onField(host, creature("ho", "Portador", 4, 4, "G", 10), FRONT);
        holder.getResources().add(card("ub", "Umbrielge", 4, 4, "G"));
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(holder)).isEqualTo(12);
        room.setCurrentTurnUserId("guest");
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(holder)).isEqualTo(10);
    }

    @Test
    void umbrielgeReactivatesWhenItDestroysAnEnemy() {
        CardSlot umbrielge = onField(host, creature("um", "Umbrielge", 4, 4, "G", 11), FRONT);
        CardSlot foe = onField(guest, exhaustedEnemy("e", 5), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "um", "e")).isNull();
        drain();
        assertThat(guest.getField()).doesNotContain(foe);
        assertThat(umbrielge.isTapped()).isFalse();
    }

    @Test
    void thaliaTributesACardFromTheHandToExhaustAWeakerEnemy() {
        CardSlot thalia = inHand("th", "Thalia", 3, 3, "G");
        thalia.setStrength(7);
        CardSlot fodder = inHand("fd", "Descarte", 1, 1, "R");
        CardSlot weak = onField(guest, enemy("w", 6), FRONT);
        onField(guest, enemy("s", 7), FRONT);
        // Into the Línea frontal (as an Evolución on a creature there): from the Retaguardia an effect reaches no enemy (rule 4.0.6.2)
        host.getHand().remove(thalia);
        summonByEffectToFront(thalia);
        accept(thalia);
        answer("fd");
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("w");
        answer(target, "w");
        drain();
        assertThat(weak.isTapped()).isTrue();
        assertThat(host.getTributeZone()).contains(fodder);
    }

    @Test
    void dragonEncadenadoDestroysAnEnemyOfStrengthEightOrLessWhenItAttacks() {
        CardSlot dragon = onField(host, creature("de", "Dragón Encadenado", 4, 4, "G", 9), FRONT);
        CardSlot weak = onField(guest, enemy("w", 8), FRONT);
        onField(guest, enemy("s", 9), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "de", null)).isNull();
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).contains("w").doesNotContain("s");
        answer(target, "w");
        assertThat(guest.getField()).doesNotContain(weak);
    }

    @Test
    void carnotaurusRexGainsThreeUntilTheEndOfTheAttack() {
        CardSlot rex = onField(host, creature("cr", "Carnotaurus Rex", 6, 5, "G", 13), FRONT);
        assertThat(CombatRules.attackByPlayer(room, "host", "cr", null)).isNull();
        drain();
        // The attack hits for 16 (no blocker), and the gain ends with it
        assertThat(room.getEventLog()).anyMatch(e -> e.getText() != null && e.getText().contains("inflige 16 de daño directo"));
        assertThat(EffectEngine.strength(rex)).isEqualTo(13);
    }

    @Test
    void encantoDelBosqueMagicoExhaustsAnAllyAndAWeakerEnemy() {
        CardSlot ally = onField(host, creature("al", "Aliado", 3, 3, "G", 6), FRONT);
        CardSlot weak = onField(guest, enemy("w", 5), FRONT);
        onField(guest, enemy("s", 6), FRONT);
        play(utility("en", "Encanto del Bosque Mágico", 1, "G"));
        answer("al");
        Prompt foe = room.getPrompt();
        assertThat(foe.getValidIds()).containsExactly("w");
        answer(foe, "w");
        drain();
        assertThat(ally.isTapped()).isTrue();
        assertThat(weak.isTapped()).isTrue();
    }
}
