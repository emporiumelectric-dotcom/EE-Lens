package com.fanlens.prototype.util

import java.io.InputStream
import java.security.MessageDigest

object Hashing {

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
