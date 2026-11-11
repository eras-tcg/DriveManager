package com.drive.drive_manager.controller;

import com.drive.drive_manager.controller.DeckBoxController.DeckBoxRequest;
import com.drive.drive_manager.dto.DeckBox;
import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.repository.DeckBoxRepository;
import com.drive.drive_manager.repository.DeckListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** DeckBoxController over an in-memory repository. */
class DeckBoxControllerTest {

    private final Map<String, DeckBox> store = new LinkedHashMap<>();
    private final Map<String, DeckList> deckStore = new HashMap<>();
    private DeckBoxController controller;
    private int nextId = 1;

    @BeforeEach
    void setUp() {
        DeckBoxRepository repo = (DeckBoxRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DeckBoxRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findByUserIdOrderByOrderAsc" -> store.values().stream()
                            .filter(b -> b.getUserId().equals(args[0]))
                            .sorted(Comparator.comparingInt(DeckBox::getOrder))
                            .collect(Collectors.toCollection(ArrayList::new));
                    case "findById" -> Optional.ofNullable(store.get((String) args[0]));
                    case "save" -> save((DeckBox) args[0]);
                    case "saveAll" -> {
                        List<DeckBox> out = new ArrayList<>();
                        ((Iterable<?>) args[0]).forEach(b -> out.add(save((DeckBox) b)));
                        yield out;
                    }
                    case "deleteById" -> {
                        store.remove((String) args[0]);
                        yield null;
                    }
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DeckListRepository decks = (DeckListRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DeckListRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(deckStore.get((String) args[0]));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        controller = new DeckBoxController(repo, decks);
        deckStore.put("d-alice", deck("d-alice", "alice"));
        deckStore.put("d-bob", deck("d-bob", "bob"));
    }

    private DeckBox save(DeckBox box) {
        if (box.getId() == null) box.setId("b" + nextId++);
        store.put(box.getId(), box);
        return box;
    }

    @Test
    void createsInOrderAndListsOnlyMine() {
        create("alice", "Competitivos", "red");
        create("alice", "Casuales", null);
        create("bob", "De bob", "blue");

        List<DeckBox> mine = controller.mine(user("alice")).getBody();
        assertThat(mine).extracting(DeckBox::getName).containsExactly("Competitivos", "Casuales");
        assertThat(mine).extracting(DeckBox::getOrder).containsExactly(0, 1);
        assertThat(mine.get(1).getColor()).isEqualTo("red"); // default: first color
    }

    @Test
    void rejectsBadNamesAndColors() {
        create("alice", "Competitivos", "red");
        assertThat(status(controller.create(new DeckBoxRequest("  ", "red"), user("alice")))).isEqualTo(400);
        assertThat(status(controller.create(new DeckBoxRequest("x".repeat(31), "red"), user("alice")))).isEqualTo(400);
        assertThat(status(controller.create(new DeckBoxRequest("competitivos", "red"), user("alice")))).isEqualTo(400);
        assertThat(status(controller.create(new DeckBoxRequest("Otra", "neon"), user("alice")))).isEqualTo(400);
        // Another user may reuse the name
        assertThat(status(controller.create(new DeckBoxRequest("Competitivos", "red"), user("bob")))).isEqualTo(200);
    }

    @Test
    void allowsAtMost50() {
        for (int i = 0; i < DeckBoxController.MAX_DECKBOXES; i++) create("alice", "Caja " + i, null);
        assertThat(status(controller.create(new DeckBoxRequest("Una más", null), user("alice")))).isEqualTo(400);
    }

    @Test
    void renamesRecolorsAndDeletesOnlyMine() {
        DeckBox box = create("alice", "Viejo", "red");
        assertThat(status(controller.update(box.getId(), new DeckBoxRequest("Nuevo", null), user("bob")))).isEqualTo(404);
        assertThat(status(controller.delete(box.getId(), user("bob")))).isEqualTo(404);

        controller.update(box.getId(), new DeckBoxRequest("  Nuevo   nombre ", "teal"), user("alice"));
        assertThat(store.get(box.getId()).getName()).isEqualTo("Nuevo nombre");
        assertThat(store.get(box.getId()).getColor()).isEqualTo("teal");

        assertThat(status(controller.delete(box.getId(), user("alice")))).isEqualTo(204);
        assertThat(store).isEmpty();
    }

    @Test
    void reordersWithEveryIdOnce() {
        DeckBox a = create("alice", "A", null);
        DeckBox b = create("alice", "B", null);
        DeckBox c = create("alice", "C", null);
        assertThat(status(controller.reorder(List.of(c.getId(), a.getId()), user("alice")))).isEqualTo(400);

        controller.reorder(List.of(c.getId(), a.getId(), b.getId()), user("alice"));
        assertThat(controller.mine(user("alice")).getBody()).extracting(DeckBox::getName).containsExactly("C", "A", "B");
    }

    @Test
    void setsWhichDeckboxesHoldADeck() {
        DeckBox a = create("alice", "A", null);
        DeckBox b = create("alice", "B", null);
        DeckBox bobs = create("bob", "B", null);

        controller.setDeckBoxes("d-alice", List.of(a.getId(), b.getId()), user("alice"));
        assertThat(a.getDeckIds()).containsExactly("d-alice");
        assertThat(b.getDeckIds()).containsExactly("d-alice");

        controller.setDeckBoxes("d-alice", List.of(b.getId()), user("alice"));
        assertThat(a.getDeckIds()).isEmpty();
        assertThat(b.getDeckIds()).containsExactly("d-alice");

        // Only my decks, only into my deckboxes
        assertThat(status(controller.setDeckBoxes("d-bob", List.of(a.getId()), user("alice")))).isEqualTo(404);
        assertThat(status(controller.setDeckBoxes("d-alice", List.of(bobs.getId()), user("alice")))).isEqualTo(400);
        assertThat(bobs.getDeckIds()).isEmpty();
    }

    private DeckBox create(String user, String name, String color) {
        return (DeckBox) controller.create(new DeckBoxRequest(name, color), user(user)).getBody();
    }

    private static int status(org.springframework.http.ResponseEntity<?> response) {
        return response.getStatusCode().value();
    }

    private static Authentication user(String id) {
        return new UsernamePasswordAuthenticationToken(id, null, AuthorityUtils.NO_AUTHORITIES);
    }

    private static DeckList deck(String id, String userId) {
        DeckList d = new DeckList();
        d.setId(id);
        d.setUserId(userId);
        d.setCards(List.of("E2-B01"));
        return d;
    }
}
