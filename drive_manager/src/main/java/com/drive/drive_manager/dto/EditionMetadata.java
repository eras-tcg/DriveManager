package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "edition_metadata")
public class EditionMetadata {

    @Id
    private String editionId;          // E1, E1.1, ST4, etc.

    @Field("edition_name")
    private String editionName;        // "El comienzo del fin de los tiempos"

    @Field("number_of_cards")
    private int numberOfCards;

    @Field("edition_description")
    private String editionDescription; // lore, rates, etc.

    @Field("edition_image")
    private String editionImage;       // R2 URL once pulled, or Drive file ID before
}
