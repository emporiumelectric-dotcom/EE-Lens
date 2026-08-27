package com.fanlens.prototype.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an update APK and hands it to Android's own installer.
 *
 * This app never installs anything itself -- it cannot, and should not be
 * able to. It downloads the file, then asks the system installer to take
 * over, the same as opening an APK from a downloads folder. Android still
 * requires the owner to grant "install unknown apps" once, and to tap
 * Install on the system's own screen every time -- there is no way around
 * either step outside the Play Store, by design.
 */
class UpdateInstaller(private val context: Context) {

    /** True once this app is allowed to hand a file to the system installer. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Sends the owner to the one-time system setting that grants that permission. */
    fun requestInstallPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Downloads the release APK into the app's own cache. Overwrites any previous download. */
    fun download(update: UpdateInfo, onProgress: (doneBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "ee-lens-${update.versionName}.apk")
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode != 200) {
                throw UpdateCheckException("The download failed (${connection.responseCode}).")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
            var done = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            if (target.length() == 0L) throw UpdateCheckException("The downloaded file was empty.")
            return target
        } catch (error: UpdateCheckException) {
            target.delete()
            throw error
        } catch (error: Throwable) {
            target.delete()
            throw UpdateCheckException(error.message ?: "The update could not be downloaded.")
        } finally {
            connection.disconnect()
        }
    }

    /** Hands the downloaded file to the system package installer. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
