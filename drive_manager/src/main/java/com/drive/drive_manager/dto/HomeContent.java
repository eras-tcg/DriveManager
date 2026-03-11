package com.drive.drive_manager.dto;

import lombok.Data;
import java.util.List;
import org.springframework.data.annotation.Id;

@Data
public class HomeContent {

    @Id
    private String id;

    private EditionMetadata edition;
    private List<DeckList> decks;

    public HomeContent() {}

    public HomeContent(EditionMetadata edition, List<DeckList> decks) {
        this.edition = edition;
        this.decks = decks;
    }
}