package com.fanlens.prototype.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What the latest GitHub release says, once it has been read. */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/**
 * Looks up the newest release of this app on GitHub.
 *
 * Public repo, public endpoint, no account or token involved -- the same
 * information anyone sees on the repo's Releases page. Nothing is sent except
 * the request itself.
 */
class UpdateChecker(
    private val repoOwner: String = "emporiumelectric-dotcom",
    private val repoName: String = "EE-Lens"
) {

    /** The newest release, or null if the repo has none yet. */
    fun latest(): UpdateInfo? {
        val connection = open("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
        try {
            when (connection.responseCode) {
                200 -> Unit
                404 -> return null   // No releases published yet.
                403 -> throw UpdateCheckException(
                    "GitHub asked to slow down (rate limit). Try again in a few minutes."
                )
                else -> throw UpdateCheckException("GitHub answered with an error (${connection.responseCode}).")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
            if (tag.isBlank()) return null

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }
            val url = apkUrl ?: throw UpdateCheckException(
                "Release $tag was found, but it has no APK file attached."
            )
            return UpdateInfo(
                versionName = tag,
                notes = json.optString("body").trim(),
                downloadUrl = url,
                sizeBytes = apkSize
            )
        } catch (error: UpdateCheckException) {
            throw error
        } catch (error: Throwable) {
            throw UpdateCheckException(friendly(error))
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "EE-Lens-App")
        }

    private fun friendly(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "GitHub did not answer in time. Check the connection and try again."
        is java.net.UnknownHostException -> "No internet connection. Connect to Wi-Fi or mobile data and try again."
        else -> error.message ?: "Could not check for an update."
    }

    companion object {
        /**
         * Compares two "1.2.3"-style version strings. True when [remote] is
         * newer than [current]; unequal part counts pad the shorter one with
         * zeros, so "1.2" and "1.2.0" are treated as equal.
         */
        fun isNewer(remote: String, current: String): Boolean {
            val r = remote.split(".").mapNotNull { it.toIntOrNull() }
            val c = current.split(".").mapNotNull { it.toIntOrNull() }
            val length = maxOf(r.size, c.size)
            for (i in 0 until length) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            return false
        }
    }
}

class UpdateCheckException(message: String) : Exception(message)
