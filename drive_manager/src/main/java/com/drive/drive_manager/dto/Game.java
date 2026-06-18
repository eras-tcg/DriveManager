package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "games")
public class Game {

    @Id
    private String id;

    private PlayerEntry player1;
    private PlayerEntry player2;

    private String winner;       // "player1" | "player2"

    @Field("first_player")
    private String firstPlayer;  // "player1" | "player2"

    private String comment;

    private Integer duration; // total seconds, nullable

    @Field("created_at")
    private Instant createdAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlayerEntry {
        private String playerName;
        private String deckId;
        private List<String> lives; // ordered list of "up" | "down"
    }
}
