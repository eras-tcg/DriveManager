package com.drive.drive_manager.controller;

import com.drive.drive_manager.dto.HomeContent;
import com.drive.drive_manager.dto.HomeNews;
import com.drive.drive_manager.dto.HomeUpdateRequest;
import com.drive.drive_manager.repository.FrontContentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrontContentNewsTest {

    private static HomeNews news(String id, String title, List<String> cards, String route) {
        return new HomeNews(id, title, "2026-11-23T20:00", " Texto ", cards, route, route == null ? null : "Ver cartas");
    }

    @Test
    void theNewsAreSavedInOrderTrimmedAndEachWithAnId() {
        FrontContentRepository repo = mock(FrontContentRepository.class);
        when(repo.findById("home")).thenReturn(Optional.empty());
        HomeUpdateRequest req = new HomeUpdateRequest();
        req.setNews(List.of(news("n1", " Llega E1.3 ", List.of("c1", "c2"), "/cards?edition=E1.3"), news(null, "Otra", null, " ")));

        assertThat(new FrontContentController(repo).updateHome(req).getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<HomeContent> saved = ArgumentCaptor.forClass(HomeContent.class);
        verify(repo).save(saved.capture());
        List<HomeNews> out = saved.getValue().getNews();
        assertThat(out).extracting(HomeNews::getTitle).containsExactly("Llega E1.3", "Otra");
        assertThat(out.get(0).getExcerpt()).isEqualTo("Texto");
        assertThat(out.get(0).getCardIds()).containsExactly("c1", "c2");
        assertThat(out.get(1).getId()).isNotBlank();
        assertThat(out.get(1).getLinkRoute()).isNull();
        assertThat(out.get(1).getLinkLabel()).isNull();
    }

    @Test
    void newsWithoutTitleOrWithTooManyCardsAreRefused() {
        FrontContentRepository repo = mock(FrontContentRepository.class);
        when(repo.findById("home")).thenReturn(Optional.empty());
        HomeUpdateRequest req = new HomeUpdateRequest();
        req.setNews(List.of(news("n1", " ", List.of(), null)));
        assertThat(new FrontContentController(repo).updateHome(req).getStatusCode().value()).isEqualTo(400);
        req.setNews(List.of(news("n1", "Ok", List.of("a", "b", "c", "d"), null)));
        assertThat(new FrontContentController(repo).updateHome(req).getStatusCode().value()).isEqualTo(400);
        req.setNews(List.of(news("n1", "Ok", List.of(), "https://otro.sitio")));
        assertThat(new FrontContentController(repo).updateHome(req).getStatusCode().value()).isEqualTo(400);
        verify(repo, never()).save(any());
    }

    @Test
    void withoutNewsTheSavedOnesStay() {
        FrontContentRepository repo = mock(FrontContentRepository.class);
        HomeContent home = new HomeContent(null, new java.util.ArrayList<>());
        home.setId("home");
        home.setNews(List.of(news("n1", "Queda", List.of(), null)));
        when(repo.findById("home")).thenReturn(Optional.of(home));

        new FrontContentController(repo).updateHome(new HomeUpdateRequest());

        assertThat(home.getNews()).extracting(HomeNews::getTitle).containsExactly("Queda");
    }
}
