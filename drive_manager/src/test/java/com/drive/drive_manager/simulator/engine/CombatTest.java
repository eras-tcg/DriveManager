package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Combat (rule 10) and the ST3 effects that use it, passive triggers and per-turn limits. */
class CombatTest {

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
        room.setSharedCounter(-5);
        host.setResourceCount(5);
        guest.setResourceCount(2);
        host.setDeck(cards("hd", 8));
        guest.setDeck(cards("gd", 8));
        guest.setLifeStack(cards("life", 3));
        // Weak lives: tributed by any attack (Reforzar has its own tests)
        guest.getLifeStack().forEach(l -> l.setStrength(1));
    }

    // ------------------------------------------------------------------ Combat rules

    @Test
    void unblockedAttackDealsDirectDamageToTheTopLife() {
        CardSlot attacker = field(host, creature("a", "Atacante", 1, 5), FRONT);
        guest.getLifeStack().get(0).setStrength(4);

        assertThat(EffectEngine.attackByPlayer(room, "host", "a", null)).isNull();

        assertThat(attacker.isTapped()).isTrue();
        assertThat(room.getCombat()).isNull();
        // A creature life with strength 4 ≤ 5 is tributed
        assertThat(guest.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("life1");
        assertThat(guest.getLifeStack()).hasSize(2);
        assertThat(room.getTimeline()).extracting(GameEvent::getType).containsSubsequence(
                GameEvent.ATTACK_DECLARED, GameEvent.LIFE_DAMAGE, GameEvent.ATTACK_ENDED);
    }

    @Test
    void attackRules() {
        field(host, creature("back", "Atrás", 1, 5), BACK);
        CardSlot tapped = field(host, creature("t", "Agotada", 1, 5), FRONT);
        tapped.setTapped(true);
        field(guest, creature("active", "Activa", 1, 3), FRONT);
        assertThat(EffectEngine.attackByPlayer(room, "host", "back", null)).contains("línea frontal");
        assertThat(EffectEngine.attackByPlayer(room, "host", "t", null)).contains("agotada");
        field(host, creature("a", "Atacante", 1, 5), FRONT);
        assertThat(EffectEngine.attackByPlayer(room, "host", "a", "active")).contains("agotadas");
        assertThat(EffectEngine.attackByPlayer(room, "guest", "active", null)).contains("tu turno");
    }

    @Test
    void declaringAnAttackNeedsZeroOrMoreGems() {
        field(host, creature("a", "Atacante", 1, 5), FRONT);
        room.setSharedCounter(1); // host: -1 gems
        assertThat(EffectEngine.attackByPlayer(room, "host", "a", null)).contains("0 o más gemas");
        room.setSharedCounter(0);
        assertThat(EffectEngine.attackByPlayer(room, "host", "a", null)).isNull();
    }

    @Test
    void everyCombatStepOpensAWindowWhenEnteredAndLeft() {
        field(host, creature("a", "Atacante", 1, 5), FRONT);
        EffectEngine.attackByPlayer(room, "host", "a", null);
        List<String> steps = room.getTimeline().stream()
                .filter(e -> GameEvent.COMBAT_STEP.equals(e.getType()))
                .map(e -> e.getData().get("step") + ":" + e.getData().get("moment")).toList();
        assertThat(steps).containsExactly("ATTACK:EXIT", "BLOCK:ENTER", "BLOCK:EXIT", "DAMAGE:ENTER", "DAMAGE:EXIT");
        assertThat(room.getTimeline().get(room.getTimeline().size() - 1).getType()).isEqualTo(GameEvent.ATTACK_ENDED);
    }

    @Test
    void anAttackerDestroyedInTheClashHasNoEndOfCombat() {
        field(host, creature("rho", "Rhogler", 4, 3), FRONT);
        CardSlot beast = creature("b3", "Bestia 3", 3, 8);
        beast.getClasses().add("bestia");
        host.getTributeZone().add(beast);
        field(guest, creature("big", "Grande", 1, 9), FRONT);
        EffectEngine.attackByPlayer(room, "host", "rho", null);
        EffectEngine.answer(room, "guest", room.getPrompt().getId(), List.of("big"), List.of());
        // Rhogler lost the clash: the combat ends there and "Al Final Del Ataque" does not trigger
        assertThat(host.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("rho");
        assertThat(room.getTimeline()).extracting(GameEvent::getType).doesNotContain(GameEvent.ATTACK_ENDED);
        assertThat(room.isEngineBusy()).isFalse();
    }

    @Test
    void theRetaguardiaMovesUpAllAtOnce() {
        CardSlot a = field(host, creature("a", "A", 1, 3), BACK);
        CardSlot b = field(host, creature("b", "B", 1, 3), BACK);
        EffectEngine.offerAdvance(room, "host", true);
        Prompt advance = room.getPrompt();
        assertThat(advance.getPurpose()).isEqualTo("ADVANCE");
        assertThat(advance.getMax()).isEqualTo(2);
        EffectEngine.answer(room, "host", advance.getId(), List.of("a", "b"), List.of());
        assertThat(EffectEngine.inFrontLine(a)).isTrue();
        assertThat(EffectEngine.inFrontLine(b)).isTrue();
        // Nothing left behind: no question
        EffectEngine.offerAdvance(room, "host", false);
        assertThat(room.getPrompt()).isNull();
    }

    @Test
    void theDefenderBlocksAndTheWeakerCreatureIsDestroyed() {
        field(host, creature("a", "Atacante", 1, 5), FRONT);
        CardSlot damon = field(guest, creature("damon", "Damon", 1, 3), FRONT);

        EffectEngine.attackByPlayer(room, "host", "a", null);
        Prompt block = room.getPrompt();
        assertThat(block.getPlayerId()).isEqualTo("guest");
        assertThat(block.getPurpose()).isEqualTo("BLOCK");
        EffectEngine.answer(room, "guest", block.getId(), List.of("damon"), List.of());

        assertThat(guest.getDiscardPile()).containsExactly(damon);
        GameEvent destroyed = room.getTimeline().stream().filter(e -> GameEvent.DESTROY.equals(e.getType())).findFirst().orElseThrow();
        assertThat(destroyed.getData()).containsEntry("cause", EffectEngine.CAUSE_BATTLE).containsEntry("byCard", "a");
        // Damon's "En Destrucción" resolves for the guest: roba 3, tributa 2
        assertThat(room.getPrompt().getPlayerId()).isEqualTo("guest");
        assertThat(guest.getHand()).hasSize(3);
        assertThat(guest.getLifeStack()).hasSize(3);
    }

    @Test
    void equalStrengthDestroysBoth() {
        field(host, creature("a", "Atacante", 1, 4), FRONT);
        field(guest, creature("b", "Bloqueador", 1, 4), FRONT);
        EffectEngine.attackByPlayer(room, "host", "a", null);
        EffectEngine.answer(room, "guest", room.getPrompt().getId(), List.of("b"), List.of());
        assertThat(host.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("a");
        assertThat(guest.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("b");
        assertThat(room.isEngineBusy()).isFalse();
    }

    @Test
    void anAttackedCreatureThatIsNotBlockedBecomesTheBlocker() {
        field(host, creature("a", "Atacante", 1, 6), FRONT);
        CardSlot target = field(guest, creature("t", "Objetivo", 1, 3), FRONT);
        target.setTapped(true);
        EffectEngine.attackByPlayer(room, "host", "a", "t");
        assertThat(room.getPrompt()).isNull();
        assertThat(guest.getDiscardPile()).containsExactly(target);
        assertThat(guest.getLifeStack()).hasSize(3);
    }

    @Test
    void imbloqueableSkipsTheBlockAndLetalidadHitsAgain() {
        CardSlot bajmut = field(host, creature("baj", "Bajmut Pútrido", 1, 3), FRONT);
        bajmut.getKeywords().add("Imbloqueable");
        bajmut.getKeywords().add("Letalidad 1");
        field(guest, creature("b", "Bloqueador", 1, 9), FRONT);
        EffectEngine.attackByPlayer(room, "host", "baj", null);
        assertThat(room.getPrompt()).isNull();
        assertThat(guest.getLifeStack()).hasSize(1);
    }

    @Test
    void provocarForcesTheChosenEnemyToBlock() {
        CardSlot buratan = field(host, creature("bur", "Buratán", 1, 4), FRONT);
        buratan.getKeywords().add("Provocar");
        field(guest, creature("small", "Pequeña", 1, 2), FRONT);
        field(guest, creature("big", "Grande", 1, 9), FRONT);

        EffectEngine.attackByPlayer(room, "host", "bur", null);
        Prompt provoke = room.getPrompt();
        assertThat(provoke.getPlayerId()).isEqualTo("host");
        assertThat(provoke.getPurpose()).isEqualTo("PROVOKE");
        EffectEngine.answer(room, "host", provoke.getId(), List.of("small"), List.of());

        // No block choice for the defender: the provoked creature blocks and loses
        assertThat(guest.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("small");
        assertThat(room.isEngineBusy()).isFalse();
    }

    @Test
    void lastLifeLosesTheGame() {
        guest.setLifeStack(cards("life", 1));
        guest.getLifeStack().get(0).setStrength(1);
        field(host, creature("a", "Atacante", 1, 5), FRONT);
        EffectEngine.attackByPlayer(room, "host", "a", null);
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.GAME_LOST);
    }

    // ------------------------------------------------------------------ "Al Atacar" and "Al Final Del Ataque"

    @Test
    void zhaglizerDestroysALevel2EnemyWhenItAttacks() {
        field(host, creature("zha", "Zhaglizer", 3, 8), FRONT);
        field(guest, creature("l2", "Nivel 2", 2, 5), FRONT);
        field(guest, creature("l3", "Nivel 3", 3, 5), FRONT);

        EffectEngine.attackByPlayer(room, "host", "zha", null);
        Prompt destroy = room.getPrompt();
        assertThat(destroy.getValidIds()).containsExactly("l2");
        EffectEngine.answer(room, "host", destroy.getId(), List.of("l2"), List.of());

        // Then the block step: only the level 3 is left
        Prompt block = room.getPrompt();
        assertThat(block.getPurpose()).isEqualTo("BLOCK");
        assertThat(block.getValidIds()).containsExactly("l3");
    }

    @Test
    void zhauzSacrificesAnAllyToDestroyAndReactivatesOncePerTurn() {
        CardSlot zhauz = field(host, creature("zhz", "Zhauz", 4, 11), FRONT);
        field(host, creature("ally", "Aliado", 1, 2), BACK);
        field(guest, creature("l3", "Nivel 3", 3, 5), FRONT);

        EffectEngine.attackByPlayer(room, "host", "zhz", null);
        assertThat(zhauz.isTapped()).isTrue();
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of("zhz"), List.of());
        Prompt sacrifice = room.getPrompt();
        assertThat(sacrifice.getChosenLabel()).isEqualTo("Sacrificar");
        EffectEngine.answer(room, "host", sacrifice.getId(), List.of("ally"), List.of());
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of("l3"), List.of());

        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("ally");
        assertThat(guest.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("l3");
        // "Turno Aliado: si algún aliado es destruido, reactiva esta carta"
        assertThat(zhauz.isTapped()).isFalse();
        assertThat(guest.getLifeStack()).hasSize(2);
        assertThat(room.isEngineBusy()).isFalse();
    }

    @Test
    void rhoglerSacrificesItselfAtTheEndOfTheAttackToSummonALevel3Beast() {
        field(host, creature("rho", "Rhogler", 4, 11), FRONT);
        CardSlot beast = creature("b3", "Bestia 3", 3, 8);
        beast.setCost(3);
        beast.getClasses().add("bestia");
        host.getTributeZone().add(beast);

        EffectEngine.attackByPlayer(room, "host", "rho", null);
        Prompt confirm = room.getPrompt();
        assertThat(confirm.getPurpose()).isEqualTo("OPTIONAL");
        assertThat(room.getCombat()).isNull();
        EffectEngine.answer(room, "host", confirm.getId(), List.of("rho"), List.of());
        int gems = EffectEngine.gemsOf(room, "host");
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of("b3"), List.of());

        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("rho");
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).containsExactly("b3");
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems);
    }

    @Test
    void zohxRitualSummonsFromTheTributosWithTenBeasts() {
        CardSlot zohx = field(host, creature("zox", "Zohx", 2, 6), FRONT);
        zohx.getClasses().add("bestia");
        for (int i = 0; i < 10; i++) {
            CardSlot b = creature("b" + i, "Bestia " + i, 1, 1);
            b.getClasses().add("bestia");
            host.getTributeZone().add(b);
        }
        CardSlot hong = creature("hong", "Hong", 3, 8);
        hong.getClasses().add("bestia");
        hong.setSpecialSummonKind("ritual");
        hong.setSpecialCost(2);
        hong.setRequirement("criatura nivel 2.");
        host.getTributeZone().add(hong);

        EffectEngine.attackByPlayer(room, "host", "zox", null);
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of("zox"), List.of());
        Prompt target = room.getPrompt();
        assertThat(target.getValidIds()).containsExactly("hong");
        int gems = EffectEngine.gemsOf(room, "host");
        EffectEngine.answer(room, "host", target.getId(), List.of("hong"), List.of());

        // Zohx is the summoner: sacrificed, Hong takes its place (Línea frontal, exhausted), special cost 2 - 1
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).containsExactly("hong");
        assertThat(hong.getY()).isEqualTo(FRONT);
        assertThat(hong.isTapped()).isTrue();
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).contains("zox");
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems - 1);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("hd1");
    }

    // ------------------------------------------------------------------ Passive triggers and limits

    @Test
    void mawserDestroysAgainAfterWinningAClashOncePerTurn() {
        field(host, creature("maw", "Mawser", 5, 14), FRONT);
        field(guest, creature("b", "Bloqueador", 2, 5), FRONT);
        field(guest, creature("l3", "Nivel 3", 3, 5), BACK);
        field(guest, creature("l3f", "Nivel 3 frontal", 3, 5), FRONT);

        EffectEngine.attackByPlayer(room, "host", "maw", null);
        EffectEngine.answer(room, "guest", room.getPrompt().getId(), List.of("b"), List.of());

        // Mawser destroyed the blocker: mandatory, only the Línea frontal
        Prompt again = room.getPrompt();
        assertThat(again.getPlayerId()).isEqualTo("host");
        assertThat(again.getValidIds()).containsExactly("l3f");
        EffectEngine.answer(room, "host", again.getId(), List.of("l3f"), List.of());
        assertThat(guest.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("b", "l3f");
        // Its own effect destroyed an enemy too, but it is once per turn
        assertThat(room.isEngineBusy()).isFalse();
    }

    @Test
    void hongMayDestroyALevel1EnemyAfterYouDestroyOne() {
        field(host, creature("hong", "Hong", 3, 8), FRONT);
        field(guest, creature("b", "Bloqueador", 2, 5), FRONT);
        field(guest, creature("l1", "Nivel 1", 1, 2), FRONT);

        EffectEngine.attackByPlayer(room, "host", "hong", null);
        EffectEngine.answer(room, "guest", room.getPrompt().getId(), List.of("b"), List.of());
        Prompt confirm = room.getPrompt();
        assertThat(confirm.getPurpose()).isEqualTo("OPTIONAL");
        EffectEngine.answer(room, "host", confirm.getId(), List.of("hong"), List.of());
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of("l1"), List.of());
        assertThat(guest.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("b", "l1");
        // Its own destruction does not trigger it again this turn
        assertThat(room.isEngineBusy()).isFalse();
    }

    @Test
    void cuevaDeBestiasTapsToGainAGemAndDraw() {
        CardSlot cueva = field(host, card("cue", "Cueva De Bestias", "structure", 2, null), BACK);
        field(host, creature("v", "Violeta", 1, 3), FRONT);
        host.getHand().add(creature("h1", "Mano", 1, 1));
        int gems = EffectEngine.gemsOf(room, "host");

        EffectEngine.sacrificeByPlayer(room, "host", "v");
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of("cue"), List.of());

        assertThat(cueva.isTapped()).isTrue();
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems + 1);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("h1", "hd1");
        assertThat(room.getPrompt().getChosenLabel()).isEqualTo("Tributar");
    }

    @Test
    void alimentoIsPlayedFreeFromTheHandAndRitualSummons() {
        CardSlot alimento = card("ali", "Alimento De Las Sombras", "utility", 1, 1);
        guest.getHand().add(alimento);
        field(guest, creature("g2", "Nivel 2", 2, 5), FRONT);
        field(guest, creature("g1", "Nivel 1", 1, 3), FRONT);
        CardSlot zhaglizer = creature("zha", "Zhaglizer", 3, 8);
        zhaglizer.setSpecialSummonKind("ritual");
        zhaglizer.setSpecialCost(2);
        zhaglizer.setRequirement("criatura nivel 2.");
        guest.getTributeZone().add(zhaglizer);
        room.setSharedCounter(-2); // guest has 2 gems

        // The host destroys a guest creature
        EffectEngine.destroyByPlayer(room, "host", "guest", "g1");
        Prompt confirm = room.getPrompt();
        assertThat(confirm.getPlayerId()).isEqualTo("guest");
        EffectEngine.answer(room, "guest", confirm.getId(), List.of("ali"), List.of());
        // "En Juego": un aliado invoca por Ritual
        EffectEngine.answer(room, "guest", room.getPrompt().getId(), List.of("zha"), List.of());
        Prompt summoner = room.getPrompt();
        assertThat(summoner.getValidIds()).containsExactly("g2");
        EffectEngine.answer(room, "guest", summoner.getId(), List.of("g2"), List.of());

        assertThat(guest.getField()).extracting(CardSlot::getInstanceId).containsExactly("zha");
        assertThat(guest.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("g2");
        // The utility goes to the Descarte once resolved
        assertThat(guest.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("g1", "ali");
    }

    @Test
    void cazaNocturnaCostsLessBelowZeroGemsAndMakesAnAllyAttackWithProvocar() {
        room.setSharedCounter(1); // host has -1 gems; the guest's Marcador (2) lets them go down to -2
        CardSlot caza = card("caza", "Caza Nocturna", "utility", 3, 1);
        host.getHand().add(caza);
        CardSlot ally = field(host, creature("a", "Aliado", 1, 5), FRONT);
        field(guest, creature("small", "Pequeña", 2, 2), FRONT);

        assertThat(CardEffects.playCost(room, "host", caza)).isZero();
        assertThat(EffectEngine.playByPlayer(room, "host", "caza", null, null)).isNull();
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of("a"), List.of());

        assertThat(ally.getTurnKeywords()).containsExactly("Provocar 3");
        assertThat(ally.isTapped()).isFalse();
        // The attack by effect starts once the utility resolves: Provocar 3 asks first
        Prompt provoke = room.getPrompt();
        assertThat(provoke.getPurpose()).isEqualTo("PROVOKE");
        assertThat(host.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("caza");
    }

    @Test
    void celeridadMayMoveTheSummonedCreatureToTheFrontLine() {
        CardSlot cazador = creature("caz", "Cazador De Bestias", 3, 9);
        cazador.setCost(3);
        cazador.getKeywords().add("Celeridad");
        host.getHand().add(cazador);
        EffectEngine.normalSummon(room, "host", "caz", 50.0, 70.0);
        // Both trigger: its own effect (mandatory) is activated first, then Celeridad (optional) asks
        Prompt confirm = room.getPrompt();
        assertThat(confirm.getPurpose()).isEqualTo("OPTIONAL");
        assertThat(confirm.getCardLabels()).containsValue("Celeridad");
        EffectEngine.answer(room, "host", confirm.getId(), List.of("caz"), List.of());
        // Celeridad resolves first (last in the chain)
        assertThat(EffectEngine.inFrontLine(cazador)).isTrue();
        assertThat(room.getPrompt().getChosenLabel()).isEqualTo("Tributar");
        assertThat(host.getHand()).hasSize(3);
    }

    // ------------------------------------------------------------------ Helpers

    private static CardSlot field(PlayerState p, CardSlot c, double y) {
        c.setX(50.0);
        c.setY(y);
        c.setFaceDown(false);
        p.getField().add(c);
        return c;
    }

    private static List<CardSlot> cards(String prefix, int n) {
        List<CardSlot> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) list.add(creature(prefix + i, "Carta " + i, 1, 9));
        return list;
    }

    private static CardSlot creature(String id, String name, int level, int strength) {
        CardSlot c = card(id, name, "creature", level, 1);
        c.setStrength(strength);
        return c;
    }

    private static CardSlot card(String id, String name, String type, int level, Integer cost) {
        CardSlot c = new CardSlot();
        c.setInstanceId(id);
        c.setCardId("file-" + id);
        c.setCardName(name);
        c.setCardType(type);
        c.setCost(cost);
        c.setLevel(level);
        c.setColors(new ArrayList<>(List.of("P")));
        c.setFaceDown(true);
        return c;
    }
}
