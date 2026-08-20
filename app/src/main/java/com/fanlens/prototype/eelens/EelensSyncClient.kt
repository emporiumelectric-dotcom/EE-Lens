package com.fanlens.prototype.eelens

import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the Catalogue Manager running on the shop's PC.
 *
 * Plain HTTP over the local network only: the address is one the owner typed,
 * every request carries the pairing code shown on the PC, and the destination
 * is checked to be a private address so a mistyped entry cannot send the
 * catalogue out to the internet.
 */
class EelensSyncClient {

    /** Refusal message, or null when the address is a normal local one. */
    fun checkAddress(address: String): String? {
        val host = address.substringBefore(':').trim()
        if (host.isEmpty()) return "Enter the address shown on the PC."
        return try {
            val resolved = InetAddress.getByName(host)
            if (resolved.isSiteLocalAddress || resolved.isLoopbackAddress || resolved.isLinkLocalAddress) {
                null
            } else {
                "That address is not on your own network. Use the one shown on the PC."
            }
        } catch (_: Throwable) {
            "Could not find \"$host\" on this network. Check the address and the Wi-Fi."
        }
    }

    /** Collects the catalogue the PC shared. */
    fun download(address: String, code: String, into: File): File {
        checkAddress(address)?.let { throw EelensException(it) }
        val connection = open(address, "/sync/pc", code, "GET")
        try {
            when (connection.responseCode) {
                200 -> Unit
                403 -> throw EelensException("The PC refused that pairing code. Check the six digits.")
                404 -> throw EelensException("The PC has not shared a catalogue yet.")
                else -> throw EelensException("The PC answered with an error (${connection.responseCode}).")
            }
            connection.inputStream.use { input ->
                into.outputStream().use(input::copyTo)
            }
            if (into.length() == 0L) throw EelensException("The PC sent an empty catalogue.")
            return into
        } catch (error: EelensException) {
            throw error
        } catch (error: Throwable) {
            throw EelensException(friendly(error))
        } finally {
            connection.disconnect()
        }
    }

    /** Hands this phone's catalogue to the PC. */
    fun upload(address: String, code: String, file: File) {
        checkAddress(address)?.let { throw EelensException(it) }
        val connection = open(address, "/sync/phone", code, "PUT")
        try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(file.length())
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            when (connection.responseCode) {
                200 -> Unit
                403 -> throw EelensException("The PC refused that pairing code. Check the six digits.")
                413 -> throw EelensException("This catalogue is too large to send.")
                else -> throw EelensException("The PC answered with an error (${connection.responseCode}).")
            }
        } catch (error: EelensException) {
            throw error
        } catch (error: Throwable) {
            throw EelensException(friendly(error))
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String, path: String, code: String, method: String): HttpURLConnection {
        val clean = address.trim().removePrefix("http://").removeSuffix("/")
        val url = URL("http://$clean$path?code=${URLEncoder.encode(code.trim(), "UTF-8")}")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            useCaches = false
        }
    }

    private fun friendly(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException ->
            "The PC did not answer in time. Check both are on the same Wi-Fi."
        is java.net.ConnectException ->
            "Could not reach the PC. Make sure the Catalogue Manager is running with phone sync switched on."
        is java.net.UnknownHostException ->
            "Could not find that address on this network."
        else -> error.message ?: "The catalogue could not be transferred."
    }
}
