package org.flixlang.intellij.lang

/**
 * A minimal, dependency-free JSON reader.
 *
 * Ported from `flix-spec`'s `Json.scala` rather than pulling in Gson: it is not reliably on this
 * module's test classpath (it lives inside the IntelliJ platform's bundled Maven plugin, not
 * exposed to a project's own dependencies), and flix-spec's own tooling already established the
 * house precedent of a hand-rolled reader over a new dependency for exactly this class of problem.
 * Read-only: everything here reads committed or generated JSON, never writes it.
 */
sealed class Json {
    class JsonException(message: String) : RuntimeException(message)

    data class JObject(val fields: Map<String, Json>) : Json()
    data class JArray(val items: List<Json>) : Json()
    data class JString(val value: String) : Json()
    data class JNumber(val value: Double) : Json()
    data class JBool(val value: Boolean) : Json()
    object JNull : Json()

    fun get(key: String): Json? = (this as? JObject)?.fields?.get(key)

    fun asObject(): Map<String, Json> =
        (this as? JObject)?.fields ?: throw JsonException("expected object, got $this")

    fun asArray(): List<Json> =
        (this as? JArray)?.items ?: throw JsonException("expected array, got $this")

    fun asString(): String =
        (this as? JString)?.value ?: throw JsonException("expected string, got $this")

    companion object {
        fun parse(text: String): Json {
            val p = Parser(text)
            p.skipWhitespace()
            val v = p.parseValue()
            p.skipWhitespace()
            if (!p.atEnd()) throw JsonException("trailing content at offset ${p.pos}")
            return v
        }

        private class Parser(val text: String) {
            var pos: Int = 0

            fun atEnd(): Boolean = pos >= text.length
            private fun peek(): Char = text[pos]
            private fun advance(): Char = text[pos].also { pos += 1 }

            fun skipWhitespace() {
                while (!atEnd() && peek().isWhitespace()) pos += 1
            }

            private fun expect(c: Char) {
                if (atEnd() || peek() != c) throw JsonException("expected '$c' at offset $pos")
                pos += 1
            }

            fun parseValue(): Json {
                skipWhitespace()
                if (atEnd()) throw JsonException("unexpected end of input")
                return when (val c = peek()) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> JString(parseStringLiteral())
                    't' -> parseLiteral("true", JBool(true))
                    'f' -> parseLiteral("false", JBool(false))
                    'n' -> parseLiteral("null", JNull)
                    else ->
                        if (c == '-' || c.isDigit()) parseNumber()
                        else throw JsonException("unexpected character '$c' at offset $pos")
                }
            }

            private fun parseLiteral(literal: String, value: Json): Json {
                if (pos + literal.length > text.length || text.substring(pos, pos + literal.length) != literal) {
                    throw JsonException("expected '$literal' at offset $pos")
                }
                pos += literal.length
                return value
            }

            private fun parseObject(): JObject {
                expect('{')
                skipWhitespace()
                val fields = LinkedHashMap<String, Json>()
                if (!atEnd() && peek() == '}') {
                    pos += 1
                    return JObject(fields)
                }
                while (true) {
                    skipWhitespace()
                    val key = parseStringLiteral()
                    skipWhitespace()
                    expect(':')
                    fields[key] = parseValue()
                    skipWhitespace()
                    if (!atEnd() && peek() == ',') {
                        pos += 1
                    } else {
                        expect('}')
                        return JObject(fields)
                    }
                }
            }

            private fun parseArray(): JArray {
                expect('[')
                skipWhitespace()
                val items = mutableListOf<Json>()
                if (!atEnd() && peek() == ']') {
                    pos += 1
                    return JArray(items)
                }
                while (true) {
                    items += parseValue()
                    skipWhitespace()
                    if (!atEnd() && peek() == ',') {
                        pos += 1
                    } else {
                        expect(']')
                        return JArray(items)
                    }
                }
            }

            private fun parseStringLiteral(): String {
                expect('"')
                val sb = StringBuilder()
                while (peek() != '"') {
                    val c = advance()
                    if (c == '\\') {
                        when (val esc = advance()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = text.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw JsonException("invalid escape '\\$esc'")
                        }
                    } else {
                        sb.append(c)
                    }
                }
                pos += 1 // closing quote
                return sb.toString()
            }

            private fun parseNumber(): JNumber {
                val start = pos
                if (!atEnd() && peek() == '-') pos += 1
                while (!atEnd() && peek().isDigit()) pos += 1
                if (!atEnd() && peek() == '.') {
                    pos += 1
                    while (!atEnd() && peek().isDigit()) pos += 1
                }
                if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                    pos += 1
                    if (!atEnd() && (peek() == '+' || peek() == '-')) pos += 1
                    while (!atEnd() && peek().isDigit()) pos += 1
                }
                return JNumber(text.substring(start, pos).toDouble())
            }
        }
    }
}
