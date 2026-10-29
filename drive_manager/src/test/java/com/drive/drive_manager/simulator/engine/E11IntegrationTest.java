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

/** E1.1 (first subedition): the cards whose effects use the engine's existing vocabulary. */
class E11IntegrationTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(6, 6);
        room = table.room();
        host = table.host();
        guest = table.guest();
    }

    @Test
    void dragonGolosoDestroysAnEnemyOfStrength4OrLessWhenItAttacks() {
        CardSlot goloso = onField(host, withClasses(card("g", "Dragón Goloso", 1, 1, "G"), "dragón"));
        CardSlot weak = onField(guest, card("w", "Débil", 1, 1, "R"));
        CardSlot strong = onField(guest, card("s", "Fuerte", 1, 1, "R"));
        strong.setStrength(5);
        assertThat(CombatRules.attackByPlayer(room, "host", goloso.getInstanceId(), null)).isNull();
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly(weak.getInstanceId());
        answer(target, weak.getInstanceId());
        assertThat(guest.getField()).doesNotContain(weak).contains(strong);
    }

    @Test
    void spentalosMayTributeTwoMaterialsForAGem() {
        CardSlot spentalos = card("sp", "Spentalos", 2, 2, "B");
        spentalos.getMaterials().addAll(List.of(card("m1", "Material 1", 1, 1, "B"), card("m2", "Material 2", 1, 1, "B")));
        host.getHand().add(spentalos);
        int gems = EffectEngine.gemsOf(room, "host");
        assertThat(Summons.normalSummon(room, "host", "sp", 50.0, 75.0)).isNull();
        accept(spentalos);
        // Which 2 materials (here, both)
        answer(room.getPrompt(), "m1", "m2");
        assertThat(spentalos.getMaterials()).isEmpty();
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("m1", "m2");
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems - 2 + 1);
    }

    @Test
    void krullTakesABeastFromTheTopThree() {
        host.getDeck().clear();
        host.getDeck().addAll(List.of(card("d1", "Otra", 1, 1, "P"), withClasses(card("d2", "Bestia", 1, 1, "P"), "bestia"),
                card("d3", "Otra más", 1, 1, "P"), card("d4", "Cuarta", 1, 1, "P")));
        CardSlot krull = card("k", "Krull", 1, 1, "P");
        host.getHand().add(krull);
        assertThat(Summons.normalSummon(room, "host", "k", 50.0, 75.0)).isNull();
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).containsExactly("d2");
        Answers.answer(room, "host", take.getId(), List.of("d2"), List.of("d1", "d3"));
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("d2");
        assertThat(host.getDeck()).extracting(CardSlot::getInstanceId).endsWith("d1", "d3");
    }

    @Test
    void aracnopantoDestroyedSummonsALevel1BeastFromTheDiscardForFree() {
        CardSlot aracno = onField(host, card("a", "Aracnopanto", 3, 3, "P"));
        CardSlot beast = withClasses(card("b", "Bestia", 1, 1, "P"), "bestia");
        host.getDiscardPile().add(beast);
        int gems = EffectEngine.gemsOf(room, "host");
        Removals.destroyByPlayer(room, "guest", "host", "a");
        accept(aracno);
        answer(room.getPrompt(), "b");
        assertThat(host.getField()).contains(beast);
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems);
    }

    @Test
    void cuevaDeBestiasGainsAGemAndDrawsWhenAVioletAllyIsDestroyed() {
        CardSlot cueva = onField(host, structure("cv", "Cueva De Bestias"), BACK);
        CardSlot violet = onField(host, card("v", "Violeta", 1, 1, "P"));
        host.getHand().add(card("h", "En mano", 1, 1, "R"));
        int gems = EffectEngine.gemsOf(room, "host");
        Removals.destroyByPlayer(room, "guest", "host", "v");
        accept(cueva);
        assertThat(cueva.isTapped()).isTrue();
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems + 1);
        // Si lo haces: tribute 1 card of the hand (2 now: the drawn one too)
        Prompt tribute = room.getPrompt();
        assertThat(tribute.getValidIds()).hasSize(2);
        answer(tribute, "h");
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("h");
    }

    @Test
    void peonExperimentadoTributesItselfToSummonALevel2RedAndEspadaFerreaFollows() {
        CardSlot peon = onField(host, card("pe", "Peón Experimentado", 1, 1, "R"));
        CardSlot red2 = card("r2", "Roja 2", 2, 2, "R");
        CardSlot ferrea = card("ef", "Espada Ferrea", 1, 1, "R");
        host.getHand().addAll(List.of(red2, ferrea));
        assertThat(Chain.activateByPlayer(room, "host", "pe", "peon-experimentado:turno-aliado")).isNull();
        answer(room.getPrompt(), "r2");
        assertThat(host.getTributeZone()).contains(peon);
        assertThat(host.getField()).contains(red2);
        // Summoned by effect: Espada Ferrea may follow from the hand, paying its cost, to the Línea frontal
        accept(ferrea);
        assertThat(host.getField()).contains(ferrea);
        assertThat(EffectEngine.inFrontLine(ferrea)).isTrue();
    }

    @Test
    void serenoFronterizoReactivatesUpToTwoAlliesAtTheEndOfTheTurn() {
        onField(host, card("sf", "Sereno Fronterizo", 3, 3, "R"));
        CardSlot a = onField(host, card("a1", "Aliado 1", 1, 1, "R"));
        CardSlot b = onField(host, card("a2", "Aliado 2", 1, 1, "R"), BACK);
        a.setTapped(true);
        b.setTapped(true);
        TurnRules.turnEnding(room, "host");
        for (int i = 0; i < 4 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            List<String> pick = p.getValidIds().stream().filter(id -> id.startsWith("a")).limit(Math.max(1, p.getMin())).toList();
            Answers.answer(room, p.getPlayerId(), p.getId(), pick.isEmpty() ? List.of() : pick, List.of());
        }
        assertThat(a.isTapped()).isFalse();
        assertThat(b.isTapped()).isFalse();
    }

    @Test
    void alabardaSummonedByEffectBringsARedCreatureForThreeLessAndMovesUp() {
        // A test card whose En Invocación summons Alabarda from the hand by effect, without paying
        CardEffects.register("invocador de prueba", new EffectScript("invocador-prueba:en-invocacion", EffectScript.Moment.ON_SUMMON, true,
                "En Invocación", "Invoca Alabarda De Ala Blanca de tu mano sin pagar su coste.",
                List.of(new EffectStep.SummonFromZone("hand", CardFilter.creatures().named("Alabarda De Ala Blanca"), EffectStep.SummonFromZone.FREE, false))));
        CardSlot red3 = card("r3", "Roja 3", 4, 3, "R");
        CardSlot alabarda = card("al", "Alabarda De Ala Blanca", 4, 3, "R");
        CardSlot summoner = card("iv", "Invocador De Prueba", 0, 1, "R");
        host.getHand().addAll(List.of(red3, alabarda, summoner));
        int gems = EffectEngine.gemsOf(room, "host");
        assertThat(Summons.normalSummon(room, "host", "iv", 50.0, 75.0)).isNull();
        answer(room.getPrompt(), "al");
        accept(alabarda);
        answer(room.getPrompt(), "r3");
        assertThat(host.getField()).contains(red3, alabarda);
        assertThat(EffectEngine.inFrontLine(alabarda)).isTrue();
        // Roja 3 costs 4, reduced by 3
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems - 1);
    }

    // ------------------------------------------------------------------ Helpers

    private static CardSlot withClasses(CardSlot card, String... classes) {
        card.setClasses(new ArrayList<>(List.of(classes)));
        return card;
    }

    private static CardSlot structure(String id, String name) {
        CardSlot s = utility(id, name);
        s.setCardType("structure");
        return s;
    }

    /** Says yes to an optional effect of {@code source} that asks first. */
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
