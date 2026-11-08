package com.drive.drive_manager.simulator.bot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The bot's resources for the engine's core: the class path's {@code /bot/...} files, the trained model read with
 * Jackson ({@link ValueModelFiles}). Offered as a service (META-INF/services/com.drive.drive_manager.simulator.bot.BotResourceSource),
 * so the core finds it on first use, in the server and in every test, without depending on the server.
 */
public final class ServerBotResourceSource implements BotResourceSource {

    @Override
    public String text(String path, String failure) {
        try (InputStream input = ServerBotResourceSource.class.getResourceAsStream(path)) {
            return input == null ? null : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(failure, e);
        }
    }

    @Override
    public ValueModel valueModel(String path) {
        return ValueModelFiles.resource(path);
    }
}
