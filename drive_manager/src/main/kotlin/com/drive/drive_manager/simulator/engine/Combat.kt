package com.drive.drive_manager.simulator.engine

/** A combat in progress (rule 10): attack, block, clash or direct damage, end. */
class Combat {

    enum class Step { ATTACK, BLOCK, CLASH, DAMAGE, END }

    /** Within a step: entering it, its own action, leaving it. Each one opens a window for triggers (rule 10.1). */
    enum class Phase { ENTER, ACT, EXIT }

    var attackerId: String? = null
    var attackerOwnerId: String? = null
    var defenderId: String? = null
    /** Exhausted enemy creature being attacked; null when attacking the opponent. */
    var targetId: String? = null
    var blockerId: String? = null
    var step: Step? = Step.ATTACK
    var phase: Phase? = Phase.ACT
    /** Declared by an effect (the attacker was not exhausted). */
    @get:JvmName("isByEffect")
    var byEffect: Boolean = false
    @get:JvmName("isProvokeAsked")
    var provokeAsked: Boolean = false
    @get:JvmName("isBlockAsked")
    var blockAsked: Boolean = false
    /** Direct damage still to deal (1 + Letalidad), counted down hit by hit; null until the damage step. */
    var hitsLeft: Int? = null
    /** Abilities gained "hasta el final del ataque" ("instanceId|keyword"), removed when the combat ends. */
    var untilEndOfAttack: MutableList<String> = ArrayList()
    /** An enemy of the attacker was destroyed during this attack. */
    @get:JvmName("isEnemyDestroyed")
    var enemyDestroyed: Boolean = false
    /** "salta el paso de bloqueo". */
    @get:JvmName("isSkipBlock")
    var skipBlock: Boolean = false
    /** The defender was offered to replace the direct damage (Mercenario Valeroso). */
    @get:JvmName("isDamageReplaceAsked")
    var damageReplaceAsked: Boolean = false
    /** The hits left when "en su lugar" of the damage was last offered (Estoque): it is offered before each hit. */
    var damageTributeAskedAt: Int? = null

    // --- What Lombok's @Data generated ---

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Combat) return false
        if (!other.canEqual(this)) return false
        if (byEffect != other.byEffect) return false
        if (provokeAsked != other.provokeAsked) return false
        if (blockAsked != other.blockAsked) return false
        if (enemyDestroyed != other.enemyDestroyed) return false
        if (skipBlock != other.skipBlock) return false
        if (damageReplaceAsked != other.damageReplaceAsked) return false
        if (hitsLeft != other.hitsLeft) return false
        if (damageTributeAskedAt != other.damageTributeAskedAt) return false
        if (attackerId != other.attackerId) return false
        if (attackerOwnerId != other.attackerOwnerId) return false
        if (defenderId != other.defenderId) return false
        if (targetId != other.targetId) return false
        if (blockerId != other.blockerId) return false
        if (step != other.step) return false
        if (phase != other.phase) return false
        if (untilEndOfAttack != other.untilEndOfAttack) return false
        return true
    }

    protected fun canEqual(other: Any?): Boolean = other is Combat

    override fun hashCode(): Int {
        val PRIME = 59
        var result = 1
        result = result * PRIME + (if (byEffect) 79 else 97)
        result = result * PRIME + (if (provokeAsked) 79 else 97)
        result = result * PRIME + (if (blockAsked) 79 else 97)
        result = result * PRIME + (if (enemyDestroyed) 79 else 97)
        result = result * PRIME + (if (skipBlock) 79 else 97)
        result = result * PRIME + (if (damageReplaceAsked) 79 else 97)
        val h_hitsLeft: Any? = hitsLeft
        result = result * PRIME + (h_hitsLeft?.hashCode() ?: 43)
        val h_damageTributeAskedAt: Any? = damageTributeAskedAt
        result = result * PRIME + (h_damageTributeAskedAt?.hashCode() ?: 43)
        val h_attackerId: Any? = attackerId
        result = result * PRIME + (h_attackerId?.hashCode() ?: 43)
        val h_attackerOwnerId: Any? = attackerOwnerId
        result = result * PRIME + (h_attackerOwnerId?.hashCode() ?: 43)
        val h_defenderId: Any? = defenderId
        result = result * PRIME + (h_defenderId?.hashCode() ?: 43)
        val h_targetId: Any? = targetId
        result = result * PRIME + (h_targetId?.hashCode() ?: 43)
        val h_blockerId: Any? = blockerId
        result = result * PRIME + (h_blockerId?.hashCode() ?: 43)
        val h_step: Any? = step
        result = result * PRIME + (h_step?.hashCode() ?: 43)
        val h_phase: Any? = phase
        result = result * PRIME + (h_phase?.hashCode() ?: 43)
        val h_untilEndOfAttack: Any? = untilEndOfAttack
        result = result * PRIME + (h_untilEndOfAttack?.hashCode() ?: 43)
        return result
    }

    override fun toString(): String =
        "Combat(attackerId=${attackerId}, attackerOwnerId=${attackerOwnerId}, defenderId=${defenderId}, targetId=${targetId}, blockerId=${blockerId}, step=${step}, phase=${phase}, byEffect=${byEffect}, provokeAsked=${provokeAsked}, blockAsked=${blockAsked}, hitsLeft=${hitsLeft}, untilEndOfAttack=${untilEndOfAttack}, enemyDestroyed=${enemyDestroyed}, skipBlock=${skipBlock}, damageReplaceAsked=${damageReplaceAsked}, damageTributeAskedAt=${damageTributeAskedAt})"
}
