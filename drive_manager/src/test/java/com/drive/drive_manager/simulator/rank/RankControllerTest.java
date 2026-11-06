package com.drive.drive_manager.simulator.rank;

import com.drive.drive_manager.service.AvatarService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Retador board as the web reads it: each row's place and icon, and never anyone's hidden strength. */
class RankControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void theBoardNumbersItsRowsAndAddsTheirIcons() {
        RankService ranks = mock(RankService.class);
        when(ranks.challengers()).thenReturn(new ArrayList<>(List.of(
                new Document("userId", "a").append("username", "Ana").append("points", 80),
                new Document("userId", "b").append("username", "Beto").append("points", 40))));
        AvatarService avatars = mock(AvatarService.class);
        when(avatars.urlOf("a")).thenReturn("https://img/a.png");
        ObjectProvider<AvatarService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(avatars);
        RankController controller = new RankController(ranks, provider);

        var auth = new UsernamePasswordAuthenticationToken("a", null, List.of());
        List<Document> board = controller.challengers(auth).getBody();
        assertThat(board).hasSize(2);
        assertThat(board.get(0).getInteger("place")).isEqualTo(1);
        assertThat(board.get(0).getString("avatar")).isEqualTo("https://img/a.png");
        assertThat(board.get(1).getInteger("place")).isEqualTo(2);
        assertThat(board.get(1).get("avatar")).isNull();
        assertThat(board).allMatch(d -> !d.containsKey("mmr"));
        assertThat(controller.challengers(null).getStatusCode().value()).isEqualTo(401);
    }
}
