package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "edition_metadata")
public class EditionMetadata {

    @Id
    private String editionId;

    @Field("edition_name")
    private String editionName;

    @Field("number_of_cards")
    private int numberOfCards;

    @Field("edition_description")
    private String editionDescription;

    @Field("edition_image")
    private String editionImage;

    @Field("release_date")
    private String releaseDate;

    // ── Booster simulation config ──────────────────────────────────────────────

    @Field("pack_types")
    private List<PackTypeConfig> packTypes;

    @Field("box_config_v2")
    private BoxConfig boxConfig;

    // ── Nested types ───────────────────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackTypeConfig {
        private String name;

        /** Cards drawn per rarity slot. Keys: "C", "UC", "R", "SR", "SEC" */
        @Field("cards_per_rarity")
        private Map<String, Integer> cardsPerRarity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BoxConfig {
        /** Total number of packs in a box. */
        @Field("total_packs")
        private int totalPacks;

        private List<BoxEntry> entries;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BoxEntry {
        @Field("pack_type_name")
        private String packTypeName;

        /**
         * "FIXED"  — always exactly `quantity` packs of this type.
         * "PROB"   — pick a quantity according to `probOptions` distribution.
         * "FLEX"   — fills remaining slots after FIXED and PROB are placed.
         */
        private String mode;

        /** Used when mode = "FIXED". */
        private Integer quantity;

        /** Used when mode = "PROB". Each option: { quantity, prob }; probs must sum to 1. */
        @Field("prob_options")
        private List<ProbOption> probOptions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProbOption {
        private int quantity;
        private double prob;
    }
}
