package com.fanlens.prototype.ui.catalogue

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fanlens.prototype.data.AddPhotoOutcome
import com.fanlens.prototype.data.CatalogRepository
import com.fanlens.prototype.data.PendingPhoto
import com.fanlens.prototype.model.CategoryTaxonomy
import com.fanlens.prototype.model.DraftErrors
import com.fanlens.prototype.model.DraftValidator
import com.fanlens.prototype.model.Photo
import com.fanlens.prototype.model.PhotoOrigin
import com.fanlens.prototype.model.PhotoRole
import com.fanlens.prototype.model.ProductDraft
import com.fanlens.prototype.model.SpecRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * Holds one editing session. Photos are compressed as soon as they are chosen
 * but stay in memory until Save, so abandoning a half-finished product leaves
 * nothing on disk.
 */
class ProductEditViewModel(private val repository: CatalogRepository) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val draft: ProductDraft = ProductDraft(),
        val errors: DraftErrors = DraftErrors(),
        val showErrors: Boolean = false,
        val existingPhotos: List<Photo> = emptyList(),
        val pendingPhotos: List<PendingPhoto> = emptyList(),
        val removedPhotoIds: Set<String> = emptySet(),
        val roleOverrides: Map<String, PhotoRole> = emptyMap(),
        val coverSelection: String? = null,
        /** Categories already used by other products, so an old free-typed one never disappears from the picker. */
        val usedCategories: List<String> = emptyList(),
        val saving: Boolean = false,
        val savedProductId: String? = null,
        val message: String? = null
    ) {
        val keptPhotos: List<Photo> get() = existingPhotos.filterNot { it.id in removedPhotoIds }
        val photoCount: Int get() = keptPhotos.size + pendingPhotos.size
        val isNew: Boolean get() = draft.isNew

        fun roleOf(photo: Photo): PhotoRole = roleOverrides[photo.id] ?: photo.role

        fun keptWithRole(role: PhotoRole): List<Photo> = keptPhotos.filter { roleOf(it) == role }

        fun pendingWithRole(role: PhotoRole): List<PendingPhoto> =
            pendingPhotos.filter { it.role == role }

        fun countWithRole(role: PhotoRole): Int =
            keptWithRole(role).size + pendingWithRole(role).size

        /** The cover that would be applied if the product were saved right now. */
        val effectiveCover: String?
            get() = coverSelection
                ?: keptPhotos.firstOrNull()?.id
                ?: pendingPhotos.firstOrNull()?.localId

        /** Guidance for the recognition set only — display photos have no target. */
        val photoGuidance: String
            get() {
                val count = countWithRole(PhotoRole.Recognition)
                val min = CatalogRepository.RECOMMENDED_MIN_PHOTOS
                val max = CatalogRepository.RECOMMENDED_MAX_PHOTOS
                return when {
                    count == 0 -> "Add $min–$max shop photos so the camera can recognise this product"
                    count < min -> "$count of $min–$max recommended"
                    count <= max -> "$count shop photos"
                    else -> "$count shop photos — more than $max adds little"
                }
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Begins an editing session, discarding anything left over from a previous
     * one. This view model is keyed per target and survives the whole activity,
     * so re-entering "Add product" must not inherit the last save's result.
     */
    fun start(productId: String?) {
        _state.value = UiState(loading = true)
        viewModelScope.launch {
            // Categories already in use never disappear from the picker just
            // because they aren't in the standard list, e.g. something typed
            // before this screen had a dropdown at all.
            val usedCategories = repository.products()
                .mapNotNull { it.category?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()

            if (productId == null) {
                _state.value = UiState(loading = false, draft = ProductDraft(), usedCategories = usedCategories)
                return@launch
            }
            val loaded = repository.productWithPhotos(productId)
            _state.value = if (loaded == null) {
                UiState(loading = false, message = "That product is no longer here.")
            } else {
                UiState(
                    loading = false,
                    draft = ProductDraft.from(loaded.product),
                    existingPhotos = loaded.photos,
                    coverSelection = loaded.product.coverPhotoId,
                    usedCategories = usedCategories
                )
            }
        }
    }

    fun edit(change: (ProductDraft) -> ProductDraft) {
        _state.update { current ->
            val draft = change(current.draft)
            current.copy(draft = draft, errors = DraftValidator.validate(draft))
        }
    }

    fun addSpecRow() = edit { it.copy(specs = it.specs + SpecRow()) }

    fun updateSpecRow(index: Int, row: SpecRow) = edit { draft ->
        draft.copy(specs = draft.specs.toMutableList().also { it[index] = row })
    }

    fun removeSpecRow(index: Int) = edit { draft ->
        draft.copy(specs = draft.specs.toMutableList().also { it.removeAt(index) })
    }

    /**
     * Adds this category's usual fields as blank spec rows, mirroring
     * pc-catalogue-manager/app.js's "Add usual … fields" button. Never
     * touches a key the owner already has a row for, and skips Sweep or
     * Wattage when this category already gets its own dedicated field for
     * that -- one field for it beats two.
     */
    fun applySuggestedSpecs() = edit { draft ->
        val template = CategoryTaxonomy.templateFor(draft.category) ?: return@edit draft
        val dedicated = buildSet {
            if (CategoryTaxonomy.hasSizeField(draft.category)) add("sweep")
            if (CategoryTaxonomy.hasWattageField(draft.category)) add("wattage")
        }
        val existingKeys = draft.specs.map { it.key.trim().lowercase() }.toSet()
        val additions = template
            .filter { key -> key.trim().lowercase().let { it !in existingKeys && it !in dedicated } }
            .map { SpecRow(key = it) }
        if (additions.isEmpty()) draft else draft.copy(specs = draft.specs + additions)
    }

    /**
     * @param roleOverride set when the owner adds into a specific section, which
     *   beats the guess made from where the photo came from.
     */
    fun stagePhoto(origin: PhotoOrigin, roleOverride: PhotoRole? = null, open: () -> InputStream) {
        viewModelScope.launch {
            val current = _state.value
            when (val outcome = repository.stagePhoto(
                productId = current.draft.id,
                origin = origin,
                alreadyStaged = current.pendingPhotos,
                open = open
            )) {
                is AddPhotoOutcome.Added -> _state.update {
                    val staged = roleOverride
                        ?.let { role -> outcome.photo.copy(role = role) }
                        ?: outcome.photo
                    it.copy(pendingPhotos = it.pendingPhotos + staged)
                }

                AddPhotoOutcome.Duplicate -> _state.update {
                    it.copy(message = "You already added this photo.")
                }

                is AddPhotoOutcome.Unreadable -> _state.update {
                    it.copy(message = outcome.message)
                }
            }
        }
    }

    fun removePending(localId: String) = _state.update { current ->
        current.copy(
            pendingPhotos = current.pendingPhotos.filterNot { it.localId == localId },
            coverSelection = current.coverSelection?.takeUnless { it == localId }
        )
    }

    fun removeExisting(photoId: String) = _state.update { current ->
        current.copy(
            removedPhotoIds = current.removedPhotoIds + photoId,
            coverSelection = current.coverSelection?.takeUnless { it == photoId }
        )
    }

    fun chooseCover(id: String) = _state.update { it.copy(coverSelection = id) }

    /** Flips a staged photo between the recognition set and the display set. */
    fun togglePendingRole(localId: String) = _state.update { current ->
        current.copy(
            pendingPhotos = current.pendingPhotos.map { pending ->
                if (pending.localId == localId) {
                    pending.copy(
                        role = if (pending.role == PhotoRole.Recognition) PhotoRole.Display
                        else PhotoRole.Recognition
                    )
                } else {
                    pending
                }
            }
        )
    }

    /** Same for a saved photo; applied when the product is saved. */
    fun toggleSavedRole(photo: Photo) = _state.update { current ->
        val next = if (current.roleOf(photo) == PhotoRole.Recognition) PhotoRole.Display
        else PhotoRole.Recognition
        current.copy(roleOverrides = current.roleOverrides + (photo.id to next))
    }

    fun save() {
        val current = _state.value
        val errors = DraftValidator.validate(current.draft)
        Log.i(
            TAG,
            "save() brand='${current.draft.brand}' name='${current.draft.name}' " +
                "valid=${errors.isValid} saving=${current.saving}"
        )
        if (!errors.isValid) {
            _state.update { it.copy(errors = errors, showErrors = true) }
            return
        }
        if (current.saving) return

        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                repository.saveProduct(
                    draft = current.draft,
                    newPhotos = current.pendingPhotos,
                    removedPhotoIds = current.removedPhotoIds,
                    coverSelection = current.effectiveCover,
                    roleChanges = current.roleOverrides
                )
            }.onSuccess { productId ->
                Log.i(TAG, "saved product $productId")
                _state.update { it.copy(saving = false, savedProductId = productId) }
            }.onFailure { error ->
                Log.e(TAG, "save failed", error)
                _state.update {
                    it.copy(
                        saving = false,
                        message = error.message ?: "This product could not be saved."
                    )
                }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Clears the save result once the caller has navigated, so it cannot fire twice. */
    fun consumeSaved() = _state.update { it.copy(savedProductId = null) }

    companion object {
        private const val TAG = "EeProductEdit"

        fun factory(repository: CatalogRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ProductEditViewModel(repository) as T
        }
    }
}
