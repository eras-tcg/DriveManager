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
@Document(collection = "newsletter")
public class Newsletter {

    @Id
    private String id;

    private String title;
    private String author;
    private Instant publishedAt;
    private String content;
    private List<String> tags;
}
