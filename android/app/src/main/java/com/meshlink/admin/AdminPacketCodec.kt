package com.meshlink.admin

import com.meshlink.crypto.AdminKeyManager
import com.meshlink.crypto.Base64Compat

/**
 * Wire codec for encapsulating and parsing [AdminCommand] payloads inside standard ML1 MeshPackets.
 *
 * Wire Format:
 * ADM1|<commandType>|<commandId>|<adminFingerprint>|<targetNodeId>|<sequenceNumber>|<timestampMs>|<commandData>|<signatureBase64>
 */
object AdminPacketCodec {
    const val PREFIX = "ADM1|"

    fun encode(command: AdminCommand): String {
        return buildString {
            append(PREFIX)
            append(escape(command.type.wireName)).append('|')
            append(escape(command.commandId)).append('|')
            append(escape(command.adminFingerprint)).append('|')
            append(escape(command.targetNodeId)).append('|')
            append(command.sequenceNumber).append('|')
            append(command.timestampMs).append('|')
            append(escape(command.commandData)).append('|')
            append(escape(command.signatureBase64))
        }
    }

    fun decode(raw: String): AdminCommand? {
        if (!raw.startsWith(PREFIX)) return null
        val body = raw.removePrefix(PREFIX)
        val parts = splitFields(body, limit = 8)
        if (parts.size != 8) return null

        val type = AdminCommandType.fromWireName(unescape(parts[0])) ?: return null
        val commandId = unescape(parts[1])
        val adminFingerprint = unescape(parts[2])
        val targetNodeId = unescape(parts[3])
        val sequenceNumber = parts[4].toLongOrNull() ?: return null
        val timestampMs = parts[5].toLongOrNull() ?: return null
        val commandData = unescape(parts[6])
        val signatureBase64 = unescape(parts[7])

        if (commandId.isBlank() || adminFingerprint.isBlank() || targetNodeId.isBlank()) return null
        if (sequenceNumber <= 0 || timestampMs <= 0) return null

        return AdminCommand(
            type = type,
            commandId = commandId,
            adminFingerprint = adminFingerprint,
            targetNodeId = targetNodeId,
            sequenceNumber = sequenceNumber,
            timestampMs = timestampMs,
            commandData = commandData,
            signatureBase64 = signatureBase64
        )
    }

    fun isAdminFrame(raw: String): Boolean = raw.startsWith(PREFIX)

    /**
     * Creates and signs an [AdminCommand] using the provided [AdminKeyManager].
     */
    fun createSignedCommand(
        type: AdminCommandType,
        commandId: String,
        adminFingerprint: String,
        targetNodeId: String,
        sequenceNumber: Long,
        timestampMs: Long,
        commandData: String,
        keyManager: AdminKeyManager
    ): AdminCommand {
        val unsigned = AdminCommand(
            type = type,
            commandId = commandId,
            adminFingerprint = adminFingerprint,
            targetNodeId = targetNodeId,
            sequenceNumber = sequenceNumber,
            timestampMs = timestampMs,
            commandData = commandData,
            signatureBase64 = ""
        )
        val sigBytes = keyManager.sign(unsigned.toSignableBytes())
        val sigB64 = Base64Compat.encodeToString(sigBytes)
        return unsigned.copy(signatureBase64 = sigB64)
    }

    /**
     * Verifies the digital signature of an [AdminCommand] against a Base64-encoded Admin public key.
     */
    fun verifySignature(command: AdminCommand, adminPublicKeyBase64: String): Boolean {
        if (command.signatureBase64.isBlank()) return false
        val pubKey = AdminKeyManager.decodePublicKey(adminPublicKeyBase64) ?: return false
        val expectedFingerprint = AdminKeyManager.computeFingerprint(adminPublicKeyBase64) ?: return false
        if (command.adminFingerprint != expectedFingerprint) return false

        return try {
            val keyManager = AdminKeyManager()
            val sigBytes = Base64Compat.decode(command.signatureBase64)
            keyManager.verify(command.toSignableBytes(), sigBytes, pubKey)
        } catch (_: Throwable) {
            false
        }
    }

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
