package io.github.marlonpya.iso8583

/**
 * Arma una venta 0200 con datos de prueba, la firma con MAC, la empaqueta y
 * muestra qué pasa si alguien altera un byte en el camino.
 *
 * La tarjeta es la de prueba que todos los procesadores aceptan en sandbox y
 * la llave de MAC es de laboratorio.
 */
fun main() {
    val codec = IsoCodec()
    val mac = MessageMac(codec)
    val macKey = "0123456789ABCDEFFEDCBA9876543210".hexToBytes()

    val emv = BerTlv.encode(
        listOf(
            Tlv(EmvTags.AMOUNT_AUTHORISED, "000000002550".hexToBytes()),
            Tlv(EmvTags.TRANSACTION_CURRENCY, "0604".hexToBytes()),
            Tlv(EmvTags.APPLICATION_CRYPTOGRAM, "1122334455667788".hexToBytes()),
            Tlv(EmvTags.ATC, "0001".hexToBytes()),
        ),
    )

    val sale = IsoMessage.of(Mti.FINANCIAL_REQUEST)
        .with(Fields.PROCESSING_CODE, "000000")
        .with(Fields.AMOUNT, "2550")
        .with(Fields.TRANSMISSION_DATETIME, "1030153000")
        .with(Fields.STAN, "1")
        .with(Fields.ENTRY_MODE, "051")
        .with(Fields.TRACK2, "4111111111111111=30121010000000000000")
        .with(Fields.TERMINAL_ID, "LAB00001")
        .with(Fields.MERCHANT_ID, "COMERCIO-LAB")
        .with(Fields.CURRENCY, "604")
        .with(Fields.EMV_DATA, emv)

    val signed = mac.sign(sale, macKey)
    val wire = codec.pack(signed)

    println("=== Venta 0200 tal como la ve un log ===")
    println(signed)
    println()
    println("Bitmap: ${signed.bitmap}  ->  campos ${signed.fields}")
    println("En el cable: ${wire.size} bytes, MAC ${signed.bytes(Fields.MAC).toHex()}")
    println("MAC válido al recibir: ${mac.verify(wire, macKey)}")

    // Byte 29: último dígito del monto (MTI 4 + bitmap 8 + campo 3 de 6 + campo 4 de 12).
    val tampered = wire.copyOf().also { it[29] = (it[29].toInt() xor 0x01).toByte() }
    println("Con un bit alterado en el monto: ${runCatching { mac.verify(tampered, macKey) }.getOrDefault(false)}")

    println()
    println("=== Respuesta del host ===")
    println(signed.toResponse("00").with(Fields.AUTH_CODE, "A1B2C3").with(Fields.RRN, "430315000001"))

    println()
    println("=== Campo 55 leído como TLV ===")
    BerTlv.parse(signed.bytes(Fields.EMV_DATA)).forEach {
        println("  ${it.tag.padEnd(4)} ${EmvTags.NAMES[it.tag] ?: "?"}: ${it.value.toHex()}")
    }
}
