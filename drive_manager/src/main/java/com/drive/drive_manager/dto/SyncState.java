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
@Document("sync_state")
public class SyncState {

    @Id
    private String id; // always "drive" — single document

    @Field("page_token")
    private String pageToken;

    @Field("channel_id")
    private String channelId;

    @Field("resource_id")
    private String resourceId; // needed to stop the channel

    @Field("channel_expiration")
    private Instant channelExpiration;
}
