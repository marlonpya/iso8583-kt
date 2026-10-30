# iso8583-kt

Codec **ISO 8583** en Kotlin, con **BER-TLV** para los datos EMV del campo 55 y
**Retail MAC** (ISO 9797-1 algoritmo 3, el de ANSI X9.19) para firmar mensajes.

Es un proyecto de estudio del laboratorio de adquirencia. Lo usan
`pinpad-protocol-kt` (el PINpad firma con el MAC), `gateway-mock` (el host)
y `android-pos` (la caja).

## Qué demuestra

| Archivo | Qué resuelve |
| --- | --- |
| `Mti.kt` | Los cuatro dígitos del MTI y por qué la respuesta es "función + 1" (0200 → 0210, 0400 → 0410) |
| `Bitmap.kt` | Bitmap primario y secundario: el bit 1 anuncia los campos 65 a 128 |
| `FieldSpec.kt` | El diccionario de campos del laboratorio: fijos, LLVAR y LLLVAR, y cuáles son sensibles |
| `IsoCodec.kt` | Empaquetar y desempaquetar, con relleno de fijos y validación de caracteres; encabezado de largo para TCP |
| `IsoMessage.kt` | Mensaje inmutable cuyo `toString()` enmascara el PAN y oculta pista 2, PIN block y EMV |
| `BerTlv.kt` | Tags de uno o más bytes, largos 81/82, tags constructores y un glosario de tags EMV |
| `RetailMac.kt` | Retail MAC y la firma de un mensaje completo, con verificación en tiempo constante |

## Cómo correrlo

```bash
./gradlew test   # 27 pruebas
./gradlew run    # venta 0200 firmada, alterada en el camino y su respuesta
```

## El dialecto

ISO 8583 define el formato, pero cada procesador tiene su dialecto. El de este
laboratorio es: texto en ASCII, PIN block, EMV y MAC en binario, encabezado de
dos bytes big-endian sobre TCP, y el KSN como hexadecimal en el campo privado
62. Los campos públicos siguen las definiciones de ISO 8583:1987.

## Cómo se validó el MAC

No tengo a mano un vector publicado de ISO 9797-1 que pueda citar con certeza,
así que no puse uno "de memoria". El MAC se contrasta con dos construcciones
que deben coincidir con él por definición:

- para un solo bloque, el Retail MAC es TDES-EDE de ese bloque;
- para n bloques, es TDES-EDE de (DES-CBC con K1 de los n−1 primeros) XOR el
  último, escrito a mano con DES-ECB y sin el modo CBC de la JCE.

Un detalle que salió al escribir las pruebas: cambiar el bit menos
significativo de un byte de la llave no cambia el MAC, porque en DES ese bit es
de paridad y el algoritmo lo ignora. Quedó como prueba.

## Pendiente

- MAC de respuesta (requiere la variante de MAC de respuesta de DUKPT, que
  `dukpt-kt` todavía no tiene contrastada).
- Codificación BCD de numéricos, que usan varios procesadores.
- Un vector publicado de ISO 9797-1 cuando se tenga la fuente.
