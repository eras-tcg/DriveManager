package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.EffectScript.Moment;
import com.drive.drive_manager.simulator.engine.EffectStep.DeclareAttack;
import com.drive.drive_manager.simulator.engine.EffectStep.DestroyEnemy;
import com.drive.drive_manager.simulator.engine.EffectStep.Draw;
import com.drive.drive_manager.simulator.engine.EffectStep.GainUntilEndOfTurn;
import com.drive.drive_manager.simulator.engine.EffectStep.LookTop;
import com.drive.drive_manager.simulator.engine.EffectStep.MayDo;
import com.drive.drive_manager.simulator.engine.EffectStep.SendFromZoneToDeckBottom;
import com.drive.drive_manager.simulator.engine.EffectStep.SendSelfTo;
import com.drive.drive_manager.simulator.engine.EffectStep.SummonFromZone;
import com.drive.drive_manager.simulator.engine.EffectStep.TakeFromLookedRestToBottom;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeFromHand;
import com.drive.drive_manager.simulator.engine.EffectStep.UntapSelf;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Effects implemented by the engine, by card name (reprints keep their effects). Cards not listed here are
 * still played by hand on the board.
 */
public final class CardEffects {

    private static final String EN_INVOCACION = "En Invocación";
    private static final String EN_DESTRUCCION = "En Destrucción";

    private static final Map<String, List<EffectScript>> BY_NAME = Map.of(
            // ST3-1 Carnhaus (Iniciador)
            "carnhaus", List.of(
                    new EffectScript("carnhaus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                            "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta Violeta o Blanca. "
                                    + "Envía el resto al fondo de tu mazo. Luego: tributa 1 carta de tu mano.",
                            List.of(new LookTop(4), new TakeFromLookedRestToBottom(1, CardFilter.colors("P", "W")), new TributeFromHand(1))),
                    new EffectScript("carnhaus:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                            "Roba 1 carta. Luego: tributa 1 carta de tu mano.",
                            List.of(new Draw(1), new TributeFromHand(1)))),
            // ST3-2 Damon
            "damon", List.of(
                    new EffectScript("damon:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                            "Roba 3 cartas. Luego: tributa 2 cartas de tu mano.",
                            List.of(new Draw(3), new TributeFromHand(2)))),
            // ST3-3 Bajmut Pútrido
            "bajmut pútrido", List.of(
                    new EffectScript("bajmut-putrido:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                            "Tributa 1 carta de tu mano.",
                            List.of(new TributeFromHand(1)))),
            // ST3-5 Slicker: the cost can only be paid from the Tributos (sacrificed), not from the Descarte
            "slicker", List.of(
                    new EffectScript("slicker:en-destruccion", Moment.ON_DESTROY, false, EN_DESTRUCCION,
                            "Puedes enviar esta carta a tu descarte para invocar 1 criatura Violeta de tus tributos reduciendo su coste en 1.",
                            List.of(new SendSelfTo("discardPile")),
                            List.of(new SummonFromZone("tributeZone", CardFilter.creatures().withColors("P"), 1)))),
            // ST3-6 Virdris
            "virdris", List.of(
                    new EffectScript("virdris:en-destruccion", Moment.ON_DESTROY, true, EN_DESTRUCCION,
                            "Roba 3 cartas. Luego: tributa 2 cartas de tu mano.",
                            List.of(new Draw(3), new TributeFromHand(2)))),
            // ST3-7 Krokler
            "krokler", List.of(
                    new EffectScript("krokler:en-invocacion", Moment.ON_SUMMON, false, EN_INVOCACION,
                            "Puedes elegir 1 enemigo nivel 1; destrúyelo.",
                            List.of(new DestroyEnemy(1, CardFilter.any().withLevel(1))))),
            // ST3-11 Cazador De Bestias
            "cazador de bestias", List.of(
                    new EffectScript("cazador-de-bestias:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                            "Roba 3 cartas. Luego: tributa 2 cartas de tu mano.",
                            List.of(new Draw(3), new TributeFromHand(2)))),
            // ST3-15 Berkelus El Cazarrecompensas Del Bajomundo
            "berkelus el cazarrecompensas del bajomundo", List.of(
                    new EffectScript("berkelus:en-invocacion", Moment.ON_SUMMON, true, EN_INVOCACION,
                            "Reactiva esta carta. Luego: puedes enviar 10 criaturas [bestia] de tus tributos al fondo de tu mazo; "
                                    + "esta carta declara ataque y gana Furia 1 hasta el final del turno.",
                            List.of(
                                    new UntapSelf(),
                                    new MayDo("¿Envías 10 criaturas [bestia] de tus tributos al fondo de tu mazo para que Berkelus declare ataque y gane Furia 1?", 3),
                                    new SendFromZoneToDeckBottom("tributeZone", 10, CardFilter.creatures().withClasses("bestia")),
                                    new GainUntilEndOfTurn("Furia 1"),
                                    new DeclareAttack())))
    );

    private CardEffects() {}

    public static List<EffectScript> of(String cardName) {
        if (cardName == null) return List.of();
        return BY_NAME.getOrDefault(cardName.trim().toLowerCase(Locale.ROOT), List.of());
    }
}
