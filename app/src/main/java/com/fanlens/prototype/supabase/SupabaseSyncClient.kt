package com.fanlens.prototype.supabase

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SupabaseSyncException(message: String) : Exception(message)

/**
 * Plain REST calls against the ee_lens schema (PostgREST) and the
 * ee-lens-photos Storage bucket. Anon/publishable key only -- see
 * SupabaseConfig.
 *
 * Android's HttpURLConnection refuses to send a PATCH request (it validates
 * against a fixed method list and throws), so every write here is a POST
 * upsert against a unique client_id column
 * (Prefer: resolution=merge-duplicates) instead of a partial PATCH -- this
 * also matches how a new row and an update to an existing one are the same
 * call. cloud.js on the PC uses fetch() instead, which has no such
 * restriction, so it uses PATCH directly; the two are functionally
 * equivalent from the database's point of view.
 */
class SupabaseSyncClient {

    /** Insert-or-update by client_id. [body] must include every column that matters -- an upsert is a full row replace. */
    fun upsertProduct(accessToken: String?, body: JSONObject): JSONObject =
        request(
            method = "POST",
            path = "/rest/v1/products",
            query = "on_conflict=client_id",
            accessToken = accessToken,
            prefer = "resolution=merge-duplicates,return=representation",
            body = body.toString()
        ).let { JSONArray(it).getJSONObject(0) }

    fun upsertPhoto(accessToken: String, body: JSONObject): JSONObject =
        request(
            method = "POST",
            path = "/rest/v1/product_photos",
            query = "on_conflict=client_id",
            accessToken = accessToken,
            prefer = "resolution=merge-duplicates,return=representation",
            body = body.toString()
        ).let { JSONArray(it).getJSONObject(0) }

    /** Every live and soft-deleted product, oldest-updated first. */
    fun fetchAllProducts(accessToken: String?): JSONArray =
        JSONArray(
            request(
                method = "GET",
                path = "/rest/v1/products",
                query = "select=*&order=updated_at.asc",
                accessToken = accessToken
            )
        )

    fun fetchPhotosForProduct(accessToken: String?, remoteProductId: Long): JSONArray =
        JSONArray(
            request(
                method = "GET",
                path = "/rest/v1/product_photos",
                query = "product_id=eq.$remoteProductId&select=*&order=sort_order.asc",
                accessToken = accessToken
            )
        )

    fun uploadPhoto(accessToken: String, productClientId: String, photoClientId: String, bytes: ByteArray) {
        val url = URL(
            "${SupabaseConfig.URL}/storage/v1/object/${SupabaseConfig.PHOTOS_BUCKET}/" +
                photoStoragePath(productClientId, photoClientId)
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            useCaches = false
            setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "image/jpeg")
            setRequestProperty("x-upsert", "true")
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            checkOk(connection, "Photo upload")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads by the storage_path the row itself carries, rather than
     * reconstructing one from local ids -- the device pulling a photo may
     * not use the same local ids as the device that pushed it (a legacy-id
     * product's client_id is a generated UUID, not its local id), so the
     * row's own path is the only value both can agree on. Reading is open
     * to anon; no session is required to download.
     */
    fun downloadPhoto(storagePath: String): ByteArray {
        val url = URL("${SupabaseConfig.URL}/storage/v1/object/${SupabaseConfig.PHOTOS_BUCKET}/$storagePath")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            useCaches = false
            setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            setRequestProperty("Authorization", "Bearer ${SupabaseConfig.ANON_KEY}")
        }
        try {
            checkOk(connection, "Photo download")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun request(
        method: String,
        path: String,
        accessToken: String?,
        query: String? = null,
        prefer: String? = null,
        body: String? = null
    ): String {
        val url = URL("${SupabaseConfig.URL}$path" + if (query != null) "?$query" else "")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("apikey", SupabaseConfig.ANON_KEY)
            setRequestProperty("Authorization", "Bearer ${accessToken ?: SupabaseConfig.ANON_KEY}")
            setRequestProperty("Accept-Profile", SupabaseConfig.SCHEMA)
            setRequestProperty("Content-Profile", SupabaseConfig.SCHEMA)
            if (prefer != null) setRequestProperty("Prefer", prefer)
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            checkOk(connection, "Cloud sync")
            return connection.inputStream.use { it.bufferedReader().readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun checkOk(connection: HttpURLConnection, what: String) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val text = (connection.errorStream ?: connection.inputStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()
            throw SupabaseSyncException("$what failed ($code): $text")
        }
    }

    companion object {
        fun photoStoragePath(productClientId: String, photoClientId: String) = "$productClientId/$photoClientId.jpg"
    }
}
