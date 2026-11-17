package com.drive.drive_manager.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the cards' illustrations are kept: R2, under the key as given ({@code card-art/…}), or a local folder when
 * {@code card-art.dir} is set (local development, where R2 is not configured). The site never reads them from
 * R2 directly: they are served by the cards' API (CardArtController).
 */
@Component
public class CardArtStore {

    private final R2Service r2;
    private final Path dir;

    public CardArtStore(R2Service r2, @Value("${card-art.dir:}") String dir) {
        this.r2 = r2;
        this.dir = dir == null || dir.isBlank() ? null : Path.of(dir);
    }

    public void put(String key, byte[] bytes, String contentType) {
        if (dir == null) {
            r2.uploadRaw(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
            return;
        }
        try {
            Path file = local(key);
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] get(String key) {
        if (dir == null) return r2.getRaw(key);
        try {
            return Files.readAllBytes(local(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String key) {
        if (dir == null) {
            r2.deleteRaw(key);
            return;
        }
        try {
            Files.deleteIfExists(local(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Where a stored object can be read without a session: its public R2 address, or {@code apiPath} (served by this
     * server) when it is in the local folder.
     */
    public String publicUrl(String key, String apiPath) {
        return dir == null ? r2.publicUrl(key) : apiPath;
    }

    /** The key's file inside the folder (keys are built by the server, never taken from a request). */
    private Path local(String key) {
        Path file = dir.resolve(key).normalize();
        if (!file.startsWith(dir.normalize())) throw new IllegalArgumentException("Bad art key");
        return file;
    }
}
