package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** E1.1, third batch: the designer's rulings of 2026-10-29 and the new mechanics (immunity, durations, reactions). */
class E11ThirdBatchTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(8, 8);
        room = table.room();
        host = table.host();
        guest = table.guest();
        for (int i = 0; i < 3; i++) guest.getLifeStack().add(card("life" + i, "Vida " + i, 1, 1, "R"));
    }

    @Test
    void kagravuSacrificesAnEnemyToItsOwnersTributesThenSummonsAVioletFromTheDiscard() {
        CardSlot kagravu = withClasses(card("kg", "Kagravú", 3, 3, "P"), "bestia");
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        CardSlot violet = card("v1", "Violeta 1", 1, 1, "P");
        host.getDiscardPile().add(violet);
        summonToFront(kagravu);
        accept(kagravu);
        answer(room.getPrompt(), "e");
        assertThat(guest.getField()).doesNotContain(enemy);
        assertThat(guest.getTributeZone()).contains(enemy);
        // It destroyed a creature by effect: a Violet level 1 from the Tributos or the Descarte
        accept(kagravu);
        answer(room.getPrompt(), "v1");
        assertThat(host.getField()).contains(violet);
    }

    @Test
    void kagravuFromTheRetaguardiaOnlyReachesYourOwnCreatures() {
        CardSlot own = onField(host, card("o", "Propia", 1, 1, "P"));
        onField(guest, card("e", "Enemigo", 1, 1, "R"));
        host.getHand().add(withClasses(card("kg", "Kagravú", 3, 3, "P"), "bestia"));
        assertThat(Summons.normalSummon(room, "host", "kg", 50.0, 75.0)).isNull();
        accept(findHost("kg"));
        assertThat(room.getPrompt().getValidIds()).containsExactly(own.getInstanceId());
    }

    @Test
    void remnisActsAtTheStartOfTheOpponentsTurn() {
        CardSlot remnis = card("rm", "Remnis", 1, 1, "B");
        host.getTributeZone().add(remnis);
        CardSlot ally = onField(host, card("al", "Azul", 2, 2, "B"));
        room.setCurrentTurnUserId("guest");
        TurnRules.turnStarted(room, "guest");
        accept(remnis);
        answer(room.getPrompt(), "al");
        assertThat(ally.getMaterials()).contains(remnis);
    }

    @Test
    void geminiPlacesItsLevel1MaterialsInTheRetaguardiaAndIsStillDestroyed() {
        CardSlot gemini = onField(host, card("gm", "Gémini", 2, 2, "B"));
        CardSlot m1 = card("m1", "Material 1", 1, 1, "B");
        CardSlot m2 = card("m2", "Material 2", 1, 1, "B");
        CardSlot m3 = card("m3", "Material 2 nivel 2", 1, 2, "B");
        gemini.getMaterials().addAll(List.of(m1, m2, m3));
        Removals.destroy(room, gemini, host, false, EffectEngine.CAUSE_EFFECT, "guest", null, null);
        Chain.advance(room);
        answer(room.getPrompt(), "gm");
        Prompt materials = room.getPrompt();
        assertThat(materials.getValidIds()).containsExactlyInAnyOrder("m1", "m2");
        answer(materials, "m1", "m2");
        assertThat(host.getField()).contains(m1, m2).doesNotContain(gemini);
        assertThat(host.getDiscardPile()).contains(gemini, m3);
    }

    @Test
    void valorDelHeroeGivesThreeStrengthAndFuria1ForTheTurn() {
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "R"));
        host.getHand().add(utility("vh", "Valor Del Heroe"));
        assertThat(Summons.playByPlayer(room, "host", "vh", 50.0, 75.0)).isNull();
        answer(room.getPrompt(), "al");
        assertThat(EffectEngine.strength(ally)).isEqualTo(6);
        assertThat(EffectEngine.keywords(ally)).contains("Furia 1");
    }

    @Test
    void emboscadaEndsWithTheAllysNextAttack() {
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "R"));
        host.getHand().add(utility("em", "Emboscada Al Carromato"));
        assertThat(Summons.playByPlayer(room, "host", "em", 50.0, 75.0)).isNull();
        answer(room.getPrompt(), "al");
        assertThat(EffectEngine.strength(ally)).isEqualTo(6);
        assertThat(EffectEngine.keywords(ally)).contains("Provocar");
        assertThat(CombatRules.attackByPlayer(room, "host", "al", null)).isNull();
        for (int i = 0; i < 10 && room.getCombat() != null; i++) {
            if (room.getPrompt() != null) Answers.answer(room, room.getPrompt().getPlayerId(), room.getPrompt().getId(), List.of(), List.of());
            else Chain.advance(room);
        }
        assertThat(room.getCombat()).isNull();
        assertThat(EffectEngine.keywords(ally)).doesNotContain("Provocar");
        assertThat(EffectEngine.strength(ally)).isEqualTo(3);
    }

    @Test
    void barreraRefractoraKeepsTheAllyFromBeingRemovedByOpponentEffects() {
        CardSlot ally = onField(host, card("al", "Aliado", 2, 2, "W"));
        host.getHand().addAll(List.of(card("h1", "H1", 1, 1, "R"), card("h2", "H2", 1, 1, "R")));
        host.getHand().add(utility("br", "Barrera Refractora"));
        assertThat(Summons.playByPlayer(room, "host", "br", 50.0, 75.0)).isNull();
        answer(room.getPrompt(), "al");
        answer(room.getPrompt(), "h1", "h2");
        PendingEffect opponents = new PendingEffect();
        opponents.setControllerId("guest");
        Removals.destroy(room, ally, host, false, EffectEngine.CAUSE_EFFECT, "guest", null, opponents);
        assertThat(host.getField()).contains(ally);
    }

    @Test
    void he110IsNotATargetForOpponentCardsOfLevel4OrLess() {
        CardSlot he = onField(host, card("he", "HE 110", 5, 4, "B"));
        EffectEngine.refreshContinuous(room);
        assertThat(Steps.enemyTargets(room, guestEffect(4), CardFilter.creatures())).doesNotContain(he);
        assertThat(Steps.enemyTargets(room, guestEffect(5), CardFilter.creatures())).contains(he);
    }

    @Test
    void escudoJuradoIsNotATargetWhileYouControlALevel4Ally() {
        CardSlot escudo = onField(host, card("ej", "Escudo Jurado", 2, 2, "R"));
        EffectEngine.refreshContinuous(room);
        assertThat(Steps.enemyTargets(room, guestEffect(5), CardFilter.creatures())).contains(escudo);
        onField(host, card("big", "Grande", 5, 4, "R"), BACK);
        EffectEngine.refreshContinuous(room);
        assertThat(Steps.enemyTargets(room, guestEffect(5), CardFilter.creatures())).doesNotContain(escudo);
    }

    @Test
    void lesvikSkipsTheBlockStepWhenItsTargetFalls() {
        CardSlot lesvik = onField(host, card("lv", "Lesvik", 6, 5, "B"));
        lesvik.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setStrength(8);
        assertThat(CombatRules.attackByPlayer(room, "host", "lv", null)).isNull();
        accept(lesvik);
        answer(room.getPrompt(), "m1", "m2");
        answer(room.getPrompt(), "e");
        assertThat(guest.getField()).doesNotContain(enemy);
        assertThat(room.getTimeline()).noneMatch(ev -> GameEvent.COMBAT_STEP.equals(ev.getType()) && ev.getData() != null
                && "BLOCK".equals(ev.getData().get("step")));
    }

    @Test
    void lectranatusLeavesTheWeakEnemiesUnableToBeExhausted() {
        CardSlot strong = onField(guest, card("s", "Fuerte", 1, 1, "R"));
        strong.setStrength(9);
        CardSlot weak = onField(guest, card("w", "Débil", 1, 1, "R"));
        CardSlot lect = withClasses(card("lc", "Lectranatus", 6, 4, "G"), "dragón");
        summonToFront(lect);
        answer(room.getPrompt(), "s");
        assertThat(guest.getField()).doesNotContain(strong);
        assertThat(EffectEngine.keywords(weak)).contains(EffectEngine.CANNOT_TAP);
        room.setCurrentTurnUserId("guest");
        assertThat(CombatRules.attackByPlayer(room, "guest", "w", null)).contains("no puede agotarse");
    }

    @Test
    void principeCharlesGivesReactivarUntilTheEndOfTheOpponentsTurn() {
        CardSlot knight = onField(host, withClasses(card("k", "Caballero", 1, 1, "R"), "caballero"));
        summonToFront(withClasses(card("pc", "Principe Charles Di Castirion", 4, 4, "R"), "armada", "caballero"));
        assertThat(knight.getLaterKeywords()).contains("Reactivar");
        assertThat(EffectEngine.keywords(knight)).contains("Reactivar");
    }

    @Test
    void hominoxSummonsAMutantFromTheTributesUpToTheCopiesThereWhenItDestroysAnEnemy() {
        CardSlot hominox = onField(host, withClasses(card("hx", "Hominox", 7, 4, "P"), "bestia", "mutante"));
        hominox.setStrength(12);
        host.getTributeZone().addAll(List.of(card("c1", "Hominox", 7, 4, "P"), card("c2", "Hominox", 7, 4, "P")));
        CardSlot level2 = withClasses(card("m2", "Mutante 2", 1, 2, "P"), "mutante");
        CardSlot level3 = withClasses(card("m3", "Mutante 3", 1, 3, "P"), "mutante");
        host.getTributeZone().addAll(List.of(level2, level3));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setTapped(true);
        assertThat(CombatRules.attackByPlayer(room, "host", "hx", "e")).isNull();
        for (int i = 0; i < 10 && room.getCombat() != null && (room.getPrompt() == null || !"OPTIONAL".equals(room.getPrompt().getPurpose())); i++) {
            if (room.getPrompt() != null) Answers.answer(room, room.getPrompt().getPlayerId(), room.getPrompt().getId(), List.of(), List.of());
            else Chain.advance(room);
        }
        assertThat(guest.getField()).doesNotContain(enemy);
        accept(hominox);
        // 2 copies in the Tributos: level 2 or less
        Prompt pick = room.getPrompt();
        assertThat(pick.getValidIds()).containsExactly("m2");
        answer(pick, "m2");
        assertThat(host.getField()).contains(level2);
        assertThat(EffectEngine.inFrontLine(level2)).isFalse();
    }

    // ------------------------------------------------------------------ Helpers

    /** Summons the card from the hand to the Línea frontal by effect (a test card's En Invocación), for effects that reach enemies. */
    private void summonToFront(CardSlot card) {
        CardEffects.register("invocador frontal", new EffectScript("invocador-frontal:en-invocacion", EffectScript.Moment.ON_SUMMON, true,
                "En Invocación", "Invoca 1 criatura de tu mano en tu línea frontal sin pagar su coste.",
                List.of(new EffectStep.SummonFromZone("hand", CardFilter.creatures().named(card.getCardName()), EffectStep.SummonFromZone.FREE, true))));
        host.getHand().addAll(List.of(card, card("iv", "Invocador Frontal", 0, 1, "R")));
        assertThat(Summons.normalSummon(room, "host", "iv", 50.0, 75.0)).isNull();
        answer(room.getPrompt(), card.getInstanceId());
    }

    /** An effect of an opponent's card of that level, in its Línea frontal. */
    private PendingEffect guestEffect(int level) {
        CardSlot source = onField(guest, card("src" + level, "Fuente " + level, 1, level, "R"));
        return Chain.candidate(CardEffects.of("Dragón Goloso").get(0), "guest", source, 0);
    }

    private CardSlot findHost(String id) {
        return host.getField().stream().filter(c -> c.getInstanceId().equals(id)).findFirst().orElseThrow();
    }

    private static CardSlot withClasses(CardSlot card, String... classes) {
        card.setClasses(new ArrayList<>(List.of(classes)));
        return card;
    }

    private void accept(CardSlot source) {
        Prompt offer = room.getPrompt();
        assertThat(offer).as("se ofrece el efecto de " + source.getCardName()).isNotNull();
        assertThat(offer.getValidIds()).contains(source.getInstanceId());
        Answers.answer(room, offer.getPlayerId(), offer.getId(), List.of(source.getInstanceId()), List.of());
    }

    private void answer(Prompt prompt, String... ids) {
        assertThat(prompt).isNotNull();
        assertThat(Answers.answer(room, prompt.getPlayerId(), prompt.getId(), List.of(ids), List.of())).isNull();
    }
}
