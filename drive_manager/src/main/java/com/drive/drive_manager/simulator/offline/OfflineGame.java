package com.drive.drive_manager.simulator.offline;

import java.util.List;
import java.util.Map;

/**
 * A game played offline against the bot ("Modo solo sin conexión": eras-engine's LocalMatch in the browser), as the
 * site records it and sends it when it connects again. Enough to play it again exactly on the server: the match's seed
 * and bot level, the decks it used (card ids as the site had them), and every action of the player in order, each with
 * how many moves the bot made after it before the next one (the browser's worker moves the bot between the player's
 * actions; this order is the game). The claimed result is checked against the replay, never trusted.
 *
 * @param id          the game's id, made by the site (a UUID): the same game sent twice is counted once
 * @param engineBuild the engine's build that played it (eras-web's vendor/eras-engine BUILD.json commit), kept with it
 * @param seed        the match's seed (a decimal 64-bit number, as LocalMatch reads it)
 * @param botLevel    the match's configured bot level (null: LocalMatch's default); the pre-room may change it by action
 * @param startedAt   when the room was made, epoch millis (the device's clock)
 * @param endedAt     when the game reached its end, epoch millis
 * @param decks       every deck the actions chose (SET_DECK), by id: its card ids, one per copy, in the deck's order
 * @param actions     the player's actions, as the board sent them, with the bot's moves after each
 * @param result      what the site saw at the end
 */
public record OfflineGame(String id, String engineBuild, String seed, String botLevel, Long startedAt, Long endedAt,
                          Map<String, List<String>> decks, List<Step> actions, Claim result) {

    /** One action of the player (the body the board publishes) and the bot's moves after it, before the next action. */
    public record Step(String body, int botMoves) {
    }

    /**
     * The result as the site saw it (LocalMatch.result()): which game of the room it was (a rematch plays another in the
     * same room), the winner, why it ended, its turns and the digest of the game (MatchDigest).
     */
    public record Claim(int gameNumber, String winnerId, String endReason, int turns, String digest) {
    }
}
