package com.drive.drive_manager.simulator.engine;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The programmed cards: one JSON file each, {@code resources/cards/<edition>/<card>.json} ({@link EffectJson}). */
final class CardFiles {

    private CardFiles() {}

    /**
     * @throws IllegalStateException when a file is invalid, or a card or an effect id is there twice: the engine does not run on a broken card
     */
    static List<CardDefinition> load() {
        List<CardDefinition> cards = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath*:cards/**/*.json")) {
                CardDefinition card;
                try {
                    card = EffectJson.read(file.getContentAsString(StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(file.getFilename() + ": " + e.getMessage(), e);
                }
                if (!names.add(card.name())) throw new IllegalStateException("Carta en dos archivos: " + card.name());
                for (EffectScript script : card.effects()) if (!ids.add(script.id())) throw new IllegalStateException("Efecto repetido: " + script.id());
                for (EffectScript script : card.inherited()) if (!ids.add(script.id())) throw new IllegalStateException("Efecto repetido: " + script.id());
                cards.add(card);
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron leer las cartas", e);
        }
        return cards;
    }
}
