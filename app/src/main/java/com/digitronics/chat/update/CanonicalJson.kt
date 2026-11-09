package com.digitronics.chat.update

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * Byte-for-byte port of the Worker's `canonicalJson`
 * (whatsapp-inbox-saas worker/src/lib/release-manifest.ts):
 *
 *   - object keys sorted recursively by UTF-16 code units (JS `<`), no whitespace;
 *   - strings escaped exactly as `JSON.stringify` does (", \, \b \f \n \r \t,
 *     other C0 controls and lone surrogates as lowercase \u00xx; everything else raw);
 *   - numbers printed as JavaScript prints them.
 *
 * It is applied to the manifest JSON exactly as received, never to a re-encoded
 * Kotlin object, so a field the app does not know about is still covered.
 * The unit test pins it to vectors produced by the TypeScript function itself.
 */
object CanonicalJson {
    fun encode(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                element.entries.sortedWith { a, b -> a.key.compareTo(b.key) }.forEachIndexed { i, (key, value) ->
                    if (i > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(value, out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, value ->
                    if (i > 0) out.append(',')
                    write(value, out)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                element.isString -> writeString(element.content, out)
                element.content == "true" || element.content == "false" -> out.append(element.content)
                else -> out.append(jsNumber(element.content))
            }
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(unicodeEscape(c))
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) {
                        out.append(c).append(value[i + 1])
                        i++
                    } else {
                        out.append(unicodeEscape(c))
                    }
                }
                Character.isLowSurrogate(c) -> out.append(unicodeEscape(c))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private fun unicodeEscape(c: Char): String = "\\u" + "%04x".format(c.code)

    /** JavaScript `Number#toString` for a JSON number literal. */
    internal fun jsNumber(literal: String): String {
        literal.toLongOrNull()?.let { return it.toString() }
        val value = literal.toDouble()
        require(value.isFinite()) { "Non-finite number in manifest." }
        if (value == 0.0) return "0"
        val decimal = BigDecimal(value.toString()).stripTrailingZeros()
        val digits = decimal.unscaledValue().abs().toString()
        val exponent = digits.length - decimal.scale() // position of the decimal point
        val sign = if (value < 0) "-" else ""
        return sign + when {
            exponent in 1..21 && digits.length <= exponent -> digits + "0".repeat(exponent - digits.length)
            exponent in 1..21 -> digits.substring(0, exponent) + "." + digits.substring(exponent)
            exponent in -5..0 -> "0." + "0".repeat(-exponent) + digits
            else -> {
                val e = exponent - 1
                val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + (if (e >= 0) "+" else "-") + kotlin.math.abs(e)
            }
        }
    }
}
