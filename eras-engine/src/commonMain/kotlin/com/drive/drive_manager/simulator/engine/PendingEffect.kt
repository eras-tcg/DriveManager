package com.drive.drive_manager.simulator.engine

import com.drive.drive_manager.simulator.platform.Platform
import kotlin.jvm.JvmName
import com.drive.drive_manager.simulator.room.CardSlot

/** An effect in the chain: activated, waiting to resolve, or resolving step by step. */
class PendingEffect {
    var id: String? = null
    var scriptId: String? = null
    var controllerId: String? = null
    var sourceInstanceId: String? = null
    /** When its card entered the field: a card that leaves and comes back is a new one, with its own "una vez por turno". */
    var sourceFieldSeq: Long = 0
    var sourceName: String? = null
    var label: String? = null
    var text: String? = null
    /** Timeline seq of the event that triggered it. */
    var triggerSeq: Long = 0
    /** Its link in the chain: 1 for the first one activated (resolves last). */
    var link: Int = 0
    /** An inherited effect: the Recurso that gives it (its art too, when it is still attached), shown on its card. */
    var inheritedFromName: String? = null
    var inheritedFromImage: String? = null

    /** Cost steps already paid: the cost is paid right after it is activated, choices included (rule 11.2.0.2). */
    var costIndex: Int = 0
    /** Targets chosen when it was activated ("elige 1 enemigo …;"), acted on when it resolves (rule 11.10.1). */
    var targetIds: MutableList<String?> = ArrayList()
    /** How many times a repeated choice was already made ("hasta 2 veces", "tantas veces como quieras"). */
    var repeatsDone: Int = 0

    // --- Resolution progress (set while it resolves) ---
    var resolutionId: String? = null
    var stepIndex: Int = 0
    /** Which action of a sequence is running. */
    var sequenceIndex: Int = 0
    /** What its actions saved by name, other than the fields below (see EngineContext). */
    var saved: MutableMap<String, MutableList<String?>> = Platform.hashMap()
    /** The ally chosen by "elige 1 aliado", which the next steps act on. */
    var subjectId: String? = null
    /** Ritual by effect: the creature chosen to be summoned, before choosing who meets its Requerimiento. */
    var ritualTargetId: String? = null
    /** Ritual by effect with a level sum: the summoner, chosen before the other creatures used. */
    var ritualSummonerId: String? = null
    /** The creature the effect just summoned ("añádele … como materiales del aliado invocado"). */
    var summonedId: String? = null
    /** Cards taken out of the deck by "mira las primeras N", waiting for the next step. Owner-only info. */
    var looked: MutableList<CardSlot> = ArrayList()
    /** Its controller already chose it to activate next among simultaneous triggers (rule 11.7.0.5). */
    @get:JvmName("isOrdered")
    var ordered: Boolean = false
    /** An optional effect its controller already chose to activate (in the combined order choice). */
    @get:JvmName("isConfirmed")
    var confirmed: Boolean = false
    /** The last "puedes …" step was done, for "Si lo haces:" (IfDone). */
    @get:JvmName("isLastDone")
    var lastDone: Boolean = false
    /** How many cards the last step moved ("toma 1 carta por cada material tributado", "roba la misma cantidad"). */
    var lastCount: Int = 0
    /** The cards the last tribute step tributed ("si tributaste 1 carta [ente]"). */
    var lastTributed: MutableList<String?> = ArrayList()
    /** A utility that stays on the field after resolving ("coloca esta carta agotada en tu retaguardia"). */
    @get:JvmName("isKeepOnField")
    var keepOnField: Boolean = false
    /** Players who passed priority while it was the last effect in the chain (rule 11.3.0.3). */
    var passed: MutableSet<String?> = HashSet()
    /** The chain it belongs to was closed: both players passed (rule 11.3.0.5). */
    @get:JvmName("isPriorityDone")
    var priorityDone: Boolean = false

    // --- What Lombok's @Data generated ---

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is PendingEffect) return false
        if (!other.canEqual(this)) return false
        if (sourceFieldSeq != other.sourceFieldSeq) return false
        if (triggerSeq != other.triggerSeq) return false
        if (link != other.link) return false
        if (costIndex != other.costIndex) return false
        if (repeatsDone != other.repeatsDone) return false
        if (stepIndex != other.stepIndex) return false
        if (sequenceIndex != other.sequenceIndex) return false
        if (ordered != other.ordered) return false
        if (confirmed != other.confirmed) return false
        if (lastDone != other.lastDone) return false
        if (lastCount != other.lastCount) return false
        if (keepOnField != other.keepOnField) return false
        if (priorityDone != other.priorityDone) return false
        if (id != other.id) return false
        if (scriptId != other.scriptId) return false
        if (controllerId != other.controllerId) return false
        if (sourceInstanceId != other.sourceInstanceId) return false
        if (sourceName != other.sourceName) return false
        if (label != other.label) return false
        if (text != other.text) return false
        if (inheritedFromName != other.inheritedFromName) return false
        if (inheritedFromImage != other.inheritedFromImage) return false
        if (targetIds != other.targetIds) return false
        if (resolutionId != other.resolutionId) return false
        if (saved != other.saved) return false
        if (subjectId != other.subjectId) return false
        if (ritualTargetId != other.ritualTargetId) return false
        if (ritualSummonerId != other.ritualSummonerId) return false
        if (summonedId != other.summonedId) return false
        if (looked != other.looked) return false
        if (lastTributed != other.lastTributed) return false
        if (passed != other.passed) return false
        return true
    }

    protected fun canEqual(other: Any?): Boolean = other is PendingEffect

    override fun hashCode(): Int {
        val PRIME = 59
        var result = 1
        result = result * PRIME + (sourceFieldSeq ushr 32 xor sourceFieldSeq).toInt()
        result = result * PRIME + (triggerSeq ushr 32 xor triggerSeq).toInt()
        result = result * PRIME + link
        result = result * PRIME + costIndex
        result = result * PRIME + repeatsDone
        result = result * PRIME + stepIndex
        result = result * PRIME + sequenceIndex
        result = result * PRIME + (if (ordered) 79 else 97)
        result = result * PRIME + (if (confirmed) 79 else 97)
        result = result * PRIME + (if (lastDone) 79 else 97)
        result = result * PRIME + lastCount
        result = result * PRIME + (if (keepOnField) 79 else 97)
        result = result * PRIME + (if (priorityDone) 79 else 97)
        result = result * PRIME + (id?.hashCode() ?: 43)
        result = result * PRIME + (scriptId?.hashCode() ?: 43)
        result = result * PRIME + (controllerId?.hashCode() ?: 43)
        result = result * PRIME + (sourceInstanceId?.hashCode() ?: 43)
        result = result * PRIME + (sourceName?.hashCode() ?: 43)
        result = result * PRIME + (label?.hashCode() ?: 43)
        result = result * PRIME + (text?.hashCode() ?: 43)
        result = result * PRIME + (inheritedFromName?.hashCode() ?: 43)
        result = result * PRIME + (inheritedFromImage?.hashCode() ?: 43)
        result = result * PRIME + targetIds.hashCode()
        result = result * PRIME + (resolutionId?.hashCode() ?: 43)
        result = result * PRIME + saved.hashCode()
        result = result * PRIME + (subjectId?.hashCode() ?: 43)
        result = result * PRIME + (ritualTargetId?.hashCode() ?: 43)
        result = result * PRIME + (ritualSummonerId?.hashCode() ?: 43)
        result = result * PRIME + (summonedId?.hashCode() ?: 43)
        result = result * PRIME + looked.hashCode()
        result = result * PRIME + lastTributed.hashCode()
        result = result * PRIME + passed.hashCode()
        return result
    }

    override fun toString(): String =
        "PendingEffect(id=${id}, scriptId=${scriptId}, controllerId=${controllerId}, sourceInstanceId=${sourceInstanceId}, sourceFieldSeq=${sourceFieldSeq}, sourceName=${sourceName}, label=${label}, text=${text}, triggerSeq=${triggerSeq}, link=${link}, inheritedFromName=${inheritedFromName}, inheritedFromImage=${inheritedFromImage}, costIndex=${costIndex}, targetIds=${targetIds}, repeatsDone=${repeatsDone}, resolutionId=${resolutionId}, stepIndex=${stepIndex}, sequenceIndex=${sequenceIndex}, saved=${saved}, subjectId=${subjectId}, ritualTargetId=${ritualTargetId}, ritualSummonerId=${ritualSummonerId}, summonedId=${summonedId}, looked=${looked}, ordered=${ordered}, confirmed=${confirmed}, lastDone=${lastDone}, lastCount=${lastCount}, lastTributed=${lastTributed}, keepOnField=${keepOnField}, passed=${passed}, priorityDone=${priorityDone})"
}
