package com.fanlens.prototype.supabase

import com.fanlens.prototype.data.db.EeDatabase
import com.fanlens.prototype.data.db.MetaDao
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch seconds, as Supabase Auth returns it. */
    val expiresAt: Long,
    val email: String
)

class SupabaseAuthException(message: String) : Exception(message)

/**
 * Supabase Auth for the phone: email/password sign-in, mirroring
 * pc-catalogue-manager/supabase.js so both tools gate writes the same way.
 * The session is kept in the app's own meta table (never the service_role
 * key -- this is the same user session a shop owner types a password for).
 */
class SupabaseAuthClient(private val metaDao: MetaDao) {

    suspend fun currentSession(): SupabaseSession? {
        val token = metaDao.value(EeDatabase.KEY_SUPABASE_ACCESS_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = metaDao.value(EeDatabase.KEY_SUPABASE_REFRESH_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = metaDao.value(EeDatabase.KEY_SUPABASE_EXPIRES_AT)?.toLongOrNull() ?: return null
        val email = metaDao.value(EeDatabase.KEY_SUPABASE_EMAIL)?.takeIf { it.isNotBlank() } ?: return null
        return SupabaseSession(token, refresh, expiresAt, email)
    }

    suspend fun signIn(email: String, password: String): SupabaseSession {
        val connection = open("/auth/v1/token?grant_type=password")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val requestBody = JSONObject().put("email", email).put("password", password).toString()
        try {
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val text = readBody(connection)
            if (connection.responseCode !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error_description") }.getOrNull()
                throw SupabaseAuthException(
                    message?.takeIf { it.isNotBlank() } ?: "Sign in failed. Check the email and password."
                )
            }
            val json = JSONObject(text)
            val session = SupabaseSession(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresAt = json.getLong("expires_at"),
                email = json.optJSONObject("user")?.optString("email")?.takeIf { it.isNotBlank() } ?: email
            )
            save(session)
            return session
        } catch (error: SupabaseAuthException) {
            throw error
        } catch (error: Throwable) {
            throw SupabaseAuthException(friendly(error))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun signOut() {
        val session = currentSession()
        clear()
        if (session == null) return
        runCatching {
            val connection = open("/auth/v1/logout")
            connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            connection.responseCode
            connection.disconnect()
        }
    }

    /**
     * Refreshes a session about to expire. Returns null only when there is no
     * session to work with; offline, the existing session is returned as-is
     * rather than signing the owner out.
     */
    suspend fun ensureFreshSession(): SupabaseSession? {
        val session = currentSession() ?: return null
        val expiringSoon = session.expiresAt * 1000 < System.currentTimeMillis() + 60_000
        if (!expiringSoon) return session

        val refreshed = runCatching {
            val connection = open("/auth/v1/token?grant_type=refresh_token")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            try {
                val requestBody = JSONObject().put("refresh_token", session.refreshToken).toString()
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                val text = readBody(connection)
                if (connection.responseCode !in 200..299) {
                    clear()
                    return@runCatching null
                }
                val json = JSONObject(text)
                SupabaseSession(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.getString("refresh_token"),
                    expiresAt = json.getLong("expires_at"),
                    email = json.optJSONObject("user")?.optString("email")?.takeIf { it.isNotBlank() } ?: session.email
                ).also { save(it) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        return refreshed ?: session
    }

    private suspend fun save(session: SupabaseSession) {
        metaDao.put(EeDatabase.KEY_SUPABASE_ACCESS_TOKEN, session.accessToken)
        metaDao.put(EeDatabase.KEY_SUPABASE_REFRESH_TOKEN, session.refreshToken)
        metaDao.put(EeDatabase.KEY_SUPABASE_EXPIRES_AT, session.expiresAt.toString())
        metaDao.put(EeDatabase.KEY_SUPABASE_EMAIL, session.email)
    }

    /** MetaDao has no delete; blank values read back as "no session" in currentSession(). */
    private suspend fun clear() {
        metaDao.put(EeDatabase.KEY_SUPABASE_ACCESS_TOKEN, "")
        metaDao.put(EeDatabase.KEY_SUPABASE_REFRESH_TOKEN, "")
        metaDao.put(EeDatabase.KEY_SUPABASE_EXPIRES_AT, "")
        metaDao.put(EeDatabase.KEY_SUPABASE_EMAIL, "")
    }

    private fun open(path: String): HttpURLConnection =
        (URL("${SupabaseConfig.URL}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            useCaches = false
            setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
        }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun friendly(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "Supabase did not answer in time. Check the connection and try again."
        is java.net.UnknownHostException -> "No internet connection. Connect to Wi-Fi or mobile data and try again."
        else -> error.message ?: "Sign in failed."
    }
}
