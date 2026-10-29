package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.simulator.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.*;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** ST1 (Entes) with its real metadata: Materialización, materials as costs, strength reduction and its triggers. */
class St1IntegrationTest {

    private static final double FRONT = 20.0;

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final RoomService service = new RoomService();
    private Map<String, Card> cards;
    private GameRoom room;
    private PlayerState host;
    private PlayerState guest;
    private int ids;

    @BeforeEach
    void setUp() throws Exception {
        cards = new HashMap<>();
        for (String file : List.of("/simulator/st1-cards.json", "/simulator/st3-cards.json")) {
            try (InputStream in = getClass().getResourceAsStream(file)) {
                List<Card> list = json.readValue(in, new TypeReference<>() {});
                list.forEach(c -> cards.put(c.getCardName(), c));
            }
        }
        room = service.createRoom();
        // These tests chain a lone mandatory effect straight away (its confirmation is tested on its own)
        room.setConfirmMandatory(false);
        host = new PlayerState("host", "Ana");
        guest = new PlayerState("guest", "Beto");
        room.getPlayers().put("host", host);
        room.getPlayers().put("guest", guest);
        room.setStatus(GameRoom.RoomStatus.IN_PROGRESS);
        room.setCurrentTurnUserId("host");
        room.setCurrentPhase("MAIN");
        room.setSharedCounter(-6);
        host.setResourceCount(5);
        guest.setResourceCount(2);
        for (int i = 0; i < 10; i++) {
            host.getDeck().add(slot("Fhalid"));
            guest.getDeck().add(slot("Damon"));
        }
        for (int i = 0; i < 3; i++) {
            CardSlot life = slot("Damon");
            life.setStrength(1);
            guest.getLifeStack().add(life);
        }
    }

    @Test
    void bertalumMaterializedReducesAnEnemyToZeroAndTheTributosReact() {
        // Bertalum by Materialización: a level 2 summoner and a level 2 material
        CardSlot summoner = onField(host, slot("Fhalid"), FRONT);
        CardSlot material = slot("Zula");
        host.getTributeZone().add(material);
        CardSlot falis = slot("Falis");
        host.getTributeZone().add(falis);
        CardSlot bertalum = slot("Bertalum");
        host.getHand().add(bertalum);
        CardSlot damon = onField(guest, slot("Damon"), FRONT);

        assertThat(Summons.specialByPlayer(room, "host", bertalum.getInstanceId(), summoner.getInstanceId())).isNull();
        Answers.answer(room, "host", room.getPrompt().getId(), List.of(material.getInstanceId()), List.of());
        assertThat(bertalum.getMaterials()).containsExactly(summoner, material);

        // Its "En Invocación": tribute both materials and reduce Damon (3) by 4: destroyed
        Prompt offer = room.getPrompt();
        assertThat(offer.getPurpose()).isEqualTo("OPTIONAL");
        Answers.answer(room, "host", offer.getId(), List.of(bertalum.getInstanceId()), List.of());
        Answers.answer(room, "host", room.getPrompt().getId(), room.getPrompt().getValidIds(), List.of());
        Answers.answer(room, "host", room.getPrompt().getId(), List.of(damon.getInstanceId()), List.of());
        assertThat(guest.getDiscardPile()).contains(damon);
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.STRENGTH_REDUCED);

        // Falis, in the Tributos, may now be summoned for 2 less
        boolean offered = false;
        for (int i = 0; i < 6 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            if ("host".equals(p.getPlayerId()) && "OPTIONAL".equals(p.getPurpose()) && p.getValidIds().contains(falis.getInstanceId())) {
                offered = true;
                Answers.answer(room, "host", p.getId(), List.of(falis.getInstanceId()), List.of());
                break;
            }
            List<String> pick = p.getMin() > 0 ? p.getValidIds().subList(0, p.getMin()) : List.of();
            Answers.answer(room, p.getPlayerId(), p.getId(), pick, List.of());
        }
        assertThat(offered).isTrue();
        assertThat(host.getField()).contains(falis);
    }

    @Test
    void jhavDrawsWhenItTributesAnEnte() {
        CardSlot jhav = slot("Jhav");
        host.getHand().add(jhav);
        host.getHand().add(slot("Eisten"));
        host.getTributeZone().add(slot("Flarix"));
        assertThat(Summons.normalSummon(room, "host", jhav.getInstanceId(), 50.0, 75.0)).isNull();
        // Take up to 1 Azul (Flarix), tribute 1 (an Ente) and draw 1
        Answers.answer(room, "host", room.getPrompt().getId(), room.getPrompt().getValidIds(), List.of());
        int hand = host.getHand().size();
        Prompt tribute = room.getPrompt();
        Answers.answer(room, "host", tribute.getId(), List.of(tribute.getValidIds().get(0)), List.of());
        assertThat(host.getHand()).hasSize(hand);
    }

    @Test
    void composicionAdimensionalStaysExhaustedOnTheField() {
        CardSlot composicion = slot("Composición Adimensional");
        host.getHand().add(composicion);
        host.getHand().add(slot("Eisten"));
        host.getHand().add(slot("Jhav"));
        assertThat(Summons.playByPlayer(room, "host", composicion.getInstanceId(), 50.0, 75.0)).isNull();
        Prompt tribute = room.getPrompt();
        Answers.answer(room, "host", tribute.getId(), tribute.getValidIds(), List.of());
        assertThat(host.getHand()).hasSize(2);
        assertThat(host.getField()).contains(composicion);
        assertThat(composicion.isTapped()).isTrue();
    }

    @Test
    void mathausActivatesItsSlowEffectWithTwoMaterials() {
        CardSlot mathaus = onField(host, slot("Mathaus"), FRONT);
        mathaus.getMaterials().add(slot("Eisten"));
        mathaus.getMaterials().add(slot("Jhav"));
        CardSlot enemy = onField(guest, slot("Zhaglizer"), FRONT);
        assertThat(Chain.activatable(room, "host")).extracting(Chain.Activatable::scriptId).contains("mathaus:turno-aliado");
        assertThat(Chain.activateByPlayer(room, "host", mathaus.getInstanceId(), null)).isNull();
        Answers.answer(room, "host", room.getPrompt().getId(), room.getPrompt().getValidIds(), List.of());
        Answers.answer(room, "host", room.getPrompt().getId(), List.of(enemy.getInstanceId()), List.of());
        // Until the end of the opponent's turn
        assertThat(enemy.getLaterStrength()).isEqualTo(-3);
        assertThat(mathaus.getMaterials()).isEmpty();
    }

    @Test
    void composicionFromTheFieldBecomesAMaterialSoAnAllyMaterializes() {
        CardSlot composicion = onField(host, slot("Composición Adimensional"), 80.0);
        CardSlot eisten = onField(host, slot("Eisten"), FRONT);
        // Exhausted (as it stays after being played) it cannot be used
        composicion.setTapped(true);
        assertThat(Chain.activatable(room, "host")).isEmpty();
        composicion.setTapped(false);
        CardSlot flarix = slot("Flarix");
        host.getHand().add(flarix);
        CardSlot jhav = slot("Jhav");
        host.getTributeZone().add(jhav);
        assertThat(Chain.activatable(room, "host")).extracting(Chain.Activatable::scriptId)
                .contains("composicion-adimensional:turno-aliado");
        int gems = EffectEngine.gemsOf(room, "host");
        assertThat(Chain.activateByPlayer(room, "host", composicion.getInstanceId(), null)).isNull();
        Answers.answer(room, "host", room.getPrompt().getId(), List.of(eisten.getInstanceId()), List.of());
        Answers.answer(room, "host", room.getPrompt().getId(), List.of(flarix.getInstanceId()), List.of());
        Prompt material = room.getPrompt();
        assertThat(material.getChosenLabel()).isEqualTo("Material");
        Answers.answer(room, "host", material.getId(), List.of(jhav.getInstanceId()), List.of());
        assertThat(host.getField()).contains(flarix).doesNotContain(eisten, composicion);
        assertThat(flarix.getMaterials()).contains(eisten, jhav, composicion);
        // Special cost 1, minus 1
        assertThat(EffectEngine.gemsOf(room, "host")).isEqualTo(gems);
    }

    @Test
    void galafendraxMaterializedPlacesALevel4EnteOfItsMaterialsInTheFrontLine() {
        CardSlot mathaus = onField(host, slot("Mathaus"), FRONT);
        CardSlot falanx = slot("Falanx");
        host.getTributeZone().add(falanx);
        CardSlot galafendrax = slot("Galafendrax El Transmutador");
        host.getHand().add(galafendrax);

        assertThat(Summons.specialByPlayer(room, "host", galafendrax.getInstanceId(), mathaus.getInstanceId())).isNull();
        Answers.answer(room, "host", room.getPrompt().getId(), List.of(falanx.getInstanceId()), List.of());
        assertThat(galafendrax.getMaterials()).containsExactly(mathaus, falanx);

        // Its "En Invocación" (mandatory): up to 1 level 4 ente of its materials to the Línea frontal, with the rest
        Prompt pick = room.getPrompt();
        assertThat(pick).isNotNull();
        assertThat(pick.getValidIds()).containsExactlyInAnyOrder(mathaus.getInstanceId(), falanx.getInstanceId());
        Answers.answer(room, "host", pick.getId(), List.of(mathaus.getInstanceId()), List.of());
        assertThat(host.getField()).contains(mathaus);
        assertThat(EffectEngine.inFrontLine(mathaus)).isTrue();
        assertThat(mathaus.getMaterials()).containsExactly(falanx);
        assertThat(galafendrax.getMaterials()).isEmpty();
    }

    @Test
    void mathausReducesAnEnemyBy3UntilTheEndOfTheOpponentsTurn() {
        CardSlot mathaus = onField(host, slot("Mathaus"), FRONT);
        mathaus.getMaterials().add(slot("Jhav"));
        mathaus.getMaterials().add(slot("Mihel"));
        CardSlot damon = onField(guest, slot("Damon"), FRONT);
        damon.setStrength(7);
        assertThat(Chain.activateByPlayer(room, "host", mathaus.getInstanceId(), null)).isNull();
        // Its cost: 2 materials, then the enemy
        while (room.getPrompt() != null) {
            Prompt p = room.getPrompt();
            List<String> picks = p.getValidIds().contains(damon.getInstanceId()) ? List.of(damon.getInstanceId())
                    : p.getValidIds().subList(0, Math.max(p.getMin(), Math.min(p.getMax(), p.getValidIds().size())));
            assertThat(Answers.answer(room, "host", p.getId(), picks, List.of())).isNull();
        }
        assertThat(EffectEngine.strength(damon)).isEqualTo(4);
        assertThat(damon.getLaterStrength()).isEqualTo(-3);
    }

    @Test
    void falanxIsMaterializedFromTheTributosWithAlliesAddingUpTo6() {
        CardSlot falis = onField(host, slot("Falis"), FRONT);
        CardSlot fhalid = onField(host, slot("Fhalid"), FRONT);
        CardSlot jhav = onField(host, slot("Jhav"), FRONT);
        CardSlot mihel = onField(host, slot("Mihel"), FRONT);
        CardSlot falanx = slot("Falanx");
        host.getTributeZone().add(falanx);
        room.setSharedCounter(-10);

        // "En Tributos": as if it were in the hand for special summons
        assertThat(Summons.specialSummonable(room, "host")).contains(falanx.getInstanceId());
        assertThat(Summons.specialByPlayer(room, "host", falanx.getInstanceId(), falis.getInstanceId())).isNull();
        Prompt materials = room.getPrompt();
        assertThat(materials.getValidIds()).containsExactlyInAnyOrder(fhalid.getInstanceId(), jhav.getInstanceId(), mihel.getInstanceId());
        // 3 + 2 + 1 + 1 = 7: not exactly 6
        assertThat(Answers.answer(room, "host", materials.getId(),
                List.of(fhalid.getInstanceId(), jhav.getInstanceId(), mihel.getInstanceId()), List.of())).contains("exactamente 6");
        assertThat(Answers.answer(room, "host", materials.getId(), List.of(fhalid.getInstanceId(), jhav.getInstanceId()), List.of())).isNull();
        assertThat(host.getField()).contains(falanx, mihel).doesNotContain(falis, fhalid, jhav);
        assertThat(host.getTributeZone()).doesNotContain(falanx);
        assertThat(falanx.getMaterials()).containsExactly(falis, fhalid, jhav);
    }

    @Test
    void falanxTriggersWhenItIsExhaustedToAttack() {
        CardSlot falanx = onField(host, slot("Falanx"), FRONT);
        falanx.getMaterials().add(slot("Jhav"));
        falanx.getMaterials().add(slot("Mihel"));
        CardSlot damon = onField(guest, slot("Damon"), FRONT);
        damon.setStrength(7);
        CombatRules.attackByPlayer(room, "host", falanx.getInstanceId(), null);
        Prompt offer = room.getPrompt();
        assertThat(offer.getPurpose()).isEqualTo("OPTIONAL");
        assertThat(offer.getEffects()).extracting(Prompt.OfferedEffect::label).contains("Todos Los Turnos");
        Answers.answer(room, "host", offer.getId(), List.of(falanx.getInstanceId()), List.of());
        while (room.getPrompt() != null && room.getPrompt().getEffectId() != null) {
            Prompt p = room.getPrompt();
            List<String> picks = p.getValidIds().contains(damon.getInstanceId()) ? List.of(damon.getInstanceId())
                    : p.getValidIds().subList(0, Math.max(p.getMin(), Math.min(p.getMax(), p.getValidIds().size())));
            assertThat(Answers.answer(room, "host", p.getId(), picks, List.of())).isNull();
        }
        assertThat(EffectEngine.strength(damon)).isEqualTo(3);
    }

    @Test
    void composicionAdimensionalOnlyLetsAnAllyThatMeetsTheRequerimientoSummon() {
        CardSlot composicion = onField(host, slot("Composición Adimensional"), 75.0);
        CardSlot falis = onField(host, slot("Falis"), FRONT);
        CardSlot sahiel = slot("Sahiel");
        host.getHand().add(sahiel);
        host.getTributeZone().add(slot("Zula"));
        // Sahiel asks for a level 2 summoner: Falis (level 3) cannot, so nothing can be summoned
        assertThat(Chain.activatable(room, "host")).extracting(Chain.Activatable::scriptId)
                .doesNotContain("composicion-adimensional:turno-aliado");
        CardSlot fhalid = onField(host, slot("Fhalid"), FRONT);
        assertThat(Chain.activateByPlayer(room, "host", composicion.getInstanceId(), null)).isNull();
        assertThat(room.getPrompt().getValidIds()).containsExactly(fhalid.getInstanceId());
    }

    @Test
    void mihelOnTheFieldJoinsAnAllyAsAMaterialToReduceTheEnemy2More() {
        CardSlot mathaus = onField(host, slot("Mathaus"), FRONT);
        mathaus.getMaterials().add(slot("Jhav"));
        mathaus.getMaterials().add(slot("Eisten"));
        // In the Retaguardia it works too
        CardSlot mihel = onField(host, slot("Mihel"), 75.0);
        CardSlot damon = onField(guest, slot("Damon"), FRONT);
        damon.setStrength(7);
        assertThat(Chain.activateByPlayer(room, "host", mathaus.getInstanceId(), null)).isNull();
        boolean offered = false;
        while (room.getPrompt() != null) {
            Prompt p = room.getPrompt();
            if ("OPTIONAL".equals(p.getPurpose()) && p.getValidIds().contains(mihel.getInstanceId())) {
                offered = true;
                Answers.answer(room, "host", p.getId(), List.of(mihel.getInstanceId()), List.of());
                // "a un aliado": another one
                assertThat(room.getPrompt().getValidIds()).containsExactly(mathaus.getInstanceId());
                continue;
            }
            List<String> picks = p.getValidIds().contains(damon.getInstanceId()) ? List.of(damon.getInstanceId())
                    : p.getValidIds().subList(0, Math.max(p.getMin(), Math.min(p.getMax(), p.getValidIds().size())));
            assertThat(Answers.answer(room, p.getPlayerId(), p.getId(), picks, List.of())).isNull();
        }
        assertThat(offered).isTrue();
        assertThat(host.getField()).doesNotContain(mihel);
        assertThat(mathaus.getMaterials()).contains(mihel);
        // 7 - 3 - 2, for as long as Mathaus's reduction lasts
        assertThat(EffectEngine.strength(damon)).isEqualTo(2);
        assertThat(damon.getLaterStrength()).isEqualTo(-5);
        // Its details show each reduction with its source
        assertThat(damon.getFloating()).extracting(FloatingEffect::source, FloatingEffect::strength)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Mathaus", -3), org.assertj.core.groups.Tuple.tuple("Mihel", -2));
    }

    @Test
    void pasoInterdimensionalSummonsAnEnteOfTheSameLevelAndTheAllyBecomesItsMaterial() {
        CardSlot fhalid = onField(host, slot("Fhalid"), FRONT);
        CardSlot eisten = slot("Eisten");
        fhalid.getMaterials().add(eisten);
        CardSlot zula = slot("Zula");
        host.getTributeZone().add(zula);
        // Same name: not valid
        host.getTributeZone().add(slot("Fhalid"));
        CardSlot paso = slot("Paso Interdimensional");
        host.getHand().add(paso);

        assertThat(Summons.playByPlayer(room, "host", paso.getInstanceId(), 50.0, 75.0)).isNull();
        Prompt ally = room.getPrompt();
        assertThat(ally.getValidIds()).containsExactly(fhalid.getInstanceId());
        Answers.answer(room, "host", ally.getId(), List.of(fhalid.getInstanceId()), List.of());
        Prompt summon = room.getPrompt();
        assertThat(summon.getValidIds()).containsExactly(zula.getInstanceId());
        Answers.answer(room, "host", summon.getId(), List.of(zula.getInstanceId()), List.of());

        assertThat(host.getField()).contains(zula).doesNotContain(fhalid, paso);
        assertThat(EffectEngine.inFrontLine(zula)).isTrue();
        assertThat(zula.getMaterials()).containsExactly(fhalid, eisten);
        assertThat(host.getDiscardPile()).contains(paso);
    }

    @Test
    void flureteusReactsToAnEffectSummonWithALevel2EnteFromTheTributosAndJoinsAnAlly() {
        CardSlot flureteus = onField(host, slot("Flureteus"), 75.0);
        CardSlot fhalid = onField(host, slot("Fhalid"), FRONT);
        CardSlot zula = slot("Zula");
        host.getTributeZone().add(zula);
        CardSlot otherFhalid = slot("Fhalid");
        host.getTributeZone().add(otherFhalid);
        CardSlot paso = slot("Paso Interdimensional");
        host.getHand().add(paso);

        assertThat(Summons.playByPlayer(room, "host", paso.getInstanceId(), 50.0, 75.0)).isNull();
        boolean offered = false;
        for (int i = 0; i < 12 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            List<String> pick;
            if ("OPTIONAL".equals(p.getPurpose()) && p.getValidIds().contains(flureteus.getInstanceId())) {
                offered = true;
                pick = List.of(flureteus.getInstanceId());
            } else if ("OPTIONAL".equals(p.getPurpose())) {
                // Zula's own "En Invocación": not used
                pick = List.of();
            } else if (p.getValidIds().contains(fhalid.getInstanceId())) {
                pick = List.of(fhalid.getInstanceId());
            } else if (p.getValidIds().contains(zula.getInstanceId())) {
                pick = List.of(zula.getInstanceId());
            } else {
                pick = p.getValidIds().subList(0, Math.max(1, p.getMin()));
            }
            assertThat(Answers.answer(room, "host", p.getId(), pick, List.of())).isNull();
        }
        assertThat(offered).isTrue();
        // Paso summoned Zula (Fhalid as its material); Flureteus summoned the other Fhalid free and joined an ally
        assertThat(host.getField()).contains(zula, otherFhalid).doesNotContain(flureteus);
        assertThat(EffectEngine.inFrontLine(otherFhalid)).isTrue();
        assertThat(host.getField().stream().flatMap(c -> c.getMaterials().stream())).contains(flureteus);
    }

    @Test
    void hezPaysAndChoosesTheEnemiesOnActivationThenReducesThem() {
        CardSlot hez = onField(host, slot("Hez"), FRONT);
        hez.getMaterials().add(slot("Jhav"));
        hez.getMaterials().add(slot("Eisten"));
        CardSlot damon = onField(guest, slot("Damon"), FRONT);
        damon.setStrength(7);
        // Before ";" (materials and "elegir todos los enemigos") is its cost; after it, the reduction
        EffectScript script = CardEffects.byId("hez:inicio-del-turno").orElseThrow();
        assertThat(script.cost()).hasAtLeastOneElementOfType(EffectStep.TargetAllEnemies.class);
        assertThat(script.steps()).noneMatch(EffectStep.TargetAllEnemies.class::isInstance);

        TurnRules.turnStarted(room, "host");
        for (int i = 0; i < 10 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            List<String> pick = "OPTIONAL".equals(p.getPurpose()) ? List.of(hez.getInstanceId())
                    : p.getValidIds().subList(0, Math.max(p.getMin(), Math.min(p.getMax(), p.getValidIds().size())));
            assertThat(Answers.answer(room, p.getPlayerId(), p.getId(), pick, List.of())).isNull();
        }
        assertThat(hez.getMaterials()).isEmpty();
        assertThat(EffectEngine.strength(damon)).isEqualTo(3);
    }

    /** Mathaus reduces Damon (strength {@code strength}) by 3; every optional effect offered is used. */
    private CardSlot reduceWithMathausAcceptingAll(int strength) {
        CardSlot mathaus = onField(host, slot("Mathaus"), FRONT);
        mathaus.getMaterials().add(slot("Jhav"));
        mathaus.getMaterials().add(slot("Eisten"));
        CardSlot damon = onField(guest, slot("Damon"), FRONT);
        damon.setStrength(strength);
        assertThat(Chain.activateByPlayer(room, "host", mathaus.getInstanceId(), null)).isNull();
        for (int i = 0; i < 30 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            List<String> pick;
            if ("OPTIONAL".equals(p.getPurpose())) pick = p.getValidIds().subList(0, 1);
            else if (p.getValidIds().contains(damon.getInstanceId())) pick = List.of(damon.getInstanceId());
            else if (p.getValidIds().contains(mathaus.getInstanceId())) pick = List.of(mathaus.getInstanceId());
            else pick = p.getValidIds().subList(0, Math.max(p.getMin(), Math.min(p.getMax(), p.getValidIds().size())));
            assertThat(Answers.answer(room, p.getPlayerId(), p.getId(), pick, List.of())).isNull();
        }
        return damon;
    }

    @Test
    void twoMihelsChainAndTheSecondDoesNothingOnceTheEnemyIsGone() {
        CardSlot first = onField(host, slot("Mihel"), 75.0);
        CardSlot second = onField(host, slot("Mihel"), 75.0);
        CardSlot damon = reduceWithMathausAcceptingAll(5);
        // 5 - 3 = 2: both Mihels trigger; the first to resolve takes it to 0. The other one is still paid ("puedes A para B":
        // A can be paid when B does nothing, designer ruling 2026-10-30), so both become materials
        assertThat(guest.getDiscardPile()).contains(damon);
        long left = host.getField().stream().filter(c -> c == first || c == second).count();
        assertThat(left).isZero();
    }

    @Test
    void twoMihelsBothReduceWhileTheEnemyHasStrengthLeft() {
        onField(host, slot("Mihel"), 75.0);
        onField(host, slot("Mihel"), 75.0);
        CardSlot damon = reduceWithMathausAcceptingAll(9);
        // 9 - 3 - 2 - 2
        assertThat(EffectEngine.strength(damon)).isEqualTo(2);
        assertThat(host.getField()).extracting(CardSlot::getCardName).doesNotContain("Mihel");
    }

    @Test
    void arcXiomaGoesToTheDeckOnlyWhenItResolves() {
        CardSlot mihel = onField(host, slot("Mihel"), 75.0);
        CardSlot arc = slot("Arc-xioma");
        host.getTributeZone().add(arc);
        CardSlot damon = reduceWithMathausAcceptingAll(5);
        // Both trigger at 2; whichever resolves first destroys Damon. The other one is still paid though it does nothing
        // ("puedes A para B", designer ruling 2026-10-30)
        assertThat(guest.getDiscardPile()).contains(damon);
        assertThat(host.getDeck()).contains(arc);
        assertThat(host.getField()).doesNotContain(mihel);
    }

    // ------------------------------------------------------------------ Helpers

    private CardSlot slot(String name) {
        Card meta = cards.get(name);
        assertThat(meta).as(name).isNotNull();
        CardSlot s = new CardSlot();
        s.setInstanceId("c" + (++ids));
        s.setCardId(meta.getId());
        s.setFaceDown(true);
        ReflectionTestUtils.invokeMethod(service, "applyMeta", s, meta.getId(), meta);
        return s;
    }

}
