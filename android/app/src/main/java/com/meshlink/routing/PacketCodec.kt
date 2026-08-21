package com.meshlink.routing

/**
 * Wire format for [MeshPacket] over Nearby (or test) byte/string payloads.
 * Prefix makes mesh frames distinguishable from legacy plain chat text.
 */
object PacketCodec {
    const val PREFIX = "ML1|"

    fun encode(packet: MeshPacket): String {
        return buildString {
            append(PREFIX)
            append(escape(packet.messageId)).append('|')
            append(escape(packet.sourceId)).append('|')
            append(escape(packet.destinationId)).append('|')
            append(packet.ttl).append('|')
            append(packet.timestampMs).append('|')
            append(escape(packet.payload))
        }
    }

    fun decode(raw: String): MeshPacket? {
        if (!raw.startsWith(PREFIX)) return null
        val body = raw.removePrefix(PREFIX)
        val parts = splitFields(body, limit = 6)
        if (parts.size != 6) return null
        val ttl = parts[3].toIntOrNull() ?: return null
        val timestamp = parts[4].toLongOrNull() ?: return null
        return MeshPacket(
            messageId = unescape(parts[0]),
            sourceId = unescape(parts[1]),
            destinationId = unescape(parts[2]),
            ttl = ttl,
            timestampMs = timestamp,
            payload = unescape(parts[5])
        )
    }

    fun isMeshFrame(raw: String): Boolean = raw.startsWith(PREFIX)

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n")

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> out.append('\\')
                    '|' -> out.append('|')
                    'n' -> out.append('\n')
                    else -> {
                        out.append(c)
                        i -= 1
                    }
                }
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }

    /** Split on unescaped '|'. */
    private fun splitFields(input: String, limit: Int): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length) {
                current.append(c).append(input[i + 1])
                i += 2
                continue
            }
            if (c == '|' && fields.size < limit - 1) {
                fields.add(current.toString())
                current.clear()
                i += 1
                continue
            }
            current.append(c)
            i += 1
        }
        fields.add(current.toString())
        return fields
    }
}
