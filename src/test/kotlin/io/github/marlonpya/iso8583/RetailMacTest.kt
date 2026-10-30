package io.github.marlonpya.iso8583

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * No tengo a mano un vector publicado de ISO 9797-1 que pueda citar con
 * certeza, así que el MAC se valida con dos construcciones independientes que
 * deben coincidir con él por definición:
 *
 *  - Para un bloque, Retail MAC es TDES-EDE de ese bloque.
 *  - Para n bloques, es TDES-EDE de (DES-CBC con K1 de los n-1 primeros) XOR
 *    el último, calculado a mano con DES-ECB, sin usar el modo CBC de la JCE.
 */
class RetailMacTest {

    private val key = "0123456789ABCDEFFEDCBA9876543210".hexToBytes()
    private val k1 = key.copyOfRange(0, 8)

    private fun tdesEde(block: ByteArray): ByteArray {
        val k24 = key + k1
        val c = Cipher.getInstance("DESede/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k24, "DESede"))
        return c.doFinal(block)
    }

    private fun desEcb(block: ByteArray): ByteArray {
        val c = Cipher.getInstance("DES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k1, "DES"))
        return c.doFinal(block)
    }

    /** Retail MAC escrito desde la definición, bloque por bloque. */
    private fun reference(data: ByteArray): ByteArray {
        val padded = RetailMac.pad(data)
        val blocks = padded.toList().chunked(8).map { it.toByteArray() }
        var h = ByteArray(8)
        for (b in blocks.dropLast(1)) h = desEcb(h xorWith b)
        return tdesEde(h xorWith blocks.last())
    }

    @Test
    @DisplayName("Para un solo bloque, el Retail MAC es TDES-EDE del bloque")
    fun singleBlockIsTdes() {
        val block = "4E6F772069732074".hexToBytes()
        assertContentEquals(tdesEde(block), RetailMac.compute(key, block))
    }

    @Test
    @DisplayName("Para varios bloques coincide con la construcción a mano")
    fun multiBlockMatchesReference() {
        val random = Random(8583)
        for (size in listOf(0, 1, 7, 8, 9, 16, 23, 64, 255)) {
            val data = random.nextBytes(size)
            assertContentEquals(reference(data), RetailMac.compute(key, data), "largo $size")
        }
    }

    @Test
    @DisplayName("El relleno es con ceros y no agrega bloque si ya es múltiplo de 8")
    fun padding() {
        assertEquals(8, RetailMac.pad(ByteArray(0)).size)
        assertEquals(8, RetailMac.pad(ByteArray(8)).size)
        assertEquals(16, RetailMac.pad(ByteArray(9)).size)
        assertEquals("AA00000000000000", RetailMac.pad(byteArrayOf(0xAA.toByte())).toHex())
    }

    @Test
    @DisplayName("Cambiar un bit del mensaje o de la llave cambia el MAC")
    fun sensitivity() {
        val data = "0200 venta de prueba".toByteArray()
        val mac = RetailMac.compute(key, data)
        val flipped = data.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertFalse(RetailMac.verify(key, flipped, mac))
        val otherKey = key.copyOf().also { it[15] = (it[15].toInt() xor 0x02).toByte() }
        assertFalse(RetailMac.verify(otherKey, data, mac))
        assertTrue(RetailMac.verify(key, data, mac))
    }

    @Test
    @DisplayName("El bit menos significativo de cada byte de la llave es paridad y DES lo ignora")
    fun parityBitsAreIgnored() {
        val data = "0200 venta de prueba".toByteArray()
        val parityFlipped = ByteArray(16) { (key[it].toInt() xor 0x01).toByte() }
        assertContentEquals(RetailMac.compute(key, data), RetailMac.compute(parityFlipped, data))
    }

    @Test
    @DisplayName("Un mensaje firmado verifica y uno alterado en el cable no")
    fun messageSignAndVerify() {
        val codec = IsoCodec()
        val mac = MessageMac(codec)
        val signed = mac.sign(
            IsoMessage.of(Mti.FINANCIAL_REQUEST).with(Fields.AMOUNT, "2550").with(Fields.STAN, "1"),
            key,
        )
        assertEquals(Fields.MAC, signed.fields.last())
        val wire = codec.pack(signed)
        assertTrue(mac.verify(wire, key))
        val tampered = wire.copyOf().also { it[20] = '9'.code.toByte() }
        assertFalse(mac.verify(tampered, key))
    }

    @Test
    @DisplayName("Con bitmap secundario el MAC va en el campo 128")
    fun secondaryMacField() {
        val codec = IsoCodec()
        val mac = MessageMac(codec)
        val reversal = IsoMessage.of(Mti.REVERSAL_REQUEST)
            .with(Fields.STAN, "2")
            .with(Fields.ORIGINAL_DATA, "0200" + "000001" + "1030153000" + "0".repeat(22))
        val signed = mac.sign(reversal, key)
        assertEquals(Fields.MAC_SECONDARY, signed.fields.last())
        assertFalse(Fields.MAC in signed)
        assertTrue(mac.verify(codec.pack(signed), key))
    }

    @Test
    @DisplayName("Un mensaje sin MAC no verifica")
    fun missingMac() {
        val codec = IsoCodec()
        val raw = codec.pack(IsoMessage.of(Mti.NETWORK_REQUEST).with(Fields.STAN, "1"))
        assertFalse(MessageMac(codec).verify(raw, key))
    }
}
