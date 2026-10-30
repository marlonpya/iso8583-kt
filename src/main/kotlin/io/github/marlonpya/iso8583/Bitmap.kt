package io.github.marlonpya.iso8583

/**
 * El bitmap dice qué campos trae el mensaje: el bit n en uno significa que el
 * campo n está presente.
 *
 * El primario tiene 64 bits. Si algún campo del 65 al 128 está presente, se
 * enciende el bit 1 y se agrega un bitmap secundario de otros 64 bits. Por eso
 * el bit 1 nunca es un campo de datos.
 */
class Bitmap private constructor(private val bits: BooleanArray) {

    operator fun contains(field: Int): Boolean = field in 2..MAX_FIELD && bits[field]

    /** Campos presentes, en orden ascendente, sin el bit 1. */
    val fields: List<Int> get() = (2..MAX_FIELD).filter { bits[it] }

    val hasSecondary: Boolean get() = fields.any { it > PRIMARY_BITS }

    /** 8 bytes, o 16 si hace falta el secundario. */
    fun encode(): ByteArray {
        val all = bits.copyOf()
        all[1] = hasSecondary
        val size = if (hasSecondary) 16 else 8
        val out = ByteArray(size)
        for (n in 1..size * 8) {
            if (all[n]) {
                val index = (n - 1) / 8
                out[index] = (out[index].toInt() or (0x80 ushr ((n - 1) % 8))).toByte()
            }
        }
        return out
    }

    override fun toString(): String = encode().toHex()

    companion object {
        const val PRIMARY_BITS = 64
        const val MAX_FIELD = 128

        fun of(fields: Collection<Int>): Bitmap {
            val bits = BooleanArray(MAX_FIELD + 1)
            for (f in fields) {
                require(f in 2..MAX_FIELD) { "Campo fuera de rango: $f" }
                bits[f] = true
            }
            return Bitmap(bits)
        }

        /**
         * Lee el bitmap desde [offset]. Devuelve el bitmap y cuántos bytes ocupó
         * (8 u 16).
         */
        fun decode(data: ByteArray, offset: Int): Pair<Bitmap, Int> {
            require(data.size >= offset + 8) { "No alcanzan los bytes para el bitmap primario" }
            val secondary = data[offset].toInt() and 0x80 != 0
            val size = if (secondary) 16 else 8
            require(data.size >= offset + size) { "No alcanzan los bytes para el bitmap secundario" }
            val bits = BooleanArray(MAX_FIELD + 1)
            for (n in 1..size * 8) {
                val b = data[offset + (n - 1) / 8].toInt()
                bits[n] = b and (0x80 ushr ((n - 1) % 8)) != 0
            }
            bits[1] = false
            return Bitmap(bits) to size
        }
    }
}
