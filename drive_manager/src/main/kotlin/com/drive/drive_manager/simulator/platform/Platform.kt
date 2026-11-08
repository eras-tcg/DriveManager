package com.drive.drive_manager.simulator.platform

/**
 * What the game's core needs from the platform it runs on, in one place: the JVM's implementation today. When the
 * core moves to eras-engine (commonMain), this object becomes an `expect object` with one `actual` per target; the
 * rest of the core stays as it is. Nothing else in the core may use a JVM-only API.
 */
object Platform {

    /** A seed for a chance nobody seeded (`new java.util.Random()`): different each time. */
    @JvmStatic
    fun randomSeed(): Long = java.util.Random().nextLong()

    /**
     * The text (UTF-8) of a resource shipped with the engine (`/bot/models.txt`), or null when there is none. A resource
     * that cannot be read throws IllegalStateException with `failure` as its message.
     */
    @JvmStatic
    fun resourceText(path: String, failure: String): String? {
        try {
            Platform::class.java.getResourceAsStream(path).use { input ->
                return if (input == null) null else String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException(failure, e)
        }
    }
}
