package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One alternative art of a card (Card.altArts): a very rare picture of the same card a pulled copy may come out with.
 * `odds` is its own chance per pulled copy; null takes the shop's (ShopOdds.alt).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AltArt {

    /** Short id, kept in the collection's keys ("alt-" + id + "-" + finish): letters and digits only. */
    private String id;
    private String url;
    private Double odds;
}
