package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.repository.CardRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * A card's alternative art (Card.altImage), set from the site by whoever manages cards: the image goes to R2 under
 * {@code cards-alt/<metadata id>.jpg}, never through Drive (the sync would read the art's code as a card of its own),
 * and the card's metadata keeps its URL, versioned so a replaced image is not served from cache.
 */
@Service
public class AltArtService {

    /** The largest file accepted. */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    private final R2Service r2;
    private final CardRepository cards;

    @Value("${r2.public-url:}")
    private String publicUrl;

    public AltArtService(R2Service r2, CardRepository cards) {
        this.r2 = r2;
        this.cards = cards;
    }

    static String key(String cardId) {
        return "cards-alt/" + cardId + ".jpg";
    }

    /** Stores the image as the card's alternative art (replacing any earlier one): the card after it, empty if none. */
    public Optional<Card> put(String cardId, byte[] image) throws IOException {
        Optional<Card> found = cards.findById(cardId);
        if (found.isEmpty()) return found;
        byte[] jpeg = jpeg(image);
        r2.uploadRaw(key(cardId), new ByteArrayInputStream(jpeg), jpeg.length);
        Card card = found.get();
        card.setAltImage(publicUrl.strip().replaceAll("/+$", "") + "/" + key(cardId) + "?v=" + Instant.now().getEpochSecond());
        return Optional.of(cards.save(card));
    }

    /**
     * The card has no alternative art any more: copies already pulled with it show the usual one until it gets an art
     * again. The card after it, empty if none.
     */
    public Optional<Card> remove(String cardId) {
        Optional<Card> found = cards.findById(cardId);
        if (found.isEmpty()) return found;
        r2.deleteRaw(key(cardId));
        Card card = found.get();
        card.setAltImage(null);
        return Optional.of(cards.save(card));
    }

    /** The uploaded image as a JPEG of the same size (transparency goes black, like a card's edge). */
    static byte[] jpeg(byte[] image) throws IOException {
        BufferedImage source = image == null ? null : ImageIO.read(new ByteArrayInputStream(image));
        if (source == null) throw new IllegalArgumentException("El archivo no es una imagen JPG, PNG, GIF o BMP");
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(source, 0, 0, null);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "jpg", bytes);
        return bytes.toByteArray();
    }
}
