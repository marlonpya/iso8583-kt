package io.github.marlonpya.iso8583

/** Valor de un campo: texto para `n`, `an`, `z`; bytes para `b`. */
sealed interface FieldValue {
    data class Text(val value: String) : FieldValue

    class Bytes(value: ByteArray) : FieldValue {
        private val data = value.copyOf()
        val value: ByteArray get() = data.copyOf()
        override fun equals(other: Any?) = other is Bytes && data.contentEquals(other.data)
        override fun hashCode() = data.contentHashCode()
        override fun toString() = data.toHex()
    }
}

/**
 * Un mensaje ISO 8583 ya decodificado: MTI más campos.
 *
 * Es inmutable: `with` devuelve un mensaje nuevo. Así un mensaje que ya se
 * firmó con MAC no se puede alterar por accidente después.
 *
 * `toString()` nunca muestra datos sensibles: el PAN sale enmascarado y la
 * pista 2, el PIN block y los datos EMV no salen. Es la única representación
 * que debería llegar a un log.
 */
class IsoMessage private constructor(
    val mti: Mti,
    private val values: Map<Int, FieldValue>,
) {
    val fields: List<Int> get() = values.keys.sorted()

    val bitmap: Bitmap get() = Bitmap.of(values.keys)

    operator fun contains(field: Int): Boolean = field in values

    fun text(field: Int): String = when (val v = values[field]) {
        is FieldValue.Text -> v.value
        is FieldValue.Bytes -> throw IsoFormatException("El campo $field es binario")
        null -> throw NoSuchElementException("El mensaje $mti no trae el campo $field")
    }

    fun bytes(field: Int): ByteArray = when (val v = values[field]) {
        is FieldValue.Bytes -> v.value
        is FieldValue.Text -> throw IsoFormatException("El campo $field es texto")
        null -> throw NoSuchElementException("El mensaje $mti no trae el campo $field")
    }

    fun textOrNull(field: Int): String? = (values[field] as? FieldValue.Text)?.value

    fun bytesOrNull(field: Int): ByteArray? = (values[field] as? FieldValue.Bytes)?.value

    fun with(field: Int, value: String): IsoMessage = copy(values + (field to FieldValue.Text(value)))

    fun with(field: Int, value: ByteArray): IsoMessage = copy(values + (field to FieldValue.Bytes(value)))

    fun without(field: Int): IsoMessage = copy(values - field)

    fun withMti(newMti: Mti): IsoMessage = IsoMessage(newMti, values)

    /**
     * Esqueleto de la respuesta: MTI de respuesta y los campos que el host
     * devuelve tal cual para que el terminal empareje (monto, STAN, terminal...).
     */
    fun toResponse(responseCode: String): IsoMessage {
        val echoed = values.filterKeys { it in ECHOED_IN_RESPONSE }
        return IsoMessage(mti.response(), echoed).with(Fields.RESPONSE_CODE, responseCode)
    }

    private fun copy(newValues: Map<Int, FieldValue>) = IsoMessage(mti, newValues)

    override fun equals(other: Any?) = other is IsoMessage && mti == other.mti && values == other.values

    override fun hashCode() = 31 * mti.hashCode() + values.hashCode()

    override fun toString(): String = describe(IsoSpec.LAB)

    /** Volcado legible, con los datos sensibles protegidos. */
    fun describe(spec: IsoSpec): String = buildString {
        append("MTI ").append(mti)
        for (f in fields) {
            val name = if (f in spec) spec[f].name else "?"
            append("\n  [").append(f.toString().padStart(3, '0')).append("] ").append(name).append(": ")
            append(safeValue(f, spec))
        }
    }

    private fun safeValue(field: Int, spec: IsoSpec): String {
        val value = values.getValue(field)
        val sensitive = field in spec && spec[field].sensitive
        return when {
            field == Fields.PAN -> Pan.mask(text(field))
            sensitive && value is FieldValue.Bytes -> "[protegido, ${value.value.size} bytes]"
            sensitive -> "[protegido]"
            value is FieldValue.Text -> value.value
            else -> value.toString()
        }
    }

    companion object {
        fun of(mti: Mti): IsoMessage = IsoMessage(mti, emptyMap())

        private val ECHOED_IN_RESPONSE = setOf(
            Fields.PROCESSING_CODE, Fields.AMOUNT, Fields.TRANSMISSION_DATETIME, Fields.STAN,
            Fields.LOCAL_TIME, Fields.LOCAL_DATE, Fields.NII, Fields.RRN, Fields.TERMINAL_ID,
            Fields.MERCHANT_ID, Fields.CURRENCY,
        )
    }
}

/** Números de campo con nombre, para no escribir números mágicos. */
object Fields {
    const val PAN = 2
    const val PROCESSING_CODE = 3
    const val AMOUNT = 4
    const val TRANSMISSION_DATETIME = 7
    const val STAN = 11
    const val LOCAL_TIME = 12
    const val LOCAL_DATE = 13
    const val EXPIRY = 14
    const val ENTRY_MODE = 22
    const val CARD_SEQUENCE = 23
    const val NII = 24
    const val POS_CONDITION = 25
    const val TRACK2 = 35
    const val RRN = 37
    const val AUTH_CODE = 38
    const val RESPONSE_CODE = 39
    const val TERMINAL_ID = 41
    const val MERCHANT_ID = 42
    const val CURRENCY = 49
    const val PIN_BLOCK = 52
    const val EMV_DATA = 55
    const val KSN = 62
    const val MAC = 64
    const val ORIGINAL_DATA = 90
    const val MAC_SECONDARY = 128
}

/** Manejo del PAN sin exponerlo. */
object Pan {
    /** Primeros seis y últimos cuatro, que es lo máximo que PCI DSS permite mostrar. */
    fun mask(pan: String): String {
        if (pan.length < 13) return "*".repeat(pan.length)
        return pan.take(6) + "*".repeat(pan.length - 10) + pan.takeLast(4)
    }

    /** Algoritmo de Luhn: el dígito verificador del PAN. */
    fun isValidLuhn(pan: String): Boolean {
        if (pan.length !in 13..19 || !pan.all { it.isDigit() }) return false
        var sum = 0
        pan.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /** El PAN que va dentro de la pista 2, antes del separador. */
    fun fromTrack2(track2: String): String = track2.takeWhile { it.isDigit() }
}
