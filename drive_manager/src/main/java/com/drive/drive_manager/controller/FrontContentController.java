package com.drive.drive_manager.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.drive.drive_manager.repository.FrontContentRepository;
import org.springframework.web.bind.annotation.GetMapping;

import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.HomeContent;
import com.drive.drive_manager.dto.HomeUpdateRequest;
import com.drive.drive_manager.dto.HomeUpdateRequest.DeckWithPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
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

        List<DeckWithPosition> currentDecks = req.getDecks();

        for (DeckWithPosition deckWithPos : currentDecks) {
            int pos;
            try {
                pos = Integer.parseInt(deckWithPos.getDeckPosition());
            } catch (NumberFormatException e) {
                continue; // Skip invalid positions
            }
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
