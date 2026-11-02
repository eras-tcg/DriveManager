package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.engine.effects.selection.Selection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** What the manual run of the full test plan (2026-11-02) found in what the player is told. */
class ManualRunFixesTest extends CardBatchSupport {

    @Test
    void kagravuFromTheRetaguardiaSaysWhyNoEnemyIsOffered() {
        onField(host, card("o", "Propia", 1, 1, "P"));
        onField(guest, card("e", "Enemigo", 1, 1, "R"));
        CardSlot kagravu = withClasses(card("kg", "Kagravú", 3, 3, "P"), "bestia");
        summon(kagravu);
        accept(kagravu);
        assertThat(room.getPrompt().getValidIds()).containsExactly("o");
        assertThat(room.getPrompt().getInstruction()).contains(Selection.OUT_OF_REACH);
    }

    @Test
    void kagravuFromTheLineaFrontalDoesNotWarn() {
        onField(guest, card("e", "Enemigo", 1, 1, "R"));
        CardSlot kagravu = withClasses(card("kg", "Kagravú", 3, 3, "P"), "bestia");
        summonByEffectToFront(kagravu);
        accept(kagravu);
        assertThat(room.getPrompt().getValidIds()).contains("e");
        assertThat(room.getPrompt().getInstruction()).doesNotContain(Selection.OUT_OF_REACH);
    }

    @Test
    void hanaFromTheRetaguardiaTellsTheHistoryItCannotExhaustAnEnemy() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setStrength(1);
        host.getHand().add(card("ds", "Descarte", 1, 1, "W"));
        CardSlot hana = card("hn", "Hana", 2, 2, "W");
        summon(hana);
        accept(hana);
        answer("ds");
        assertThat(enemy.isTapped()).isFalse();
        assertThat(room.getEventLog()).extracting(GameLogEntry::getText).anyMatch(t -> t.startsWith(Selection.OUT_OF_REACH));
    }

    @Test
    void brujaMensajeraOffersEveryBrujoAmongTheLookedCards() {
        host.getDeck().add(0, withClasses(card("b1", "Brujo Uno", 1, 1, "P"), "brujo"));
        host.getDeck().add(0, withClasses(card("b2", "Brujo Dos", 1, 1, "P"), "brujo"));
        summon(withClasses(card("bm", "Bruja Mensajera", 1, 1, "P"), "brujo"));
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("b1", "b2");
        assertThat(room.getPrompt().getMax()).isEqualTo(2);
        assertThat(room.getPrompt().getInstruction()).contains("las cartas que miras").doesNotContain("looked");
    }

    @Test
    void aCardTakenFromTheDeckForARestrictionIsRevealedInTheHistory() {
        // Dírumy: "mira las primeras 4 … toma hasta 1 carta Verde o Blanca": the taken one is named, the rest is not
        host.getDeck().add(0, card("vd", "Verde Tomada", 1, 1, "G"));
        host.getDeck().add(0, card("rj", "Roja Mirada", 1, 1, "R"));
        CardSlot dirumy = card("dy", "Dírumy", 1, 1, "G");
        summon(dirumy);
        drainUntilChoice("vd");
        answer("vd");
        drain();
        assertThat(room.getEventLog()).extracting(GameLogEntry::getText)
                .anyMatch(t -> t.contains("añade a su mano 1 carta") && t.contains("Verde Tomada"))
                .noneMatch(t -> t.contains("Roja Mirada"));
    }

    @Test
    void hiddenToHiddenIsNamedOnlyWithARestriction() {
        // From the hand to the deck: named only when the effect asked for a kind of card
        assertThat(Reveal.named(true, "deck", true, false)).isFalse();
        assertThat(Reveal.named(true, "deck", true, true)).isTrue();
        assertThat(Reveal.named(true, "hand", false, false)).isFalse();
        // Into sight, or out of sight from where everyone saw it: always named
        assertThat(Reveal.named(true, "tributeZone", false, false)).isTrue();
        assertThat(Reveal.named(false, "hand", false, false)).isTrue();
        // Face down in the Descarte is a hidden zone
        assertThat(Reveal.named(true, "discardPile", true, false)).isFalse();
    }

    /** Answers "none" (or accepts the lone effect) until the choice offers that card. */
    private void drainUntilChoice(String id) {
        for (int i = 0; i < 10 && room.getPrompt() != null && !room.getPrompt().getValidIds().contains(id); i++) {
            Prompt p = room.getPrompt();
            List<String> pick = p.getValidIds().size() == 1 && p.getMin() > 0 ? p.getValidIds() : p.getValidIds().stream().filter(v -> v.equals("dy")).toList();
            Answers.answer(room, p.getPlayerId(), p.getId(), pick, List.of());
        }
    }

    @Test
    void theBoardIsToldWhyADefinitiveEffectCannotBePlayedOnTheFirstTurn() {
        CardSlot trato = utility("ts", "Trato Sucio", 0, "B");
        host.getHand().add(trato);
        room.setFirstTurn(true);
        var legal = LegalActions.of(room, "host");
        assertThat(legal).doesNotContainKey("ts");
        assertThat(LegalActions.blocked(room, "host", legal).get("ts")).endsWith("no se pueden activar Efectos Definitivos en el primer turno de la partida");
        room.setFirstTurn(false);
        assertThat(LegalActions.blocked(room, "host", LegalActions.of(room, "host"))).doesNotContainKey("ts");
    }

    @Test
    void aResourceSummonedByEffectIsLoggedFromTheResources() {
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setStrength(5);
        CardSlot yako = withClasses(card("yk", "Yako Kitsune", 3, 3, "G"), "shogún", "fauna");
        yako.getResources().add(card("rs", "Recurso", 2, 2, "G"));
        summonByEffectToFront(yako);
        answer("e");
        answer("rs");
        assertThat(room.getEventLog()).extracting(GameLogEntry::getText)
                .anyMatch(t -> t.contains("invoca Recurso de sus recursos"))
                .noneMatch(t -> t.contains("invoca Recurso de su mano"));
    }
}
