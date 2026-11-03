package com.drive.drive_manager.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The shop's odds that no edition sets (each edition keeps its own pack types and box), one document in
 * "shop_settings": the finish of every copy pulled or crafted, the alternative art of a pulled copy (unless the art
 * has its own) and a box's pack going up a rarity. Each is a probability from 0 to 1; editable by manage_editions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "shop_settings")
public class ShopOdds {

    public static final String ID = "odds";

    @Id
    private String id;
    /** A copy comes out foil. */
    private double foil;
    /** A copy comes out prismatic (rolled before foil: both together are at most 1). */
    private double prism;
    /** A pulled copy of a card with alternative arts comes out with one, for an art without odds of its own. */
    private double alt;
    /** A box of an edition (E1, E2…) turns one of its packs into the next type up. */
    private double boxUpgrade;
    /** The same for a box of a sub-edition (E1.1…). */
    private double subEditionBoxUpgrade;

    /** The odds until an administrator changes them (the ones the shop always had). */
    public static ShopOdds defaults() {
        return new ShopOdds(ID, 0.05, 0.005, 0.0001, 1, 0.5);
    }
}
