package com.drive.drive_manager.simulator.engine.cards

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The checked-in [CardModelReader] is what the model's classes give today. */
class CardModelReaderGeneratorTest {

    @Test
    fun theGeneratedReaderIsCurrent() {
        val file = File(CardModelReaderGenerator.FILE)
        val generated = CardModelReaderGenerator.generate()
        if (System.getProperty("eras.regenerateCardReader") == "true") {
            if (!file.exists() || file.readText() != generated) file.writeText(generated)
            return
        }
        assertEquals(generated, file.readText(), "CardModelReader.kt is stale: run ./gradlew jvmTest -PregenerateCardReader")
    }
}
