package com.drive.drive_manager.simulator.room

class GameLogEntry() {
    var ts: Long = 0
    var text: String? = null
    /** Timeline event this entry describes, when it comes from one. */
    var eventSeq: Long? = null
    /** What it is: the timeline event's type, MANUAL (an action by hand) or null (a note of the rules). */
    var kind: String? = null
    /** The card behind it: the effect's card for an effect's actions, else the card the action is about. */
    var sourceId: String? = null
    var sourceName: String? = null
    var imageUrl: String? = null
    /** An inherited effect's Recurso (the card that gives it), as in the chaining choice. */
    var inheritedFromName: String? = null
    var inheritedFromImage: String? = null
    /** One of the steps of an effect (not its activation or resolution): hidden in the history's short view. */
    @get:JvmName("isDetail")
    var detail: Boolean = false

    constructor(ts: Long, text: String?) : this() {
        this.ts = ts
        this.text = text
    }

    constructor(ts: Long, text: String?, eventSeq: Long?) : this() {
        this.ts = ts
        this.text = text
        this.eventSeq = eventSeq
    }

    constructor(
        ts: Long, text: String?, eventSeq: Long?, kind: String?, sourceId: String?, sourceName: String?, imageUrl: String?,
        inheritedFromName: String?, inheritedFromImage: String?, detail: Boolean,
    ) : this() {
        this.ts = ts
        this.text = text
        this.eventSeq = eventSeq
        this.kind = kind
        this.sourceId = sourceId
        this.sourceName = sourceName
        this.imageUrl = imageUrl
        this.inheritedFromName = inheritedFromName
        this.inheritedFromImage = inheritedFromImage
        this.detail = detail
    }

    // --- What Lombok's @Data generated ---

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is GameLogEntry) return false
        if (!other.canEqual(this)) return false
        if (ts != other.ts) return false
        if (detail != other.detail) return false
        if (eventSeq != other.eventSeq) return false
        if (text != other.text) return false
        if (kind != other.kind) return false
        if (sourceId != other.sourceId) return false
        if (sourceName != other.sourceName) return false
        if (imageUrl != other.imageUrl) return false
        if (inheritedFromName != other.inheritedFromName) return false
        if (inheritedFromImage != other.inheritedFromImage) return false
        return true
    }

    protected fun canEqual(other: Any?): Boolean = other is GameLogEntry

    override fun hashCode(): Int {
        val PRIME = 59
        var result = 1
        val h_ts = ts
        result = result * PRIME + (h_ts ushr 32 xor h_ts).toInt()
        result = result * PRIME + (if (detail) 79 else 97)
        val h_eventSeq: Any? = eventSeq
        result = result * PRIME + (h_eventSeq?.hashCode() ?: 43)
        val h_text: Any? = text
        result = result * PRIME + (h_text?.hashCode() ?: 43)
        val h_kind: Any? = kind
        result = result * PRIME + (h_kind?.hashCode() ?: 43)
        val h_sourceId: Any? = sourceId
        result = result * PRIME + (h_sourceId?.hashCode() ?: 43)
        val h_sourceName: Any? = sourceName
        result = result * PRIME + (h_sourceName?.hashCode() ?: 43)
        val h_imageUrl: Any? = imageUrl
        result = result * PRIME + (h_imageUrl?.hashCode() ?: 43)
        val h_inheritedFromName: Any? = inheritedFromName
        result = result * PRIME + (h_inheritedFromName?.hashCode() ?: 43)
        val h_inheritedFromImage: Any? = inheritedFromImage
        result = result * PRIME + (h_inheritedFromImage?.hashCode() ?: 43)
        return result
    }

    override fun toString(): String =
        "GameLogEntry(ts=${ts}, text=${text}, eventSeq=${eventSeq}, kind=${kind}, sourceId=${sourceId}, sourceName=${sourceName}, imageUrl=${imageUrl}, inheritedFromName=${inheritedFromName}, inheritedFromImage=${inheritedFromImage}, detail=${detail})"
}
