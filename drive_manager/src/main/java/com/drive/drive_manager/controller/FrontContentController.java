package com.drive.drive_manager.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.drive.drive_manager.repository.FrontContentRepository;
import org.springframework.web.bind.annotation.GetMapping;

import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.HomeContent;
import com.drive.drive_manager.dto.HomeNews;
import com.drive.drive_manager.dto.HomeUpdateRequest;
import com.drive.drive_manager.dto.HomeUpdateRequest.DeckWithPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;




@RestController
@RequestMapping("/api/drive/front-content")
public class FrontContentController {
    
    private final FrontContentRepository repo;

    public FrontContentController(FrontContentRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/home")
    public ResponseEntity<HomeContent> getHome() {

        return repo.findAll().stream().findFirst()
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /** The featured decks never go past this slot: a huge position would fill the list with millions of gaps. */
    private static final int MAX_DECK_POSITION = 50;

    /** Why the news cannot be saved, or null: each needs a title, a date and its text, within their lengths and card count. */
    static String newsProblem(List<HomeNews> news) {
        for (HomeNews n : news) {
            if (n == null || n.getTitle() == null || n.getTitle().isBlank()) return "Cada noticia necesita un título";
            if (n.getTitle().strip().length() > HomeNews.MAX_TITLE) return "El título admite hasta " + HomeNews.MAX_TITLE + " caracteres";
            if (n.getDate() == null || n.getDate().isBlank()) return "Cada noticia necesita una fecha";
            if (n.getExcerpt() != null && n.getExcerpt().strip().length() > HomeNews.MAX_EXCERPT)
                return "El texto admite hasta " + HomeNews.MAX_EXCERPT + " caracteres";
            if (n.getBody() != null && n.getBody().strip().length() > HomeNews.MAX_BODY)
                return "La noticia completa admite hasta " + HomeNews.MAX_BODY + " caracteres";
            if (n.getCardIds() != null && n.getCardIds().size() > HomeNews.MAX_CARDS) return "Cada noticia lleva hasta " + HomeNews.MAX_CARDS + " cartas";
            if (n.getLinkRoute() != null && !n.getLinkRoute().isBlank() && !n.getLinkRoute().startsWith("/"))
                return "El botón lleva a una página del sitio (empieza con /)";
        }
        return null;
    }

    /** The news trimmed, each with an id (a new one gets one) and without empty button parts. */
    static List<HomeNews> cleanNews(List<HomeNews> news) {
        List<HomeNews> out = new ArrayList<>();
        for (HomeNews n : news) {
            String route = n.getLinkRoute() == null || n.getLinkRoute().isBlank() ? null : n.getLinkRoute().strip();
            String label = route == null || n.getLinkLabel() == null || n.getLinkLabel().isBlank() ? null : n.getLinkLabel().strip();
            out.add(new HomeNews(
                    n.getId() == null || n.getId().isBlank() ? java.util.UUID.randomUUID().toString() : n.getId(),
                    n.getTitle().strip(), n.getDate().strip(), n.getExcerpt() == null ? "" : n.getExcerpt().strip(),
                    n.getCardIds() == null ? List.of() : n.getCardIds().stream().filter(id -> id != null && !id.isBlank()).toList(),
                    route, label, n.getBody() == null || n.getBody().isBlank() ? null : n.getBody().strip()));
        }
        return out;
    }

    @PreAuthorize("hasAuthority('manage_home')")
    @PostMapping("/home")
    public ResponseEntity<?> updateHome(@RequestBody HomeUpdateRequest req) {

        HomeContent home = repo.findById("home").orElse(null);
        if (home == null) {
            home = new HomeContent(null, new ArrayList<>());
            home.setId("home");   // force singleton document
        }

        if (req.getEdition() != null) {
            home.setEdition(req.getEdition());
        }

        if (req.getNews() != null) {
            String problem = newsProblem(req.getNews());
            if (problem != null) return ResponseEntity.badRequest().body(java.util.Map.of("error", problem));
            home.setNews(cleanNews(req.getNews()));
        }

        List<DeckWithPosition> currentDecks = req.getDecks() == null ? List.of() : req.getDecks();

        for (DeckWithPosition deckWithPos : currentDecks) {
            int pos;
            try {
                pos = Integer.parseInt(deckWithPos.getDeckPosition());
            } catch (NumberFormatException e) {
                continue; // Skip invalid positions
            }
            if (pos < 0 || pos > MAX_DECK_POSITION) continue;
            List<DeckList> decks = home.getDecks();
            while (decks.size() <= pos) {
                decks.add(null); // Fill gaps with nulls
            }
            decks.set(pos, deckWithPos.getDeck());
        }
        repo.save(home);

        return ResponseEntity.ok("Home content updated");
    }
    
}
