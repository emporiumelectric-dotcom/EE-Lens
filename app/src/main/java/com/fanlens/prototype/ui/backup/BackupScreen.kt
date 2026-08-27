package com.fanlens.prototype.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fanlens.prototype.eelens.EelensFormat
import com.fanlens.prototype.eelens.ImportConflictPolicy
import com.fanlens.prototype.ui.FanLensColors

/**
 * Backup and restore.
 *
 * Uninstalling the app removes its private storage, so this screen is the only
 * thing standing between the shop and a lost catalogue. It is deliberately
 * plain: two buttons, and a preview of exactly what an import will change
 * before anything is touched.
 */
@Composable
fun BackupScreen(viewModel: BackupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EelensFormat.MIME)
    ) { uri -> uri?.let(viewModel::exportTo) }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::stageImport) }

    Column(
        Modifier
            .fillMaxSize()
            .background(FanLensColors.Paper)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = "Backup",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = FanLensColors.Ink
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.summaryLine,
            color = FanLensColors.InkMuted
        )

        Spacer(Modifier.height(24.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = FanLensColors.PaperRaised,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Uninstalling EE Lens deletes everything on this device.",
                    fontWeight = FontWeight.Bold,
                    color = FanLensColors.Ink
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Export a catalogue file regularly and keep it somewhere safe. " +
                        "It is the only way back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FanLensColors.InkMuted
                )
                state.lastExportLabel?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = FanLensColors.BrandRed)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { createFile.launch(EelensFormat.suggestFileName()) },
            enabled = !state.busy && state.productCount > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = FanLensColors.BrandRed,
                contentColor = FanLensColors.Paper
            )
        ) { Text("Export catalogue", fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { openFile.launch(arrayOf("*/*")) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restore from a catalogue file") }

        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = FanLensColors.Rule)
        Spacer(Modifier.height(20.dp))

        Text(
            "Sync with the PC over Wi-Fi",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = FanLensColors.Ink
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Open the Catalogue Manager on the PC and press Phone sync. Type what it shows " +
                "below — once only, it is remembered. Both must be on the same Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = FanLensColors.InkMuted
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.syncAddress,
            onValueChange = viewModel::setSyncAddress,
            label = { Text("PC address") },
            placeholder = { Text("192.168.1.20:8730") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.syncCode,
            onValueChange = viewModel::setSyncCode,
            label = { Text("Pairing code") },
            placeholder = { Text("6 digits") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Button(
                onClick = viewModel::getFromPc,
                enabled = !state.busy && state.canSync,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FanLensColors.BrandRed,
                    contentColor = FanLensColors.Paper
                )
            ) { Text("Get from PC", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = viewModel::sendToPc,
                enabled = !state.busy && state.canSync && state.productCount > 0,
                modifier = Modifier.weight(1f)
            ) { Text("Send to PC") }
        }

        if (state.busy) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.width(18.dp).height(18.dp),
                    color = FanLensColors.BrandRed,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(12.dp))
                Text(state.busyMessage, color = FanLensColors.InkMuted)
            }
        }

        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = FanLensColors.Rule)
        Spacer(Modifier.height(20.dp))

        Text(
            "App updates",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = FanLensColors.Ink
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "You have version ${state.currentVersion}.",
            style = MaterialTheme.typography.bodySmall,
            color = FanLensColors.InkMuted
        )
        Spacer(Modifier.height(12.dp))

        val updateAvailable = state.updateAvailable
        when {
            state.readyApk != null -> Button(
                onClick = viewModel::installUpdate,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FanLensColors.BrandRed,
                    contentColor = FanLensColors.Paper
                )
            ) { Text("Install now", fontWeight = FontWeight.Bold) }

            state.downloadingUpdate -> Column {
                LinearProgressIndicator(
                    progress = { state.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = FanLensColors.BrandRed
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Downloading version ${updateAvailable?.versionName}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = FanLensColors.InkMuted
                )
            }

            updateAvailable != null -> Column {
                Text(
                    "Version ${updateAvailable.versionName} is available.",
                    fontWeight = FontWeight.Bold,
                    color = FanLensColors.Ink
                )
                if (updateAvailable.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        updateAvailable.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = FanLensColors.InkMuted
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = viewModel::downloadUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FanLensColors.BrandRed,
                        contentColor = FanLensColors.Paper
                    )
                ) { Text("Download update", fontWeight = FontWeight.Bold) }
            }

            else -> OutlinedButton(
                onClick = viewModel::checkForUpdate,
                enabled = !state.checkingUpdate,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.checkingUpdate) "Checking…" else "Check for update") }
        }

        state.updateMessage?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.updateMessageIsProblem) MaterialTheme.colorScheme.error else FanLensColors.InkMuted
            )
        }

        state.message?.let { message ->
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = FanLensColors.Rule)
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                color = if (state.messageIsProblem) MaterialTheme.colorScheme.error else FanLensColors.Ink
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = viewModel::consumeMessage) { Text("OK") }
        }
    }

    // Nothing is written until this is answered.
    state.pending?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Restore this catalogue?") },
            text = {
                Column {
                    Text("${preview.products} products and ${preview.photos} photos, made by ${preview.createdBy}.")
                    if (preview.alreadyHere > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("${preview.newProducts} are new. ${preview.alreadyHere} already exist here.")
                    }
                    if (preview.hasProblems) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            buildString {
                                if (preview.missingPhotos.isNotEmpty()) {
                                    append("${preview.missingPhotos.size} photos are missing from the file. ")
                                }
                                if (preview.corruptPhotos.isNotEmpty()) {
                                    append("${preview.corruptPhotos.size} photos failed their checksum and will be skipped.")
                                }
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.commit(ImportConflictPolicy.KeepMine) }) {
                    Text(if (preview.alreadyHere > 0) "Add new only" else "Restore")
                }
            },
            dismissButton = {
                if (preview.alreadyHere > 0) {
                    TextButton(onClick = { viewModel.commit(ImportConflictPolicy.ReplaceWithImported) }) {
                        Text("Replace existing")
                    }
                } else {
                    TextButton(onClick = viewModel::cancelImport) { Text("Cancel") }
                }
            }
        )
    }
}
