package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.CardEffects.Aura;
import com.drive.drive_manager.simulator.engine.CardEffects.Continuous;
import com.drive.drive_manager.simulator.engine.CardEffects.Replacement;
import com.drive.drive_manager.simulator.engine.CostModifiers.CostModifier;

import java.util.List;

/**
 * Everything the engine runs for one card, as data: what {@link EffectJson} writes and reads, so it can be kept with the
 * card in the database.
 *
 * @param name                  the card's name, in lower case (how the engine finds it)
 * @param effects               its own effects
 * @param inherited             what it gives the creature it is a Recurso of
 * @param continuous            its continuous effects
 * @param auras                 its continuous effects on other cards
 * @param costModifiers         how it changes costs
 * @param replacements          its own replacements ("en su lugar")
 * @param inheritedReplacements the replacements it gives as a Recurso
 */
public record CardDefinition(String name, List<EffectScript> effects, List<EffectScript> inherited, List<Continuous> continuous,
                             List<Aura> auras, List<CostModifier> costModifiers, List<Replacement> replacements,
                             List<Replacement> inheritedReplacements) {

    public CardDefinition {
        effects = effects == null ? List.of() : List.copyOf(effects);
        inherited = inherited == null ? List.of() : List.copyOf(inherited);
        continuous = continuous == null ? List.of() : List.copyOf(continuous);
        auras = auras == null ? List.of() : List.copyOf(auras);
        costModifiers = costModifiers == null ? List.of() : List.copyOf(costModifiers);
        replacements = replacements == null ? List.of() : List.copyOf(replacements);
        inheritedReplacements = inheritedReplacements == null ? List.of() : List.copyOf(inheritedReplacements);
    }
}
