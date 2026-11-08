package com.drive.drive_manager.simulator.platform

import kotlin.jvm.JvmStatic

/**
 * The two clocks the core reads: the game's ([millis]: the time written on the history's lines and the timeline's
 * events) and the bots' ([nanos]: how long a bot may keep thinking, its budgets). Both are the platform's
 * ([Platform.currentTimeMillis], [Platform.nanoTime]) unless whoever runs the game sets a clock of its own for the
 * current thread with [using]: a game in the browser played from a seed (`com.eras.engine.local.LocalMatch`) uses a fixed
 * game clock and a bot clock that moves a fixed step on each reading, so the same seed plays the same game on every
 * machine. The server never sets them (each thread reads the platform's clocks, as before).
 */
object Clocks {

    private val game = LocalValue<(() -> Long)?> { null }
    private val bot = LocalValue<(() -> Long)?> { null }

    /** The game's time in epoch milliseconds. */
    @JvmStatic
    fun millis(): Long = game.get()?.invoke() ?: Platform.currentTimeMillis()

    /** The bots' clock in nanoseconds (only differences mean something). */
    @JvmStatic
    fun nanos(): Long = bot.get()?.invoke() ?: Platform.nanoTime()

    /** Runs `block` with these clocks on this thread (null: the platform's), then puts back the ones there were. */
    fun <T> using(millis: (() -> Long)?, nanos: (() -> Long)?, block: () -> T): T {
        val gameBefore = game.get()
        val botBefore = bot.get()
        game.set(millis)
        bot.set(nanos)
        try {
            return block()
        } finally {
            game.set(gameBefore)
            bot.set(botBefore)
        }
    }

    /**
     * A bot clock that does not depend on the machine: each reading moves it `stepNanos` forward (so a budget of `b`
     * nanoseconds lasts `b / stepNanos` readings, whatever the machine's speed).
     */
    fun ticks(stepNanos: Long): () -> Long {
        var now = 0L
        return {
            now += stepNanos
            now
        }
    }
}
