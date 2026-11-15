package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.Card;
import com.drive.drive_manager.dto.CardDraft;
import com.drive.drive_manager.repository.CardDraftRepository;
import com.drive.drive_manager.repository.CardRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The card editor's drafts (CardDraft): created with their code (edition, number and color), saved with their metadata
 * and design, and their art kept apart in DraftArtStore. A code is unique among published cards and drafts: a draft
 * of a published card may keep that card's code.
 */
@Service
public class CardDraftService {

    /** The largest art file accepted. */
    public static final int MAX_ART_BYTES = 25 * 1024 * 1024;

    /** A code already used by another card or draft. */
    public static class CodeTaken extends RuntimeException {
        public CodeTaken(String message) {
            super(message);
        }
    }

    /** An art file and its media type. */
    public record Art(byte[] bytes, String type) {
    }

    private final CardDraftRepository drafts;
    private final CardRepository cards;
    private final DraftArtStore store;

    public CardDraftService(CardDraftRepository drafts, CardRepository cards, DraftArtStore store) {
        this.drafts = drafts;
        this.cards = cards;
        this.store = store;
    }

    public List<CardDraft> list() {
        return drafts.findAll();
    }

    public Optional<CardDraft> get(String id) {
        return drafts.findById(id);
    }

    public Optional<CardDraft> forCard(String metaId) {
        return drafts.findFirstByMetaId(metaId);
    }

    /** A new draft: of a published card (metaId), which can have only one, or of a new card (metaId null). */
    public CardDraft create(String metaId, Card meta, Map<String, Object> design, String user) {
        if (metaId != null) {
            if (cards.findById(metaId).isEmpty()) throw new IllegalArgumentException("La carta ya no existe");
            if (drafts.findFirstByMetaId(metaId).isPresent()) throw new CodeTaken("Esta carta ya tiene un borrador");
        }
        CardDraft draft = new CardDraft();
        draft.setMetaId(metaId);
        return save(draft, meta, design, user);
    }

    public Optional<CardDraft> update(String id, Card meta, Map<String, Object> design, String user) {
        return drafts.findById(id).map(draft -> save(draft, meta, design, user));
    }

    private CardDraft save(CardDraft draft, Card meta, Map<String, Object> design, String user) {
        if (meta == null) throw new IllegalArgumentException("Faltan los datos de la carta");
        requireCode(meta);
        checkFree(meta, draft.getMetaId(), draft.getId());
        meta.setId(null);
        draft.setMeta(meta);
        draft.setDesign(design);
        draft.setUpdatedAt(Instant.now());
        draft.setUpdatedBy(user);
        return drafts.save(draft);
    }

    /** Stores a new art for the draft (JPG, PNG or WebP, as it came) and drops the one it had. */
    public Optional<CardDraft> setArt(String id, byte[] bytes, String user) {
        String type = mediaType(bytes);
        if (type == null) throw new IllegalArgumentException("El archivo no es una imagen JPG, PNG o WebP");
        if (bytes.length > MAX_ART_BYTES) throw new IllegalArgumentException("La imagen pesa más de 25 MB");
        return drafts.findById(id).map(draft -> {
            String old = draft.getArtKey();
            String key = "card-art/" + id + "/" + Instant.now().toEpochMilli() + "." + type.substring(6);
            store.put(key, bytes, type);
            draft.setArtKey(key);
            draft.setArtType(type);
            draft.setUpdatedAt(Instant.now());
            draft.setUpdatedBy(user);
            CardDraft saved = drafts.save(draft);
            if (old != null) store.delete(old);
            return saved;
        });
    }

    public Optional<CardDraft> removeArt(String id, String user) {
        return drafts.findById(id).map(draft -> {
            if (draft.getArtKey() != null) store.delete(draft.getArtKey());
            draft.setArtKey(null);
            draft.setArtType(null);
            draft.setUpdatedAt(Instant.now());
            draft.setUpdatedBy(user);
            return drafts.save(draft);
        });
    }

    public Optional<Art> art(String id) {
        return drafts.findById(id)
                .filter(d -> d.getArtKey() != null)
                .map(d -> new Art(store.get(d.getArtKey()), d.getArtType()));
    }

    /** Discards a draft and its art (the published card, if any, stays as it is). */
    public boolean delete(String id) {
        Optional<CardDraft> found = drafts.findById(id);
        found.ifPresent(d -> {
            if (d.getArtKey() != null) store.delete(d.getArtKey());
            drafts.delete(d);
        });
        return found.isPresent();
    }

    /** "E1-R03", "E1.1-P01": how the code is shown. */
    static String code(Card c) {
        String sub = blank(c.getSubEdition()) ? "" : "." + c.getSubEdition();
        return c.getEdition() + sub + "-" + c.getColorIdentity() + String.format("%02d", c.getCardNumber());
    }

    static boolean sameCode(Card a, Card b) {
        return a != null && b != null
                && Objects.equals(a.getEdition(), b.getEdition())
                && Objects.equals(blank(a.getSubEdition()) ? null : a.getSubEdition(), blank(b.getSubEdition()) ? null : b.getSubEdition())
                && a.getColorIdentity() != null && a.getColorIdentity().equalsIgnoreCase(b.getColorIdentity())
                && Objects.equals(a.getCardNumber(), b.getCardNumber());
    }

    private static void requireCode(Card meta) {
        if (blank(meta.getEdition()) || meta.getCardNumber() == null || blank(meta.getColorIdentity())) {
            throw new IllegalArgumentException("La carta necesita edición, número y color");
        }
    }

    private void checkFree(Card meta, String metaId, String draftId) {
        boolean card = cards.findByEdition(meta.getEdition()).stream()
                .anyMatch(c -> sameCode(c, meta) && !c.getId().equals(metaId));
        if (card) throw new CodeTaken("Ya existe una carta con el código " + code(meta));
        boolean draft = drafts.findAll().stream()
                .anyMatch(d -> sameCode(d.getMeta(), meta) && !d.getId().equals(draftId));
        if (draft) throw new CodeTaken("Ya hay un borrador con el código " + code(meta));
    }

    /** The image's media type from its first bytes: image/jpeg, image/png or image/webp; null for anything else. */
    static String mediaType(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
