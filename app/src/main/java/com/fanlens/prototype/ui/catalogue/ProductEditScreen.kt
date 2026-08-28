package com.fanlens.prototype.ui.catalogue

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.fanlens.prototype.data.CatalogRepository
import com.fanlens.prototype.model.CategoryTaxonomy
import com.fanlens.prototype.model.PhotoOrigin
import com.fanlens.prototype.model.PhotoRole
import com.fanlens.prototype.model.SpecRow
import com.fanlens.prototype.ui.FanLensColors
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductEditScreen(
    viewModel: ProductEditViewModel,
    repository: CatalogRepository,
    productId: String?,
    onCancel: () -> Unit,
    onSaved: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sourceSheetOpen by remember { mutableStateOf(false) }
    // Which section the owner tapped "Add" in; beats the guess from the source.
    var addTargetRole by remember { mutableStateOf(PhotoRole.Recognition) }
    // Survives the process being killed while the camera app is in front,
    // which is otherwise where a just-taken photo would be lost.
    var pendingCapturePath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(productId) { viewModel.start(productId) }
    LaunchedEffect(state.savedProductId) {
        state.savedProductId?.let { saved ->
            onSaved(saved)
            viewModel.consumeSaved()
        }
    }

    // Camera scratch files are copies of product photos; don't leave them in cache.
    DisposableEffect(Unit) {
        onDispose { repository.photoStore.clearCaptures() }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(CatalogRepository.RECOMMENDED_MAX_PHOTOS)
    ) { uris ->
        uris.forEach { uri ->
            viewModel.stagePhoto(PhotoOrigin.Gallery, addTargetRole) {
                context.contentResolver.openInputStream(uri)
                    ?: error("That photo could not be opened.")
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            viewModel.stagePhoto(PhotoOrigin.File, addTargetRole) {
                context.contentResolver.openInputStream(uri)
                    ?: error("That file could not be opened.")
            }
        }
    }

    val cameraCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCapturePath?.let(::File)
        pendingCapturePath = null
        if (success && file != null && file.length() > 0) {
            viewModel.stagePhoto(PhotoOrigin.Camera, addTargetRole) { file.inputStream() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(FanLensColors.Paper)
    ) {
        EditHeader(
            title = if (state.isNew) "Add product" else "Edit product",
            saving = state.saving,
            onCancel = onCancel,
            onSave = viewModel::save
        )

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FanLensColors.BrandRed)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp)
        ) {
            item {
                SectionLabel("Shop photos · used for recognition")
                Text(
                    text = state.photoGuidance,
                    style = MaterialTheme.typography.bodySmall,
                    color = FanLensColors.InkMuted
                )
                Spacer(Modifier.height(10.dp))
                PhotoRow(
                    state = state,
                    repository = repository,
                    role = PhotoRole.Recognition,
                    viewModel = viewModel,
                    onAdd = {
                        addTargetRole = PhotoRole.Recognition
                        sourceSheetOpen = true
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Photos of the actual product on your shelf. These are what the camera " +
                        "matches against — they are never shown to customers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FanLensColors.InkMuted
                )
                Spacer(Modifier.height(22.dp))
            }

            item {
                SectionLabel("Catalogue photos · shown in the app")
                Text(
                    text = when (val count = state.countWithRole(PhotoRole.Display)) {
                        0 -> "Optional — clean product images for the gallery"
                        1 -> "1 catalogue photo"
                        else -> "$count catalogue photos"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FanLensColors.InkMuted
                )
                Spacer(Modifier.height(10.dp))
                PhotoRow(
                    state = state,
                    repository = repository,
                    role = PhotoRole.Display,
                    viewModel = viewModel,
                    onAdd = {
                        addTargetRole = PhotoRole.Display
                        sourceSheetOpen = true
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Clean images from a catalogue or the manufacturer. Tap a photo to make " +
                        "it the cover, or tap its badge to move it to the other section. Every " +
                        "photo is copied into EE Lens, so deleting the original will not break it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FanLensColors.InkMuted
                )
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = FanLensColors.Rule)
                Spacer(Modifier.height(16.dp))
            }

            item {
                SectionLabel("Details")
                Text(
                    text = "Pick a category first — it decides which fields show up below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FanLensColors.InkMuted
                )
                Spacer(Modifier.height(6.dp))
                CategoryField(
                    draftId = state.draft.id,
                    category = state.draft.category,
                    usedCategories = state.usedCategories,
                    onChange = { new -> viewModel.edit { it.copy(category = new) } }
                )
                Field(
                    label = "Brand",
                    value = state.draft.brand,
                    error = state.errors.brand.takeIf { state.showErrors },
                    onChange = { new -> viewModel.edit { it.copy(brand = new) } }
                )
                Field(
                    label = "Product name",
                    value = state.draft.name,
                    error = state.errors.name.takeIf { state.showErrors },
                    onChange = { new -> viewModel.edit { it.copy(name = new) } }
                )
                Field(
                    label = "Model",
                    value = state.draft.model,
                    onChange = { new -> viewModel.edit { it.copy(model = new) } }
                )
                Field(
                    label = "Colour",
                    value = state.draft.colour,
                    onChange = { new -> viewModel.edit { it.copy(colour = new) } }
                )
                // Only the field(s) this category actually uses -- a fan shows
                // Size, a mixer shows Wattage, never both indiscriminately.
                if (CategoryTaxonomy.hasSizeField(state.draft.category)) {
                    Field(
                        label = "Size or sweep (mm)",
                        value = state.draft.sizeSweep,
                        error = state.errors.sizeSweep.takeIf { state.showErrors },
                        keyboard = KeyboardType.Number,
                        onChange = { new -> viewModel.edit { it.copy(sizeSweep = new) } }
                    )
                }
                if (CategoryTaxonomy.hasWattageField(state.draft.category)) {
                    Field(
                        label = "Wattage (W)",
                        value = state.draft.wattage,
                        keyboard = KeyboardType.Number,
                        onChange = { new -> viewModel.edit { it.copy(wattage = new) } }
                    )
                }
                Field(
                    label = "MRP (₹)",
                    value = state.draft.mrpText,
                    error = state.errors.mrp.takeIf { state.showErrors },
                    keyboard = KeyboardType.Decimal,
                    onChange = { new -> viewModel.edit { it.copy(mrpText = new) } }
                )
                Field(
                    label = "Selling price (₹)",
                    value = state.draft.priceText,
                    error = state.errors.price.takeIf { state.showErrors },
                    keyboard = KeyboardType.Decimal,
                    onChange = { new -> viewModel.edit { it.copy(priceText = new) } }
                )
                // Never typed, always derived from the two prices above.
                state.draft.discountPercent?.let { percent ->
                    Text(
                        text = "$percent% off",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = FanLensColors.BrandRed,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp)
                    )
                }
                Field(
                    label = "Description",
                    value = state.draft.description,
                    singleLine = false,
                    onChange = { new -> viewModel.edit { it.copy(description = new) } }
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = FanLensColors.Rule)
                Spacer(Modifier.height(16.dp))
            }

            item {
                SectionLabel("Specifications")
                Text(
                    text = "Anything else worth recording — warranty, air delivery, wattage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FanLensColors.InkMuted
                )
                Spacer(Modifier.height(8.dp))
                CategoryTaxonomy.templateFor(state.draft.category)?.let {
                    TextButton(onClick = viewModel::applySuggestedSpecs) {
                        Text(
                            "Add usual ${state.draft.category.trim().lowercase()} fields",
                            color = FanLensColors.BrandRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            itemsIndexed(state.draft.specs) { index, row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = row.key,
                        onValueChange = { viewModel.updateSpecRow(index, row.copy(key = it)) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = row.value,
                        onValueChange = { viewModel.updateSpecRow(index, row.copy(value = it)) },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.removeSpecRow(index) }) {
                        Text("Remove", color = FanLensColors.BrandRed)
                    }
                }
            }

            item {
                TextButton(onClick = viewModel::addSpecRow) {
                    Text("Add a specification", color = FanLensColors.BrandRed, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (sourceSheetOpen) {
        ModalBottomSheet(onDismissRequest = { sourceSheetOpen = false }) {
            PhotoSourceOptions(
                onCamera = {
                    sourceSheetOpen = false
                    val file = repository.photoStore.newCaptureFile()
                    pendingCapturePath = file.absolutePath
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    cameraCapture.launch(uri)
                },
                onGallery = {
                    sourceSheetOpen = false
                    galleryPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onFile = {
                    sourceSheetOpen = false
                    documentPicker.launch(arrayOf("image/*"))
                }
            )
        }
    }

    state.message?.let { message ->
        MessageBar(message = message, onDismiss = viewModel::consumeMessage)
    }
}

@Composable
private fun EditHeader(
    title: String,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FanLensColors.Paper)
            // Without this the Save and Cancel controls sit under the status
            // bar, which swallows the taps meant for them.
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = FanLensColors.InkMuted)
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FanLensColors.Ink
            )
            Button(
                onClick = onSave,
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FanLensColors.BrandRed,
                    contentColor = FanLensColors.Paper
                )
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        }
        HorizontalDivider(color = FanLensColors.Rule)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = FanLensColors.InkMuted
    )
    Spacer(Modifier.height(6.dp))
}

/**
 * The exact same category list as the PC Catalogue Manager's dropdown
 * (CategoryTaxonomy.STANDARD_CATEGORIES), plus anything already used by a
 * product here, so category names never fragment into near-duplicates like
 * "Ceiling fan" vs "ceiling_fan" across the two tools. "Other…" reveals a
 * text box so a new kind of product is never blocked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(
    draftId: String?,
    category: String,
    usedCategories: List<String>,
    onChange: (String) -> Unit
) {
    val options = remember(usedCategories) {
        (CategoryTaxonomy.STANDARD_CATEGORIES + usedCategories)
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }
    val matchesKnown = options.any { it.equals(category, ignoreCase = true) }
    var expanded by remember { mutableStateOf(false) }
    // Sticks once picked, even while the text box below is still blank, so
    // typing a custom category doesn't keep bouncing the box back to the
    // list. Keyed on draftId (this ViewModel survives across edit sessions,
    // per ProductEditViewModel.start()'s own doc comment) so opening a
    // different product resets this instead of carrying over the last one's.
    var otherMode by remember(draftId, options) { mutableStateOf(category.isNotBlank() && !matchesKnown) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = if (otherMode) "Other…" else category,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("— none —") },
                    onClick = {
                        otherMode = false
                        onChange("")
                        expanded = false
                    }
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            otherMode = false
                            onChange(option)
                            expanded = false
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Other…") },
                    onClick = {
                        otherMode = true
                        expanded = false
                    }
                )
            }
        }
        if (otherMode) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = category.takeUnless { matchesKnown }.orEmpty(),
                onValueChange = onChange,
                label = { Text("Type the category") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String? = null,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = singleLine,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun AddPhotoTile(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(96.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = FanLensColors.PaperRaised,
        contentColor = FanLensColors.Ink
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("+", style = MaterialTheme.typography.headlineMedium, color = FanLensColors.BrandRed)
            Text("Add", style = MaterialTheme.typography.labelSmall, color = FanLensColors.InkMuted)
        }
    }
}

/** One horizontal strip of photos for a single role, with its own Add tile. */
@Composable
private fun PhotoRow(
    state: ProductEditViewModel.UiState,
    repository: CatalogRepository,
    role: PhotoRole,
    viewModel: ProductEditViewModel,
    onAdd: () -> Unit
) {
    val moveLabel = if (role == PhotoRole.Recognition) "Move to catalogue" else "Move to shop photos"
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item { AddPhotoTile(onClick = onAdd) }

        items(state.keptWithRole(role), key = { it.id }) { photo ->
            PhotoTile(
                model = repository.thumbFor(photo),
                isCover = state.effectiveCover == photo.id,
                roleBadge = if (role == PhotoRole.Recognition) "SHOP" else "CATALOGUE",
                moveLabel = moveLabel,
                onMakeCover = { viewModel.chooseCover(photo.id) },
                onToggleRole = { viewModel.toggleSavedRole(photo) },
                onRemove = { viewModel.removeExisting(photo.id) }
            )
        }

        items(state.pendingWithRole(role), key = { it.localId }) { pending ->
            PhotoTile(
                model = pending.encoded.thumbBytes,
                isCover = state.effectiveCover == pending.localId,
                roleBadge = if (role == PhotoRole.Recognition) "SHOP" else "CATALOGUE",
                moveLabel = moveLabel,
                onMakeCover = { viewModel.chooseCover(pending.localId) },
                onToggleRole = { viewModel.togglePendingRole(pending.localId) },
                onRemove = { viewModel.removePending(pending.localId) }
            )
        }
    }
}

@Composable
private fun PhotoTile(
    model: Any,
    isCover: Boolean,
    roleBadge: String,
    moveLabel: String,
    onMakeCover: () -> Unit,
    onToggleRole: () -> Unit,
    onRemove: () -> Unit
) {
    Box(Modifier.size(96.dp)) {
        AsyncImage(
            model = model,
            contentDescription = if (isCover) "Cover photo" else "Product photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(FanLensColors.PaperRaised)
                .clickable(onClick = onMakeCover)
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clickable(onClickLabel = moveLabel, onClick = onToggleRole),
            shape = RoundedCornerShape(6.dp),
            color = if (isCover) FanLensColors.BrandRed else FanLensColors.CameraScrim,
            contentColor = if (isCover) FanLensColors.Paper else FanLensColors.CameraInk
        ) {
            Text(
                text = if (isCover) "COVER" else roleBadge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .clickable(onClick = onRemove),
            shape = CircleShape,
            color = FanLensColors.CameraScrim,
            contentColor = FanLensColors.CameraInk
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("×", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PhotoSourceOptions(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        Text(
            "Add photos",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = FanLensColors.Ink
        )
        Spacer(Modifier.height(12.dp))
        SourceRow("Take a photo", "Use the camera now", onCamera)
        SourceRow("Choose from Gallery", "Pick several at once", onGallery)
        SourceRow("Pick a file", "From Downloads or a folder", onFile)
    }
}

@Composable
private fun SourceRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = FanLensColors.Ink)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = FanLensColors.InkMuted)
    }
}

@Composable
private fun MessageBar(message: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(10.dp),
            color = FanLensColors.Ink,
            contentColor = FanLensColors.Paper
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDismiss) {
                    Text("OK", color = FanLensColors.Paper, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
