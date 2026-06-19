package com.drive.drive_manager.simulator;

import lombok.Data;

import java.time.Instant;
import java.util.*;

@Data
public class GameRoom {

    public enum RoomStatus { WAITING, SELECTING_STARTER, MULLIGAN, IN_PROGRESS, POST_GAME, FINISHED }

    private String     roomId;
    private RoomStatus status           = RoomStatus.WAITING;
    private Map<String, PlayerState> players = new LinkedHashMap<>();
    private String     hostId;
    private String     currentTurnUserId;
    private String     currentPhase     = "MAIN";
    private int        sharedCounter    = 0;
    private Instant    createdAt        = Instant.now();

    private Map<String, String> starterSelections = new LinkedHashMap<>();
    private Set<String>         mulliganReady     = new HashSet<>();
    private Map<String, String> postGameChoices   = new LinkedHashMap<>();

    public boolean isFull() {
        return players.size() >= 2;
    }

    public PlayerState getPlayer(String userId) {
        return players.get(userId);
    }

    public String getOpponentId(String userId) {
        return players.keySet().stream()
                .filter(id -> !id.equals(userId))
                .findFirst()
                .orElse(null);
    }
}
