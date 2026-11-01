package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.CardSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.Effect;
import com.drive.drive_manager.simulator.RoomService;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Designer rulings of 2026-10-31 (third round): Abraza Almas level 2 for [espectro], Biok's self damage, XTRM-1N3's field pieces, Unidad
 * Exoplanetaria's declared summoner, Sarkhydrax and Fósil inherited reactions, Carnirex summoned by a sacrifice; and the fourth round: "con [tipo]
 * en su texto", "en tu retaguardia", Preparando Expedición, Helena and the lives, Agrupar, Velkerop-1 and A-5H Requerimientos.
 */
class DesignerRulingsRound3Test extends CardBatchSupport {

    private static CardSlot special(CardSlot card, String kind, int cost, String requirement) {
        card.setSpecialSummonKind(kind);
        card.setSpecialCost(cost);
        card.setRequirement(requirement);
        return card;
    }

    private void destroyByOpponent(CardSlot card) {
        Removals.destroy(room, card, host, false, EffectEngine.CAUSE_EFFECT, "guest", "src", null);
        if (room.getPrompt() == null) Chain.advance(room);
    }

    // ------------------------------------------------------------------ Abraza Almas

    @Test
    void abrazaAlmasIsLevelTwoForTheSpecialSummonOfAnEspectro() {
        onField(host, withClasses(card("aa", "Abraza Almas", 2, 1, "P"), "espectro"));
        CardSlot evo = special(inHand("ev", "Espectro Mayor", 3, 3, "P", "espectro"), "evolution", 1, "criatura nivel 2.");
        assertThat(Summons.specialByPlayer(room, "host", "ev", "aa")).isNull();
        drain();
        assertThat(host.getField()).contains(evo);
    }

    @Test
    void abrazaAlmasIsLevelOneForOtherCards() {
        onField(host, withClasses(card("aa", "Abraza Almas", 2, 1, "P"), "espectro"));
        special(inHand("ev", "Otro Mayor", 3, 3, "P", "fauna"), "evolution", 1, "criatura nivel 2.");
        assertThat(Summons.specialByPlayer(room, "host", "ev", "aa")).isNotNull();
    }

    @Test
    void abrazaAlmasIsLevelTwoForTheEffectsOfAnEspectro() {
        CardSlot abraza = withClasses(card("aa", "Abraza Almas", 2, 1, "P"), "espectro");
        CardSlot espectro = withClasses(card("es", "Espectro", 1, 1, "P"), "espectro");
        CardSlot other = withClasses(card("ot", "Otro", 1, 1, "P"), "fauna");
        assertThat(EffectEngine.lookingAs(espectro, () -> CardFilter.creatures().withLevel(2).matches(abraza))).isTrue();
        assertThat(EffectEngine.lookingAs(other, () -> CardFilter.creatures().withLevel(2).matches(abraza))).isFalse();
    }

    // ------------------------------------------------------------------ Biok

    @Test
    void biokDamagesItsPlayerTenPerGemOfTheSpecialCost() {
        onField(host, withClasses(card("bk", "Biok", 5, 4, "B"), "necrómata"));
        CardSlot evo = special(inHand("ev", "Evolución", 6, 5, "B"), "evolution", 3, "criatura nivel 4.");
        int before = gems();
        assertThat(Summons.specialByPlayer(room, "host", "ev", "bk")).isNull();
        assertThat(room.getPrompt().getPurpose()).isEqualTo(EffectEngine.PURPOSE_SUMMONER_DISCOUNT);
        answer("hl0", "hl1");
        drain();
        assertThat(host.getField()).contains(evo);
        assertThat(gems()).isEqualTo(before - 1);
        assertThat(host.getLifeStack()).hasSize(1);
    }

    @Test
    void biokCanPayNothing() {
        onField(host, withClasses(card("bk", "Biok", 5, 4, "B"), "necrómata"));
        CardSlot evo = special(inHand("ev", "Evolución", 6, 5, "B"), "evolution", 3, "criatura nivel 4.");
        int before = gems();
        assertThat(Summons.specialByPlayer(room, "host", "ev", "bk")).isNull();
        answer();
        drain();
        assertThat(host.getField()).contains(evo);
        assertThat(gems()).isEqualTo(before - 3);
        assertThat(host.getLifeStack()).hasSize(3);
    }

    // ------------------------------------------------------------------ Prototipo XTRM-1N3

    private static final String XTRM = "criatura nivel 3 en mano o campo (invocador) + 1 o más criaturas nivel 3 en mano o campo.";

    @Test
    void xtrmCostsThreeLessWhenTwoFieldCreaturesAreUsed() {
        onField(host, withClasses(card("su", "Invocador", 3, 3, "B"), "neomáquina"));
        onField(host, withClasses(card("m1", "Material", 3, 3, "B"), "neomáquina"));
        CardSlot xtrm = special(inHand("xt", "Prototipo XTRM-1N3", 4, 4, "B", "neomáquina"), "materialization", 4, XTRM);
        int before = gems();
        assertThat(Summons.specialByPlayer(room, "host", "xt", "su")).isNull();
        answer("m1");
        drain();
        assertThat(host.getField()).contains(xtrm);
        assertThat(xtrm.getMaterials()).extracting(CardSlot::getInstanceId).contains("su", "m1");
        assertThat(gems()).isEqualTo(before - 1);
    }

    @Test
    void xtrmPaysItsCostWithOneFieldCreature() {
        onField(host, withClasses(card("su", "Invocador", 3, 3, "B"), "neomáquina"));
        inHand("m1", "Material", 3, 3, "B", "neomáquina");
        inHand("m2", "Material 2", 3, 3, "B", "neomáquina");
        CardSlot xtrm = special(inHand("xt", "Prototipo XTRM-1N3", 4, 4, "B", "neomáquina"), "materialization", 4, XTRM);
        int before = gems();
        assertThat(Summons.specialByPlayer(room, "host", "xt", "su")).isNull();
        answer("m1", "m2");
        drain();
        assertThat(host.getField()).contains(xtrm);
        assertThat(xtrm.getMaterials()).extracting(CardSlot::getInstanceId).contains("su", "m1", "m2");
        assertThat(gems()).isEqualTo(before - 4);
    }

    // ------------------------------------------------------------------ Unidad Exoplanetaria de Rescate

    private CardSlot exoplanetaria() {
        return special(inHand("ex", "Unidad Exoplanetaria de Rescate", 4, 4, "B", "autómata", "neomáquina", "mecha"), "materialization", 2,
                "invocador nivel 3 + materiales que sumen 3 en tus tributos.");
    }

    @Test
    void exoplanetariaIsSummonedByACreatureOfTheHandWhenTheOpponentHasFiveMoreLevels() {
        onField(guest, card("g5", "Enemigo", 5, 5, "R"));
        CardSlot exo = exoplanetaria();
        inHand("su", "Invocador", 3, 3, "B", "autómata");
        host.getTributeZone().add(withClasses(card("m3", "Material", 3, 3, "B"), "autómata"));
        int before = gems();
        assertThat(Chain.activateByPlayer(room, "host", "ex", "exoplanetaria:en-mano")).isNull();
        answer("su");
        answer("m3");
        drain();
        assertThat(host.getField()).contains(exo);
        assertThat(exo.getMaterials()).extracting(CardSlot::getInstanceId).contains("su", "m3");
        assertThat(gems()).isEqualTo(before - 2);
    }

    @Test
    void exoplanetariaNeedsFiveMoreLevels() {
        onField(guest, card("g4", "Enemigo", 4, 4, "R"));
        exoplanetaria();
        inHand("su", "Invocador", 3, 3, "B", "autómata");
        host.getTributeZone().add(withClasses(card("m3", "Material", 3, 3, "B"), "autómata"));
        assertThat(Chain.activateByPlayer(room, "host", "ex", "exoplanetaria:en-mano")).isNotNull();
    }

    // ------------------------------------------------------------------ Sarkhydrax

    @Test
    void sarkhydraxTributesOneResourceAndTakesAnotherBeforeTheCardIsRemoved() {
        CardSlot holder = onField(host, card("ho", "Portador", 3, 3, "G"));
        CardSlot sark = withClasses(card("sk", "Sarkhydrax", 2, 2, "G"), "hydra", "dragón");
        CardSlot other = card("r2", "Recurso", 1, 1, "G");
        holder.getResources().addAll(List.of(sark, other));
        destroyByOpponent(holder);
        answer(room.getPrompt(), "ho");
        answer("sk", "r2");
        drain();
        assertThat(host.getField()).doesNotContain(holder);
        assertThat(host.getTributeZone()).contains(sark);
        assertThat(host.getHand()).contains(other);
    }

    // ------------------------------------------------------------------ Fósil

    @Test
    void fosilGoesToTheBackWithTheDinosaurResourcesWhenTheOpponentRemovesTheCard() {
        CardSlot holder = onField(host, card("ho", "Portador", 3, 3, "G"));
        CardSlot fosil = withClasses(utility("fo", "Fósil", 1, "G"), "dinosaurio");
        CardSlot dino = withClasses(card("dn", "Dino", 1, 1, "G"), "dinosaurio");
        CardSlot other = card("ot", "Otro", 1, 1, "G");
        holder.getResources().addAll(List.of(fosil, dino, other));
        destroyByOpponent(holder);
        drain();
        assertThat(host.getField()).contains(fosil).doesNotContain(holder);
        assertThat(fosil.getResources()).containsExactly(dino);
    }

    @Test
    void fosilStaysWithTheCardWhenItsOwnPlayerRemovesIt() {
        CardSlot holder = onField(host, card("ho", "Portador", 3, 3, "G"));
        CardSlot fosil = withClasses(utility("fo", "Fósil", 1, "G"), "dinosaurio");
        holder.getResources().add(fosil);
        Removals.destroy(room, holder, host, false, EffectEngine.CAUSE_EFFECT, "host", "src", null);
        drain();
        assertThat(host.getField()).doesNotContain(fosil);
    }

    @Test
    void fosilIsACreatureOfStrengthOneForEffectsButNotForSpecialSummons() {
        CardSlot fosil = withClasses(utility("fo", "Fósil", 1, "G"), "dinosaurio");
        CardSlot looker = card("lk", "Efecto", 1, 1, "G");
        CardFilter weak = CardFilter.creatures().withMaxStrength(1);
        assertThat(EffectEngine.lookingAs(looker, () -> weak.matches(fosil))).isTrue();
        assertThat(EffectEngine.summoningAs(looker, () -> weak.matches(fosil))).isFalse();
        assertThat(EffectEngine.lookingAs(looker, () -> CardFilter.creatures().withMinStrength(2).matches(fosil))).isFalse();
    }

    // ------------------------------------------------------------------ Carnirex

    @Test
    void carnirexSacrificesAnActiveDinosaurToEnterTheFrontLineCostingItsCostLess() {
        CardSlot dino = onField(host, withClasses(card("dn", "Dino", 4, 3, "G"), "dinosaurio"), BACK);
        CardSlot carnirex = inHand("cx", "Carnirex", 10, 5, "G", "dinosaurio");
        int before = gems();
        assertThat(Summons.normalSummon(room, "host", "cx", 50.0, 75.0)).isNull();
        assertThat(room.getPrompt().getPurpose()).isEqualTo(EffectEngine.PURPOSE_SUMMON_SACRIFICE);
        answer("dn");
        drain();
        assertThat(host.getField()).contains(carnirex).doesNotContain(dino);
        assertThat(carnirex.getY()).isLessThan(50.0);
        assertThat(gems()).isEqualTo(before - 6);
    }

    @Test
    void carnirexCannotBeSummonedWithoutTheGemsOrADinosaur() {
        inHand("cx", "Carnirex", 10, 5, "G", "dinosaurio");
        assertThat(Summons.normalSummon(room, "host", "cx", 50.0, 75.0)).isNotNull();
    }

    @Test
    void carnirexIgnoresATappedDinosaur() {
        CardSlot dino = onField(host, withClasses(card("dn", "Dino", 4, 3, "G"), "dinosaurio"), BACK);
        dino.setTapped(true);
        inHand("cx", "Carnirex", 10, 5, "G", "dinosaurio");
        assertThat(Summons.normalSummon(room, "host", "cx", 50.0, 75.0)).isNotNull();
    }

    // ------------------------------------------------------------------ "con [tipo] en su texto" (Cervalyra, Petrodalix)

    @Test
    void aTypeInItsTextIsItsTypeItsNameOrItsEffects() {
        CardFilter fauna = CardFilter.type("structure").mentioning("fauna");
        CardSlot ofType = withClasses(structure("s1", "Guarida", 1, "R"), "fauna");
        CardSlot inName = structure("s2", "Refugio De Fauna", 1, "R");
        CardSlot inText = structure("s3", "Santuario", 1, "R");
        inText.setMentions(new java.util.ArrayList<>(List.of("fauna")));
        CardSlot none = structure("s4", "Muralla", 1, "R");
        assertThat(List.of(ofType, inName, inText)).allMatch(fauna::matches);
        assertThat(fauna.matches(none)).isFalse();
    }

    @Test
    void theTypesOfItsTextComeFromItsEffectsAndInheritedEffects() {
        Card meta = new Card();
        Effect own = new Effect();
        own.setEffectBlocks(List.of(new Effect.effectBlock("", "", "toma 1 [Fauna] de tu mazo y gana [[Reactivar]].")));
        Effect inherited = new Effect();
        inherited.setEffectBlocks(List.of(new Effect.effectBlock("si un aliado [dinosaurio] ataca", "", "roba 1 carta.")));
        meta.setEffects(List.of(own));
        meta.setInheritEffects(List.of(inherited));
        assertThat(RoomService.mentionedTypes(meta)).containsExactly("fauna", "dinosaurio");
    }

    @Test
    void cervalyraTakesAStructureWithFaunaInItsText() {
        CardSlot santuario = structure("s3", "Santuario", 1, "R");
        santuario.setMentions(new java.util.ArrayList<>(List.of("fauna")));
        host.getDeck().add(0, santuario);
        CardSlot cervalyra = withClasses(card("cv", "Cervalyra", 1, 1, "G"), "fauna");
        summon(cervalyra);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("cv")) answer("cv");
        answer("s3");
        drain();
        assertThat(host.getHand()).contains(santuario);
    }

    // ------------------------------------------------------------------ "en tu retaguardia" (Puerto de Cineria, Preparando Expedición)

    @Test
    void anArtefactoInTheFrontLineIsNotInTheBack() {
        CardFilter back = CardFilter.any().withClasses("artefacto").inBack();
        CardSlot inBack = onField(host, withClasses(utility("a1", "Artefacto", 0, "B"), "artefacto"), BACK);
        CardSlot inFront = onField(host, withClasses(card("a2", "Artefacto Vivo", 1, 1, "B"), "artefacto"), FRONT);
        assertThat(back.matches(inBack)).isTrue();
        assertThat(back.matches(inFront)).isFalse();
    }

    @Test
    void preparandoExpedicionPlaysAnArtefactoThenPlacesOneFromTheMaterialsOfAnExhaustedAlly() {
        CardSlot fromHand = withClasses(utility("ah", "Artefacto Mano", 0, "B"), "artefacto");
        host.getHand().add(fromHand);
        CardSlot ally = onField(host, card("al", "Aliado", 2, 2, "B"));
        ally.setTapped(true);
        CardSlot material = withClasses(utility("am", "Artefacto Material", 0, "B"), "artefacto");
        ally.getMaterials().add(material);
        CardSlot preparando = utility("pe", "Preparando Expedición", 1, "B");
        play(preparando);
        if (room.getPrompt() != null && room.getPrompt().getValidIds().contains("pe")) answer("pe");
        answer("ah");
        answer("am");
        drain();
        assertThat(host.getField()).contains(fromHand, material);
        assertThat(ally.getMaterials()).isEmpty();
        assertThat(EffectEngine.inFrontLine(material)).isFalse();
        assertThat(material.getCardType()).isEqualTo("utility");
    }

    // ------------------------------------------------------------------ Helena

    @Test
    void helenaReducesTheOpponentsFaceUpLivesUntilTheEndOfTheTurn() {
        CardSlot faceUp = guest.getLifeStack().get(0);
        faceUp.setStrength(12);
        faceUp.setFaceDown(false);
        CardSlot faceDown = guest.getLifeStack().get(1);
        faceDown.setStrength(12);
        faceDown.setFaceDown(true);
        onField(guest, card("e", "Enemigo", 20, 5, "R"));
        int gems = gems();
        CardSlot helena = onField(host, withClasses(card("he", "Helena la Pirata de Cineria", 2, 2, "B"), "cineriano"));
        helena.getMaterials().add(card("m", "Material", 1, 1, "B"));
        EffectEngine.record(room, GameEvent.SPECIAL_SUMMON, "host", helena, "hand", "field", null, java.util.Map.of("summonType", "materialization"), "x");
        Chain.advance(room);
        for (int i = 0; i < 6 && room.getPrompt() != null; i++) {
            List<String> valid = room.getPrompt().getValidIds();
            if (valid.contains("e")) answer("e");
            else if (valid.contains("he")) answer("he");
            else if (valid.contains("m")) answer("m");
            else answer();
        }
        assertThat(CardEffects.lifeStrength(room, "guest", faceUp)).isEqualTo(12 - gems);
        assertThat(CardEffects.lifeStrength(room, "guest", faceDown)).isEqualTo(12);
    }

    // ------------------------------------------------------------------ Agrupar

    @Test
    void agruparGivesLetalidadOneForEachExhaustedAlly() {
        CardSlot attacker = onField(host, card("at", "Atacante", 2, 2, "R"));
        attacker.setKeywords(new java.util.ArrayList<>(List.of("Agrupar 2")));
        onField(host, card("a1", "Aliado 1", 1, 1, "R"), BACK);
        onField(host, card("a2", "Aliado 2", 1, 1, "R"), BACK);
        assertThat(CombatRules.attackByPlayer(room, "host", "at", null)).isNull();
        untilOffered(attacker);
        answer(room.getPrompt(), "at");
        answer("a1", "a2");
        assertThat(EffectEngine.keywords(attacker)).contains("Letalidad 2");
    }

    // ------------------------------------------------------------------ Requerimientos: Velkerop-1, A-5H

    @Test
    void velkeropTakesItsMaterialsFromTheDiscard() {
        CardSlot velkerop = special(card("vk", "Velkerop 1", 5, 4, "B"), "materialization", 2,
                "criatura nivel 3 + materiales que sumen 6 en nivel de tu descarte.");
        assertThat(Summons.specialRequirement(velkerop).materialZones()).containsExactly("discardPile");
    }

    @Test
    void a5hIsSummonedByACreatureOfTheHandWithAnotherAsItsMaterial() {
        CardSlot a5h = special(inHand("a5", "Unidad de Seguridad A-5H", 3, 3, "B", "neomáquina"), "materialization", 3, "2 criaturas en mano.");
        inHand("c1", "Uno", 1, 1, "B", "neomáquina");
        inHand("c2", "Dos", 2, 2, "B", "neomáquina");
        int before = gems();
        assertThat(Summons.specialByPlayer(room, "host", "a5", "c1")).isNull();
        answer("c2");
        drain();
        assertThat(host.getField()).contains(a5h);
        assertThat(a5h.getMaterials()).extracting(CardSlot::getInstanceId).contains("c1", "c2");
        assertThat(gems()).isEqualTo(before - 3);
    }
}
