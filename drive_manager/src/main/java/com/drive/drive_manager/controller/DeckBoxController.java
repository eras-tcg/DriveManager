package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.DeckBox;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.repository.DeckBoxRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * The signed-in user's deckboxes: labels that group and order their own decks (a deck may be in several).
 *
 * GET    /api/drive/deckboxes                — mine, in order
 * POST   /api/drive/deckboxes                — create {name, color}; goes last
 * PUT    /api/drive/deckboxes/{id}           — rename / recolor {name?, color?}
 * DELETE /api/drive/deckboxes/{id}           — delete (its decks stay)
 * PUT    /api/drive/deckboxes/order          — reorder: body is every deckbox id, in the new order
 * PUT    /api/drive/deckboxes/deck/{deckId}  — which of my deckboxes hold one of my decks: body is deckbox ids
 *
 * Writes require manage_decks, like decks. Everything is scoped to the caller: another user's deckbox is a 404.
 */
@RestController
@RequestMapping("/api/drive/deckboxes")
public class DeckBoxController {

    public static final int MAX_DECKBOXES = 50;
    public static final int MAX_NAME_LENGTH = 30;
    public static final List<String> COLORS =
            List.of("red", "orange", "amber", "green", "teal", "blue", "violet", "pink", "slate");

    private final DeckBoxRepository repo;
    private final DeckListRepository decks;

    public DeckBoxController(DeckBoxRepository repo, DeckListRepository decks) {
        this.repo = repo;
        this.decks = decks;
    }

    /** Body of POST and PUT /{id}. */
    public record DeckBoxRequest(String name, String color) {}

    @GetMapping
    public ResponseEntity<List<DeckBox>> mine(Authentication auth) {
        return ResponseEntity.ok(repo.findByUserIdOrderByOrderAsc(auth.getName()));
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody DeckBoxRequest body, Authentication auth) {
        String userId = auth.getName();
        List<DeckBox> mine = repo.findByUserIdOrderByOrderAsc(userId);
        if (mine.size() >= MAX_DECKBOXES) {
            return bad("Puedes tener hasta " + MAX_DECKBOXES + " deckboxes");
        }
        String name = body == null ? null : clean(body.name());
        String err = validateName(name, mine, null);
        if (err == null) err = validateColor(body.color());
        if (err != null) return bad(err);

        DeckBox box = new DeckBox();
        box.setUserId(userId);
        box.setName(name);
        box.setColor(body.color() == null ? COLORS.get(0) : body.color());
        box.setOrder(mine.stream().mapToInt(DeckBox::getOrder).max().orElse(-1) + 1);
        box.setDeckIds(new ArrayList<>());
        box.setCreatedAt(Instant.now());
        return ResponseEntity.ok(repo.save(box));
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable String id, @RequestBody DeckBoxRequest body, Authentication auth) {
        Optional<DeckBox> found = owned(id, auth);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        DeckBox box = found.get();
        if (body == null) return bad("Falta el cuerpo");

        if (body.name() != null) {
            String name = clean(body.name());
            String err = validateName(name, repo.findByUserIdOrderByOrderAsc(auth.getName()), id);
            if (err != null) return bad(err);
            box.setName(name);
        }
        if (body.color() != null) {
            String err = validateColor(body.color());
            if (err != null) return bad(err);
            box.setColor(body.color());
        }
        return ResponseEntity.ok(repo.save(box));
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable String id, Authentication auth) {
        Optional<DeckBox> found = owned(id, auth);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PutMapping("/order")
    public ResponseEntity<Object> reorder(@RequestBody List<String> ids, Authentication auth) {
        List<DeckBox> mine = repo.findByUserIdOrderByOrderAsc(auth.getName());
        Set<String> mineIds = new HashSet<>();
        mine.forEach(box -> mineIds.add(box.getId()));
        if (ids == null || ids.size() != mine.size() || !mineIds.equals(new HashSet<>(ids))) {
            return bad("El orden debe incluir cada una de tus deckboxes una vez");
        }
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) position.put(ids.get(i), i);
        mine.forEach(box -> box.setOrder(position.get(box.getId())));
        repo.saveAll(mine);
        mine.sort(Comparator.comparingInt(DeckBox::getOrder));
        return ResponseEntity.ok(mine);
    }

    @PreAuthorize("hasAuthority('manage_decks')")
    @PutMapping("/deck/{deckId}")
    public ResponseEntity<Object> setDeckBoxes(@PathVariable String deckId,
                                               @RequestBody List<String> boxIds,
                                               Authentication auth) {
        String userId = auth.getName();
        Optional<DeckList> deck = decks.findById(deckId);
        if (deck.isEmpty() || !userId.equals(deck.get().getUserId())) {
            return ResponseEntity.status(404).body(Map.of("error", "Solo puedes guardar tus propios mazos"));
        }
        List<DeckBox> mine = repo.findByUserIdOrderByOrderAsc(userId);
        Set<String> wanted = new HashSet<>(boxIds == null ? List.of() : boxIds);
        Set<String> mineIds = new HashSet<>();
        mine.forEach(box -> mineIds.add(box.getId()));
        if (!mineIds.containsAll(wanted)) return bad("Deckbox desconocida");

        List<DeckBox> changed = new ArrayList<>();
        for (DeckBox box : mine) {
            List<String> ids = box.getDeckIds() == null ? new ArrayList<>() : new ArrayList<>(box.getDeckIds());
            boolean has = ids.contains(deckId);
            if (wanted.contains(box.getId()) && !has) ids.add(deckId);
            else if (!wanted.contains(box.getId()) && has) ids.removeIf(deckId::equals);
            else continue;
            box.setDeckIds(ids);
            changed.add(box);
        }
        repo.saveAll(changed);
        return ResponseEntity.ok(mine);
    }

    private Optional<DeckBox> owned(String id, Authentication auth) {
        return repo.findById(id).filter(box -> auth.getName().equals(box.getUserId()));
    }

    private static String clean(String name) {
        return name == null ? null : name.trim().replaceAll("\\s+", " ");
    }

    private static String validateName(String name, List<DeckBox> mine, String selfId) {
        if (name == null || name.isEmpty()) return "El nombre no puede estar vacío";
        if (name.length() > MAX_NAME_LENGTH) return "El nombre admite hasta " + MAX_NAME_LENGTH + " caracteres";
        boolean taken = mine.stream()
                .anyMatch(box -> !box.getId().equals(selfId) && box.getName().equalsIgnoreCase(name));
        return taken ? "Ya tienes una deckbox con ese nombre" : null;
    }

    private static String validateColor(String color) {
        return color == null || COLORS.contains(color) ? null : "Color desconocido";
    }

    private static ResponseEntity<Object> bad(String error) {
        return ResponseEntity.badRequest().body(Map.of("error", error));
    }
}
