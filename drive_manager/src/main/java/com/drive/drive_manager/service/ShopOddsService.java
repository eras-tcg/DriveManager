package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.ShopOdds;
import com.drive.drive_manager.repository.ShopOddsRepository;
import org.springframework.stereotype.Service;

/** The shop's odds (ShopOdds): read on every purchase and craft, so a change applies at once. */
@Service
public class ShopOddsService {

    private final ShopOddsRepository repository;

    public ShopOddsService(ShopOddsRepository repository) {
        this.repository = repository;
    }

    /** The odds in force: the saved ones, or the defaults while none were saved. */
    public ShopOdds current() {
        return repository.findById(ShopOdds.ID).orElseGet(ShopOdds::defaults);
    }

    /** Saves new odds after checking them (IllegalArgumentException with the reason, in Spanish). */
    public ShopOdds save(ShopOdds odds) {
        validate(odds);
        odds.setId(ShopOdds.ID);
        return repository.save(odds);
    }

    /** Every odd is a probability, and foil plus prismatic never go past 1. */
    static void validate(ShopOdds odds) {
        if (odds == null) throw new IllegalArgumentException("Faltan las probabilidades");
        probability(odds.getFoil(), "foil");
        probability(odds.getPrism(), "prismática");
        probability(odds.getAlt(), "arte alternativo");
        probability(odds.getBoxUpgrade(), "mejora de sobre en caja");
        probability(odds.getSubEditionBoxUpgrade(), "mejora de sobre en caja de sub-edición");
        if (odds.getFoil() + odds.getPrism() > 1) throw new IllegalArgumentException("Foil y prismática juntas no pueden pasar del 100 %");
    }

    static void probability(double value, String name) {
        if (Double.isNaN(value) || value < 0 || value > 1) throw new IllegalArgumentException("La probabilidad de " + name + " debe estar entre 0 y 100 %");
    }
}
