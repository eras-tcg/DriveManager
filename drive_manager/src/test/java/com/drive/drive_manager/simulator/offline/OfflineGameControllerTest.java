package com.drive.drive_manager.simulator.offline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The upload is the signed-in player's, bounded in size and in games, and answers one verdict per game, in order. */
class OfflineGameControllerTest {

    private OfflineGames games;
    private OfflineGameController controller;

    @BeforeEach
    void setUp() {
        games = mock(OfflineGames.class);
        controller = new OfflineGameController(games, 4_000, 2);
    }

    private static final String GAME = """
            {"id":"6f1c2b9e-1111-4222-8333-944455556666","engineBuild":"abc123","seed":"42","botLevel":"easy",
             "startedAt":1762600000000,"endedAt":1762600600000,"decks":{"deck-st1":["a","a","a"]},
             "actions":[{"body":"{\\"type\\":\\"JOIN_ROOM\\",\\"payload\\":{}}","botMoves":0},
                        {"body":"{\\"type\\":\\"SET_DECK\\",\\"payload\\":{\\"deckId\\":\\"deck-st1\\"}}","botMoves":2}],
             "result":{"gameNumber":1,"winnerId":"alice","endReason":"lives","turns":9,"digest":"0123456789abcdef"},
             "unknownField":true}""";

    @Test
    void eachGameGetsItsVerdictForTheSignedInPlayer() throws Exception {
        when(games.submit(eq("alice"), any())).thenReturn(new OfflineGames.Outcome("6f1c2b9e-1111-4222-8333-944455556666",
                OfflineGames.ACCEPTED, null, null, new OfflineGames.Credit(true, 9, List.of(), "m1")));

        ResponseEntity<?> response = controller.upload(player("alice"), request("{\"games\":[" + GAME + "]}"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<OfflineGames.Outcome> results = (List<OfflineGames.Outcome>) ((Map<String, Object>) response.getBody()).get("results");
        assertThat(results).singleElement().extracting(OfflineGames.Outcome::status).isEqualTo(OfflineGames.ACCEPTED);
        verify(games).submit(eq("alice"), argThat(g -> g.seed().equals("42") && g.actions().size() == 2
                && g.actions().get(1).botMoves() == 2 && g.result().digest().equals("0123456789abcdef")
                && g.decks().get("deck-st1").size() == 3));
    }

    @Test
    void anOversizedUploadIsRefusedUnread() throws Exception {
        String big = "{\"games\":[" + GAME + "],\"pad\":\"" + "x".repeat(5_000) + "\"}";
        assertThat(controller.upload(player("alice"), request(big)).getStatusCode().value()).isEqualTo(413);
        // A body that does not say its length is cut at the limit
        assertThat(controller.upload(player("alice"), new MockHttpServletRequest() {
            {
                setContent(big.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        }).getStatusCode().value()).isEqualTo(413);
        verify(games, never()).submit(any(), any());
    }

    @Test
    void tooManyGamesOrAnUnreadableBodyIsABadRequest() throws Exception {
        assertThat(controller.upload(player("alice"), request("{\"games\":[" + GAME + "," + GAME + "," + GAME + "]}"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.upload(player("alice"), request("not json")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.upload(player("alice"), request("{}")).getStatusCode().value()).isEqualTo(400);
        verify(games, never()).submit(any(), any());
    }

    @Test
    void onlyASignedInPlayer() throws Exception {
        assertThat(controller.upload(null, request("{\"games\":[]}")).getStatusCode().value()).isEqualTo(401);
    }

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/simulator/offline-games");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static Authentication player(String id) {
        return new UsernamePasswordAuthenticationToken(id, null, AuthorityUtils.createAuthorityList("manage_decks"));
    }
}
