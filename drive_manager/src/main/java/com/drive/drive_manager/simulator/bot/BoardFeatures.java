package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.EffectEngine;
import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.List;

/**
 * The table as numbers, seen by one player: what the bot's value model reads (see {@link ValueModel}). Only what
 * that player may see: of the rival, how many cards each hidden zone holds, never which. The order of
 * {@link #NAMES} is the order of the vector; a saved model keeps the names it was trained with.
 */
final class BoardFeatures {

    static final List<String> NAMES = List.of(
            "myLives", "oppLives", "myHand", "oppHand", "myDeck", "oppDeck",
            "myCreatures", "oppCreatures",
            "myFrontActiveStr", "oppFrontActiveStr", "myFrontTappedStr", "oppFrontTappedStr", "myBackStr", "oppBackStr",
            "myFieldValue", "oppFieldValue", "myMaxStr", "oppMaxStr",
            "mySupport", "oppSupport", "myKeywords", "oppKeywords", "myAttached", "oppAttached",
            "myGems", "myMarker", "oppMarker", "myTurn", "lifeDiff", "fieldValueDiff");

    private BoardFeatures() {}

    /** The vector for {@code me} (in the order of {@link #NAMES}). */
    static double[] of(GameRoom room, String me) {
        PlayerState self = room.getPlayer(me);
        PlayerState rival = room.getPlayer(room.getOpponentId(me));
        Side a = side(self);
        Side b = side(rival);
        return new double[]{
                a.lives, b.lives, a.hand, b.hand, a.deck, b.deck,
                a.creatures, b.creatures,
                a.frontActive, b.frontActive, a.frontTapped, b.frontTapped, a.back, b.back,
                a.value, b.value, a.maxStr, b.maxStr,
                a.support, b.support, a.keywords, b.keywords, a.attached, b.attached,
                EffectEngine.gemsOf(room, me), self == null ? 0 : self.getResourceCount(), rival == null ? 0 : rival.getResourceCount(),
                me.equals(room.getCurrentTurnUserId()) ? 1 : 0, a.lives - b.lives, a.value - b.value};
    }

    private record Side(double lives, double hand, double deck, double creatures, double frontActive, double frontTapped,
                        double back, double value, double maxStr, double support, double keywords, double attached) {}

    private static Side side(PlayerState p) {
        if (p == null) return new Side(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        double creatures = 0, frontActive = 0, frontTapped = 0, back = 0, value = 0, maxStr = 0, support = 0, keywords = 0, attached = 0;
        for (CardSlot c : p.getField()) {
            attached += c.getMaterials().size() + c.getResources().size();
            if (!"creature".equals(c.getCardType())) {
                support++;
                continue;
            }
            int str = EffectEngine.strength(c);
            creatures++;
            value += BotPlayer.value(c);
            maxStr = Math.max(maxStr, str);
            keywords += EffectEngine.keywords(c).size();
            if (!EffectEngine.inFrontLine(c)) back += str;
            else if (c.isTapped()) frontTapped += str;
            else frontActive += str;
        }
        return new Side(p.getLifeStack().size(), p.getHand().size(), p.getDeck().size(), creatures, frontActive, frontTapped,
                back, value, maxStr, support, keywords, attached);
    }
}
