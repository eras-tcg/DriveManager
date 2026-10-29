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

/** Fixes reported while playing E1 (2026-10-30): timing of costs, blocks, Asalto, Enfrentar and several cards. */
class E1BugfixTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(8, 8);
        room = table.room();
        host = table.host();
        guest = table.guest();
        for (int i = 0; i < 4; i++) host.getLifeStack().add(card("hl" + i, "Vida H" + i, 1, 1, "R"));
        for (int i = 0; i < 5; i++) guest.getLifeStack().add(card("gl" + i, "Vida G" + i, 1, 1, "R"));
    }

    @Test
    void aMaterialTributedAsACostTriggersOnlyAfterTheChainResolves() {
        // Arc-xioma pays with Androide A7 as its material: Androide waits for Arc-xioma's effect to resolve
        CardSlot androide = card("an", "Androide A7", 1, 1, "B");
        CardSlot arc = card("arc", "Arc-xioma", 2, 2, "B");
        arc.getMaterials().add(androide);
        CardSlot other = onField(host, card("o", "Otro", 1, 1, "B"), BACK);
        other.getMaterials().add(card("om", "Material de otro", 1, 1, "B"));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setStrength(9);
        summonToFront(arc);
        answer(room.getPrompt(), "arc");
        answer(room.getPrompt(), "an");
        answer(room.getPrompt(), "e");
        Prompt offer = room.getPrompt();
        assertThat(offer.getValidIds()).contains("an");
        assertThat(EffectEngine.strength(enemy)).isEqualTo(6);
    }

    @Test
    void guardianImperialReactivatesWhenItBlocksOnTheOpponentsTurn() {
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(4);
        onField(guest, card("at", "Atacante", 1, 3, "R"));
        CardSlot guardian = onField(host, card("gi", "Guardian Imperial", 5, 4, "R"));
        guardian.setStrength(10);
        assertThat(CombatRules.attackByPlayer(room, "guest", "at", null)).isNull();
        answer(untilPurpose(EffectEngine.PURPOSE_BLOCK), "gi");
        for (int i = 0; i < 8 && room.getPrompt() != null && !room.getPrompt().getValidIds().contains("gi"); i++) answerNone();
        assertThat(guardian.isTapped()).isFalse();
    }

    @Test
    void antorchaLegionariaSummonsALegionarioFromTheHandAtTheEndOfTheTurn() {
        onField(host, legionario(card("ant", "Antorcha Legionaria", 2, 2, "R")));
        for (int i = 0; i < 3; i++) onField(host, legionario(card("l" + i, "Legionario " + i, 1, 1, "R")), BACK);
        CardSlot inHand = legionario(card("lh", "Legionario En Mano", 2, 2, "R"));
        host.getHand().add(inHand);
        TurnRules.turnEnding(room, "host");
        for (int i = 0; i < 6 && room.getPrompt() != null && !room.getPrompt().getValidIds().contains("ant"); i++) answerNone();
        answer(room.getPrompt(), "ant");
        answer(room.getPrompt(), "lh");
        assertThat(host.getField()).contains(inHand);
    }

    @Test
    void deleselDestroyedSummonsALevel2EnteFromTheTributes() {
        onField(host, ente(card("de", "Delesel", 2, 2, "B")));
        CardSlot level2 = ente(card("e2", "Ente 2", 3, 2, "B"));
        host.getTributeZone().add(level2);
        Removals.destroyByPlayer(room, "guest", "host", "de");
        answer(room.getPrompt(), "de");
        answer(room.getPrompt(), "e2");
        assertThat(host.getField()).contains(level2);
    }

    @Test
    void exploradorTemerarioLowersItsAscensoOncePerTurn() {
        room.setSharedCounter(-1);
        CardSlot explorer = onField(host, knight(card("ex", "Explorador Temerario", 2, 2, "R")));
        onField(host, knight(card("a1", "Aliado 1", 1, 1, "R")), BACK);
        onField(host, knight(card("a2", "Aliado 2", 1, 1, "R")), BACK);
        CardSlot promoted = knight(promotion(card("pr", "Ascendido", 4, 3, "R"), 2, "criatura nivel 2."));
        host.getHand().add(promoted);
        // 1 gem and a special cost of 2: only with Explorador's reduction
        assertThat(Summons.canSpecial(room, "host", promoted)).isTrue();
        assertThat(Summons.specialByPlayer(room, "host", "pr", "ex")).isNull();
        answerAll();
        assertThat(host.getField()).contains(promoted).doesNotContain(explorer);
        assertThat(EffectEngine.gemsOf(room, "host")).isZero();
    }

    @Test
    void imperialAscendsOnlyFromTheStrongestAlly() {
        CardSlot imperial = promotion(card("im", "Imperial De La Llama Eterna", 6, 5, "R"), 4,
                "criatura nivel 4 con fuerza igual o mayor que la de cualquier otro aliado.");
        host.getHand().add(imperial);
        CardSlot summoner = onField(host, card("s", "Nivel 4", 5, 4, "R"));
        summoner.setStrength(10);
        CardSlot stronger = onField(host, card("st", "Más fuerte", 1, 1, "R"), BACK);
        stronger.setStrength(12);
        assertThat(Summons.canSpecial(room, "host", imperial)).isFalse();
        stronger.setStrength(8);
        assertThat(Summons.canSpecial(room, "host", imperial)).isTrue();
    }

    @Test
    void unidadDeEmergenciaReturnsTheChosenMaterialsAndReactivates() {
        CardSlot unidad = card("ul", "Unidad De Emergencia L1", 5, 5, "B");
        CardSlot m1 = card("m1", "M1", 1, 1, "B");
        CardSlot m2 = card("m2", "M2", 1, 1, "B");
        unidad.getMaterials().addAll(List.of(m1, m2));
        summonToFront(unidad);
        unidad.setTapped(true);
        answer(room.getPrompt(), "m1");
        assertThat(host.getHand()).contains(m1).doesNotContain(m2);
        assertThat(unidad.getMaterials()).containsExactly(m2);
        assertThat(unidad.isTapped()).isFalse();
    }

    @Test
    void asaltoIsChosenAsTheCreatureEntersNotAsATriggeredEffect() {
        CardSlot assault = card("as", "Con Asalto", 1, 1, "G");
        assault.setKeywords(new ArrayList<>(List.of("Asalto")));
        host.getHand().add(assault);
        assertThat(Summons.normalSummon(room, "host", "as", 50.0, 75.0)).isNull();
        Prompt choice = room.getPrompt();
        assertThat(choice.getPurpose()).isEqualTo(EffectEngine.PURPOSE_ASSAULT);
        assertThat(room.getChain()).isEmpty();
        answer(choice, "as");
        assertThat(EffectEngine.inFrontLine(assault)).isTrue();
        assertThat(assault.isTapped()).isTrue();
    }

    @Test
    void anActiveCreatureAttackedWithEnfrentarCannotBeItsOwnBlocker() {
        CardSlot attacker = onField(host, card("en", "Con Enfrentar", 2, 2, "R"));
        attacker.setKeywords(new ArrayList<>(List.of("Enfrentar")));
        onField(guest, card("t", "Objetivo", 1, 1, "R"));
        onField(guest, card("b", "Otro", 1, 1, "R"));
        assertThat(CombatRules.attackByPlayer(room, "host", "en", "t")).isNull();
        Prompt block = untilPurpose(EffectEngine.PURPOSE_BLOCK);
        assertThat(block.getValidIds()).containsExactly("b");
    }

    @Test
    void whatTheEngineDoesNotRunIsReportedPerCard() {
        assertThat(CardEffects.pending("Franchute", 1, 0, List.of("Perforante"))).isNull();
        assertThat(CardEffects.pending("Carta Sin Programar", 2, 1, List.of("Letalidad 1", "Agrupar")))
                .isEqualTo("2 de 2 efectos propios; 1 de 1 heredados; Agrupar");
    }

    // ------------------------------------------------------------------ Helpers

    private void summonToFront(CardSlot card) {
        CardEffects.register("invocador frontal", new EffectScript("invocador-frontal:en-invocacion", EffectScript.Moment.ON_SUMMON, true,
                "En Invocación", "Invoca 1 criatura de tu mano en tu línea frontal sin pagar su coste.",
                List.of(new EffectStep.SummonFromZone("hand", CardFilter.creatures().named(card.getCardName()), EffectStep.SummonFromZone.FREE, true))));
        host.getHand().addAll(List.of(card, card("iv", "Invocador Frontal", 0, 1, "R")));
        assertThat(Summons.normalSummon(room, "host", "iv", 50.0, 75.0)).isNull();
        answer(room.getPrompt(), card.getInstanceId());
    }

    private static CardSlot promotion(CardSlot card, int cost, String requirement) {
        card.setSpecialSummonKind("promotion");
        card.setSpecialCost(cost);
        card.setRequirement(requirement);
        return card;
    }

    private static CardSlot legionario(CardSlot c) {
        c.setClasses(new ArrayList<>(List.of("legionario")));
        return c;
    }

    private static CardSlot knight(CardSlot c) {
        c.setClasses(new ArrayList<>(List.of("caballero")));
        return c;
    }

    private static CardSlot ente(CardSlot c) {
        c.setClasses(new ArrayList<>(List.of("ente")));
        return c;
    }

    private Prompt untilPurpose(String purpose) {
        for (int i = 0; i < 12; i++) {
            Prompt p = room.getPrompt();
            if (p == null) {
                if (room.getCombat() == null) break;
                Chain.advance(room);
                continue;
            }
            if (purpose.equals(p.getPurpose())) return p;
            answerNone();
        }
        throw new AssertionError("No llegó la elección " + purpose);
    }

    private void answerNone() {
        Prompt p = room.getPrompt();
        Answers.answer(room, p.getPlayerId(), p.getId(), List.of(), List.of());
    }

    private void answerAll() {
        for (int i = 0; i < 6 && room.getPrompt() != null; i++) answerNone();
    }

    private void answer(Prompt prompt, String... ids) {
        assertThat(prompt).isNotNull();
        assertThat(Answers.answer(room, prompt.getPlayerId(), prompt.getId(), List.of(ids), List.of())).isNull();
    }
}
