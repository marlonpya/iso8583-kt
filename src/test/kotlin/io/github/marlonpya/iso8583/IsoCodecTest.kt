package io.github.marlonpya.iso8583

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsoCodecTest {

    private val codec = IsoCodec()

    private fun sale() = IsoMessage.of(Mti.FINANCIAL_REQUEST)
        .with(Fields.PROCESSING_CODE, "000000")
        .with(Fields.AMOUNT, "2550")
        .with(Fields.STAN, "42")
        .with(Fields.TRACK2, "4111111111111111=30121010000000000000")
        .with(Fields.TERMINAL_ID, "LAB00001")
        .with(Fields.PIN_BLOCK, "1B9C1845EB993A7A".hexToBytes())

    @Test
    @DisplayName("La respuesta de un MTI es la misma clase con la función más uno")
    fun mtiResponse() {
        assertEquals(Mti.FINANCIAL_RESPONSE, Mti.FINANCIAL_REQUEST.response())
        assertEquals(Mti.REVERSAL_RESPONSE, Mti.REVERSAL_REQUEST.response())
        assertEquals(Mti.NETWORK_RESPONSE, Mti.NETWORK_REQUEST.response())
        assertEquals(Mti.FINANCIAL_ADVICE_RESPONSE, Mti.FINANCIAL_ADVICE.response())
        assertThrows<IllegalStateException> { Mti.FINANCIAL_RESPONSE.response() }
    }

    @Test
    @DisplayName("Un MTI que no son cuatro dígitos se rechaza")
    fun mtiInvalid() {
        assertThrows<IllegalArgumentException> { Mti("200") }
        assertThrows<IllegalArgumentException> { Mti("02A0") }
    }

    @Test
    @DisplayName("El bitmap primario enciende el bit de cada campo, de izquierda a derecha")
    fun primaryBitmap() {
        // Campos 3, 4 y 11: 0011 0000 | 0010 0000 → 30 20 00 00 00 00 00 00
        assertEquals("3020000000000000", Bitmap.of(listOf(3, 4, 11)).encode().toHex())
    }

    @Test
    @DisplayName("Un campo mayor que 64 enciende el bit 1 y agrega el bitmap secundario")
    fun secondaryBitmap() {
        val bitmap = Bitmap.of(listOf(3, 90))
        val bytes = bitmap.encode()
        assertEquals(16, bytes.size)
        assertEquals("A000000000000000", bytes.copyOfRange(0, 8).toHex())
        // Campo 90 es el bit 26 del secundario: 0000 0000 | 0000 0000 | 0000 0000 | 0100 0000
        assertEquals("0000004000000000", bytes.copyOfRange(8, 16).toHex())
        val (decoded, size) = Bitmap.decode(bytes, 0)
        assertEquals(16, size)
        assertEquals(listOf(3, 90), decoded.fields)
    }

    @Test
    @DisplayName("Empaquetar y desempaquetar devuelve el mismo mensaje")
    fun roundTrip() {
        val message = sale()
        val unpacked = codec.unpack(codec.pack(message))
        assertEquals(message.with(Fields.AMOUNT, "000000002550").with(Fields.STAN, "000042")
            .with(Fields.TERMINAL_ID, "LAB00001"), unpacked)
    }

    @Test
    @DisplayName("Los numéricos fijos se rellenan con ceros y el texto fijo con espacios")
    fun fixedPadding() {
        val packed = codec.pack(IsoMessage.of(Mti.FINANCIAL_REQUEST).with(Fields.AMOUNT, "2550").with(Fields.MERCHANT_ID, "LAB"))
        val text = String(packed, 12, packed.size - 12, Charsets.US_ASCII)
        assertEquals("000000002550" + "LAB" + " ".repeat(12), text)
    }

    @Test
    @DisplayName("Los campos LLVAR llevan su largo en dos dígitos ASCII")
    fun llvarPrefix() {
        val packed = codec.pack(IsoMessage.of(Mti.FINANCIAL_REQUEST).with(Fields.PAN, "4111111111111111"))
        assertEquals("164111111111111111", String(packed, 12, packed.size - 12, Charsets.US_ASCII))
    }

    @Test
    @DisplayName("Un valor más largo que su campo no se empaqueta")
    fun tooLong() {
        assertThrows<IsoFormatException> {
            codec.pack(IsoMessage.of(Mti.FINANCIAL_REQUEST).with(Fields.AMOUNT, "1234567890123"))
        }
        assertThrows<IsoFormatException> {
            codec.pack(IsoMessage.of(Mti.FINANCIAL_REQUEST).with(Fields.PAN, "41111111111111111111"))
        }
    }

    @Test
    @DisplayName("Letras en un campo numérico se rechazan sin mostrar el valor")
    fun invalidCharacters() {
        val error = assertThrows<IsoFormatException> {
            codec.pack(IsoMessage.of(Mti.FINANCIAL_REQUEST).with(Fields.PAN, "41111111X1111111"))
        }
        assertFalse(error.message!!.contains("4111"))
    }

    @Test
    @DisplayName("Un mensaje truncado o con bytes de más se rechaza")
    fun truncatedOrTrailing() {
        val packed = codec.pack(sale())
        assertThrows<IsoFormatException> { codec.unpack(packed.copyOf(packed.size - 3)) }
        assertThrows<IsoFormatException> { codec.unpack(packed + byteArrayOf(0x30)) }
    }

    @Test
    @DisplayName("El volcado del mensaje enmascara el PAN y oculta pista 2 y PIN block")
    fun safeToString() {
        val dump = sale().with(Fields.PAN, "4111111111111111").toString()
        assertTrue(dump.contains("411111******1111"))
        assertFalse(dump.contains("4111111111111111"))
        assertFalse(dump.contains("1B9C1845EB993A7A"))
        assertTrue(dump.contains("[protegido"))
    }

    @Test
    @DisplayName("La respuesta repite los campos de emparejamiento y no los datos de tarjeta")
    fun responseEchoesMatchingFields() {
        val response = sale().toResponse("00")
        assertEquals(Mti.FINANCIAL_RESPONSE, response.mti)
        assertEquals("00", response.text(Fields.RESPONSE_CODE))
        assertEquals("42", response.text(Fields.STAN))
        assertFalse(Fields.TRACK2 in response)
        assertFalse(Fields.PIN_BLOCK in response)
    }

    @Test
    @DisplayName("El encabezado de largo son dos bytes big-endian")
    fun lengthPrefix() {
        val framed = LengthPrefixFraming.frame(ByteArray(300))
        assertEquals("012C", framed.copyOfRange(0, 2).toHex())
        assertEquals(300, LengthPrefixFraming.readLength(framed.copyOfRange(0, 2)))
    }

    @Test
    @DisplayName("Luhn acepta las tarjetas de prueba y rechaza un dígito cambiado")
    fun luhn() {
        assertTrue(Pan.isValidLuhn("4111111111111111"))
        assertTrue(Pan.isValidLuhn("5555555555554444"))
        assertFalse(Pan.isValidLuhn("4111111111111112"))
        assertEquals("4111111111111111", Pan.fromTrack2("4111111111111111=3012101"))
        assertEquals("4111111111111111", Pan.fromTrack2("4111111111111111D3012101"))
    }
}
