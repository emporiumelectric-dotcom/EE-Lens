package com.fanlens.prototype.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fanlens.prototype.data.CatalogRepository
import com.fanlens.prototype.data.EeGraph
import com.fanlens.prototype.data.db.EeDatabase
import com.fanlens.prototype.eelens.EelensException
import com.fanlens.prototype.eelens.EelensReader
import com.fanlens.prototype.eelens.EelensSyncClient
import com.fanlens.prototype.eelens.ImportConflictPolicy
import com.fanlens.prototype.eelens.ImportPreview
import com.fanlens.prototype.eelens.StagedPackage
import com.fanlens.prototype.supabase.CloudSyncManager
import com.fanlens.prototype.supabase.SupabaseAuthClient
import com.fanlens.prototype.update.UpdateChecker
import com.fanlens.prototype.update.UpdateInfo
import com.fanlens.prototype.update.UpdateInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BackupViewModel(
    private val context: Context,
    private val repository: CatalogRepository,
    private val appVersion: String,
    private val onCatalogueChanged: () -> Unit
) : ViewModel() {

    data class UiState(
        val productCount: Int = 0,
        val photoCount: Int = 0,
        val storageBytes: Long = 0,
        val busy: Boolean = false,
        val busyMessage: String = "",
        val message: String? = null,
        val messageIsProblem: Boolean = false,
        val pending: ImportPreview? = null,
        val lastExportLabel: String? = null,
        val syncAddress: String = "",
        val syncCode: String = "",
        val currentVersion: String = "",
        val checkingUpdate: Boolean = false,
        val updateAvailable: UpdateInfo? = null,
        val downloadingUpdate: Boolean = false,
        val downloadProgress: Float = 0f,
        val readyApk: File? = null,
        val updateMessage: String? = null,
        val updateMessageIsProblem: Boolean = false,

        val cloudEmail: String? = null,
        val cloudSignInEmail: String = "",
        val cloudSignInPassword: String = "",
        val showCloudSignIn: Boolean = false,
        val cloudBusy: Boolean = false,
        val cloudBusyMessage: String = "",
        val cloudMessage: String? = null,
        val cloudMessageIsProblem: Boolean = false,
        val cloudLastPushAt: Long? = null,
        val cloudLastPullAt: Long? = null
    ) {
        val canSync: Boolean get() = syncAddress.isNotBlank() && syncCode.length >= 4
        val cloudSignedIn: Boolean get() = cloudEmail != null

        val summaryLine: String
            get() = "$productCount products · $photoCount photos · " +
                String.format(java.util.Locale.UK, "%.1f MB", storageBytes / (1024.0 * 1024.0))
    }

    private val _state = MutableStateFlow(UiState(currentVersion = appVersion))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var staged: StagedPackage? = null

    private val sync = EelensSyncClient()

    private val database = EeGraph.database(context)
    private val cloudAuth = SupabaseAuthClient(database.metaDao())
    private val cloudSync = CloudSyncManager(database, repository.photoStore, cloudAuth)

    init {
        refreshCounts()
        viewModelScope.launch {
            _state.update {
                it.copy(syncAddress = repository.syncAddress(), syncCode = repository.syncCode())
            }
        }
        viewModelScope.launch {
            val email = cloudAuth.currentSession()?.email
            val lastPush = database.metaDao().value(EeDatabase.KEY_CLOUD_LAST_PUSH_AT)?.toLongOrNull()
            val lastPull = database.metaDao().value(EeDatabase.KEY_CLOUD_LAST_PULL_AT)?.toLongOrNull()
            _state.update { it.copy(cloudEmail = email, cloudLastPushAt = lastPush, cloudLastPullAt = lastPull) }
        }
    }

    fun setSyncAddress(value: String) = _state.update { it.copy(syncAddress = value) }

    fun setSyncCode(value: String) = _state.update { it.copy(syncCode = value.filter(Char::isDigit).take(6)) }

    /** Collects the catalogue the PC shared, then goes through the normal preview. */
    fun getFromPc() {
        val current = _state.value
        _state.update { it.copy(busy = true, busyMessage = "Asking the PC…", message = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.rememberSync(current.syncAddress, current.syncCode)
                    val temp = File(context.cacheDir, "sync-${System.currentTimeMillis()}.eelens")
                    try {
                        sync.download(current.syncAddress, current.syncCode, temp)
                        EelensReader().read(temp, repository.knownProductIds())
                    } finally {
                        temp.delete()
                    }
                }
            }.onSuccess { result ->
                staged = result
                _state.update { it.copy(busy = false, pending = result.preview) }
            }.onFailure { error ->
                Log.e(TAG, "Sync download failed", error)
                staged = null
                _state.update {
                    it.copy(busy = false, messageIsProblem = true,
                        message = error.message ?: "Nothing could be collected from the PC.")
                }
            }
        }
    }

    /** Hands this phone's catalogue to the PC. */
    fun sendToPc() {
        val current = _state.value
        _state.update { it.copy(busy = true, busyMessage = "Preparing the catalogue…", message = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.rememberSync(current.syncAddress, current.syncCode)
                    val temp = File(context.cacheDir, "outbound-${System.currentTimeMillis()}.eelens")
                    try {
                        val summary = temp.outputStream().use { out ->
                            repository.exportTo(out, appVersion)
                        }
                        sync.upload(current.syncAddress, current.syncCode, temp)
                        summary
                    } finally {
                        temp.delete()
                    }
                }
            }.onSuccess { summary ->
                _state.update {
                    it.copy(busy = false, messageIsProblem = false,
                        message = "Sent ${summary.products} products and ${summary.photos} photos to the PC. " +
                            "On the PC, open Phone sync and press \"Load what the phone shared\".")
                }
            }.onFailure { error ->
                Log.e(TAG, "Sync upload failed", error)
                _state.update {
                    it.copy(busy = false, messageIsProblem = true,
                        message = error.message ?: "The catalogue could not be sent.")
                }
            }
        }
    }

    private fun refreshCounts() {
        viewModelScope.launch {
            val products = repository.products()
            val photos = products.sumOf { repository.photos(it.id).size }
            _state.update {
                it.copy(
                    productCount = products.size,
                    photoCount = photos,
                    storageBytes = repository.storageUsedBytes()
                )
            }
        }
    }

    fun exportTo(uri: Uri) {
        _state.update { it.copy(busy = true, busyMessage = "Building the catalogue file…", message = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        repository.exportTo(output, appVersion) { done, total ->
                            _state.update { it.copy(busyMessage = "Adding photos — $done of $total") }
                        }
                    } ?: throw EelensException("That location could not be written to.")
                }
            }.onSuccess { summary ->
                val mb = summary.bytes / (1024.0 * 1024.0)
                _state.update {
                    it.copy(
                        busy = false,
                        lastExportLabel = "Last exported just now",
                        message = String.format(
                            java.util.Locale.UK,
                            "Exported %d products and %d photos (%.1f MB).",
                            summary.products, summary.photos, mb
                        ),
                        messageIsProblem = false
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Export failed", error)
                _state.update {
                    it.copy(
                        busy = false,
                        message = error.message ?: "The catalogue could not be exported.",
                        messageIsProblem = true
                    )
                }
            }
        }
    }

    /** Reads and checks the package. Nothing is written until [commit]. */
    fun stageImport(uri: Uri) {
        _state.update { it.copy(busy = true, busyMessage = "Reading the catalogue file…", message = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val temp = File(context.cacheDir, "import-${System.currentTimeMillis()}.eelens")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().use(input::copyTo)
                        } ?: throw EelensException("That file could not be opened.")
                        EelensReader().read(temp, repository.knownProductIds())
                    } finally {
                        temp.delete()
                    }
                }
            }.onSuccess { result ->
                staged = result
                _state.update { it.copy(busy = false, pending = result.preview) }
            }.onFailure { error ->
                Log.e(TAG, "Import could not be read", error)
                staged = null
                _state.update {
                    it.copy(
                        busy = false,
                        message = error.message ?: "This catalogue could not be read.",
                        messageIsProblem = true
                    )
                }
            }
        }
    }

    fun commit(policy: ImportConflictPolicy) {
        val pending = staged ?: return
        _state.update { it.copy(pending = null, busy = true, busyMessage = "Adding products…") }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.commitImport(pending, policy) }
            }.onSuccess { summary ->
                staged = null
                refreshCounts()
                onCatalogueChanged()
                val notes = buildList {
                    if (summary.productsAdded > 0) add("${summary.productsAdded} added")
                    if (summary.productsReplaced > 0) add("${summary.productsReplaced} replaced")
                    if (summary.productsSkipped > 0) add("${summary.productsSkipped} kept as they were")
                    add("${summary.photosAdded} photos")
                    if (summary.photosMissing > 0) add("${summary.photosMissing} photos missing")
                    if (summary.photosCorrupt > 0) add("${summary.photosCorrupt} photos damaged")
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = "Restored: ${notes.joinToString(" · ")}. " +
                            "Recognition is preparing the new photos now.",
                        messageIsProblem = summary.photosMissing + summary.photosCorrupt > 0
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Import failed", error)
                staged = null
                _state.update {
                    it.copy(
                        busy = false,
                        message = (error.message ?: "This catalogue could not be restored.") +
                            " Your existing products are unchanged.",
                        messageIsProblem = true
                    )
                }
            }
        }
    }

    fun cancelImport() {
        staged = null
        _state.update { it.copy(pending = null) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /* ---------- cloud sync ---------- */

    fun setCloudSignInEmail(value: String) = _state.update { it.copy(cloudSignInEmail = value) }

    fun setCloudSignInPassword(value: String) = _state.update { it.copy(cloudSignInPassword = value) }

    fun openCloudSignIn() = _state.update {
        it.copy(showCloudSignIn = true, cloudSignInPassword = "", cloudSignInEmail = it.cloudEmail ?: it.cloudSignInEmail)
    }

    fun closeCloudSignIn() = _state.update { it.copy(showCloudSignIn = false) }

    fun cloudSignIn() {
        val current = _state.value
        if (current.cloudSignInEmail.isBlank() || current.cloudSignInPassword.isBlank()) {
            _state.update { it.copy(cloudMessageIsProblem = true, cloudMessage = "Enter both an email and a password.") }
            return
        }
        _state.update { it.copy(cloudBusy = true, cloudBusyMessage = "Signing in…", cloudMessage = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { cloudAuth.signIn(current.cloudSignInEmail.trim(), current.cloudSignInPassword) }
            }.onSuccess { session ->
                _state.update {
                    it.copy(
                        cloudBusy = false, showCloudSignIn = false, cloudEmail = session.email,
                        cloudSignInPassword = "", cloudMessage = "Signed in as ${session.email}", cloudMessageIsProblem = false
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Cloud sign in failed", error)
                _state.update {
                    it.copy(cloudBusy = false, cloudMessageIsProblem = true, cloudMessage = error.message ?: "Sign in failed.")
                }
            }
        }
    }

    fun cloudSignOut() {
        _state.update { it.copy(cloudBusy = true, cloudBusyMessage = "Signing out…") }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { cloudAuth.signOut() }
            _state.update {
                it.copy(cloudBusy = false, cloudEmail = null, cloudMessage = "Signed out", cloudMessageIsProblem = false)
            }
        }
    }

    /** Pushes every product on this phone to the cloud. Needs a signed-in session. */
    fun cloudPush() {
        if (!_state.value.cloudSignedIn) { openCloudSignIn(); return }
        _state.update { it.copy(cloudBusy = true, cloudBusyMessage = "Pushing to the cloud…", cloudMessage = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    cloudSync.pushAll { done, total ->
                        _state.update { it.copy(cloudBusyMessage = "Pushing to the cloud — $done of $total") }
                    }
                }
            }.onSuccess { summary ->
                val now = System.currentTimeMillis()
                database.metaDao().put(EeDatabase.KEY_CLOUD_LAST_PUSH_AT, now.toString())
                val failedNote = if (summary.failed > 0) " · ${summary.failed} failed" else ""
                _state.update {
                    it.copy(
                        cloudBusy = false, cloudLastPushAt = now, cloudMessageIsProblem = summary.failed > 0,
                        cloudMessage = "Pushed ${summary.processed} of ${summary.total} products$failedNote."
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Cloud push failed", error)
                _state.update {
                    it.copy(cloudBusy = false, cloudMessageIsProblem = true, cloudMessage = error.message ?: "The cloud push failed.")
                }
            }
        }
    }

    /** Pulls in changes from the cloud. Works even signed out -- reading is open to everyone. */
    fun cloudPull() {
        _state.update { it.copy(cloudBusy = true, cloudBusyMessage = "Pulling from the cloud…", cloudMessage = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    cloudSync.pullAll { done, total ->
                        _state.update { it.copy(cloudBusyMessage = "Pulling from the cloud — $done of $total") }
                    }
                }
            }.onSuccess { summary ->
                val now = System.currentTimeMillis()
                database.metaDao().put(EeDatabase.KEY_CLOUD_LAST_PULL_AT, now.toString())
                refreshCounts()
                onCatalogueChanged()
                val failedNote = if (summary.failed > 0) " · ${summary.failed} failed" else ""
                _state.update {
                    it.copy(
                        cloudBusy = false, cloudLastPullAt = now, cloudMessageIsProblem = summary.failed > 0,
                        cloudMessage = "Pulled ${summary.processed} of ${summary.total} products$failedNote. " +
                            "Recognition is preparing any new photos now."
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Cloud pull failed", error)
                _state.update {
                    it.copy(cloudBusy = false, cloudMessageIsProblem = true, cloudMessage = error.message ?: "The cloud pull failed.")
                }
            }
        }
    }

    fun consumeCloudMessage() = _state.update { it.copy(cloudMessage = null) }

    /* ---------- app updates ---------- */

    fun checkForUpdate() {
        _state.update {
            it.copy(checkingUpdate = true, updateAvailable = null, readyApk = null,
                updateMessage = null, updateMessageIsProblem = false)
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { UpdateChecker().latest() }
            }.onSuccess { info ->
                val newer = info != null && UpdateChecker.isNewer(info.versionName, appVersion)
                _state.update {
                    it.copy(
                        checkingUpdate = false,
                        updateAvailable = if (newer) info else null,
                        updateMessage = when {
                            info == null -> "No releases have been published yet."
                            newer -> null
                            else -> "You already have the latest version ($appVersion)."
                        }
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Update check failed", error)
                _state.update {
                    it.copy(checkingUpdate = false, updateMessageIsProblem = true,
                        updateMessage = error.message ?: "Could not check for an update.")
                }
            }
        }
    }

    fun downloadUpdate() {
        val update = _state.value.updateAvailable ?: return
        _state.update { it.copy(downloadingUpdate = true, downloadProgress = 0f, updateMessage = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    UpdateInstaller(context).download(update) { done, total ->
                        if (total > 0) {
                            val progress = done.toFloat() / total
                            _state.update { it.copy(downloadProgress = progress) }
                        }
                    }
                }
            }.onSuccess { file ->
                _state.update { it.copy(downloadingUpdate = false, readyApk = file) }
            }.onFailure { error ->
                Log.e(TAG, "Update download failed", error)
                _state.update {
                    it.copy(downloadingUpdate = false, updateMessageIsProblem = true,
                        updateMessage = error.message ?: "The update could not be downloaded.")
                }
            }
        }
    }

    /** Hands the downloaded APK to the system installer, asking for permission first if needed. */
    fun installUpdate() {
        val apk = _state.value.readyApk ?: return
        val installer = UpdateInstaller(context)
        if (!installer.canInstall()) {
            context.startActivity(installer.requestInstallPermissionIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _state.update {
                it.copy(updateMessageIsProblem = true,
                    updateMessage = "Allow EE Lens to install updates on the screen that just opened, then come back and press \"Install now\" again.")
            }
            return
        }
        context.startActivity(installer.installIntent(apk))
    }

    fun consumeUpdateMessage() = _state.update { it.copy(updateMessage = null) }

    companion object {
        private const val TAG = "EeBackup"

        fun factory(
            context: Context,
            repository: CatalogRepository,
            appVersion: String,
            onCatalogueChanged: () -> Unit
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BackupViewModel(context.applicationContext, repository, appVersion, onCatalogueChanged) as T
        }
    }
}
