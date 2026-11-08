package com.drive.drive_manager.simulator.engine.cards

import com.drive.drive_manager.simulator.engine.Amount
import com.drive.drive_manager.simulator.engine.Condition
import com.drive.drive_manager.simulator.engine.EffectScript
import com.drive.drive_manager.simulator.engine.EffectStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The common card reader: what it builds, what it refuses (the JVM's parity test compares it with Jackson's on every card). */
class CardReaderTest {

    private fun effect(steps: String, extra: String = "") =
        """{"name":"x","effects":[{"id":"x:1","moment":"ON_PLAY","steps":[$steps]$extra}]}"""

    @Test
    fun readsRecordsObjectsAndDefaults() {
        val card = CardReader.read(effect("""{"type":"IfPossible"},{"type":"IfChosenGone","covers":2}""", ""","condition":{"type":"YouReducedStrength","toAtMost":0}"""))
        val script = card.effects().single()
        assertEquals("x", card.name)
        assertEquals(EffectScript.Moment.ON_PLAY, script.moment)
        assertSame(EffectStep.IfPossible, script.steps!![0])
        assertEquals(EffectStep.IfChosenGone(2), script.steps!![1])
        assertEquals(Condition.YouReducedStrength(0, false), script.condition)
        // What Jackson gave an absent value: false, 0, null, an empty list
        assertEquals(false, script.mandatory)
        assertNull(script.limit)
        assertNull(script.sourceZone)
        assertEquals(emptyList(), script.cost)
        assertTrue(card.inherited().isEmpty())
        assertNull(card.traits)
    }

    @Test
    fun readsAnAmountByItsFamily() {
        // "Gems" names a member of Amount and one of Action: the declared type decides
        val card = CardReader.read("""{"name":"x","continuous":[{"bonus":{"type":"Fixed","value":2}}]}""")
        assertEquals(Amount.Fixed(2), card.continuous().single().bonus)
    }

    private fun refused(json: String, part: String) {
        val e = assertFailsWith<IllegalArgumentException> { CardReader.read(json) }
        assertTrue(e.message!!.startsWith("Efectos inválidos: "), e.message)
        assertTrue(e.message!!.contains(part), "\"${e.message}\" should say \"$part\"")
    }

    @Test
    fun refusesWhatIsNotTheVocabulary() {
        refused("""{"name":"x","bogus":1}""", "campo desconocido \"bogus\"")
        refused(effect("""{"type":"IfPossible","x":1}"""), "campo desconocido \"x\"")
        refused(effect("""{"type":"Nope"}"""), "tipo desconocido \"Nope\"")
        refused(effect("""{"type":"Fixed","value":1}"""), "tipo desconocido \"Fixed\"")
        refused(effect("{}"), "falta \"type\"")
        refused(effect("""{"type":null}"""), "falta \"type\"")
        refused(effect("\"IfPossible\""), "se esperaba un objeto")
        refused("""{"name":"x","effects":[{"id":"a","steps":{"type":"IfPossible"}}]}""", "se esperaba una lista")
        refused("""{"name":"x","effects":[{"id":"a","moment":"on_summon"}]}""", "no es un EffectScript.Moment")
        refused("""{"name":"x","name":"y"}""", "clave repetida \"name\"")
        refused("""{"name":"x","traits":{"alias":"a","alias":"b"}}""", "clave repetida \"alias\"")
        refused(effect("""{"type":"IfChosenGone","covers":99999999999}"""), "fuera del rango")
        refused("""{"name":{}}""", "se esperaba un texto")
        refused("""{"name":"x"} {}""", "JSON mal formado")
        refused("""{"name":"x",}""", "JSON mal formado")
        refused("[]", "se esperaba un objeto")
        refused("null", "se esperaba un objeto")
        refused("""{"name":"x","effects":[null]}""", "elemento nulo")
    }

    @Test
    fun keepsANullOrAbsentIntegerApartFromZero() {
        assertNull(CardReader.read("""{"name":"x","traits":{}}""").traits!!.creatureForEffects)
        assertEquals(0, CardReader.read("""{"name":"x","traits":{"creatureForEffects":0}}""").traits!!.creatureForEffects)
    }
}
