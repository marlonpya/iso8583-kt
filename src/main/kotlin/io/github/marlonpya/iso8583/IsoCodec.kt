package io.github.marlonpya.iso8583

import java.io.ByteArrayOutputStream

/**
 * Empaqueta y desempaqueta mensajes según un [IsoSpec].
 *
 * Formato en el cable: MTI (4 bytes ASCII) + bitmap (8 o 16 bytes binarios) +
 * campos en orden ascendente. Los prefijos de largo variable van en ASCII. El
 * encabezado de dos bytes con el largo total lo agrega el transporte, no el
 * codec: ver [LengthPrefixFraming].
 */
class IsoCodec(private val spec: IsoSpec = IsoSpec.LAB) {

    fun pack(message: IsoMessage): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(message.mti.code.toByteArray(Charsets.US_ASCII))
        out.write(message.bitmap.encode())
        for (id in message.fields) {
            out.write(packField(spec[id], message, id))
        }
        return out.toByteArray()
    }

    fun unpack(data: ByteArray): IsoMessage {
        if (data.size < 12) throw IsoFormatException("Mensaje demasiado corto: ${data.size} bytes")
        val mtiText = String(data, 0, 4, Charsets.US_ASCII)
        val mti = runCatching { Mti(mtiText) }.getOrElse { throw IsoFormatException("MTI inválido") }
        val (bitmap, bitmapSize) = Bitmap.decode(data, 4)
        var offset = 4 + bitmapSize
        var message = IsoMessage.of(mti)
        for (id in bitmap.fields) {
            val field = spec[id]
            val (length, prefixSize) = readLength(field, data, offset)
            offset += prefixSize
            if (offset + length > data.size) {
                throw IsoFormatException("El campo $id declara $length bytes y no alcanzan")
            }
            val raw = data.copyOfRange(offset, offset + length)
            offset += length
            message = if (field.type == FieldType.BINARY) {
                message.with(id, raw)
            } else {
                val text = String(raw, Charsets.US_ASCII)
                validate(field, text)
                message.with(id, text)
            }
        }
        if (offset != data.size) {
            throw IsoFormatException("Sobran ${data.size - offset} bytes después del último campo")
        }
        return message
    }

    private fun packField(field: FieldSpec, message: IsoMessage, id: Int): ByteArray {
        val raw = if (field.type == FieldType.BINARY) {
            message.bytes(id)
        } else {
            val text = normalize(field, message.text(id))
            validate(field, text)
            text.toByteArray(Charsets.US_ASCII)
        }
        return when (val len = field.length) {
            is FieldLength.Fixed -> {
                if (raw.size != len.max) {
                    throw IsoFormatException("El campo $id debe tener ${len.max} bytes, tiene ${raw.size}")
                }
                raw
            }
            is FieldLength.LlVar -> prefixed(id, raw, len.max, 2)
            is FieldLength.LllVar -> prefixed(id, raw, len.max, 3)
        }
    }

    private fun prefixed(id: Int, raw: ByteArray, max: Int, digits: Int): ByteArray {
        if (raw.size > max) throw IsoFormatException("El campo $id admite hasta $max, tiene ${raw.size}")
        return raw.size.toString().padStart(digits, '0').toByteArray(Charsets.US_ASCII) + raw
    }

    /** Relleno de los campos fijos: ceros a la izquierda en numéricos, espacios a la derecha en texto. */
    private fun normalize(field: FieldSpec, value: String): String {
        val len = field.length as? FieldLength.Fixed ?: return value
        if (value.length >= len.max) return value
        return when (field.type) {
            FieldType.NUMERIC -> value.padStart(len.max, '0')
            FieldType.TEXT -> value.padEnd(len.max, ' ')
            else -> value
        }
    }

    private fun validate(field: FieldSpec, text: String) {
        val ok = when (field.type) {
            FieldType.NUMERIC -> text.all { it in '0'..'9' }
            FieldType.TEXT -> text.all { it in ' '..'~' }
            FieldType.TRACK2 -> text.all { it in '0'..'9' || it == '=' || it == 'D' }
            FieldType.BINARY -> true
        }
        // El valor no va en el mensaje de error: podría ser un PAN o una pista.
        if (!ok) throw IsoFormatException("El campo ${field.id} trae caracteres inválidos para ${field.type}")
    }

    private fun readLength(field: FieldSpec, data: ByteArray, offset: Int): Pair<Int, Int> {
        val digits = when (field.length) {
            is FieldLength.Fixed -> return field.length.max to 0
            is FieldLength.LlVar -> 2
            is FieldLength.LllVar -> 3
        }
        if (offset + digits > data.size) throw IsoFormatException("Falta el prefijo de largo del campo ${field.id}")
        val text = String(data, offset, digits, Charsets.US_ASCII)
        val length = text.toIntOrNull() ?: throw IsoFormatException("Prefijo de largo inválido en el campo ${field.id}")
        if (length > field.length.max) {
            throw IsoFormatException("El campo ${field.id} declara $length y admite hasta ${field.length.max}")
        }
        return length to digits
    }
}

/**
 * Encabezado de dos bytes big-endian con el largo del mensaje, la forma más
 * común de delimitar mensajes ISO 8583 sobre TCP.
 */
object LengthPrefixFraming {
    const val MAX_LENGTH = 0xFFFF

    fun frame(message: ByteArray): ByteArray {
        require(message.size <= MAX_LENGTH) { "Mensaje demasiado largo: ${message.size}" }
        return byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()) + message
    }

    fun readLength(header: ByteArray): Int {
        require(header.size == 2) { "El encabezado tiene dos bytes" }
        return ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
    }
}
