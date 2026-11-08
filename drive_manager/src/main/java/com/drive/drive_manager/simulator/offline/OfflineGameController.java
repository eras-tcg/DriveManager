package com.drive.drive_manager.simulator.offline;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The games the signed-in player played offline against the bot, sent when they connect again
 * ({@code POST /api/simulator/offline-games}, {@code {"games": [<OfflineGame>…]}}): each gets its verdict
 * ({@link OfflineGames}), in order: {@code {"results": [{"id", "status", "reason", "message", "credit"}…]}}. A body over
 * {@code maxBytes} answers 413, more than {@code maxGames} games or an unreadable body 400.
 */
@RestController
@RequestMapping("/api/simulator/offline-games")
public class OfflineGameController {

    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final OfflineGames games;
    private final int maxBytes;
    private final int maxGames;

    public OfflineGameController(OfflineGames games, @Value("${offline-games.max-request-bytes:2000000}") int maxBytes,
                                 @Value("${offline-games.max-games-per-request:10}") int maxGames) {
        this.games = games;
        this.maxBytes = maxBytes;
        this.maxGames = maxGames;
    }

    /** What the site sends. */
    public record Upload(List<OfflineGame> games) {
    }

    @PostMapping
    public ResponseEntity<?> upload(Authentication auth, HttpServletRequest request) throws IOException {
        if (auth == null || !auth.isAuthenticated()) return ResponseEntity.status(401).build();
        if (request.getContentLengthLong() > maxBytes) return tooLarge();
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(maxBytes + 1);
        }
        if (body.length > maxBytes) return tooLarge();
        return upload(auth.getName(), body);
    }

    /** The body read (at most maxBytes): the verdicts. */
    ResponseEntity<?> upload(String userId, byte[] body) {
        Upload upload;
        try {
            upload = JSON.readValue(body, Upload.class);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "No se pudieron leer las partidas"));
        }
        if (upload == null || upload.games() == null) return ResponseEntity.badRequest().body(Map.of("message", "No se pudieron leer las partidas"));
        if (upload.games().size() > maxGames)
            return ResponseEntity.badRequest().body(Map.of("message", "Se envían a lo más " + maxGames + " partidas a la vez"));
        List<OfflineGames.Outcome> results = new ArrayList<>();
        for (OfflineGame game : upload.games()) results.add(games.submit(userId, game));
        return ResponseEntity.ok(Map.of("results", results));
    }

    private static ResponseEntity<?> tooLarge() {
        return ResponseEntity.status(413).body(Map.of("message", "Las partidas enviadas son demasiado grandes"));
    }
}
