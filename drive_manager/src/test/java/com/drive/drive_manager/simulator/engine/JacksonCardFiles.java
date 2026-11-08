package com.drive.drive_manager.simulator.engine;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server's card loader up to step B, kept as the parity tests' oracle: the card files of the class path
 * ({@code classpath*:cards/**}/*.json, Spring's scanning) read with Jackson ({@link EffectJson}), with the same checks.
 */
final class JacksonCardFiles {

    private JacksonCardFiles() {}

    /** Each card file of the class path: its name and its text, in the scanning's order. */
    static List<Map.Entry<String, String>> files() {
        List<Map.Entry<String, String>> files = new ArrayList<>();
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath*:cards/**/*.json")) {
                files.add(Map.entry(file.getURL().toString(), file.getContentAsString(StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron leer las cartas", e);
        }
        return files;
    }

    static List<CardDefinition> load() {
        return load(files());
    }

    /** {@code CardFiles.load()} as it was, on files given as (path or URL, text). */
    static List<CardDefinition> load(List<Map.Entry<String, String>> files) {
        List<CardDefinition> cards = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Map.Entry<String, String> file : files) {
            String filename = file.getKey().substring(file.getKey().lastIndexOf('/') + 1);
            CardDefinition card;
            try {
                card = EffectJson.read(file.getValue());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(filename + ": " + e.getMessage(), e);
            }
            if (!names.add(card.name())) throw new IllegalStateException("Carta en dos archivos: " + card.name());
            for (EffectScript script : card.effects()) if (!ids.add(script.id())) throw new IllegalStateException("Efecto repetido: " + script.id());
            for (EffectScript script : card.inherited()) if (!ids.add(script.id())) throw new IllegalStateException("Efecto repetido: " + script.id());
            cards.add(card);
        }
        return cards;
    }
}
