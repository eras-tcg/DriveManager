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

/** E1.1, second batch: the cards that needed new steps and conditions. */
class E11SecondBatchTest {

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        TestTable table = TestTable.inProgress(8, 8);
        room = table.room();
        host = table.host();
        guest = table.guest();
    }

    @Test
    void wyvernoDeLasNubesSendsTheLookedCardsToTheTopThenTributesThree() {
        List<String> top = host.getDeck().subList(0, 3).stream().map(CardSlot::getInstanceId).toList();
        host.getHand().add(withClasses(card("wn", "Wyverno De Las Nubes", 3, 3, "G"), "dragón"));
        assertThat(Summons.normalSummon(room, "host", "wn", 50.0, 75.0)).isNull();
        Prompt choice = room.getPrompt();
        assertThat(choice.getKind()).isEqualTo(Prompt.Kind.CONFIRM);
        answer(choice, choice.getValidIds().get(0));
        // Back on top, then those three are the ones tributed
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactlyElementsOf(top);
    }

    @Test
    void hechizoPremonitorioSendsTheTwoToTheBottomAndDrawsTheNext() {
        String third = host.getDeck().get(2).getInstanceId();
        List<String> firstTwo = host.getDeck().subList(0, 2).stream().map(CardSlot::getInstanceId).toList();
        CardSlot hechizo = utility("hp", "Hechizo Premonitorio");
        host.getHand().add(hechizo);
        assertThat(Summons.playByPlayer(room, "host", "hp", 50.0, 75.0)).isNull();
        answer(room.getPrompt());
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains(third);
        assertThat(host.getDeck()).extracting(CardSlot::getInstanceId).endsWith(firstTwo.toArray(String[]::new));
    }

    @Test
    void criaDeDragonRunicoTakesADragonAndTributesTheRestWithoutResources() {
        host.getDeck().add(0, withClasses(card("dr", "Dragón", 1, 1, "G"), "dragón"));
        List<String> others = host.getDeck().subList(1, 3).stream().map(CardSlot::getInstanceId).toList();
        host.getHand().add(withClasses(card("cr", "Cría De Dragón Rúnico", 1, 1, "G"), "dragón"));
        assertThat(Summons.normalSummon(room, "host", "cr", 50.0, 75.0)).isNull();
        Prompt take = room.getPrompt();
        assertThat(take.getValidIds()).containsExactly("dr");
        answer(take, "dr");
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).contains("dr");
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsAll(others);
    }

    @Test
    void soleoChoosesOneEnemyPerMaterialTributed() {
        CardSlot soleo = onField(host, card("so", "Soléo", 2, 2, "B"));
        soleo.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B")));
        CardSlot e1 = onField(guest, card("e1", "Enemigo 1", 1, 1, "R"));
        CardSlot e2 = onField(guest, card("e2", "Enemigo 2", 1, 1, "R"));
        e1.setStrength(5);
        e2.setStrength(5);
        assertThat(CombatRules.attackByPlayer(room, "host", "so", null)).isNull();
        accept(soleo);
        answer(room.getPrompt(), "m1", "m2");
        answer(room.getPrompt(), "e1", "e2");
        assertThat(EffectEngine.strength(e1)).isEqualTo(3);
        assertThat(EffectEngine.strength(e2)).isEqualTo(3);
    }

    @Test
    void cragzumReducesByOnePerMaterialRemoved() {
        // From the Retaguardia it could not choose an enemy: a test card summons it to the Línea frontal by effect
        CardEffects.register("invocador frontal de prueba", new EffectScript("invocador-frontal:en-invocacion", EffectScript.Moment.ON_SUMMON, true,
                "En Invocación", "Invoca Cragzum de tu mano en tu línea frontal sin pagar su coste.",
                List.of(new EffectStep.SummonFromZone("hand", CardFilter.creatures().named("Cragzum"), EffectStep.SummonFromZone.FREE, true))));
        CardSlot cragzum = card("cz", "Cragzum", 3, 3, "B");
        cragzum.getMaterials().addAll(List.of(card("m1", "M1", 1, 1, "B"), card("m2", "M2", 1, 1, "B"), card("m3", "M3", 1, 1, "B")));
        host.getHand().addAll(List.of(cragzum, card("iv", "Invocador Frontal De Prueba", 0, 1, "B")));
        CardSlot enemy = onField(guest, card("e", "Enemigo", 1, 1, "R"));
        enemy.setStrength(9);
        assertThat(Summons.normalSummon(room, "host", "iv", 50.0, 75.0)).isNull();
        answer(room.getPrompt(), "cz");
        accept(cragzum);
        answer(room.getPrompt(), "m1", "m2", "m3");
        answer(room.getPrompt(), "e");
        assertThat(EffectEngine.strength(enemy)).isEqualTo(6);
    }

    @Test
    void grodCannotDeclareAnAttack() {
        onField(host, withClasses(card("gr", "Grod", 2, 2, "G"), "bestia", "dragón"));
        EffectEngine.refreshContinuous(room);
        assertThat(CombatRules.attackByPlayer(room, "host", "gr", null)).contains("no puede declarar ataque");
    }

    @Test
    void niobatsTributedByABeastsEffectTradesAHandCardForAVioletTribute() {
        CardSlot krull = onField(host, withClasses(card("kr", "Krull", 1, 1, "P"), "bestia"));
        CardSlot niobats = withClasses(card("nb", "Niobats", 1, 1, "P"), "bestia");
        CardSlot other = card("ot", "Otra", 1, 1, "R");
        host.getHand().addAll(List.of(niobats, other));
        CardSlot violet = card("vt", "Violeta", 1, 1, "P");
        host.getTributeZone().add(violet);
        // Krull attacks: draws 1, then tributes 1 of the hand (Niobats)
        assertThat(CombatRules.attackByPlayer(room, "host", "kr", null)).isNull();
        answer(room.getPrompt(), "nb");
        accept(niobats);
        answer(room.getPrompt(), "ot");
        answer(room.getPrompt(), "vt");
        assertThat(host.getHand()).contains(violet);
    }

    @Test
    void hatchkinBecomesAResourceOfASummonedAllyForAGem() {
        CardSlot hatchkin = onField(host, withClasses(card("ha", "Hatchkin", 1, 1, "G"), "dragón"));
        CardSlot ally = card("al", "Aliado", 1, 1, "G");
        host.getHand().add(ally);
        int gems = EffectEngine.gemsOf(room, "host");
        assertThat(Summons.normalSummon(room, "host", "al", 50.0, 75.0)).isNull();
        accept(hatchkin);
        assertThat(ally.getResources()).contains(hatchkin);
        assertThat(host.getField()).doesNotContain(hatchkin);
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems - 1 + 1);
    }

    @Test
    void ascensoEspectralTributesTheHandDrawsFiveAndEndsTheTurn() {
        host.getHand().addAll(List.of(card("h1", "H1", 1, 1, "R"), card("h2", "H2", 1, 1, "R")));
        host.getHand().add(utility("ae", "Ascenso Espectral"));
        assertThat(Summons.playByPlayer(room, "host", "ae", 50.0, 75.0)).isNull();
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("h1", "h2");
        assertThat(host.getHand()).hasSize(5);
        assertThat(room.getUsedThisTurn()).contains(EffectEngine.END_TURN_REQUESTED + "host");
    }

    @Test
    void aLaCargaGivesFuriaAndPerforanteWithSevenRedAllies() {
        for (int i = 0; i < 7; i++) onField(host, card("r" + i, "Roja " + i, 1, 1, "R"), i < 3 ? FRONT : BACK);
        host.getHand().add(utility("lc", "A La Carga!"));
        assertThat(Summons.playByPlayer(room, "host", "lc", 50.0, 75.0)).isNull();
        assertThat(host.getField().stream().filter(c -> "creature".equals(c.getCardType())))
                .allSatisfy(c -> assertThat(EffectEngine.keywords(c)).contains("Furia", "Perforante"));
    }

    @Test
    void lomoPlateadoSacrificesAnAllyGainsThreeAndMovesUp() {
        CardSlot lomo = onField(host, withClasses(card("lp", "Lomo Plateado", 2, 2, "P"), "bestia"), BACK);
        CardSlot ally = onField(host, card("al", "Aliado", 1, 1, "P"));
        int before = EffectEngine.strength(lomo);
        assertThat(Chain.activateByPlayer(room, "host", "lp", "lomo-plateado:turno-aliado")).isNull();
        answer(room.getPrompt(), "al");
        assertThat(host.getTributeZone()).contains(ally);
        assertThat(EffectEngine.strength(lomo)).isEqualTo(before + 3);
        answer(room.getPrompt(), "lp");
        assertThat(EffectEngine.inFrontLine(lomo)).isTrue();
    }

    @Test
    void jabalixLooksOneMoreCardPerCopyInTheTributes() {
        host.getTributeZone().add(card("jc", "Jabalix", 1, 1, "P"));
        host.getHand().add(card("jb", "Jabalix", 1, 1, "P"));
        assertThat(Summons.normalSummon(room, "host", "jb", 50.0, 75.0)).isNull();
        assertThat(room.getPrompt().getCards()).hasSize(4);
    }

    @Test
    void zamaranzasBecomesAMaterialAndTheAllyActivatesItsEnInvocacion() {
        CardSlot eisten = onField(host, withClasses(card("ei", "Eisten", 1, 1, "B"), "ente"));
        CardSlot zamaranzas = withClasses(card("za", "Zamaranzas", 1, 1, "B"), "ente");
        host.getHand().add(zamaranzas);
        assertThat(Summons.normalSummon(room, "host", "za", 50.0, 75.0)).isNull();
        accept(zamaranzas);
        answer(room.getPrompt(), "ei");
        assertThat(eisten.getMaterials()).contains(zamaranzas);
        // Eisten's En Invocación: tribute 4 from the top
        assertThat(host.getTributeZone()).hasSizeGreaterThanOrEqualTo(4);
    }

    // ------------------------------------------------------------------ Helpers

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
