package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "drive_cards")
public class DriveCard {

    @Id
    private String id;

    @Field("file_name")
    private String fileName;

    private String name;

    private Integer number;

    @Field("color_identity")
    private String colorIdentity;

    private String edition;

    @Field("sub_edition")
    private String subEdition;

    @Field("time_stamp")
    private Instant timeStamp;

    /** "web" for a card made in the site's card editor (its image is the editor's finished card); null from Drive. */
    private String source;

    /**
     * The metadata id of the card whose editor render this Drive image shows now: the card was redrawn in the site's editor
     * after its Drive file was uploaded, so the record (and every deck and collection pointing to it) shows the editor's
     * latest image. A new upload from Drive replaces the record and shows the file again. Null: the Drive file.
     */
    @Field("render_of")
    private String renderOf;

    /** The prefix of the image record of a card made in the editor: "web-" + its metadata id. */
    public static final String WEB_PREFIX = "web-";
    public static final String WEB = "web";

    public DriveCard(String id, String fileName, String name, Integer number, String colorIdentity, String edition,
                     String subEdition, Instant timeStamp) {
        this(id, fileName, name, number, colorIdentity, edition, subEdition, timeStamp, null);
    }

    public DriveCard(String id, String fileName, String name, Integer number, String colorIdentity, String edition,
                     String subEdition, Instant timeStamp, String source) {
        this(id, fileName, name, number, colorIdentity, edition, subEdition, timeStamp, source, null);
    }

    /** Made in the site's card editor, not synced from Drive. */
    public boolean fromWeb() {
        return WEB.equals(source);
    }

    /**
     * Its image in R2, versioned by when it was uploaded: a corrected image gets a new URL, so browsers can keep
     * each one for good (R2Service.CACHE_CONTROL). A card made or redrawn in the editor shows the editor's finished card,
     * served by the cards' API.
     */
    public String imageUrl(String r2PublicUrl) {
        if (fromWeb() || renderOf != null) {
            String url = "/api/cards/" + (fromWeb() ? id.substring(WEB_PREFIX.length()) : renderOf) + "/render";
            return timeStamp == null ? url : url + "?v=" + timeStamp.toEpochMilli();
        }
        String url = r2PublicUrl.stripTrailing() + "/cards/" + id + ".jpg";
        return timeStamp == null ? url : url + "?v=" + timeStamp.getEpochSecond();
    }
}
