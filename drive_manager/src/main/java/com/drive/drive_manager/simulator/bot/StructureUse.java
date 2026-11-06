package com.drive.drive_manager.simulator.bot;

import com.drive.drive_manager.simulator.engine.GameEvent;
import com.drive.drive_manager.simulator.room.GameRoom;
import com.drive.drive_manager.simulator.room.PlayerState;

import java.util.List;

/**
 * sUse (the user's call, 2026-11-05): what a structure is worth is what it yields. The starter decks' structures are
 * engines ("si pasa X: puedes agotar esta carta para ganar 1 gema y robar / tomar 1 carta"), and the table's score
 * barely sees a yield (gems count nothing, a card drawn for one tributed is still a card), so the bot left them
 * unused and played them without caring for what makes them fire. Each activation of an effect of a structure of its
 * own in a try is worth sUse: playing the structure before what fires it, and saying yes to it, pay.
 */
final class StructureUse {

    private StructureUse() {}

    /** The activations of its own structures' effects in the room's timeline since {@code from}. */
    static int count(GameRoom room, String me, int from) {
        List<GameEvent> tl = room.getTimeline();
        PlayerState self = room.getPlayer(me);
        if (self == null) return 0;
        int n = 0;
        for (int i = Math.max(0, from); i < tl.size(); i++) {
            GameEvent e = tl.get(i);
            if (!GameEvent.EFFECT_ACTIVATED.equals(e.getType()) || !me.equals(e.getPlayerId()) || e.getData() == null) continue;
            Object source = e.getData().get("sourceId");
            if (source == null) continue;
            String id = String.valueOf(source);
            if (self.getField().stream().anyMatch(c -> id.equals(c.getInstanceId()) && "structure".equals(c.getCardType()))) n++;
        }
        return n;
    }
}
