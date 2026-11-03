package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.CardEffects.Continuous;

import java.util.List;

/**
 * Everything the engine runs for one card, as data: what {@link EffectJson} writes and reads, so it can be kept with the
 * card in the database.
 *
 * @param name                  the card's name, in lower case (how the engine finds it)
 * @param effects               its own effects
 * @param inherited             what it gives the creature it is a Recurso of
 * @param continuous            its continuous effects, on itself or on other cards
 * @param reactions             what it does before an event ("cuando … fuera a …")
 * @param inheritedReactions    the reactions it gives as a Recurso
 * @param traits                what it changes in the rules themselves; null: nothing
 */
public record CardDefinition(String name, List<EffectScript> effects, List<EffectScript> inherited, List<Continuous> continuous,
                             List<Reaction> reactions,
                             List<Reaction> inheritedReactions, CardTraits traits) {

    public CardDefinition {
        effects = effects == null ? List.of() : List.copyOf(effects);
        inherited = inherited == null ? List.of() : List.copyOf(inherited);
        continuous = continuous == null ? List.of() : List.copyOf(continuous);
        reactions = reactions == null ? List.of() : List.copyOf(reactions);
        inheritedReactions = inheritedReactions == null ? List.of() : List.copyOf(inheritedReactions);
    }
}
