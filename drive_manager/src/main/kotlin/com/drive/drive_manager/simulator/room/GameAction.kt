package com.drive.drive_manager.simulator.room

class GameAction {
    var type: String? = null
    var payload: MutableMap<String, Any?>? = null

    fun getString(key: String): String? {
        val v = payload?.get(key)
        return v as? String
    }

    fun getDouble(key: String): Double? {
        val v = payload?.get(key)
        return (v as? Number)?.toDouble()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        val v = payload?.get(key)
        return if (v is Number) v.toInt() else defaultValue
    }

    /** A list of strings (ids) from the payload; empty when missing. */
    fun getStringList(key: String): List<String> {
        val v = payload?.get(key)
        if (v !is List<*>) return java.util.List.of()
        return v.stream().filter { it is String }.map { it as String }.toList()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val v = payload?.get(key)
        return if (v is Boolean) v else defaultValue
    }

    // --- What Lombok's @Data generated ---

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is GameAction) return false
        if (!other.canEqual(this)) return false
        if (type != other.type) return false
        if (payload != other.payload) return false
        return true
    }

    protected fun canEqual(other: Any?): Boolean = other is GameAction

    override fun hashCode(): Int {
        val PRIME = 59
        var result = 1
        val h_type: Any? = type
        result = result * PRIME + (h_type?.hashCode() ?: 43)
        val h_payload: Any? = payload
        result = result * PRIME + (h_payload?.hashCode() ?: 43)
        return result
    }

    override fun toString(): String =
        "GameAction(type=${type}, payload=${payload})"
}
