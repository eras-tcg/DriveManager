package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.engine.cards.CardBundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The programmed cards: one JSON file each, {@code eras-engine/cards/<edition>/<card>.json}, read by the engine's common
 * reader ({@link com.drive.drive_manager.simulator.engine.cards.CardReader}, kotlinx.serialization) from the card bundle the
 * engine's jar carries ({@link CardBundle#RESOURCE}): the same text and the same reader the site uses. The files themselves
 * are on the class path too ({@code cards/**}), for the rules' version of the kept games.
 */
final class CardFiles {

    private CardFiles() {}

    /**
     * @throws IllegalStateException when a file is invalid, or a card or an effect id is there twice: the engine does not run on a broken card
     */
    static List<CardDefinition> load() {
        return CardBundle.read(bundle());
    }

    /** The bundle's text, from the engine's jar. */
    static String bundle() {
        try (InputStream in = CardFiles.class.getClassLoader().getResourceAsStream(CardBundle.RESOURCE)) {
            if (in == null) throw new IllegalStateException("No está el paquete de cartas (" + CardBundle.RESOURCE + ") en el class path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron leer las cartas", e);
        }
    }
}
