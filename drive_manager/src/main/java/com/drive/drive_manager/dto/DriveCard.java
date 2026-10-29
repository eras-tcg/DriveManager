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

    /**
     * Its image in R2, versioned by when it was uploaded: a corrected image gets a new URL, so browsers can keep
     * each one for good (R2Service.CACHE_CONTROL).
     */
    public String imageUrl(String r2PublicUrl) {
        String url = r2PublicUrl.stripTrailing() + "/cards/" + id + ".jpg";
        return timeStamp == null ? url : url + "?v=" + timeStamp.getEpochSecond();
    }
}
