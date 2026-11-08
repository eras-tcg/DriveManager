package com.drive.drive_manager.simulator.engine

/**
 * One entry of the game timeline (rules 11.6 and 11.7): every rules-relevant change is recorded here, in
 * order, so triggers can ask "did X happen during the last resolution" and "was X the last thing that happened".
 * The text log is generated from these events.
 */
class GameEvent {

    /** Order in the room's timeline (1, 2, 3…). */
    var seq: Long = 0
    var ts: Long = 0
    var type: String? = null
    /** Player who performed the action (the effect's controller for effect actions). */
    var playerId: String? = null
    var cardInstanceId: String? = null
    var cardName: String? = null
    var fromZone: String? = null
    var toZone: String? = null
    /** Effect instance this event belongs to (null for actions that are not part of an effect). */
    var effectId: String? = null
    /** Resolution in which it happened (rule 11.6: "lo último que ocurrió" is measured per resolution). */
    var resolutionId: String? = null
    var data: MutableMap<String, Any?>? = null

    constructor()

    constructor(
        seq: Long, ts: Long, type: String?, playerId: String?, cardInstanceId: String?, cardName: String?, fromZone: String?,
        toZone: String?, effectId: String?, resolutionId: String?, data: MutableMap<String, Any?>?,
    ) {
        this.seq = seq
        this.ts = ts
        this.type = type
        this.playerId = playerId
        this.cardInstanceId = cardInstanceId
        this.cardName = cardName
        this.fromZone = fromZone
        this.toZone = toZone
        this.effectId = effectId
        this.resolutionId = resolutionId
        this.data = data
    }

    // --- What Lombok's @Data generated ---

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is GameEvent) return false
        if (!other.canEqual(this)) return false
        if (seq != other.seq) return false
        if (ts != other.ts) return false
        if (type != other.type) return false
        if (playerId != other.playerId) return false
        if (cardInstanceId != other.cardInstanceId) return false
        if (cardName != other.cardName) return false
        if (fromZone != other.fromZone) return false
        if (toZone != other.toZone) return false
        if (effectId != other.effectId) return false
        if (resolutionId != other.resolutionId) return false
        if (data != other.data) return false
        return true
    }

    protected fun canEqual(other: Any?): Boolean = other is GameEvent

    override fun hashCode(): Int {
        val PRIME = 59
        var result = 1
        result = result * PRIME + (seq ushr 32 xor seq).toInt()
        result = result * PRIME + (ts ushr 32 xor ts).toInt()
        result = result * PRIME + (type?.hashCode() ?: 43)
        result = result * PRIME + (playerId?.hashCode() ?: 43)
        result = result * PRIME + (cardInstanceId?.hashCode() ?: 43)
        result = result * PRIME + (cardName?.hashCode() ?: 43)
        result = result * PRIME + (fromZone?.hashCode() ?: 43)
        result = result * PRIME + (toZone?.hashCode() ?: 43)
        result = result * PRIME + (effectId?.hashCode() ?: 43)
        result = result * PRIME + (resolutionId?.hashCode() ?: 43)
        result = result * PRIME + (data?.hashCode() ?: 43)
        return result
    }

    override fun toString(): String =
        "GameEvent(seq=${seq}, ts=${ts}, type=${type}, playerId=${playerId}, cardInstanceId=${cardInstanceId}, cardName=${cardName}, fromZone=${fromZone}, toZone=${toZone}, effectId=${effectId}, resolutionId=${resolutionId}, data=${data})"

    companion object {
        /** Event types recorded so far. New mechanics add their own. */
        const val NORMAL_SUMMON = "NORMAL_SUMMON"
        const val EFFECT_TRIGGERED = "EFFECT_TRIGGERED"
        const val EFFECT_ACTIVATED = "EFFECT_ACTIVATED"
        const val EFFECT_RESOLVED = "EFFECT_RESOLVED"
        const val LOOK_TOP = "LOOK_TOP"
        const val ADD_TO_HAND = "ADD_TO_HAND"
        const val TRIBUTE = "TRIBUTE"
        const val EFFECT_DECLINED = "EFFECT_DECLINED"
        const val DRAW = "DRAW"
        /** Destroyed: to the Descarte. data: cause (EFFECT, BATTLE, RULES, MANUAL), ownerId. */
        const val DESTROY = "DESTROY"
        /** Sacrificed: destroyed, but to the Tributos (rule 4.0.4.2). Same data as DESTROY. */
        const val SACRIFICE = "SACRIFICE"
        const val EFFECT_SUMMON = "EFFECT_SUMMON"
        const val SPECIAL_SUMMON = "SPECIAL_SUMMON"
        const val SEND = "SEND"
        const val UNTAP = "UNTAP"
        const val GAIN = "GAIN"
        const val PLAY = "PLAY"
        /** An attack was declared (rule 10.1.1). data: targetId (null = the opponent), byEffect. */
        const val ATTACK_DECLARED = "ATTACK_DECLARED"
        const val BLOCK = "BLOCK"
        const val CLASH = "CLASH"
        /** Direct damage to the top life card (rule 10.1.4). data: damage. */
        const val LIFE_DAMAGE = "LIFE_DAMAGE"
        const val ATTACK_ENDED = "ATTACK_ENDED"
        /** Entering or leaving a combat step. data: step (BLOCK, CLASH…), moment (ENTER, EXIT). */
        const val COMBAT_STEP = "COMBAT_STEP"
        const val GAME_LOST = "GAME_LOST"
        const val TAP = "TAP"
        /** Targets chosen when an effect is activated. data: targetIds. */
        const val TARGET = "TARGET"
        /** The start of a player's turn (rule 8.1): start-of-turn effects trigger from it. */
        const val TURN_START = "TURN_START"
        /** The end phase of a player's turn (rule 8.3): end-of-turn effects trigger from it. */
        const val TURN_END = "TURN_END"
        /** A creature's strength was reduced by an effect. data: amount, to (its strength now), ownerId. */
        const val STRENGTH_REDUCED = "STRENGTH_REDUCED"
        const val MOVE = "MOVE"
        const val GAIN_GEMS = "GAIN_GEMS"
        /** Data key: true on an event done by hand from the board, outside the engine's checks. */
        const val MANUAL = "manual"
    }
}
