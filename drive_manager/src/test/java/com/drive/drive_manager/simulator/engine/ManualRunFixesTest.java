package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameLogEntry;
import com.drive.drive_manager.simulator.engine.effects.selection.Selection;
import org.junit.jupiter.api.Test;

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
