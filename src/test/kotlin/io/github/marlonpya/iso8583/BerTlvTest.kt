package io.github.marlonpya.iso8583

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BerTlvTest {

    @Test
    @DisplayName("Un tag de un byte y uno de dos bytes se leen con su valor")
    fun simpleTags() {
        val items = BerTlv.parse("9F2608112233445566778895050000000000".hexToBytes())
        assertEquals(listOf("9F26", "95"), items.map { it.tag })
        assertEquals("1122334455667788", items[0].value.toHex())
        assertEquals(5, items[1].value.size)
    }

    @Test
    @DisplayName("Los largos de más de 127 bytes usan la forma 81 o 82")
    fun longForms() {
        val big = Tlv("9F10", ByteArray(200) { 0x11 })
        val encoded = BerTlv.encode(listOf(big))
        assertEquals("9F1081C8", encoded.copyOfRange(0, 4).toHex())
        assertContentEquals(big.value, BerTlv.parse(encoded).single().value)

        val bigger = Tlv("9F10", ByteArray(300))
        assertEquals("9F1082012C", BerTlv.encode(listOf(bigger)).copyOfRange(0, 5).toHex())
    }

    @Test
    @DisplayName("Un tag constructor se abre en sus hijos y find los encuentra")
    fun constructed() {
        // 70 es una plantilla constructora; adentro, el 5A (PAN de aplicación).
        val items = BerTlv.parse("700A5A084111111111111111".hexToBytes())
        val template = items.single()
        assertTrue(template.isConstructed)
        assertEquals("5A", template.children.single().tag)
        assertNotNull(BerTlv.find(items, "5A"))
    }

    @Test
    @DisplayName("Codificar y leer devuelve los mismos elementos")
    fun roundTrip() {
        val items = listOf(
            Tlv(EmvTags.AMOUNT_AUTHORISED, "000000002550".hexToBytes()),
            Tlv(EmvTags.APPLICATION_CRYPTOGRAM, "A1B2C3D4E5F60718".hexToBytes()),
            Tlv(EmvTags.ATC, "0007".hexToBytes()),
        )
        assertEquals(items, BerTlv.parse(BerTlv.encode(items)))
    }

    @Test
    @DisplayName("Un valor que declara más bytes de los que hay se rechaza")
    fun truncated() {
        assertThrows<IsoFormatException> { BerTlv.parse("9F260811223344".hexToBytes()) }
        assertThrows<IsoFormatException> { BerTlv.parse("9F".hexToBytes()) }
    }
}
