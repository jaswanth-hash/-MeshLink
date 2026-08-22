package com.meshlink.crypto

/**
 * Base64 helper providing compatibility between Android runtime and standard JVM unit tests.
 */
object Base64Compat {
    fun encodeToString(bytes: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    fun decode(base64: String): ByteArray {
        return try {
            android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(base64)
        }
    }
}
