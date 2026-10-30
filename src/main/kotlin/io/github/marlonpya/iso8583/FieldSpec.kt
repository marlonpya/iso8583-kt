package io.github.marlonpya.iso8583

/** Qué caracteres admite un campo. */
enum class FieldType {
    /** `n`: solo dígitos. Si es de largo fijo, se rellena con ceros a la izquierda. */
    NUMERIC,

    /** `an`/`ans`: texto ASCII imprimible. Si es de largo fijo, se rellena con espacios a la derecha. */
    TEXT,

    /** `z`: datos de pista 2, dígitos y el separador `=` o `D`. */
    TRACK2,

    /** `b`: bytes crudos. */
    BINARY,
}

/** Cómo se sabe el largo de un campo. */
sealed interface FieldLength {
    val max: Int

    /** Largo fijo, sin prefijo. */
    data class Fixed(override val max: Int) : FieldLength

    /** Prefijo de dos dígitos ASCII con el largo. */
    data class LlVar(override val max: Int) : FieldLength

    /** Prefijo de tres dígitos ASCII con el largo. */
    data class LllVar(override val max: Int) : FieldLength
}

data class FieldSpec(
    val id: Int,
    val name: String,
    val type: FieldType,
    val length: FieldLength,
    /** Campos que nunca deben aparecer en claro en un log o en pantalla. */
    val sensitive: Boolean = false,
)

/**
 * Diccionario de campos que entiende el codec.
 *
 * ISO 8583 define el formato base, pero cada procesador usa su propio
 * "dialecto": codificación ASCII o BCD, campos privados, dónde va el KSN. Este
 * es el dialecto del laboratorio: ASCII para todo lo que es texto, binario
 * crudo para PIN block, EMV y MAC, y el KSN como hexadecimal en el campo
 * privado 62. Las definiciones de los campos públicos siguen ISO 8583:1987.
 */
class IsoSpec(fields: List<FieldSpec>) {

    private val byId: Map<Int, FieldSpec> = fields.associateBy { it.id }

    operator fun get(id: Int): FieldSpec =
        byId[id] ?: throw IsoFormatException("El campo $id no está en el diccionario")

    operator fun contains(id: Int): Boolean = id in byId

    companion object {
        private fun n(id: Int, name: String, len: Int) = FieldSpec(id, name, FieldType.NUMERIC, FieldLength.Fixed(len))
        private fun an(id: Int, name: String, len: Int) = FieldSpec(id, name, FieldType.TEXT, FieldLength.Fixed(len))
        private fun b(id: Int, name: String, len: Int, sensitive: Boolean = false) =
            FieldSpec(id, name, FieldType.BINARY, FieldLength.Fixed(len), sensitive)

        val LAB = IsoSpec(
            listOf(
                FieldSpec(2, "PAN", FieldType.NUMERIC, FieldLength.LlVar(19), sensitive = true),
                n(3, "Código de procesamiento", 6),
                n(4, "Monto de la transacción", 12),
                n(7, "Fecha y hora de transmisión (MMDDhhmmss)", 10),
                n(11, "STAN", 6),
                n(12, "Hora local (hhmmss)", 6),
                n(13, "Fecha local (MMDD)", 4),
                FieldSpec(14, "Fecha de expiración (YYMM)", FieldType.NUMERIC, FieldLength.Fixed(4), sensitive = true),
                n(22, "Modo de entrada", 3),
                n(23, "Número de secuencia de la tarjeta", 3),
                n(24, "NII", 3),
                n(25, "Condición del punto de venta", 2),
                FieldSpec(35, "Pista 2", FieldType.TRACK2, FieldLength.LlVar(37), sensitive = true),
                an(37, "RRN", 12),
                an(38, "Código de autorización", 6),
                an(39, "Código de respuesta", 2),
                an(41, "Identificador del terminal", 8),
                an(42, "Identificador del comercio", 15),
                n(49, "Moneda", 3),
                b(52, "PIN block", 8, sensitive = true),
                FieldSpec(55, "Datos EMV (BER-TLV)", FieldType.BINARY, FieldLength.LllVar(255), sensitive = true),
                FieldSpec(62, "KSN (privado del laboratorio)", FieldType.TEXT, FieldLength.LllVar(999)),
                b(64, "MAC", 8),
                n(90, "Datos del mensaje original", 42),
                b(128, "MAC (bitmap secundario)", 8),
            ),
        )
    }
}

/** El mensaje no respeta el diccionario: largo, tipo de dato o campo desconocido. */
class IsoFormatException(message: String) : IllegalArgumentException(message)
