package io.github.marlonpya.iso8583

/**
 * Message Type Indicator: los cuatro dígitos con los que empieza todo mensaje.
 *
 * Cada dígito tiene un significado (ISO 8583:1987):
 *  - versión (`0` = 1987),
 *  - clase (`1` autorización, `2` financiero, `4` reversa, `8` gestión de red),
 *  - función (`0` solicitud, `1` respuesta, `2` aviso, `3` respuesta a aviso),
 *  - origen (`0` adquirente).
 *
 * Por eso la respuesta de un mensaje es siempre el mismo MTI con la función
 * más uno: 0200 → 0210, 0400 → 0410, 0800 → 0810.
 */
@JvmInline
value class Mti(val code: String) {

    init {
        require(code.length == 4 && code.all { it.isDigit() }) {
            "Un MTI son cuatro dígitos, llegó \"$code\""
        }
    }

    val messageClass: Char get() = code[1]

    val function: Char get() = code[2]

    /** Solicitudes y avisos esperan respuesta; las respuestas no. */
    val expectsResponse: Boolean get() = function == '0' || function == '2'

    val isResponse: Boolean get() = function == '1' || function == '3'

    /** El MTI de la respuesta a este mensaje. */
    fun response(): Mti {
        check(expectsResponse) { "$code ya es una respuesta" }
        return Mti(code.substring(0, 2) + (function + 1) + code[3])
    }

    override fun toString(): String = code

    companion object {
        val AUTHORIZATION_REQUEST = Mti("0100")
        val AUTHORIZATION_RESPONSE = Mti("0110")
        val FINANCIAL_REQUEST = Mti("0200")
        val FINANCIAL_RESPONSE = Mti("0210")
        val FINANCIAL_ADVICE = Mti("0220")
        val FINANCIAL_ADVICE_RESPONSE = Mti("0230")
        val REVERSAL_REQUEST = Mti("0400")
        val REVERSAL_RESPONSE = Mti("0410")
        val NETWORK_REQUEST = Mti("0800")
        val NETWORK_RESPONSE = Mti("0810")
    }
}
