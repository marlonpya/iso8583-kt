package io.github.marlonpya.iso8583

import java.io.ByteArrayOutputStream

/**
 * Un elemento BER-TLV: tag, largo y valor. Es el formato de los datos EMV que
 * viajan en el campo 55.
 *
 * El tag se guarda en hexadecimal ("9F26") porque así aparece en las
 * especificaciones y en los logs de los kernels.
 */
class Tlv(tag: String, value: ByteArray, val children: List<Tlv> = emptyList()) {

    val tag: String = tag.uppercase()

    private val data = value.copyOf()

    val value: ByteArray get() = data.copyOf()

    /** Bit 6 del primer byte del tag: el valor contiene otros TLV. */
    val isConstructed: Boolean get() = tag.substring(0, 2).toInt(16) and 0x20 != 0

    override fun equals(other: Any?) = other is Tlv && tag == other.tag && data.contentEquals(other.data)

    override fun hashCode() = 31 * tag.hashCode() + data.contentHashCode()

    override fun toString() = "$tag(${data.size})=${data.toHex()}"
}

object BerTlv {

    fun parse(data: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var i = 0
        while (i < data.size) {
            // Relleno permitido entre objetos.
            if (data[i] == 0x00.toByte() || data[i] == 0xFF.toByte()) {
                i++
                continue
            }
            val tagStart = i
            val first = data[i++].toInt() and 0xFF
            if (first and 0x1F == 0x1F) {
                do {
                    if (i >= data.size) throw IsoFormatException("Tag TLV truncado")
                    val b = data[i++].toInt() and 0xFF
                } while (b and 0x80 != 0)
            }
            val tag = data.copyOfRange(tagStart, i).toHex()
            if (i >= data.size) throw IsoFormatException("Falta el largo del tag $tag")
            val lenByte = data[i++].toInt() and 0xFF
            val length = when {
                lenByte < 0x80 -> lenByte
                lenByte == 0x81 -> readUnsigned(data, i, 1).also { i += 1 }
                lenByte == 0x82 -> readUnsigned(data, i, 2).also { i += 2 }
                else -> throw IsoFormatException("Forma de largo no soportada en el tag $tag")
            }
            if (i + length > data.size) throw IsoFormatException("El tag $tag declara $length bytes y no alcanzan")
            val value = data.copyOfRange(i, i + length)
            i += length
            val constructed = first and 0x20 != 0
            out += Tlv(tag, value, if (constructed) parse(value) else emptyList())
        }
        return out
    }

    fun encode(items: List<Tlv>): ByteArray {
        val out = ByteArrayOutputStream()
        for (t in items) {
            out.write(t.tag.hexToBytes())
            out.write(encodeLength(t.value.size))
            out.write(t.value)
        }
        return out.toByteArray()
    }

    /** Busca un tag en el primer nivel y dentro de los constructores. */
    fun find(items: List<Tlv>, tag: String): Tlv? {
        val wanted = tag.uppercase()
        for (t in items) {
            if (t.tag == wanted) return t
            find(t.children, wanted)?.let { return it }
        }
        return null
    }

    private fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
        length <= 0xFFFF -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
        else -> throw IsoFormatException("Valor TLV demasiado largo: $length")
    }

    private fun readUnsigned(data: ByteArray, offset: Int, size: Int): Int {
        if (offset + size > data.size) throw IsoFormatException("Largo TLV truncado")
        var v = 0
        for (k in 0 until size) v = (v shl 8) or (data[offset + k].toInt() and 0xFF)
        return v
    }
}

/**
 * Tags EMV que usa el laboratorio, con su nombre en EMV Book 3, Anexo A.
 *
 * Es solo un glosario para leer el campo 55: el kernel certificado es quien
 * los produce y valida.
 */
object EmvTags {
    const val AID = "4F"
    const val AIP = "82"
    const val DF_NAME = "84"
    const val AUTHORISATION_RESPONSE_CODE = "8A"
    const val ISSUER_AUTHENTICATION_DATA = "91"
    const val TVR = "95"
    const val TRANSACTION_DATE = "9A"
    const val TRANSACTION_TYPE = "9C"
    const val TRANSACTION_CURRENCY = "5F2A"
    const val AMOUNT_AUTHORISED = "9F02"
    const val AMOUNT_OTHER = "9F03"
    const val ISSUER_APPLICATION_DATA = "9F10"
    const val TERMINAL_COUNTRY = "9F1A"
    const val APPLICATION_CRYPTOGRAM = "9F26"
    const val CRYPTOGRAM_INFORMATION_DATA = "9F27"
    const val ATC = "9F36"
    const val UNPREDICTABLE_NUMBER = "9F37"

    val NAMES: Map<String, String> = mapOf(
        AID to "Application Identifier (AID)",
        AIP to "Application Interchange Profile",
        DF_NAME to "Dedicated File Name",
        AUTHORISATION_RESPONSE_CODE to "Authorisation Response Code",
        ISSUER_AUTHENTICATION_DATA to "Issuer Authentication Data (ARPC)",
        TVR to "Terminal Verification Results",
        TRANSACTION_DATE to "Transaction Date",
        TRANSACTION_TYPE to "Transaction Type",
        TRANSACTION_CURRENCY to "Transaction Currency Code",
        AMOUNT_AUTHORISED to "Amount, Authorised",
        AMOUNT_OTHER to "Amount, Other",
        ISSUER_APPLICATION_DATA to "Issuer Application Data",
        TERMINAL_COUNTRY to "Terminal Country Code",
        APPLICATION_CRYPTOGRAM to "Application Cryptogram (ARQC/TC/AAC)",
        CRYPTOGRAM_INFORMATION_DATA to "Cryptogram Information Data",
        ATC to "Application Transaction Counter",
        UNPREDICTABLE_NUMBER to "Unpredictable Number",
    )
}
