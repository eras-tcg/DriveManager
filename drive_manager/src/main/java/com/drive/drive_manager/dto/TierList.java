package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "tierlists")
public class TierList {

    @Id
    private String id;

    private List<TierPage> pages;

    private Instant updatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierPage {
        private String id;
        private String name;
        private List<TierRow> tiers;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierRow {
        private String label;
        private String color;
        private List<String> deckIds;
    }
}
