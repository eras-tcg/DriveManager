package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import com.drive.drive_manager.simulator.engine.EffectStep.Draw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ritual from the hand, moving up after a special summon, Furia, Reforzar, the "ventaja del iniciador", slow
 * effects (Arañador), the order of simultaneous triggers and responding to a chain.
 */
class RulesWindowsTest {

    private static final double FRONT = 20.0;
    private static final double BACK = 80.0;

    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;

    @BeforeEach
    void setUp() {
        room = new GameRoom();
        room.setRoomId("r1");
        host = new PlayerState("host", "Ana");
        guest = new PlayerState("guest", "Beto");
        room.getPlayers().put("host", host);
        room.getPlayers().put("guest", guest);
        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
        room.setCurrentTurnUserId("host");
        room.setCurrentPhase("MAIN");
        room.setSharedCounter(-5); // host: 5 gems
        host.setResourceCount(5);
        guest.setResourceCount(2);
        host.setDeck(cards("hd", 8));
        guest.setDeck(cards("gd", 8));
        guest.setLifeStack(cards("life", 3));
    }

    // ------------------------------------------------------------------ Ritual from the hand

    @Test
    void ritualOntoACreatureTakesItsPlaceAndPaysTheSpecialCost() {
        CardSlot zhaglizer = ritual("zha", "Zhaglizer", 3, 2, "criatura nivel 2.");
        host.getHand().add(zhaglizer);
        CardSlot summoner = field(host, creature("s", "Invocador", 2, 3), FRONT);
        summoner.setTapped(true);

        assertThat(EffectEngine.ritualByPlayer(room, "host", "zha", "s")).isNull();

        assertThat(host.getField()).containsExactly(zhaglizer);
        assertThat(zhaglizer.getY()).isEqualTo(FRONT);
        assertThat(zhaglizer.isTapped()).isTrue();
        assertThat(host.getTributeZone()).containsExactly(summoner);
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(2);
        // Already in the Línea frontal: straight to the regulatory draw
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("hd1");
        GameEvent sacrifice = room.getTimeline().stream().filter(e -> GameEvent.SACRIFICE.equals(e.getType())).findFirst().orElseThrow();
        assertThat(sacrifice.getData()).containsEntry("cause", EffectEngine.CAUSE_RITUAL);
    }

    @Test
    void ritualChecksTheRequirementAndSharedTypeOrColor() {
        host.getHand().add(ritual("zha", "Zhaglizer", 3, 2, "criatura nivel 2."));
        field(host, creature("l1", "Nivel 1", 1, 3), FRONT);
        CardSlot red = field(host, creature("red", "Roja", 2, 3), FRONT);
        red.setColors(new ArrayList<>(List.of("R")));
        assertThat(EffectEngine.ritualByPlayer(room, "host", "zha", "l1")).contains("Requerimiento");
        assertThat(EffectEngine.ritualByPlayer(room, "host", "zha", "red")).contains("no comparte");
        assertThat(EffectEngine.ritualByPlayer(room, "host", "zha", null)).contains("Requerimiento");
        room.setSharedCounter(5); // host: -5 gems, opponent Marcador 2
        assertThat(EffectEngine.ritualByPlayer(room, "host", "zha", null)).contains("gemas");
    }

    @Test
    void ritualWithoutASummonerAsksForTheCreaturesAndCanBeCancelled() {
        CardSlot mawser = ritual("maw", "Mawser", 4, 3, "criaturas que en nivel sumen 6.");
        host.getHand().add(mawser);
        field(host, creature("a", "A", 2, 3), FRONT);
        field(host, creature("b", "B", 3, 3), BACK);
        field(host, creature("c", "C", 2, 3), BACK);
        assertThat(EffectEngine.canRitual(room, "host", mawser)).isTrue();

        assertThat(EffectEngine.ritualByPlayer(room, "host", "maw", null)).isNull();
        Prompt pick = room.getPrompt();
        assertThat(pick.getPurpose()).isEqualTo("RITUAL");
        assertThat(pick.getValidIds()).containsExactly("a", "b", "c");
        // Not enough: 2 + 2 < 6
        assertThat(EffectEngine.answer(room, "host", pick.getId(), List.of("a", "c"), List.of())).contains("suman 4");
        // Cancelled: nothing happened
        assertThat(EffectEngine.answer(room, "host", pick.getId(), List.of(), List.of())).isNull();
        assertThat(host.getHand()).contains(mawser);
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(5);

        EffectEngine.ritualByPlayer(room, "host", "maw", null);
        // The first chosen is the summoner: Mawser takes the place of "b" (Retaguardia)
        assertThat(EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of("b", "a", "c"), List.of())).isNull();
        assertThat(mawser.getY()).isEqualTo(BACK);
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void droppedOntoACreatureThatIsNotEnoughAsksForTheRest() {
        host.getHand().add(ritual("maw", "Mawser", 4, 3, "criaturas que en nivel sumen 6."));
        field(host, creature("a", "A", 4, 3), FRONT);
        field(host, creature("b", "B", 2, 3), BACK);
        EffectEngine.ritualByPlayer(room, "host", "maw", "a");
        Prompt rest = room.getPrompt();
        assertThat(rest.getSummonerId()).isEqualTo("a");
        assertThat(rest.getValidIds()).containsExactly("b");
        EffectEngine.answer(room, "host", rest.getId(), List.of("b"), List.of());
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).containsExactly("maw");
    }

    @Test
    void afterASpecialSummonTheCreatureMayMoveUpThenDrawsThenTriggers() {
        CardSlot berkelus = ritual("ber", "Berkelus El Cazarrecompensas Del Bajomundo", 3, 4, "criatura nivel 4");
        host.getHand().add(berkelus);
        field(host, creature("s", "Invocador", 4, 3), BACK);
        EffectEngine.ritualByPlayer(room, "host", "ber", "s");

        Prompt front = room.getPrompt();
        assertThat(front.getPurpose()).isEqualTo("SPECIAL_FRONT");
        assertThat(front.getKind()).isEqualTo(Prompt.Kind.CONFIRM);
        assertThat(host.getHand()).isEmpty();
        assertThat(room.getChain()).isEmpty();
        EffectEngine.answer(room, "host", front.getId(), List.of("ber"), List.of());

        assertThat(EffectEngine.inFrontLine(berkelus)).isTrue();
        assertThat(room.getTimeline()).extracting(GameEvent::getType)
                .containsSubsequence(GameEvent.SPECIAL_SUMMON, GameEvent.MOVE, GameEvent.DRAW, GameEvent.EFFECT_ACTIVATED);
    }

    @Test
    void theSecondPlayerSummonsAnIniciadorForOneLessOnce() {
        room.setStarterAdvantageUserId("host");
        CardSlot starter = creature("ini", "Iniciador", 1, 3);
        starter.setStarter(true);
        starter.setCost(3);
        host.getHand().add(starter);
        assertThat(EffectEngine.normalSummon(room, "host", "ini", 50.0, 75.0)).isNull();
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(3);
        assertThat(room.getStarterAdvantageUserId()).isNull();
    }

    @Test
    void aCostReducedToZeroCanBePaidBelowTheOpponentsMarcador() {
        // Host at -8 gems, the opponent's Marcador is 2: nothing can be paid... except a cost of 0
        room.setSharedCounter(8);
        guest.setResourceCount(2);
        host.getHand().add(ritual("kro", "Krokler", 0, 2, "criatura nivel 1."));
        field(host, creature("s", "Invocador", 1, 3), FRONT);
        assertThat(EffectEngine.ritualByPlayer(room, "host", "kro", "s")).isNull();
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).containsExactly("kro");
    }

    // ------------------------------------------------------------------ Furia

    @Test
    void furiaLetsACreatureAttackBelowZeroGems() {
        room.setSharedCounter(1); // host: -1 gems
        CardSlot a = field(host, creature("a", "A", 1, 1), FRONT);
        assertThat(EffectEngine.attackByPlayer(room, "host", "a", null)).contains("0 o más");
        a.getTurnKeywords().add("Furia 1");
        room.setSharedCounter(2); // -2
        assertThat(EffectEngine.attackByPlayer(room, "host", "a", null)).contains("Furia 1");
        room.setSharedCounter(1); // -1
        assertThat(EffectEngine.attackByPlayer(room, "host", "a", null)).isNull();
        // Still exhausted by the declaration: Furia does not lift it
        assertThat(a.isTapped()).isTrue();
    }

    @Test
    void furiaWithoutANumberIgnoresTheGems() {
        room.setSharedCounter(8);
        CardSlot a = field(host, creature("a", "A", 1, 1), FRONT);
        a.getKeywords().add("Furia");
        assertThat(EffectEngine.attackGemsOk(room, "host", a)).isTrue();
    }

    // ------------------------------------------------------------------ Direct damage: Reforzar and lives that are played

    @Test
    void aStrongerLifeJustRevealedMayReforzar() {
        CardSlot life = guest.getLifeStack().get(0);
        life.setStrength(9);
        field(host, creature("a", "A", 1, 5), FRONT);
        EffectEngine.attackByPlayer(room, "host", "a", null);

        Prompt reinforce = room.getPrompt();
        assertThat(reinforce.getPurpose()).isEqualTo("REINFORCE");
        assertThat(reinforce.getPlayerId()).isEqualTo("guest");
        EffectEngine.answer(room, "guest", reinforce.getId(), List.of(life.getInstanceId()), List.of());

        assertThat(guest.getHand()).doesNotContain(life);
        assertThat(guest.getLifeStack()).hasSize(3).last().isEqualTo(life);
        assertThat(life.isFaceDown()).isFalse();
        assertThat(room.getTimeline()).extracting(GameEvent::getType).containsSubsequence(GameEvent.LIFE_DAMAGE, GameEvent.SEND, GameEvent.ATTACK_ENDED);
    }

    @Test
    void aFaceUpLifeCannotReforzarAgain() {
        CardSlot life = guest.getLifeStack().get(0);
        life.setStrength(9);
        life.setFaceDown(false);
        field(host, creature("a", "A", 1, 5), FRONT);
        EffectEngine.attackByPlayer(room, "host", "a", null);
        assertThat(room.getPrompt()).isNull();
        assertThat(guest.getHand()).contains(life);
    }

    @Test
    void aUtilityLostAsALifeMayBePlayedForFree() {
        CardSlot life = guest.getLifeStack().get(0);
        life.setCardType("utility");
        life.setCost(4);
        field(host, creature("a", "A", 1, 5), FRONT);
        EffectEngine.attackByPlayer(room, "host", "a", null);
        Prompt play = room.getPrompt();
        assertThat(play.getPurpose()).isEqualTo("LIFE_PLAY");
        int gems = EffectEngine.gemsOf(room, "guest");
        EffectEngine.answer(room, "guest", play.getId(), List.of(life.getInstanceId()), List.of());
        assertThat(guest.getField()).contains(life);
        assertThat(EffectEngine.gemsOf(room, "guest")).isEqualTo(gems);
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.PLAY);
    }

    // ------------------------------------------------------------------ Arañador

    @Test
    void aranadorMaySummonAVioletaLevel1FromTheTributos() {
        CardSlot aranador = ritual("ara", "Arañador", 3, 4, "criatura nivel 3");
        aranador.setCost(5);
        host.getHand().add(aranador);
        field(host, creature("s", "Invocador", 3, 3), FRONT);
        host.getTributeZone().add(creature("v1", "Violeta 1", 1, 2));

        EffectEngine.ritualByPlayer(room, "host", "ara", "s");
        Prompt confirm = room.getPrompt();
        assertThat(confirm.getKind()).isEqualTo(Prompt.Kind.CONFIRM);
        assertThat(confirm.getSourceName()).isEqualTo("Arañador");
        EffectEngine.answer(room, "host", confirm.getId(), List.of("ara"), List.of());
        Prompt summon = room.getPrompt();
        assertThat(summon.getValidIds()).containsExactly("v1");
        EffectEngine.answer(room, "host", summon.getId(), List.of("v1"), List.of());
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).containsExactly("ara", "v1");
    }

    @Test
    void aranadorSlowEffectNeedsSomethingToSacrificeAndAnotherToReactivate() {
        CardSlot aranador = field(host, creature("ara", "Arañador", 4, 11), FRONT);
        // Alone and active: nothing to reactivate
        assertThat(EffectEngine.activatable(room, "host")).isEmpty();
        // Exhausted and alone: it could only sacrifice itself, then nobody is left to reactivate
        aranador.setTapped(true);
        assertThat(EffectEngine.activatable(room, "host")).isEmpty();
        // With another ally it can sacrifice that one to reactivate itself
        field(host, creature("x", "Otro", 1, 1), BACK);
        assertThat(EffectEngine.activatable(room, "host")).extracting(EffectEngine.Activatable::scriptId)
                .containsExactly("aranador:turno-aliado");
        // Not on the opponent's turn (Turno Aliado), nor for the opponent
        assertThat(EffectEngine.activatable(room, "guest")).isEmpty();

        assertThat(EffectEngine.activateByPlayer(room, "host", "ara", null)).isNull();
        Prompt sacrifice = room.getPrompt();
        // Arañador is the only exhausted ally: it cannot be the one sacrificed
        assertThat(sacrifice.getValidIds()).containsExactly("x");
        EffectEngine.answer(room, "host", sacrifice.getId(), List.of("x"), List.of());
        Prompt untap = room.getPrompt();
        assertThat(untap.getValidIds()).containsExactly("ara");
        EffectEngine.answer(room, "host", untap.getId(), List.of("ara"), List.of());
        assertThat(aranador.isTapped()).isFalse();
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("x");
        // Once per turn
        assertThat(EffectEngine.activatable(room, "host")).isEmpty();
        assertThat(EffectEngine.activateByPlayer(room, "host", "ara", null)).contains("no se puede activar");
    }

    @Test
    void slowEffectsWaitForTheOpenState() {
        field(host, creature("ara", "Arañador", 4, 11), FRONT).setTapped(true);
        field(host, creature("x", "Otro", 1, 1), BACK);
        room.setCombat(new Combat());
        assertThat(EffectEngine.activatable(room, "host")).isEmpty();
    }

    // ------------------------------------------------------------------ Chains

    @Test
    void whatTriggersWhileAChainResolvesWaitsForTheWholeChain() {
        // Krokler by Ritual onto Carnhaus: Cadena 1 Carnhaus (En Destrucción), Cadena 2 Krokler (En Invocación)
        CardSlot krokler = ritual("kro", "Krokler", 1, 2, "criatura nivel 1.");
        host.getHand().add(krokler);
        field(host, creature("carn", "Carnhaus", 1, 3), FRONT);
        field(guest, creature("gcarn", "Carnhaus", 1, 3), FRONT);
        EffectEngine.ritualByPlayer(room, "host", "kro", "carn");
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of("kro"), List.of());
        assertThat(room.getChain()).extracting(PendingEffect::getSourceName).containsExactly("Carnhaus", "Krokler");

        // Cadena 2 resolves first: Krokler destroys the guest's Carnhaus
        Prompt destroy = room.getPrompt();
        assertThat(destroy.getValidIds()).containsExactly("gcarn");
        EffectEngine.answer(room, "host", destroy.getId(), List.of("gcarn"), List.of());

        // Its "En Destrucción" waits: Cadena 1 (the host's Carnhaus) resolves before it
        Prompt tribute = room.getPrompt();
        assertThat(tribute.getPlayerId()).isEqualTo("host");
        assertThat(room.getChain()).extracting(PendingEffect::getSourceInstanceId).containsExactly("carn");
        EffectEngine.answer(room, "host", tribute.getId(), List.of(host.getHand().get(0).getInstanceId()), List.of());

        // Then a new chain: Cadena 1, the guest's Carnhaus
        assertThat(room.getChain()).extracting(PendingEffect::getSourceInstanceId).containsExactly("gcarn");
        assertThat(room.getChain().get(0).getLink()).isEqualTo(1);
        assertThat(room.getPrompt().getPlayerId()).isEqualTo("guest");
    }

    @Test
    void optionalTriggersAreChosenAndOrderedInOneChoice() {
        // Cazador by Ritual onto Slicker in the Retaguardia: Cazador (mandatory) + Celeridad and Slicker (optional)
        CardSlot cazador = ritual("caz", "Cazador De Bestias", 2, 2, "criatura nivel 2.");
        cazador.getKeywords().add("Celeridad");
        host.getHand().add(cazador);
        field(host, creature("sli", "Slicker", 2, 3), BACK);
        host.getTributeZone().add(creature("v1", "Violeta", 1, 2));
        EffectEngine.ritualByPlayer(room, "host", "caz", "sli");
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of(), List.of()); // stays in the Retaguardia

        Prompt choice = room.getPrompt();
        assertThat(choice.getPurpose()).isEqualTo("ORDER");
        assertThat(choice.getMin()).isZero();
        assertThat(choice.getValidIds()).containsExactlyInAnyOrder("sli", "caz");
        assertThat(room.getChain()).extracting(PendingEffect::getSourceName).containsExactly("Cazador De Bestias");

        // Only Celeridad: Slicker is not activated, and no further yes/no question
        EffectEngine.answer(room, "host", choice.getId(), List.of("caz"), List.of());
        // Celeridad was Cadena 2 and resolved first (Cazador moved up); Cadena 1 is resolving now
        assertThat(room.getTimeline()).filteredOn(e -> GameEvent.EFFECT_ACTIVATED.equals(e.getType()))
                .extracting(e -> e.getData().get("label") + ":" + e.getData().get("link"))
                .containsExactly("En Invocación:1", "Celeridad:2");
        assertThat(EffectEngine.inFrontLine(cazador)).isTrue();
        assertThat(room.getChain()).extracting(PendingEffect::getLabel).containsExactly("En Invocación");
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.EFFECT_DECLINED);
        assertThat(room.getPrompt().getPurpose()).isNotEqualTo("ORDER");
    }

    // ------------------------------------------------------------------ Responding to a chain

    @Test
    void theOpponentMayRespondBeforeTheChainResolves() {
        CardEffects.register("respondedor de prueba", new EffectScript("test:respuesta", EffectScript.Moment.RESPONSE, false,
                "En Mano", "Roba 1 carta.", List.of(new Draw(1))).fromZone("hand").limited(EffectScript.Limit.ONCE_PER_TURN));
        CardSlot responder = creature("resp", "Respondedor De Prueba", 1, 1);
        guest.getHand().add(responder);
        host.getHand().add(creature("baj", "Bajmut Pútrido", 1, 3));
        host.getHand().add(creature("h1", "Para tributar", 1, 1));

        EffectEngine.normalSummon(room, "host", "baj", 50.0, 75.0);
        Prompt priority = room.getPrompt();
        assertThat(priority.getPurpose()).isEqualTo("PRIORITY");
        assertThat(priority.getPlayerId()).isEqualTo("guest");
        EffectEngine.answer(room, "guest", priority.getId(), List.of("resp"), List.of());

        // The response resolves first (the guest draws), then Bajmut's tribute
        assertThat(guest.getHand()).extracting(CardSlot::getInstanceId).contains("gd1");
        assertThat(room.getPrompt().getPlayerId()).isEqualTo("host");
        assertThat(room.getChain()).extracting(PendingEffect::getSourceName).containsExactly("Bajmut Pútrido");
    }

    // ------------------------------------------------------------------ Helpers

    private static CardSlot field(PlayerState p, CardSlot c, double y) {
        c.setX(50.0);
        c.setY(y);
        c.setFaceDown(false);
        p.getField().add(c);
        return c;
    }

    private static CardSlot ritual(String id, String name, int specialCost, int level, String requirement) {
        CardSlot c = creature(id, name, level, 8);
        c.setSpecialSummonKind("ritual");
        c.setSpecialCost(specialCost);
        c.setRequirement(requirement);
        return c;
    }

    private static List<CardSlot> cards(String prefix, int n) {
        List<CardSlot> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            CardSlot c = creature(prefix + i, "Carta " + i, 1, 1);
            c.setFaceDown(true);
            list.add(c);
        }
        return list;
    }

    private static CardSlot creature(String id, String name, int level, int strength) {
        CardSlot c = new CardSlot();
        c.setInstanceId(id);
        c.setCardId("file-" + id);
        c.setCardName(name);
        c.setCardType("creature");
        c.setCost(2);
        c.setLevel(level);
        c.setStrength(strength);
        c.setColors(new ArrayList<>(List.of("P")));
        return c;
    }
}
