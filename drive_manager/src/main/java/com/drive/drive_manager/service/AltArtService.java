package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.AltArt;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A card's alternative arts (Card.altArts), set from the site by whoever manages cards: each image goes to R2 under
 * {@code cards-alt/<metadata id>-<art id>.jpg} (the single art of before, {@code cards-alt/<metadata id>.jpg}), never
 * through Drive (the sync would read the art's code as a card of its own), and the card's metadata keeps its URL,
 * versioned so a replaced image is not served from cache, with the art's own odds (null: the shop's).
 */
@Service
public class AltArtService {

    /** The largest file accepted. */
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    /** Alternative arts one card can have. */
    public static final int MAX_ARTS = 10;
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final R2Service r2;
    private final CardRepository cards;
    private final SecureRandom random = new SecureRandom();

    @Value("${r2.public-url:}")
    private String publicUrl;

    public AltArtService(R2Service r2, CardRepository cards) {
        this.r2 = r2;
        this.cards = cards;
    }

    static String key(String cardId, String artId) {
        return Card.LEGACY_ART_ID.equals(artId) ? "cards-alt/" + cardId + ".jpg" : "cards-alt/" + cardId + "-" + artId + ".jpg";
    }

    /** Adds an alternative art from an image, with its own odds (null: the shop's): the card after it, empty if none. */
    public Optional<Card> add(String cardId, byte[] image, Double odds) throws IOException {
        Optional<Card> found = cards.findById(cardId);
        if (found.isEmpty()) return found;
        Card card = found.get();
        List<AltArt> arts = editable(card);
        if (arts.size() >= MAX_ARTS) throw new IllegalArgumentException("Una carta puede tener hasta " + MAX_ARTS + " artes alternativos");
        checkOdds(odds);
        String artId = newId(arts);
        arts.add(new AltArt(artId, store(cardId, artId, image), odds));
        return Optional.of(cards.save(card));
    }

    /** Replaces the image of one of the card's arts (its copies already pulled show the new one). */
    public Optional<Card> replace(String cardId, String artId, byte[] image) throws IOException {
        Optional<Card> found = cards.findById(cardId);
        if (found.isEmpty()) return found;
        Card card = found.get();
        AltArt art = artOf(editable(card), artId);
        if (art == null) return Optional.empty();
        art.setUrl(store(cardId, artId, image));
        return Optional.of(cards.save(card));
    }

    /** Sets one art's own odds per pulled copy (null: it takes the shop's). */
    public Optional<Card> setOdds(String cardId, String artId, Double odds) {
        checkOdds(odds);
        Optional<Card> found = cards.findById(cardId);
        if (found.isEmpty()) return found;
        Card card = found.get();
        AltArt art = artOf(editable(card), artId);
        if (art == null) return Optional.empty();
        art.setOdds(odds);
        return Optional.of(cards.save(card));
    }

    /**
     * The card loses one alternative art: copies already pulled with it show the usual art (they keep their key, so a
     * new art never takes their place). The card after it, empty if none.
     */
    public Optional<Card> remove(String cardId, String artId) {
        Optional<Card> found = cards.findById(cardId);
        if (found.isEmpty()) return found;
        Card card = found.get();
        List<AltArt> arts = editable(card);
        AltArt art = artOf(arts, artId);
        if (art == null) return Optional.empty();
        r2.deleteRaw(key(cardId, artId));
        arts.remove(art);
        return Optional.of(cards.save(card));
    }

    /** The card's arts as a list it keeps (the single art of before moves into it). */
    private static List<AltArt> editable(Card card) {
        List<AltArt> arts = new ArrayList<>(card.getAltArts());
        card.setAltArts(arts);
        card.setAltImage(null);
        return arts;
    }

    private static AltArt artOf(List<AltArt> arts, String artId) {
        return arts.stream().filter(a -> a.getId().equals(artId)).findFirst().orElse(null);
    }

    static void checkOdds(Double odds) {
        if (odds != null) ShopOddsService.probability(odds, "este arte");
    }

    /** Six letters and digits no other art of the card has (they go into the collection's keys). */
    private String newId(List<AltArt> arts) {
        while (true) {
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 6; i++) id.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
            if (artOf(arts, id.toString()) == null) return id.toString();
        }
    }

    /** Uploads the image as a JPEG under the art's key: its versioned URL. */
    private String store(String cardId, String artId, byte[] image) throws IOException {
        byte[] jpeg = jpeg(image);
        r2.uploadRaw(key(cardId, artId), new ByteArrayInputStream(jpeg), jpeg.length);
        return publicUrl.strip().replaceAll("/+$", "") + "/" + key(cardId, artId) + "?v=" + Instant.now().getEpochSecond();
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
