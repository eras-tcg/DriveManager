package com.drive.drive_manager.simulator.engine;

import java.util.List;

/**
 * The server's cards for the engine's core: the card bundle of the engine's jar, read by the engine's common reader
 * ({@link CardFiles}). Offered as a service (META-INF/services/com.drive.drive_manager.simulator.engine.CardSource), so the
 * core finds it on first use, in the server and in every test, without depending on the server.
 */
public final class ServerCardSource implements CardSource {

    @Override
    public List<CardDefinition> cards() {
        return CardFiles.load();
    }
}
