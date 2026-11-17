package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.PlayerState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Cards in testing T1, archetype Paladines (Enlace, 2026-11-13): one test (or more) per programmed effect, from the printed text. */
class T1PaladinesCardsTest extends CardBatchSupport {

    static final String[] BOTH = {"left", "right"};

    /** A [paladín] "Criatura Enlace" with those arrows. */
    static CardSlot pal(String id, String name, int cost, int level, String color, String... arrows) {
        CardSlot c = withClasses(card(id, name, cost, level, color), "paladín");
        c.setArrows(new ArrayList<>(List.of(arrows)));
        c.setEnlace(true);
        return c;
    }

    /** The table as set, no creature taken as one that just entered the Línea frontal. */
    private void settle() {
        EffectEngine.refreshContinuous(room);
        host.getFormationEntries().clear();
        guest.getFormationEntries().clear();
    }

    /** A formation as the table has it (the admission is LinkRulesTest's). */
    private void formation(PlayerState p, String... ids) {
        settle();
        p.getFormations().add(new ArrayList<>(List.of(ids)));
        EffectEngine.refreshContinuous(room);
    }

    private List<String> ids(List<CardSlot> cards) {
        return cards.stream().map(CardSlot::getInstanceId).toList();
    }

    /** The script id of an effect the player can activate now on that card, by the start of its id. */
    private String activatable(String instanceId, String idStart) {
        return Chain.activatable(room, "host").stream()
                .filter(a -> instanceId.equals(a.instanceId()) && a.scriptId().startsWith(idStart))
                .map(Chain.Activatable::scriptId).findFirst().orElse(null);
    }

    private void activate(String instanceId, String idStart) {
        String script = activatable(instanceId, idStart);
        assertThat(script).as("se puede activar " + idStart).isNotNull();
        assertThat(Chain.activateByPlayer(room, "host", instanceId, script)).isNull();
    }

    private CardSlot promotion(String id, String name, int level, int specialCost, String requirement) {
        CardSlot c = pal(id, name, level, level, "R", BOTH);
        c.setSpecialSummonKind("promotion");
        c.setSpecialCost(specialCost);
        c.setRequirement(requirement);
        Req.program(c);
        return c;
    }

    // ------------------------------------------------------------------ R01 Peón de las Armaduras Marfil

    @Test
    void peonTakesUpToTwoWhiteEnlaceOrPaladinCardsAndSendsTheRestToTheBottom() {
        CardSlot linkCard = card("e1", "Enlazada", 1, 1, "R");
        linkCard.setEnlace(true);
        host.getDeck().addAll(0, List.of(card("w1", "Blanca", 1, 1, "W"), card("r1", "Roja", 1, 1, "R"), linkCard,
                withClasses(card("p1", "Paladina", 1, 1, "R"), "paladín"), card("r2", "Roja Dos", 1, 1, "R")));
        inHand("h1", "Mano Uno", 1, 1, "R");
        inHand("h2", "Mano Dos", 1, 1, "R");
        summon(pal("pe", "Peón de las Armaduras Marfil", 1, 1, "R", BOTH));
        assertThat(room.getPrompt().getValidIds()).containsExactlyInAnyOrder("w1", "e1", "p1");
        answer("w1", "p1");
        answer("h1", "h2");
        assertThat(ids(host.getHand())).contains("w1", "p1").doesNotContain("h1", "h2");
        assertThat(ids(host.getTributeZone())).containsExactlyInAnyOrder("h1", "h2");
        List<CardSlot> deck = host.getDeck();
        assertThat(ids(deck.subList(deck.size() - 3, deck.size()))).containsExactlyInAnyOrder("r1", "e1", "r2");
    }

    @Test
    void peonGivesStrength3OnlyDuringItsControllersTurn() {
        onField(host, pal("pe", "Peón de las Armaduras Marfil", 1, 1, "R", BOTH));
        CardSlot r = onField(host, pal("r", "Receptor", 2, 2, "R", BOTH));
        formation(host, "pe", "r");
        assertThat(EffectEngine.strength(r)).isEqualTo(6);
        room.setCurrentTurnUserId("guest");
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(r)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ R02 Espada de la Orden

    private void discardFromTributes(CardSlot card) {
        host.getTributeZone().remove(card);
        host.getDiscardPile().add(card);
        EffectEngine.record(room, GameEvent.SEND, "host", card, "tributeZone", "discardPile", null, null, "descarta");
        Chain.advance(room);
    }

    @Test
    void espadaDiscardedFromTheTributesComesToTheFrontLineSendingFiveFromTheDiscardOncePerTurnBetweenCopies() {
        CardSlot espada = pal("es", "Espada de la Orden", 1, 1, "R", BOTH);
        espada.setFaceDown(false);
        CardSlot second = pal("es2", "Espada de la Orden", 1, 1, "R", BOTH);
        second.setFaceDown(false);
        host.getTributeZone().addAll(List.of(espada, second));
        for (int i = 0; i < 6; i++) host.getDiscardPile().add(card("d" + i, "Descartada " + i, 1, 1, "R"));
        settle();
        discardFromTributes(espada);
        accept(espada);
        answer("d0", "d1", "d2", "d3", "d4");
        assertThat(host.getField()).contains(espada);
        assertThat(EffectEngine.inFrontLine(espada)).isTrue();
        assertThat(ids(host.getDeck().subList(host.getDeck().size() - 5, host.getDeck().size()))).containsExactlyInAnyOrder("d0", "d1", "d2", "d3", "d4");
        discardFromTributes(second);
        assertThat(room.getPrompt() == null || !room.getPrompt().getValidIds().contains("es2")).isTrue();
    }

    @Test
    void espadaDrawsThreeThenTributesTwo() {
        summon(pal("es", "Espada de la Orden", 1, 1, "R", BOTH));
        assertThat(host.getHand()).hasSize(3);
        answer(room.getPrompt().getValidIds().get(0), room.getPrompt().getValidIds().get(1));
        assertThat(host.getHand()).hasSize(1);
        assertThat(host.getTributeZone()).hasSize(2);
    }

    @Test
    void espadaLinkSeesItsReceiverAscendedTributesThreeAndTakesOneFromTheTributes() {
        onField(host, pal("es", "Espada de la Orden", 1, 1, "R", BOTH));
        CardSlot r = onField(host, pal("r", "Receptor", 2, 2, "R", BOTH));
        formation(host, "es", "r");
        assertThat(r.getFormationGrants()).hasSize(1);
        host.getHand().add(promotion("asc", "Ascendido", 3, 0, "Ascenso: criatura nivel 2."));
        assertThat(SpecialSummons.specialByPlayer(room, "host", "asc", "r")).isNull();
        // The receiver went to the Tributos: its link effect resolves (3 from the top, then 1 of the Tributos to the hand)
        assertThat(room.getChain()).anyMatch(e -> e.getScriptId().startsWith("espada-de-la-orden:enlace" + Formations.VIA + "es@"));
        assertThat(host.getTributeZone()).hasSize(4);
        answer("r");
        assertThat(ids(host.getHand())).contains("r");
        assertThat(host.getTributeZone()).hasSize(3);
        // The ascended creature took its place in the formation
        assertThat(host.getFormations()).containsExactly(List.of("es", "asc"));
    }

    // ------------------------------------------------------------------ R03 Judeu la Joven Promesa

    @Test
    void judeuComesFromTheTributesToTheFrontLineDiscardingFiveOncePerTurnBetweenCopies() {
        CardSlot judeu = pal("ju", "Judeu la Joven Promesa", 2, 2, "R", BOTH);
        judeu.setFaceDown(false);
        CardSlot other = pal("ju2", "Judeu la Joven Promesa", 2, 2, "R", BOTH);
        other.setFaceDown(false);
        host.getTributeZone().addAll(List.of(judeu, other));
        for (int i = 0; i < 10; i++) host.getTributeZone().add(card("t" + i, "Tributo " + i, 1, 1, "R"));
        settle();
        activate("ju", "judeu:en-tributos");
        answer("t0", "t1", "t2", "t3", "t4");
        assertThat(host.getField()).contains(judeu);
        assertThat(EffectEngine.inFrontLine(judeu)).isTrue();
        assertThat(ids(host.getDiscardPile())).containsExactlyInAnyOrder("t0", "t1", "t2", "t3", "t4");
        assertThat(activatable("ju2", "judeu:en-tributos")).isNull();
    }

    @Test
    void judeuTributesOneFromTheHandToTakeAPaladinFromTheTributesOrDiscard() {
        host.getDiscardPile().add(withClasses(card("dp", "Paladín Descartado", 1, 1, "R"), "paladín"));
        host.getDiscardPile().add(card("dx", "Otra", 1, 1, "R"));
        inHand("x", "Pago", 1, 1, "R");
        CardSlot judeu = pal("ju", "Judeu la Joven Promesa", 2, 2, "R", BOTH);
        summon(judeu);
        accept(judeu);
        answer("x");
        assertThat(room.getPrompt().getValidIds()).containsExactly("dp");
        answer("dp");
        assertThat(ids(host.getHand())).containsExactly("dp");
        assertThat(ids(host.getTributeZone())).containsExactly("x");
    }

    @Test
    void judeuLinkDrawsTwoThenTributesTwoWhenItsReceiverAttacks() {
        onField(host, pal("ju", "Judeu la Joven Promesa", 2, 2, "R", BOTH));
        onField(host, pal("r", "Receptor", 1, 1, "R", BOTH));
        formation(host, "ju", "r");
        assertThat(CombatRules.attackByPlayer(room, "host", "r", null)).isNull();
        assertThat(host.getHand()).hasSize(2);
        answer(room.getPrompt().getValidIds().get(0), room.getPrompt().getValidIds().get(1));
        assertThat(host.getTributeZone()).hasSize(2);
        assertThat(host.getHand()).isEmpty();
    }

    // ------------------------------------------------------------------ R04 Ariel la Guía de los Puros

    @Test
    void arielComesFromTheTributesTributingTwoAllies() {
        CardSlot ariel = pal("ar", "Ariel la Guía de los Puros", 0, 2, "R", BOTH);
        ariel.setCost(null);
        ariel.setFaceDown(false);
        host.getTributeZone().add(ariel);
        onField(host, card("a1", "Aliado Uno", 1, 1, "R"), BACK);
        onField(host, card("a2", "Aliado Dos", 1, 1, "R"), BACK);
        settle();
        activate("ar", "ariel:en-tributos");
        answer("a1", "a2");
        assertThat(host.getField()).containsExactly(ariel);
        assertThat(EffectEngine.inFrontLine(ariel)).isTrue();
    }

    @Test
    void arielGoesBackToTheHandToSummonAPaladinPayingTwoLess() {
        CardSlot ariel = onField(host, pal("ar", "Ariel la Guía de los Puros", 0, 2, "R", BOTH));
        ariel.setCost(null);
        CardSlot paladin = inHand("pc", "Paladín Caro", 3, 3, "R", "paladín");
        settle();
        activate("ar", "ariel:todos-los-turnos");
        answer("pc");
        assertThat(host.getField()).contains(paladin).doesNotContain(ariel);
        assertThat(host.getHand()).contains(ariel);
        assertThat(gems()).isEqualTo(7);
    }

    @Test
    void arielLinkLetsItsReceiverExhaustAnAllyToGainEnfrentar() {
        onField(host, pal("ar", "Ariel la Guía de los Puros", 0, 2, "R", BOTH));
        CardSlot r = onField(host, pal("r", "Receptor", 1, 1, "R", BOTH));
        CardSlot other = onField(host, card("o", "Otro", 1, 1, "R"), BACK);
        formation(host, "ar", "r");
        activate("r", "ariel:enlace" + Formations.VIA + "ar@");
        assertThat(room.getPrompt().getValidIds()).doesNotContain("r").contains("o");
        answer("o");
        assertThat(other.isTapped()).isTrue();
        assertThat(EffectEngine.keywords(r)).contains("Enfrentar");
    }

    // ------------------------------------------------------------------ G01 Escuadra de Lanzas Aereas

    @Test
    void escuadraComesFromTheHandReturningAnActiveAllyAndPayingAGem() {
        CardSlot ally = onField(host, card("a", "Aliado", 1, 1, "R"));
        CardSlot escuadra = inHand("es", "Escuadra de Lanzas Aereas", 2, 2, "G", "paladín");
        settle();
        activate("es", "escuadra-lanzas-aereas:en-mano");
        answer("a");
        assertThat(host.getHand()).contains(ally);
        assertThat(host.getField()).contains(escuadra);
        assertThat(EffectEngine.inFrontLine(escuadra)).isTrue();
        assertThat(gems()).isEqualTo(7);
    }

    @Test
    void escuadraSwapsPositionsWithAnotherMemberOncePerTurnAndGivesPerforante() {
        onField(host, pal("es", "Escuadra de Lanzas Aereas", 2, 2, "G", BOTH));
        CardSlot x = onField(host, pal("x", "Compañero", 1, 1, "R", BOTH));
        formation(host, "es", "x");
        assertThat(EffectEngine.keywords(x)).contains("Perforante");
        activate("es", "escuadra-lanzas-aereas:intercambio");
        answer("x");
        assertThat(ids(Formations.ordered(host))).containsExactly("x", "es");
        assertThat(host.getFormations()).containsExactly(List.of("es", "x"));
        assertThat(activatable("es", "escuadra-lanzas-aereas:intercambio")).isNull();
        // Still pointed at (Escuadra has both arrows): it keeps Perforante
        assertThat(EffectEngine.keywords(x)).contains("Perforante");
    }

    // ------------------------------------------------------------------ G02 Nefilia la Paladín Arcanista

    @Test
    void nefiliaSummonedByAnEffectMayMoveUpToTheFrontLine() {
        CardSlot nefilia = pal("ne", "Nefilia la Paladín Arcanista", 2, 2, "G", BOTH);
        CardEffects.register("invocador de prueba", new EffectScript("invocador-prueba:en-invocacion", EffectScript.Moment.ON_SUMMON, true,
                "En Invocación", "Invoca 1 criatura de tu mano sin pagar su coste.",
                List.of(Compose.summonFromZone("hand", CardFilter.creatures().named(nefilia.getCardName()), Compose.FREE, false))));
        host.getHand().addAll(List.of(nefilia, card("iv", "Invocador De Prueba", 0, 1, "R")));
        assertThat(Summons.normalSummon(room, "host", "iv", 50.0, 75.0)).isNull();
        answer("ne");
        accept(nefilia);
        assertThat(EffectEngine.inFrontLine(nefilia)).isTrue();
    }

    @Test
    void nefiliaNormallySummonedHasNoCeleridad() {
        CardSlot nefilia = pal("ne", "Nefilia la Paladín Arcanista", 2, 2, "G", BOTH);
        summon(nefilia);
        assertThat(room.getPrompt() == null || !room.getPrompt().getValidIds().contains("ne")).isTrue();
        assertThat(EffectEngine.inFrontLine(nefilia)).isFalse();
    }

    @Test
    void nefiliaLinkReactivatesOnlyAReceiverOfLevelFourOrMore() {
        onField(host, pal("ne", "Nefilia la Paladín Arcanista", 2, 2, "G", BOTH));
        CardSlot small = onField(host, pal("s", "Pequeño", 3, 3, "R", BOTH));
        formation(host, "ne", "s");
        small.setTapped(true);
        EffectEngine.record(room, GameEvent.TAP, "host", small, null, null, null, null, null);
        Chain.advance(room);
        assertThat(small.isTapped()).isTrue();
        small.setLevel(4);
        EffectEngine.record(room, GameEvent.TAP, "host", small, null, null, null, null, null);
        Chain.advance(room);
        assertThat(small.isTapped()).isFalse();
    }

    // ------------------------------------------------------------------ R05 Paul el Paladín Hechicero

    @Test
    void paulComesFromTheTributesOnlyWithoutCreaturesOfLevelThreeOrMore() {
        CardSlot paul = pal("pa", "Paul el Paladín Hechicero", 3, 3, "R", BOTH);
        paul.setFaceDown(false);
        host.getTributeZone().add(paul);
        for (int i = 0; i < 5; i++) host.getTributeZone().add(card("t" + i, "Tributo " + i, 1, 1, "R"));
        CardSlot big = onField(host, card("b", "Grande", 3, 3, "R"), BACK);
        settle();
        assertThat(activatable("pa", "paul:en-tributos")).isNull();
        big.setLevel(2);
        activate("pa", "paul:en-tributos");
        answer("t0", "t1", "t2", "t3", "t4");
        assertThat(EffectEngine.inFrontLine(paul)).isTrue();
    }

    @Test
    void paulTributesOneToSummonAPaladinFromTheTributesPayingOneLess() {
        CardSlot target = withClasses(card("pt", "Paladín Tributado", 2, 2, "R"), "paladín");
        target.setFaceDown(false);
        host.getTributeZone().add(target);
        inHand("x", "Pago", 1, 1, "R");
        CardSlot paul = pal("pa", "Paul el Paladín Hechicero", 3, 3, "R", BOTH);
        summon(paul);
        accept(paul);
        answer("x");
        answer("pt");
        assertThat(host.getField()).contains(target);
        assertThat(gems()).isEqualTo(8 - 3 - 1);
    }

    @Test
    void paulLinkDestroysAnEnemyWeakerThanTheAttackingReceiver() {
        onField(host, pal("pa", "Paul el Paladín Hechicero", 3, 3, "R", BOTH));
        CardSlot r = onField(host, pal("r", "Receptor", 1, 1, "R", BOTH));
        CardSlot weak = onField(guest, card("w", "Débil", 1, 1, "B"));
        weak.setStrength(2);
        CardSlot strong = onField(guest, card("s", "Fuerte", 1, 1, "B"));
        strong.setStrength(5);
        formation(host, "pa", "r");
        assertThat(CombatRules.attackByPlayer(room, "host", "r", null)).isNull();
        accept(r);
        assertThat(room.getPrompt().getValidIds()).containsExactly("w");
        answer("w");
        drain();
        assertThat(guest.getField()).doesNotContain(weak).contains(strong);
    }

    // ------------------------------------------------------------------ R06 Aurora la Luz de los Puros

    @Test
    void auroraTributesAnAllyToPutAHandCardOnTopOfItsLives() {
        onField(host, card("a", "Aliado", 1, 1, "R"), BACK);
        inHand("h", "A Las Vidas", 1, 1, "R");
        CardSlot aurora = pal("au", "Aurora la Luz de los Puros", 3, 3, "R", "left");
        summon(aurora);
        accept(aurora);
        answer("a");
        answer("h");
        assertThat(host.getLifeStack().get(0).getInstanceId()).isEqualTo("h");
        assertThat(host.getLifeStack().get(0).isFaceDown()).isTrue();
        assertThat(ids(host.getTributeZone())).containsExactly("a");
    }

    @Test
    void auroraLinkPutsAnotherAllyOnTopOfTheLivesInsteadOfTheReceiversRemoval() {
        CardSlot r = onField(host, pal("r", "Receptor", 1, 1, "R", BOTH));
        onField(host, pal("au", "Aurora la Luz de los Puros", 3, 3, "R", "left"));
        CardSlot other = onField(host, card("o", "Otro", 1, 1, "R"), BACK);
        formation(host, "r", "au");
        assertThat(r.getFormationGrants()).hasSize(1).allMatch(g -> g.startsWith("au|"));
        Removals.destroy(room, r, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        Chain.advance(room);
        Prompt replace = room.getPrompt();
        assertThat(replace.getPurpose()).isEqualTo(PromptPurpose.REPLACE.id());
        answer(replace.getEffects().get(0).effectId());
        assertThat(room.getPrompt().getPurpose()).isEqualTo(PromptPurpose.REPLACE_LIFE.id());
        answer("o");
        assertThat(host.getField()).contains(r).doesNotContain(other);
        assertThat(host.getLifeStack().get(0)).isSameAs(other);
        assertThat(other.isFaceDown()).isTrue();
    }

    // ------------------------------------------------------------------ G03 Gabriel el Protector

    /** An effect of the opponent's that is removing a card. */
    private PendingEffect opponentEffect() {
        PendingEffect e = new PendingEffect();
        e.setId("eff-rival");
        e.setControllerId("guest");
        e.setSourceInstanceId("src");
        e.setSourceName("Efecto Rival");
        return e;
    }

    @Test
    void gabrielAsksForALevelThreeInAFormationOfExactlyThree() {
        CardSlot gabriel = promotion("gb", "Gabriel el Protector", 4, 2, "Ascenso: criatura nivel 3 en una formación de 3 criaturas.");
        gabriel.setColors(new ArrayList<>(List.of("G", "W")));
        SpecialRequirements.SpecialRequirement req = SpecialRequirements.specialRequirement(gabriel);
        assertThat(req.formationSize()).isEqualTo(3);
        assertThat(req.formationAtLeast()).isFalse();
        onField(host, pal("s", "Invocador", 3, 3, "R", BOTH));
        onField(host, pal("m1", "Miembro Uno", 1, 1, "R", BOTH));
        onField(host, pal("m2", "Miembro Dos", 1, 1, "R", BOTH));
        onField(host, pal("m3", "Miembro Tres", 1, 1, "R", BOTH));
        formation(host, "s", "m1");
        assertThat(SpecialRequirements.specialSummoners(host, gabriel, req)).isEmpty();
        host.getFormations().get(0).add("m2");
        assertThat(ids(SpecialRequirements.specialSummoners(host, gabriel, req))).containsExactly("s");
        host.getFormations().get(0).add("m3");
        assertThat(SpecialRequirements.specialSummoners(host, gabriel, req)).isEmpty();
    }

    @Test
    void gabrielLinkKeepsAGreenOrPaladinReceiverFromBeingRemovedByOpponentEffects() {
        onField(host, pal("gb", "Gabriel el Protector", 5, 4, "G", BOTH));
        CardSlot r = onField(host, pal("r", "Receptor", 1, 1, "R", BOTH));
        formation(host, "gb", "r");
        assertThat(EffectEngine.keywords(r)).contains(EffectEngine.UNREMOVABLE);
        Removals.destroy(room, r, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", opponentEffect());
        Chain.advance(room);
        assertThat(host.getField()).contains(r);
    }

    @Test
    void gabrielLinkDoesNotProtectAReceiverThatIsNeitherGreenNorPaladin() {
        onField(host, pal("gb", "Gabriel el Protector", 5, 4, "G", BOTH));
        CardSlot n = onField(host, card("n", "Neutral", 1, 1, "B"));
        n.setArrows(new ArrayList<>(List.of(BOTH)));
        formation(host, "gb", "n");
        assertThat(EffectEngine.keywords(n)).doesNotContain(EffectEngine.UNREMOVABLE);
        Removals.destroy(room, n, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", opponentEffect());
        Chain.advance(room);
        assertThat(host.getField()).doesNotContain(n);
    }

    // ------------------------------------------------------------------ R07 Salomón el Paladín Vanidoso

    @Test
    void salomonAsksForALevelFourInAFormationOfAtLeastThree() {
        CardSlot salomon = promotion("sa", "Salomón el Paladín Vanidoso", 5, 2, "Ascenso: criatura nivel 4 en una formación de al menos 3 criaturas.");
        SpecialRequirements.SpecialRequirement req = SpecialRequirements.specialRequirement(salomon);
        assertThat(req.formationAtLeast()).isTrue();
        onField(host, pal("s", "Invocador", 4, 4, "R", BOTH));
        onField(host, pal("m1", "Miembro Uno", 1, 1, "R", BOTH));
        onField(host, pal("m2", "Miembro Dos", 1, 1, "R", BOTH));
        onField(host, pal("m3", "Miembro Tres", 1, 1, "R", BOTH));
        formation(host, "s", "m1");
        assertThat(SpecialRequirements.specialSummoners(host, salomon, req)).isEmpty();
        host.getFormations().get(0).add("m2");
        assertThat(ids(SpecialRequirements.specialSummoners(host, salomon, req))).containsExactly("s");
        host.getFormations().get(0).add("m3");
        assertThat(ids(SpecialRequirements.specialSummoners(host, salomon, req))).containsExactly("s");
    }

    @Test
    void salomonSendsFivePaladinsFromTheDiscardToGainLetalidadWhenItAttacks() {
        CardSlot salomon = onField(host, pal("sa", "Salomón el Paladín Vanidoso", 7, 5, "R", BOTH));
        for (int i = 0; i < 5; i++) host.getDiscardPile().add(withClasses(card("d" + i, "Paladín " + i, 1, 1, "R"), "paladín"));
        settle();
        assertThat(CombatRules.attackByPlayer(room, "host", "sa", null)).isNull();
        accept(salomon);
        answer("d0", "d1", "d2", "d3", "d4");
        // Nothing blocks: Letalidad 1 hits twice, then the attack ends (and the ability with it)
        assertThat(room.getTimeline()).anyMatch(e -> GameEvent.GAIN.equals(e.getType()) && "sa".equals(e.getCardInstanceId())
                && "Letalidad 1".equals(e.getData().get("keyword")));
        assertThat(guest.getLifeStack()).hasSize(1);
        assertThat(host.getDiscardPile()).isEmpty();
        assertThat(EffectEngine.keywordLevel(salomon, "Letalidad")).isNull();
    }

    @Test
    void salomonLinkGivesStrength3ToItsReceiverAndTheReceiversAdjacentPaladinMembers() {
        CardSlot left = onField(host, pal("l", "Izquierdo", 1, 1, "R", BOTH));
        CardSlot salomon = onField(host, pal("sa", "Salomón el Paladín Vanidoso", 7, 5, "R", BOTH));
        salomon.setStrength(13);
        CardSlot right = onField(host, pal("rt", "Derecho", 1, 1, "R", BOTH));
        formation(host, "l", "sa", "rt");
        // Each receiver gains 3, and so does Salomón once for each of them (a paladín next to each in the formation)
        assertThat(EffectEngine.strength(left)).isEqualTo(6);
        assertThat(EffectEngine.strength(right)).isEqualTo(6);
        assertThat(EffectEngine.strength(salomon)).isEqualTo(19);
        room.setCurrentTurnUserId("guest");
        EffectEngine.refreshContinuous(room);
        assertThat(EffectEngine.strength(left)).isEqualTo(3);
        assertThat(EffectEngine.strength(salomon)).isEqualTo(13);
    }

    // ------------------------------------------------------------------ W14 Mano a Mano

    @Test
    void manoAManoMovesAPaladinToTheFrontLineAndGoesToTheTributes() {
        CardSlot back = onField(host, withClasses(card("pb", "Paladín Atrás", 1, 1, "R"), "paladín"), BACK);
        onField(host, card("nb", "No Paladín", 1, 1, "R"), BACK);
        CardSlot mano = utility("mm", "Mano a Mano", 0, "W");
        play(mano);
        assertThat(room.getPrompt().getValidIds()).containsExactly("pb");
        answer("pb");
        assertThat(EffectEngine.inFrontLine(back)).isTrue();
        assertThat(host.getTributeZone()).contains(mano);
        assertThat(host.getDiscardPile()).doesNotContain(mano);
    }

    @Test
    void manoAManoFromTheTributesSwapsTwoMembersAtTheBlockStepAndBothGainStrength3() {
        room.setCurrentTurnUserId("guest");
        room.setSharedCounter(0);
        CardSlot a = onField(host, pal("a", "Primero", 2, 2, "R", BOTH));
        CardSlot b = onField(host, pal("b", "Segundo", 1, 1, "R", BOTH));
        CardSlot mano = utility("mm", "Mano a Mano", 0, "W");
        mano.setFaceDown(false);
        host.getTributeZone().add(mano);
        CardSlot attacker = onField(guest, card("at", "Atacante", 1, 1, "B"));
        formation(host, "a", "b");
        assertThat(CombatRules.attackByPlayer(room, "guest", attacker.getInstanceId(), null)).isNull();
        untilOffered(mano);
        accept(mano);
        answer("a");
        answer("b");
        assertThat(ids(Formations.ordered(host))).containsExactly("b", "a");
        assertThat(host.getDiscardPile()).contains(mano);
        assertThat(EffectEngine.strength(a)).isEqualTo(6);
        assertThat(EffectEngine.strength(b)).isEqualTo(6);
    }
}
