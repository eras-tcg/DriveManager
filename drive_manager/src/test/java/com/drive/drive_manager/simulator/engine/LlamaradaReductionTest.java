package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Llamarada ("si esta carta es tributada desde tu mazo por un aliado [dragón]: puedes jugar esta carta reduciendo su coste en
 * el nivel de dicho aliado") when the dragon whose effect tributed it evolved in between: the ally is the creature it became.
 */
@Tag("cards")
class LlamaradaReductionTest extends CardBatchSupport {

    @Test
    void hydraEvolvesIntoCarmesiBeforeItsInheritedTributeAndLlamaradaCostsLessByCarmesi() {
        // Hydra Colosal (level 4) attacks with Flambreik under it: Flambreik's inherited tribute is Cadena 1, Hydra's own
        // Evolución Cadena 2, so Hydra becomes Dragón Carmesí (level 5) before the tribute
        CardSlot hydra = withClasses(card("hy", "Hydra Colosal", 5, 4, "G"), "dragón", "hydra");
        hydra.setStrength(10);
        hydra.getResources().add(withClasses(card("fl", "Flambreik", 3, 2, "G"), "dragón"));
        onField(host, hydra);
        CardSlot carmesi = withClasses(card("dc", "Dragón Carmesí", 8, 5, "G"), "dragón");
        carmesi.setStrength(14);
        carmesi.setSpecialSummonKind("evolution");
        carmesi.setSpecialCost(4);
        carmesi.setRequirement("criatura nivel 4.");
        Req.program(carmesi);
        host.getTributeZone().add(carmesi);
        CardSlot llamarada = card("ll", "Llamarada", 6, 4, "G");
        llamarada.setCardType("utility");
        llamarada.setStrength(null);
        // The evolution draws the top card; Llamarada is next, tributed by Flambreik's inherited effect
        host.getDeck().add(0, llamarada);
        host.getDeck().add(0, card("top", "Robada", 1, 1, "G"));
        onField(guest, card("en", "Enemigo", 1, 1, "R"));
        host.setResourceCount(10);

        CombatRules.attackByPlayer(room, "host", hydra.getInstanceId(), null);
        accept(hydra);
        answer("dc");
        assertThat(carmesi.getResources()).contains(hydra);
        int afterEvolution = gems();
        // Flambreik's inherited effect: no card from the hand on top, then tribute 2 (Llamarada first)
        answer();
        assertThat(host.getTributeZone()).contains(llamarada);

        accept(llamarada);
        // Cost 6 minus Dragón Carmesí's level 5 (not 0 for the Hydra, now under it)
        assertThat(gems()).isEqualTo(afterEvolution - 1);
        assertThat(room.getTimeline()).extracting(GameEvent::getType).contains(GameEvent.PLAY);
    }
}
