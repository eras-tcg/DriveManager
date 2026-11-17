package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An edition's cover (its {@code editionImage}): an image chosen on the site, kept under
 * {@code editions/<editionId>-<millis>.<ext>} (a new name for each one, so its address can be cached for good). In R2
 * its address is the public one; with a local folder (local development) the editions' API serves it.
 */
@Service
public class EditionImageService {

    /** The largest file accepted. */
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    static final String PREFIX = "editions/";
    /** The name of a cover stored by this service (never another path). */
    static final Pattern FILE = Pattern.compile("[A-Za-z0-9.]+-[0-9]+[.](jpeg|png|webp)");

    private final EditionMetadataRepository editions;
    private final CardArtStore store;

    public EditionImageService(EditionMetadataRepository editions, CardArtStore store) {
        this.editions = editions;
        this.store = store;
    }

    /** Sets the edition's cover; the one it replaces is deleted when it was stored here. Empty: no such edition. */
    public Optional<EditionMetadata> set(String editionId, byte[] image) {
        String type = CardArtService.mediaType(image);
        if (type == null) throw new IllegalArgumentException("La portada debe ser JPG, PNG o WebP");
        if (image.length > MAX_BYTES) throw new IllegalArgumentException("La imagen pesa más de 10 MB");
        return editions.findById(editionId).map(edition -> {
            String file = editionId + "-" + System.currentTimeMillis() + "." + type.substring("image/".length());
            store.put(PREFIX + file, image, type);
            String old = edition.getEditionImage();
            edition.setEditionImage(store.publicUrl(PREFIX + file, "/api/drive/editions/image/" + file));
            EditionMetadata saved = editions.save(edition);
            ownFile(old, editionId).ifPresent(f -> store.delete(PREFIX + f));
            return saved;
        });
    }

    /** A cover stored here, by its file name. */
    public Optional<byte[]> get(String file) {
        if (!FILE.matcher(file).matches()) return Optional.empty();
        try {
            return Optional.ofNullable(store.get(PREFIX + file));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The file name of a cover this service stored for the edition (older covers, from Drive, are left alone). */
    static Optional<String> ownFile(String url, String editionId) {
        if (url == null) return Optional.empty();
        String file = url.substring(url.lastIndexOf('/') + 1);
        return file.startsWith(editionId + "-") && FILE.matcher(file).matches() ? Optional.of(file) : Optional.empty();
    }
}
