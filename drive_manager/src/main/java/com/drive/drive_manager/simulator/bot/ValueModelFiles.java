package com.drive.drive_manager.simulator.bot;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** The bot's model saved and read as JSON on the JVM (the model itself, {@link ValueModel}, does without Jackson). */
public final class ValueModelFiles {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ValueModelFiles() {}

    public static void save(ValueModel model, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, JSON.writer().with(SerializationFeature.INDENT_OUTPUT).writeValueAsString(model.weights()));
    }

    public static ValueModel read(Path file) throws IOException {
        return ValueModel.of(JSON.readValue(Files.readString(file), ValueModel.Weights.class));
    }

    /** The model in the classpath at {@code resource}, or null when there is none. */
    public static ValueModel resource(String resource) {
        try (InputStream in = ValueModel.class.getResourceAsStream(resource)) {
            return in == null ? null : ValueModel.of(JSON.readValue(in, ValueModel.Weights.class));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read the bot's model " + resource, e);
        }
    }
}
