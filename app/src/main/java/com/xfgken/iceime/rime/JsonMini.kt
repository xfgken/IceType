package com.xfgken.iceime.rime

/**
 * 极简 JSON 读取器。
 *
 * 只用于解析本工程 `icetype_rime.cpp` 自己生成的固定结构，不追求通用性：
 * 支持对象、字符串、数字、布尔与对象数组。这样可以避免引入额外的序列化依赖。
 */
internal class JsonMini(private val src: String) {

    private var pos = 0

    companion object {
        fun parseObject(text: String): Map<String, Any?>? {
            if (text.isBlank()) return null
            return runCatching { JsonMini(text).readObject() }.getOrNull()
        }

        fun parseArray(text: String): List<Map<String, Any?>> {
            if (text.isBlank()) return emptyList()
            return runCatching {
                val p = JsonMini(text)
                p.skipWs()
                p.expect('[')
                val out = ArrayList<Map<String, Any?>>()
                p.skipWs()
                if (p.peek() == ']') {
                    p.pos++
                    return@runCatching out
                }
                while (true) {
                    out.add(p.readObject())
                    p.skipWs()
                    when (p.peek()) {
                        ',' -> p.pos++
                        ']' -> {
                            p.pos++
                            break
                        }

                        else -> error("期望 , 或 ]，实际为 ${p.peek()} @${p.pos}")
                    }
                }
                out
            }.getOrDefault(emptyList())
        }
    }

    private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun expect(c: Char) {
        skipWs()
        check(peek() == c) { "期望 $c，实际为 ${peek()} @$pos" }
        pos++
    }

    fun readObject(): Map<String, Any?> {
        skipWs()
        expect('{')
        val map = HashMap<String, Any?>()
        skipWs()
        if (peek() == '}') {
            pos++
            return map
        }
        while (true) {
            skipWs()
            val key = readString()
            skipWs()
            expect(':')
            skipWs()
            map[key] = readValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return map
                }

                else -> error("期望 , 或 }，实际为 ${peek()} @$pos")
            }
        }
    }

    private fun readValue(): Any? {
        return when (val c = peek()) {
            '"' -> readString()
            '{' -> readObject()
            '[' -> {
                pos++
                val list = ArrayList<Any?>()
                skipWs()
                if (peek() == ']') {
                    pos++
                    list
                } else {
                    while (true) {
                        list.add(readValue())
                        skipWs()
                        when (peek()) {
                            ',' -> pos++
                            ']' -> {
                                pos++
                                break
                            }

                            else -> error("期望 , 或 ]")
                        }
                    }
                    list
                }
            }

            't' -> {
                pos += 4; true
            }

            'f' -> {
                pos += 5; false
            }

            'n' -> {
                pos += 4; null
            }

            else -> {
                if (c == '-' || c.isDigit()) readNumber() else error("非预期字符 $c @$pos")
            }
        }
    }

    private fun readNumber(): Any {
        val start = pos
        if (peek() == '-') pos++
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
        val text = src.substring(start, pos)
        return text.toDoubleOrNull() ?: 0.0
    }

    private fun readString(): String {
        skipWs()
        expect('"')
        val sb = StringBuilder()
        while (true) {
            when (val c = src[pos]) {
                '"' -> {
                    pos++
                    return sb.toString()
                }

                '\\' -> {
                    pos++
                    when (val e = src[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = src.substring(pos + 1, pos + 5)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }

                        else -> sb.append(e)
                    }
                    pos++
                }

                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
    }
}

internal fun Map<String, Any?>.str(key: String, def: String = ""): String =
    (this[key] as? String) ?: def

internal fun Map<String, Any?>.int(key: String, def: Int = 0): Int =
    (this[key] as? Double)?.toInt() ?: def

internal fun Map<String, Any?>.bool(key: String, def: Boolean = false): Boolean =
    (this[key] as? Boolean) ?: def

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()