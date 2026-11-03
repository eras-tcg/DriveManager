package com.drive.drive_manager.service;

import com.drive.drive_manager.dto.EditionMetadata;
import com.drive.drive_manager.repository.EditionMetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Buying in the Editions shop: the gems are charged (WalletService), what was bought is drawn here and added to the
 * player's collection, and the web only shows it. A pack's type comes at random by a box's odds; a box goes up a
 * rarity on one pack (always for an edition, half the times for a sub-edition); a structure deck comes whole and
 * plain. Each card of a pack or box comes out foil by CollectionRules' odds.
 */
@Service
public class ShopService {

    private final WalletService wallets;
    private final EditionMetadataRepository editions;
    private final CardPoolService pool;
    private final CollectionService collections;
    private final DoubleSupplier random;

    @Autowired
    public ShopService(WalletService wallets, EditionMetadataRepository editions, CardPoolService pool, CollectionService collections) {
        this(wallets, editions, pool, collections, new SecureRandom()::nextDouble);
    }

    ShopService(WalletService wallets, EditionMetadataRepository editions, CardPoolService pool, CollectionService collections, DoubleSupplier random) {
        this.wallets = wallets;
        this.editions = editions;
        this.pool = pool;
        this.collections = collections;
        this.random = random;
    }

    /** A card as it came out: its id and finish. */
    public record OpenedCard(String id, String finish) {
    }

    /** A pack as it came out: its type, the type it was if it went up a rarity, and its cards in pull order. */
    public record OpenedPack(String packType, String upgradedFrom, List<OpenedCard> cards) {
    }

    /** The gems after the purchase, its price and what came out. */
    public record Opening(int gems, int price, List<OpenedPack> packs) {
    }

    public Opening buy(String userId, String editionId, String product) {
        EditionMetadata edition = editionId == null ? null : editions.findById(editionId).orElse(null);
        if (edition == null || WalletRules.priceOf(edition, product) == null) throw new WalletService.PurchaseException("Ese producto no está a la venta");
        // Nothing is charged for something that would come out empty
        boolean structure = "structure".equals(product);
        Map<String, List<String>> cards = structure ? Map.of() : pool.pool(editionId);
        List<String> structureCards = structure ? pool.structureCards(editionId) : List.of();
        if (structure ? structureCards.isEmpty() : cards.isEmpty()) throw new WalletService.PurchaseException("Esta edición todavía no tiene cartas");

        WalletService.Purchase purchase = wallets.buy(userId, editionId, product);
        List<OpenedPack> packs = structure ? List.of(new OpenedPack("Structure", null, structureCards.stream().map(id -> new OpenedCard(id, "plain")).toList()))
                : draw(edition, product, cards);
        List<CollectionService.Pull> pulls = new ArrayList<>();
        for (OpenedPack p : packs) for (OpenedCard c : p.cards()) pulls.add(new CollectionService.Pull(c.id(), c.finish()));
        collections.add(userId, pulls);
        return new Opening(purchase.gems(), purchase.price(), packs);
    }

    /** A structure deck given as a reward: nothing charged, added to the collection like a purchase. */
    public Opening giftStructure(String userId, String editionId) {
        List<String> structureCards = editionId == null ? List.of() : pool.structureCards(editionId);
        if (structureCards.isEmpty()) throw new WalletService.PurchaseException("Ese structure deck todavía no tiene cartas");
        WalletService.Purchase gift = wallets.gift(userId, editionId);
        List<OpenedCard> cards = structureCards.stream().map(id -> new OpenedCard(id, "plain")).toList();
        collections.add(userId, cards.stream().map(c -> new CollectionService.Pull(c.id(), c.finish())).toList());
        return new Opening(gift.gems(), 0, List.of(new OpenedPack("Structure", null, cards)));
    }

    private List<OpenedPack> draw(EditionMetadata edition, String product, Map<String, List<String>> cards) {
        List<BoosterRules.OpenedPack> drawn;
        if ("box".equals(product)) {
            double chance = WalletRules.isSubEdition(edition.getEditionId()) ? 0.5 : 1;
            drawn = BoosterRules.upgradeOnePack(BoosterRules.openBoxPacks(edition, cards, random), edition, cards, chance, random);
        } else {
            String name = BoosterRules.randomPackType(edition, random);
            EditionMetadata.PackTypeConfig type = edition.getPackTypes().stream().filter(p -> p.getName().equals(name)).findFirst().orElse(edition.getPackTypes().get(0));
            drawn = List.of(new BoosterRules.OpenedPack(type.getName(), BoosterRules.openPack(type, cards, random)));
        }
        return drawn.stream().map(p -> new OpenedPack(p.packType(), p.upgradedFrom(),
                p.cards().stream().map(id -> new OpenedCard(id, CollectionRules.rollFinish(random))).toList())).toList();
    }
}
