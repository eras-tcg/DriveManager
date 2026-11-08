package com.drive.drive_manager.simulator.json;

import com.drive.drive_manager.simulator.engine.PendingEffect;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.jackson.JacksonMixin;
import tools.jackson.databind.json.JsonMapper;

/**
 * What of the engine's classes is never sent to the players nor summed up in a game's fingerprint: the server's
 * bookkeeping, the engine's working state and what is secret. Kept here as Jackson mix-ins, not on the classes, so the
 * engine does without Jackson (it is to run in the browser too).
 * <p>
 * Spring Boot adds every {@link JacksonMixin} to its JSON mapper (the one the WebSocket messages and the REST answers
 * use); the mappers made by hand (the fingerprints of {@code MatchReplay}, the kept games of {@code MatchHistory})
 * take them through {@link #configure}. {@code SimulatorJsonTest} checks the JSON is the same as with the annotations.
 */
public final class SimulatorJson {

    private SimulatorJson() {}

    /** A mapper's builder with the simulator's mix-ins. */
    public static JsonMapper.Builder configure(JsonMapper.Builder builder) {
        return builder
                .addMixIn(GameRoom.class, GameRoomJson.class)
                .addMixIn(PlayerState.class, PlayerStateJson.class)
                .addMixIn(CardSlot.class, CardSlotJson.class)
                .addMixIn(PendingEffect.class, PendingEffectJson.class);
    }

    /** The room: the server's own settings and bookkeeping, and its chance (which would reveal what comes next). */
    @JacksonMixin(GameRoom.class)
    abstract static class GameRoomJson {
        @JsonIgnore Object reserved;
        @JsonIgnore Object version;
        @JsonIgnore Object manualPlayers;
        @JsonIgnore Object adminPlayers;
        @JsonIgnore Object random;
        @JsonIgnore Object headless;
        @JsonIgnore Object searchHope;
        @JsonIgnore Object startSetup;
    }

    /** A player: when they lost the connection (the server's to know). */
    @JacksonMixin(PlayerState.class)
    abstract static class PlayerStateJson {
        @JsonIgnore Object disconnectedAt;
    }

    /** A card: the addresses of its alternative arts (only the one it shows travels). */
    @JacksonMixin(CardSlot.class)
    abstract static class CardSlotJson {
        @JsonIgnore Object altArtUrls;
    }

    /** An effect being resolved: the engine's working state, and the cards it looked at (secret). */
    @JacksonMixin(PendingEffect.class)
    abstract static class PendingEffectJson {
        @JsonIgnore Object repeatsDone;
        @JsonIgnore Object looked;
        @JsonIgnore Object ordered;
        @JsonIgnore Object confirmed;
        @JsonIgnore Object lastDone;
        @JsonIgnore Object lastCount;
        @JsonIgnore Object lastTributed;
        @JsonIgnore Object keepOnField;
        @JsonIgnore Object passed;
        @JsonIgnore Object priorityDone;
    }
}
