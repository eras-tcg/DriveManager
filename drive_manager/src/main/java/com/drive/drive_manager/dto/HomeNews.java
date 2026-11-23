package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A news item of the home's carousel, edited by the home's administrators (manage_home): its title, when (ISO date and
 * time, "2026-11-23T20:00"), its text, up to {@link #MAX_CARDS} cards shown whole beside it (drive card ids), and an
 * optional button to a page of the site (`linkRoute`, "/cards?edition=E1.3", with its `linkLabel`).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HomeNews {

    public static final int MAX_CARDS = 3;
    public static final int MAX_TITLE = 80;
    public static final int MAX_EXCERPT = 400;

    private String id;
    private String title;
    private String date;
    private String excerpt;
    private List<String> cardIds;
    private String linkRoute;
    private String linkLabel;
}
