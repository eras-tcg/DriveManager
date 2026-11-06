package com.drive.drive_manager.simulator.rank;

import org.bson.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The ranked ladder: the signed-in player's rank (with what their last ranked game changed), any player's rank (to
 * show beside their name) and the Retador board. The hidden strength (MMR) is never sent.
 */
@RestController
@RequestMapping("/api/simulator/ranks")
public class RankController {

    private final RankService ranks;
    /** Each player's icon, beside their name on the board (none: their initial). */
    private final com.drive.drive_manager.service.AvatarService avatars;

    public RankController(RankService ranks, org.springframework.beans.factory.ObjectProvider<com.drive.drive_manager.service.AvatarService> avatars) {
        this.ranks = ranks;
        this.avatars = avatars.getIfAvailable();
    }

    @GetMapping("/me")
    public ResponseEntity<Document> mine(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(ranks.view(auth.getName()));
    }

    /** The Retador board, the most points first: each player's place, name, icon and points. */
    @GetMapping("/challengers")
    public ResponseEntity<List<Document>> challengers(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        List<Document> board = ranks.challengers();
        for (int i = 0; i < board.size(); i++) {
            Document row = board.get(i);
            row.put("place", i + 1);
            row.put("avatar", avatars == null ? null : avatars.urlOf(row.getString("userId")));
        }
        return ResponseEntity.ok(board);
    }

    /** A player's rank, without their last change. */
    @GetMapping("/{userId}")
    public ResponseEntity<Document> of(Authentication auth, @PathVariable String userId) {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        Document view = ranks.view(userId);
        view.remove("lastChange");
        return ResponseEntity.ok(view);
    }
}
