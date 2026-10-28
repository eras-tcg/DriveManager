package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import com.drive.drive_manager.simulator.GameRoom;
import com.drive.drive_manager.simulator.PlayerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Destruction, sacrifice, "En Destrucción" triggers and the ST3 "En Invocación" effects. */
class DestructionTest {

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
        // 5 gems for the host (first player counts negatively)
        room.setSharedCounter(-5);
        host.setResourceCount(5);
        host.setDeck(deck("hd", 6));
        guest.setDeck(deck("gd", 6));
    }

    // ------------------------------------------------------------------ Destroy and sacrifice

    @Test
    void destroyingDamonSendsItToTheDescarteAndResolvesItsEffect() {
        CardSlot damon = onField(host, card("damon", "Damon", 1, 1, "P"), 20.0);
        host.getHand().add(card("h1", "Uno", 1, 1, "P"));

        assertThat(EffectEngine.destroyByPlayer(room, "guest", "host", "damon")).isNull();

        assertThat(host.getField()).isEmpty();
        assertThat(host.getDiscardPile()).containsExactly(damon);
        GameEvent destroyed = room.getTimeline().get(0);
        assertThat(destroyed.getType()).isEqualTo(GameEvent.DESTROY);
        assertThat(destroyed.getPlayerId()).isEqualTo("guest");
        assertThat(destroyed.getData()).containsEntry("cause", EffectEngine.CAUSE_MANUAL).containsEntry("ownerId", "host");

        // Its owner resolves "roba 3 cartas. Luego: tributa 2 cartas de tu mano"
        assertThat(host.getHand()).hasSize(4);
        Prompt tribute = room.getPrompt();
        assertThat(tribute.getPlayerId()).isEqualTo("host");
        assertThat(tribute.getKind()).isEqualTo(Prompt.Kind.PICK);
        assertThat(tribute.getMin()).isEqualTo(2);
        assertThat(EffectEngine.answer(room, "host", tribute.getId(), List.of("h1", "hd1"), List.of())).isNull();
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("h1", "hd1");
        assertThat(room.isEngineBusy()).isFalse();
        assertThat(room.getTimeline()).extracting(GameEvent::getType).containsExactly(
                GameEvent.DESTROY, GameEvent.EFFECT_ACTIVATED, GameEvent.DRAW, GameEvent.DRAW, GameEvent.DRAW,
                GameEvent.TRIBUTE, GameEvent.TRIBUTE, GameEvent.EFFECT_RESOLVED);
    }

    @Test
    void sacrificeIsADestructionToTheTributos() {
        CardSlot carnhaus = onField(host, card("c1", "Carnhaus", 1, 1, "P"), 70.0);
        carnhaus.getMaterials().add(card("m1", "Material", 1, 1, "P"));

        assertThat(EffectEngine.sacrificeByPlayer(room, "guest", "c1")).contains("tus cartas");
        assertThat(EffectEngine.sacrificeByPlayer(room, "host", "c1")).isNull();

        assertThat(host.getTributeZone()).containsExactly(carnhaus);
        // Materials go to the Descarte (rule 6.4.0.5)
        assertThat(host.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("m1");
        assertThat(carnhaus.getMaterials()).isEmpty();
        GameEvent sacrificed = room.getTimeline().get(0);
        assertThat(sacrificed.getType()).isEqualTo(GameEvent.SACRIFICE);
        assertThat(sacrificed.getToZone()).isEqualTo("tributeZone");
        // "En Destrucción" triggers from the Tributos: roba 1, then tributa 1
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("hd1");
        assertThat(room.getPrompt().getMin()).isEqualTo(1);
    }

    @Test
    void strengthZeroDestroysByEffect() {
        CardSlot weak = onField(guest, card("w1", "Débil", 1, 1, "R"), 20.0);
        weak.setStrength(3);
        weak.setStrengthModifier(-3);

        EffectEngine.destroyIfNoStrength(room, "host", guest, weak);

        assertThat(guest.getDiscardPile()).containsExactly(weak);
        assertThat(weak.getStrengthModifier()).isZero();
        assertThat(room.getTimeline().get(0).getData()).containsEntry("cause", EffectEngine.CAUSE_EFFECT);
    }

    @Test
    void cannotDestroyWhileAnEffectResolves() {
        onField(host, card("damon", "Damon", 1, 1, "P"), 20.0);
        onField(host, card("other", "Otra", 1, 1, "P"), 20.0);
        EffectEngine.destroyByPlayer(room, "host", "host", "damon");
        assertThat(room.isEngineBusy()).isTrue();
        assertThat(EffectEngine.destroyByPlayer(room, "host", "host", "other")).contains("resolviéndose");
    }

    // ------------------------------------------------------------------ ST3 "En Invocación"

    @Test
    void kroklerMayDestroyAFrontLineLevel1EnemyAndItsEffectThenResolves() {
        // By Ritual onto a creature of the Línea frontal: Krokler takes its place there
        CardSlot krokler = card("kro", "Krokler", 2, 2, "P");
        krokler.setSpecialSummonKind("ritual");
        krokler.setSpecialCost(1);
        krokler.setRequirement("Criatura nivel 1.");
        host.getHand().add(krokler);
        onField(host, card("sum", "Invocador", 1, 1, "P"), 20.0);
        CardSlot enemy = onField(guest, card("ec", "Carnhaus", 1, 1, "P"), 20.0);
        onField(guest, card("lvl2", "Grande", 2, 2, "R"), 20.0);
        onField(guest, card("back", "Atrás", 1, 1, "R"), 80.0);

        assertThat(EffectEngine.ritualByPlayer(room, "host", "kro", "sum")).isNull();

        // Optional: asks first
        Prompt confirm = room.getPrompt();
        assertThat(confirm.getKind()).isEqualTo(Prompt.Kind.CONFIRM);
        assertThat(EffectEngine.answer(room, "host", confirm.getId(), List.of("kro"), List.of())).isNull();

        // Only the level 1 enemy of the Línea frontal
        Prompt target = room.getPrompt();
        assertThat(target.getCards()).extracting(CardSlot::getInstanceId).containsExactly("ec", "lvl2");
        assertThat(target.getValidIds()).containsExactly("ec");
        assertThat(EffectEngine.answer(room, "host", target.getId(), List.of("ec"), List.of())).isNull();
        assertThat(guest.getDiscardPile()).containsExactly(enemy);

        // The destroyed Carnhaus resolves its own "En Destrucción" for its owner, after Krokler's effect
        Prompt guestTribute = room.getPrompt();
        assertThat(guestTribute.getPlayerId()).isEqualTo("guest");
        assertThat(guest.getHand()).extracting(CardSlot::getInstanceId).containsExactly("gd1");
        assertThat(room.getTimeline()).extracting(GameEvent::getType).containsSubsequence(
                GameEvent.DESTROY, GameEvent.EFFECT_RESOLVED, GameEvent.EFFECT_ACTIVATED, GameEvent.DRAW);
        GameEvent destroyed = room.getTimeline().stream().filter(e -> GameEvent.DESTROY.equals(e.getType())).findFirst().orElseThrow();
        assertThat(destroyed.getEffectId()).isNotNull();
        assertThat(destroyed.getData()).containsEntry("cause", EffectEngine.CAUSE_EFFECT);
    }

    @Test
    void kroklerIsNotOfferedWithoutATarget() {
        host.getHand().add(card("kro", "Krokler", 2, 2, "P"));
        onField(guest, card("back", "Atrás", 1, 1, "R"), 80.0);
        EffectEngine.normalSummon(room, "host", "kro", 50.0, 70.0);
        assertThat(room.getPrompt()).isNull();
        assertThat(room.isEngineBusy()).isFalse();
    }

    @Test
    void kroklerInTheRetaguardiaCannotAffectEnemies() {
        host.getHand().add(card("kro", "Krokler", 2, 2, "P"));
        onField(guest, card("ec", "Carnhaus", 1, 1, "P"), 20.0);
        // A normal summon enters the Retaguardia: its effect is not even offered
        EffectEngine.normalSummon(room, "host", "kro", 50.0, 70.0);
        assertThat(room.getPrompt()).isNull();
        assertThat(guest.getField()).hasSize(1);
    }

    @Test
    void kroklerCanBeDeclined() {
        onField(host, card("kro", "Krokler", 2, 2, "P"), 20.0);
        onField(guest, card("ec", "Carnhaus", 1, 1, "P"), 20.0);
        EffectEngine.specialSummoned(room, "host", host.getField().get(0), card("x", "Nadie", 1, 1, "P"), "special", null);
        assertThat(EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of(), List.of())).isNull();
        assertThat(guest.getField()).hasSize(1);
        assertThat(room.isEngineBusy()).isFalse();
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.EFFECT_DECLINED);
    }

    @Test
    void bajmutTributesOneAndCazadorDrawsThreeThenTributesTwo() {
        host.getHand().add(card("baj", "Bajmut Pútrido", 0, 1, "P"));
        host.getHand().add(card("h1", "Uno", 1, 1, "P"));
        EffectEngine.normalSummon(room, "host", "baj", 50.0, 70.0);
        assertThat(room.getPrompt().getMin()).isEqualTo(1);
        EffectEngine.answer(room, "host", room.getPrompt().getId(), List.of("h1"), List.of());
        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("h1");

        host.getHand().add(card("caz", "Cazador De Bestias", 3, 3, "P"));
        EffectEngine.normalSummon(room, "host", "caz", 50.0, 70.0);
        assertThat(host.getHand()).hasSize(3);
        assertThat(room.getPrompt().getMin()).isEqualTo(2);
    }

    @Test
    void berkelusUntapsAndMayPayTenBeastsToAttackWithFuria() {
        CardSlot berkelus = card("ber", "Berkelus El Cazarrecompensas Del Bajomundo", 3, 5, "P");
        host.getHand().add(berkelus);
        for (int i = 0; i < 11; i++) {
            CardSlot beast = card("b" + i, "Bestia " + i, 1, 1, "P");
            beast.getClasses().add("bestia");
            host.getTributeZone().add(beast);
        }
        EffectEngine.normalSummon(room, "host", "ber", 50.0, 70.0);

        Prompt may = room.getPrompt();
        assertThat(may.getKind()).isEqualTo(Prompt.Kind.CONFIRM);
        EffectEngine.answer(room, "host", may.getId(), List.of("ber"), List.of());
        Prompt send = room.getPrompt();
        assertThat(send.getMin()).isEqualTo(10);
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add("b" + i);
        assertThat(EffectEngine.answer(room, "host", send.getId(), ten, List.of())).isNull();

        assertThat(host.getTributeZone()).extracting(CardSlot::getInstanceId).containsExactly("b10");
        assertThat(host.getDeck().subList(host.getDeck().size() - 10, host.getDeck().size()))
                .extracting(CardSlot::getInstanceId).containsExactlyElementsOf(ten);
        assertThat(berkelus.getTurnKeywords()).containsExactly("Furia 1");
        // Summoned normally it is in the Retaguardia: it cannot attack (rule 10.1.1.1)
        assertThat(room.getCombat()).isNull();
        assertThat(room.getEventLog()).anyMatch(l -> l.getText().contains("no está en la línea frontal"));
        assertThat(room.isEngineBusy()).isFalse();
    }

    @Test
    void berkelusSkipsTheChoiceWithoutTenBeasts() {
        host.getHand().add(card("ber", "Berkelus El Cazarrecompensas Del Bajomundo", 3, 5, "P"));
        EffectEngine.normalSummon(room, "host", "ber", 50.0, 70.0);
        assertThat(room.getPrompt()).isNull();
        assertThat(room.isEngineBusy()).isFalse();
    }

    // ------------------------------------------------------------------ ST3 "En Destrucción"

    @Test
    void sacrificedSlickerPaysWithItselfToSummonAVioletaCreatureFromTheTributos() {
        onField(host, card("sli", "Slicker", 2, 2, "P"), 70.0);
        host.getTributeZone().add(card("carn", "Carnhaus", 1, 1, "P"));
        host.getTributeZone().add(card("roja", "Roja", 1, 1, "R"));

        EffectEngine.sacrificeByPlayer(room, "host", "sli");
        Prompt confirm = room.getPrompt();
        assertThat(confirm.getKind()).isEqualTo(Prompt.Kind.CONFIRM);
        EffectEngine.answer(room, "host", confirm.getId(), List.of("sli"), List.of());

        // Cost paid on activation: Slicker goes from the Tributos to the Descarte
        assertThat(host.getDiscardPile()).extracting(CardSlot::getInstanceId).containsExactly("sli");
        Prompt summon = room.getPrompt();
        assertThat(summon.getValidIds()).containsExactly("carn");
        int gems = EffectEngine.gemsOf(room, "host");
        EffectEngine.answer(room, "host", summon.getId(), List.of("carn"), List.of());

        // Cost 1 reduced by 1, Retaguardia, regulatory draw, then Carnhaus' own "En Invocación"
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems);
        assertThat(host.getField()).extracting(CardSlot::getInstanceId).containsExactly("carn");
        assertThat(host.getField().get(0).getY()).isGreaterThanOrEqualTo(55.0);
        assertThat(host.getHand()).extracting(CardSlot::getInstanceId).containsExactly("hd1");
        assertThat(room.getPrompt().getKind()).isEqualTo(Prompt.Kind.TAKE_REVEALED_REST_BOTTOM);
        assertThat(room.getTimeline()).extracting(GameEvent::getType).containsSubsequence(
                GameEvent.SACRIFICE, GameEvent.EFFECT_ACTIVATED, GameEvent.SEND, GameEvent.EFFECT_SUMMON, GameEvent.DRAW,
                GameEvent.EFFECT_RESOLVED, GameEvent.EFFECT_ACTIVATED, GameEvent.LOOK_TOP);
    }

    @Test
    void destroyedSlickerCannotPayFromTheDescarte() {
        onField(host, card("sli", "Slicker", 2, 2, "P"), 20.0);
        host.getTributeZone().add(card("carn", "Carnhaus", 1, 1, "P"));
        EffectEngine.destroyByPlayer(room, "guest", "host", "sli");
        assertThat(room.getPrompt()).isNull();
        assertThat(room.isEngineBusy()).isFalse();
    }

    @Test
    void ritualSacrificesTheSummonerAndBothEffectsTrigger() {
        CardSlot summoner = card("carn", "Carnhaus", 1, 1, "P");
        CardSlot summoned = card("caz", "Cazador De Bestias", 3, 3, "P");
        host.getTributeZone().add(summoner);
        host.getField().add(summoned);
        host.getHand().add(card("h1", "Uno", 1, 1, "P"));

        EffectEngine.specialSummoned(room, "host", summoned, summoner, "ritual", "Ana invocó a Cazador");

        assertThat(room.getTimeline()).extracting(GameEvent::getType).startsWith(GameEvent.SACRIFICE, GameEvent.SPECIAL_SUMMON);
        // First the move up (declined) and the regulatory draw; only then do the effects trigger
        Prompt front = room.getPrompt();
        assertThat(front.getPurpose()).isEqualTo("SPECIAL_FRONT");
        assertThat(room.getChain()).isEmpty();
        EffectEngine.answer(room, "host", front.getId(), List.of(), List.of());
        assertThat(room.getTimeline()).extracting(GameEvent::getType).containsSubsequence(GameEvent.SPECIAL_SUMMON, GameEvent.DRAW);
        // Two mandatory effects of the same player: the player orders them
        Prompt order = room.getPrompt();
        assertThat(order.getPurpose()).isEqualTo("ORDER");
        assertThat(order.getValidIds()).containsExactlyInAnyOrder("carn", "caz");
        assertThat(order.getMin()).isEqualTo(2);
        EffectEngine.answer(room, "host", order.getId(), List.of("carn", "caz"), List.of());
        // Both are chained (Cadena 1 Carnhaus, Cadena 2 Cazador): the chain resolves the last link first
        assertThat(room.getChain()).extracting(PendingEffect::getSourceName).containsExactly("Carnhaus", "Cazador De Bestias");
        assertThat(room.getChain()).extracting(PendingEffect::getLink).containsExactly(1, 2);
    }

    // ------------------------------------------------------------------ Helpers

    private static CardSlot onField(PlayerState p, CardSlot c, double y) {
        c.setX(50.0);
        c.setY(y);
        c.setFaceDown(false);
        p.getField().add(c);
        return c;
    }

    private static List<CardSlot> deck(String prefix, int n) {
        List<CardSlot> cards = new ArrayList<>();
        for (int i = 1; i <= n; i++) cards.add(card(prefix + i, "Carta " + i, 1, 1, "R"));
        return cards;
    }

    private static CardSlot card(String id, String name, Integer cost, int level, String color) {
        CardSlot c = new CardSlot();
        c.setInstanceId(id);
        c.setCardId("file-" + id);
        c.setCardName(name);
        c.setCardType("creature");
        c.setCost(cost);
        c.setLevel(level);
        c.setStrength(3);
        c.setColors(new ArrayList<>(List.of(color)));
        c.setFaceDown(true);
        return c;
    }
}
