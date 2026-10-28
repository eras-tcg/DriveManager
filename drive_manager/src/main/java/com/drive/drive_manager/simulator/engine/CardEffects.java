package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.EffectStep.LookTop;
import com.drive.drive_manager.simulator.engine.EffectStep.TakeFromLookedRestToBottom;
import com.drive.drive_manager.simulator.engine.EffectStep.TributeFromHand;
import com.drive.drive_manager.simulator.engine.EffectScript.Moment;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Effects implemented by the engine, by card name (reprints keep their effects). Cards not listed here are
 * still played by hand on the board.
 */
public final class CardEffects {

    private static final Map<String, List<EffectScript>> BY_NAME = Map.of(
            // ST3-1 Carnhaus (Iniciador, Violeta)
            "carnhaus", List.of(new EffectScript(
                    "carnhaus:en-invocacion",
                    Moment.ON_SUMMON,
                    true,
                    "En Invocación",
                    "Mira las primeras 4 cartas de tu mazo: de ellas toma hasta 1 carta Violeta o Blanca. "
                            + "Envía el resto al fondo de tu mazo. Luego: tributa 1 carta de tu mano.",
                    List.of(
                            new LookTop(4),
                            new TakeFromLookedRestToBottom(1, CardFilter.colors("P", "W")),
                            new TributeFromHand(1)
                    )
            ))
    );

    private CardEffects() {}

    public static List<EffectScript> of(String cardName) {
        if (cardName == null) return List.of();
        return BY_NAME.getOrDefault(cardName.trim().toLowerCase(Locale.ROOT), List.of());
    }
}
