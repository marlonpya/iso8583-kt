package io.github.marlonpya.iso8583

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Retail MAC: ISO 9797-1 algoritmo 3 con método de relleno 1, el mismo
 * esquema que ANSI X9.19.
 *
 * Con una llave doble K = K1 || K2:
 *  1. Se rellena con ceros hasta un múltiplo de 8 bytes.
 *  2. Se encadena todo con DES-CBC y K1, vector inicial en cero.
 *  3. Al último bloque se le aplica descifrado con K2 y cifrado con K1.
 *
 * El paso 3 hace que, para un mensaje de un solo bloque, el resultado sea
 * exactamente TDES (EDE) del bloque: es una de las propiedades que verifican
 * los tests. La ventaja frente a TDES-CBC completo es el costo: un DES por
 * bloque y solo dos operaciones extra al final.
 */
object RetailMac {

    const val SIZE = 8
    private const val BLOCK = 8

    fun compute(key16: ByteArray, data: ByteArray): ByteArray {
        require(key16.size == 16) { "La llave de MAC debe tener 16 bytes, tiene ${key16.size}" }
        val k1 = key16.copyOfRange(0, 8)
        val k2 = key16.copyOfRange(8, 16)
        val cbc = Cipher.getInstance("DES/CBC/NoPadding")
        cbc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k1, "DES"), IvParameterSpec(ByteArray(BLOCK)))
        val chained = cbc.doFinal(pad(data))
        val last = chained.copyOfRange(chained.size - BLOCK, chained.size)
        return des(Cipher.ENCRYPT_MODE, k1, des(Cipher.DECRYPT_MODE, k2, last))
    }

    /** Comparación en tiempo constante, para no filtrar cuántos bytes coincidieron. */
    fun verify(key16: ByteArray, data: ByteArray, mac: ByteArray): Boolean =
        mac.size == SIZE && MessageDigest.isEqual(compute(key16, data), mac)

    /** Método de relleno 1: ceros hasta el siguiente múltiplo de 8; un mensaje vacío es un bloque de ceros. */
    internal fun pad(data: ByteArray): ByteArray {
        val size = if (data.isEmpty()) BLOCK else (data.size + BLOCK - 1) / BLOCK * BLOCK
        return data.copyOf(size)
    }

    private fun des(mode: Int, key8: ByteArray, block: ByteArray): ByteArray {
        val c = Cipher.getInstance("DES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(key8, "DES"))
        return c.doFinal(block)
    }
}

/**
 * Firma y verificación de un mensaje completo.
 *
 * Convención del laboratorio: el MAC va en el último campo del mensaje (64, o
 * 128 si hay bitmap secundario) y se calcula sobre todos los bytes empaquetados
 * anteriores a él, con el bit del campo de MAC ya encendido en el bitmap. Así
 * el MAC protege también al bitmap: nadie puede quitar o agregar un campo sin
 * romperlo.
 */
class MessageMac(private val codec: IsoCodec = IsoCodec()) {

    fun sign(message: IsoMessage, key16: ByteArray): IsoMessage =
        sign(message) { data -> RetailMac.compute(key16, data) }

    /**
     * Firma delegando el cálculo del MAC, para cuando la llave no está en este
     * proceso: la caja arma el mensaje y el PINpad lo firma.
     */
    fun sign(message: IsoMessage, computeMac: (ByteArray) -> ByteArray): IsoMessage {
        val field = macFieldFor(message)
        val placeholder = message.without(Fields.MAC).without(Fields.MAC_SECONDARY).with(field, ByteArray(RetailMac.SIZE))
        val packed = codec.pack(placeholder)
        val mac = computeMac(packed.copyOfRange(0, packed.size - RetailMac.SIZE))
        require(mac.size == RetailMac.SIZE) { "El MAC debe tener ${RetailMac.SIZE} bytes, tiene ${mac.size}" }
        return placeholder.with(field, mac)
    }

    /** Verifica sobre los bytes tal como llegaron, no sobre un mensaje reempaquetado. */
    fun verify(raw: ByteArray, key16: ByteArray): Boolean {
        val message = codec.unpack(raw)
        val field = macFieldFor(message)
        if (field !in message || message.fields.last() != field) return false
        val data = raw.copyOfRange(0, raw.size - RetailMac.SIZE)
        return RetailMac.verify(key16, data, message.bytes(field))
    }

    private fun macFieldFor(message: IsoMessage): Int {
        val dataFields = message.fields - Fields.MAC - Fields.MAC_SECONDARY
        return if (dataFields.any { it > Bitmap.PRIMARY_BITS }) Fields.MAC_SECONDARY else Fields.MAC
    }
}
