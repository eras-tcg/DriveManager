package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.DeckList;
import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.DeckListRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A structure deck the player gets becomes their private deck, once. */
class StructureDeckServiceTest {

    private DeckListRepository decks;
    private MongoTemplate mongo;
    private StructureDeckService service;
    private final EditionMetadata st2 = new EditionMetadata();

    @BeforeEach
    void setUp() {
        decks = mock(DeckListRepository.class);
        mongo = mock(MongoTemplate.class);
        service = new StructureDeckService(decks, mongo);
        st2.setEditionId("ST2");
        st2.setEditionName("Habitantes de la tierra mágica");
        when(decks.save(any(DeckList.class))).thenAnswer(call -> call.getArgument(0));
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("users"))).thenReturn(new Document("username", "alice"));
    }

    private static DeckList deck(String name, boolean privateDeck) {
        DeckList d = new DeckList();
        d.setDeckName(name);
        d.setPrivateDeck(privateDeck);
        d.setDeckImage("cover.png");
        d.setCards(List.of("x"));
        return d;
    }

    @Test
    void theDeckIsPrivateWithTheCardsGotAndTheStructuresNameCutAtAWord() {
        when(decks.findByUserId("u1")).thenReturn(List.of(deck("Mi mazo", true)));
        when(decks.findPublicDecks()).thenReturn(List.of(deck("ST1 Saltadores", false), deck("ST2 Habitantes de la tierra mágica", false)));

        DeckList saved = service.giveTo("u1", st2, List.of("a", "a", "b"));

        ArgumentCaptor<DeckList> captor = ArgumentCaptor.forClass(DeckList.class);
        verify(decks).save(captor.capture());
        DeckList d = captor.getValue();
        assertThat(saved).isSameAs(d);
        assertThat(d.getDeckName()).isEqualTo("ST2 Habitantes de la tierra");
        assertThat(d.getCards()).containsExactly("a", "a", "b");
        assertThat(d.getDeckImage()).isEqualTo("cover.png");
        assertThat(d.getUserId()).isEqualTo("u1");
        assertThat(d.getUsername()).isEqualTo("alice");
        assertThat(d.isPrivateDeck()).isTrue();
        assertThat(d.isSystemDeck()).isFalse();
        assertThat(d.getCreatedAt()).isNotNull();
    }

    @Test
    void aPlayerWhoHasADeckNamedAfterTheStructureGetsNoOther() {
        DeckList theirs = deck("ST2 mío", true);
        when(decks.findByUserId("u1")).thenReturn(List.of(theirs));

        assertThat(service.giveTo("u1", st2, List.of("a"))).isSameAs(theirs);
        verify(decks, never()).save(any());
    }

    @Test
    void withoutAPublicDeckItIsNamedAfterTheEdition() {
        when(decks.findByUserId("u1")).thenReturn(List.of());
        when(decks.findPublicDecks()).thenReturn(List.of());

        assertThat(service.giveTo("u1", st2, List.of("a")).getDeckName()).isEqualTo("ST2 Habitantes de la tierra");
    }

    @Test
    void namesAreCutWithoutHalfAWord() {
        assertThat(StructureDeckService.wholeWords("ST1 corto", 30)).isEqualTo("ST1 corto");
        assertThat(StructureDeckService.wholeWords("ST3 Terrores del inframundo xx", 30)).isEqualTo("ST3 Terrores del inframundo xx");
        assertThat(StructureDeckService.wholeWords("ST2 Habitantes de la tierra mágica", 30)).isEqualTo("ST2 Habitantes de la tierra");
        assertThat(StructureDeckService.wholeWords("ST4 Guerreros de la tierra ant igua", 30)).isEqualTo("ST4 Guerreros de la tierra ant");
    }
}
