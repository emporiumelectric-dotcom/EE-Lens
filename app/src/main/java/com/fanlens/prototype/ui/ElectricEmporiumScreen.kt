/* Hallmark · component: camera recognition surface · genre: modern-minimal
 * pre-emit critique: P5 H5 E5 S5 R5 V5 · contrast: pass · touch targets: 48dp
 */
package com.fanlens.prototype.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import com.fanlens.prototype.BuildConfig
import com.fanlens.prototype.R
import com.fanlens.prototype.data.CatalogRepository
import com.fanlens.prototype.model.CategoryTaxonomy
import com.fanlens.prototype.model.MatchSource
import com.fanlens.prototype.model.Product
import com.fanlens.prototype.model.ProductDetection
import com.fanlens.prototype.model.RecognitionResult
import com.fanlens.prototype.recognition.ProductRecognitionEngine
import com.fanlens.prototype.recognition.RecognitionPreprocessing
import com.fanlens.prototype.supabase.cloudRefreshStatusMessage
import com.fanlens.prototype.ui.backup.BackupScreen
import com.fanlens.prototype.ui.backup.BackupViewModel
import com.fanlens.prototype.ui.catalogue.ProductDetailScreen
import com.fanlens.prototype.ui.catalogue.ProductEditScreen
import com.fanlens.prototype.ui.catalogue.ProductEditViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private enum class EeSection { Scan, Products, Backup }

private fun productCountLabel(count: Int): String = when (count) {
    0 -> "No products saved on this device yet"
    1 -> "1 product saved on this device"
    else -> "$count products saved on this device"
}

/**
 * Only the products matching [category] (case-insensitive), or every one of
 * [products] when [category] is blank ("All categories"). Pure and
 * dependency-free so this is unit-testable on its own; see
 * ElectricEmporiumScreenTest. Mirrors pc-catalogue-manager/app.js's matches().
 */
internal fun filterProductsByCategory(products: List<Product>, category: String): List<Product> {
    val wanted = category.trim()
    if (wanted.isBlank()) return products
    return products.filter { it.category?.trim()?.equals(wanted, ignoreCase = true) == true }
}

/**
 * "N products" with nothing filtered; "X of Y products" once a category
 * filter has actually narrowed the list. Mirrors pc-catalogue-manager's
 * #stats indicator (see refreshStats in app.js).
 */
internal fun productListCountLabel(shown: Int, total: Int): String =
    if (shown == total) "$total ${if (total == 1) "product" else "products"}"
    else "$shown of $total products"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectricEmporiumScreen(
    cameraAllowed: Boolean,
    recognitionEngine: ProductRecognitionEngine,
    repository: CatalogRepository,
    products: List<Product>,
    onRequestCamera: () -> Unit
) {
    var detection by remember { mutableStateOf<ProductDetection?>(null) }
    var selected by remember { mutableStateOf<Product?>(null) }
    var section by remember { mutableStateOf(EeSection.Scan) }
    var status by remember { mutableStateOf("Preparing local products…") }
    var zoomRatio by remember { mutableStateOf(1f) }
    var route by remember { mutableStateOf<EeRoute>(EeRoute.Tabs) }
    // A single delete (detail screen) and a bulk delete (Products list) both land
    // here -- one product or several, the undo bar and purge timer below don't care.
    var recentlyDeleted by remember { mutableStateOf<List<Product>>(emptyList()) }
    var refreshingCatalogue by remember { mutableStateOf(false) }
    // Set only when a pull-to-refresh has something worth telling the owner
    // (couldn't reach the cloud, or reached it but a row failed) -- see
    // cloudRefreshStatusMessage. A clean pull never touches this, so the
    // ordinary product count keeps showing.
    var cloudRefreshMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val preparation by recognitionEngine.preparation.collectAsState()
    val recognitionReady = preparation.ready

    LaunchedEffect(recognitionEngine) {
        runCatching {
            withContext(Dispatchers.Default) { recognitionEngine.prepare() }
        }
    }

    // Startup progress owns the status line until recognition is live; after that
    // each analysed frame does.
    LaunchedEffect(preparation) {
        status = preparation.message
    }

    // A cloud-pull status message clears itself -- there is no dismiss action
    // for it, so it would otherwise sit in the header until the next refresh.
    LaunchedEffect(cloudRefreshMessage) {
        if (cloudRefreshMessage != null) {
            delay(CLOUD_REFRESH_MESSAGE_MS)
            cloudRefreshMessage = null
        }
    }

    // Deleted products are only removed from disk once the undo window closes.
    LaunchedEffect(recentlyDeleted) {
        val targets = recentlyDeleted
        if (targets.isEmpty()) return@LaunchedEffect
        delay(UNDO_WINDOW_MS)
        if (recentlyDeleted == targets) {
            repository.purgeDeletedBefore(System.currentTimeMillis())
            recentlyDeleted = emptyList()
        }
    }

    val refreshRecognition: () -> Unit = {
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { recognitionEngine.refresh() } }
        }
    }

    when (val current = route) {
        is EeRoute.Detail -> {
            BackHandler { route = EeRoute.Tabs }
            ProductDetailScreen(
                repository = repository,
                productId = current.productId,
                onBack = { route = EeRoute.Tabs },
                onEdit = { route = EeRoute.Edit(it) },
                onDelete = { productId ->
                    scope.launch {
                        recentlyDeleted = listOfNotNull(products.firstOrNull { it.id == productId })
                        repository.softDeleteProduct(productId)
                        route = EeRoute.Tabs
                        refreshRecognition()
                    }
                }
            )
            return
        }

        is EeRoute.Edit -> {
            BackHandler { route = EeRoute.Tabs }
            val editViewModel: ProductEditViewModel = viewModel(
                key = "edit-${current.productId ?: "new"}",
                factory = ProductEditViewModel.factory(repository)
            )
            ProductEditScreen(
                viewModel = editViewModel,
                repository = repository,
                productId = current.productId,
                onCancel = { route = EeRoute.Tabs },
                onSaved = { productId ->
                    route = EeRoute.Detail(productId)
                    refreshRecognition()
                }
            )
            return
        }

        EeRoute.Tabs -> Unit
    }

    BackHandler(enabled = selected != null || section != EeSection.Scan) {
        if (selected != null) selected = null else section = EeSection.Scan
    }

    Box(Modifier.fillMaxSize().background(FanLensColors.Paper)) {
        Column(Modifier.fillMaxSize()) {
            EeBrandHeader(
                cameraAllowed = cameraAllowed,
                status = if (section == EeSection.Products) {
                    cloudRefreshMessage ?: productCountLabel(products.size)
                } else if (section == EeSection.Backup) {
                    "Keep a catalogue file somewhere safe"
                } else if (cameraAllowed) {
                    status
                } else {
                    "Camera access needed"
                }
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    EeSection.Scan -> {
                        if (cameraAllowed) {
                            EeCameraPreview(
                                recognitionEnabled = recognitionReady,
                                recognitionEngine = recognitionEngine,
                                zoomRatio = zoomRatio,
                                onRecognition = { result ->
                                    detection = result.detection
                                    status = result.status
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            if (selected == null) {
                                if (detection == null) EeTargetGuide()

                                // Result card and zoom sit in one bottom-anchored
                                // column, so the zoom row stays under the thumb and
                                // can never cover the product card.
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                        .padding(bottom = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (detection != null) {
                                        EeDetectionCard(
                                            detection = detection!!,
                                            onSelect = { selected = it.product }
                                        )
                                    } else {
                                        EeScanHint()
                                    }

                                    EeZoomControls(
                                        selectedZoom = zoomRatio,
                                        onZoomSelected = { zoomRatio = it }
                                    )
                                }
                            }
                        } else {
                            EePermissionPanel(onRequestCamera)
                        }
                    }

                    EeSection.Products -> EeProductCatalogue(
                        products = products,
                        repository = repository,
                        refreshing = refreshingCatalogue,
                        onRefresh = {
                            refreshingCatalogue = true
                            cloudRefreshMessage = null
                            scope.launch {
                                // Same pull logic "Pull from cloud" used to trigger, just started
                                // by the gesture instead of a button. A failure (no signal, the
                                // cloud unreachable, a row that wouldn't sync) no longer vanishes
                                // silently: it's logged, and cloudRefreshStatusMessage decides
                                // whether it's worth telling the owner about in the header --
                                // whatever is already on this phone is unaffected either way.
                                val pullResult = runCatching {
                                    withContext(Dispatchers.IO) { repository.cloudPullAll() }
                                }
                                pullResult.exceptionOrNull()?.let {
                                    Log.e(TAG, "Cloud pull-to-refresh failed", it)
                                }
                                cloudRefreshMessage = cloudRefreshStatusMessage(pullResult)
                                runCatching {
                                    withContext(Dispatchers.Default) { recognitionEngine.refresh() }
                                }
                                refreshingCatalogue = false
                            }
                        },
                        onSelect = { route = EeRoute.Detail(it.id) },
                        onAdd = { route = EeRoute.Edit(null) },
                        onDeleteSelected = { ids ->
                            scope.launch {
                                recentlyDeleted = products.filter { it.id in ids }
                                ids.forEach { repository.softDeleteProduct(it) }
                                refreshRecognition()
                            }
                        }
                    )

                    EeSection.Backup -> {
                        val backupViewModel: BackupViewModel = viewModel(
                            key = "backup",
                            factory = BackupViewModel.factory(
                                context = LocalContext.current,
                                repository = repository,
                                appVersion = BuildConfig.VERSION_NAME,
                                onCatalogueChanged = refreshRecognition
                            )
                        )
                        BackupScreen(backupViewModel)
                    }
                }
            }

            EeBottomTabs(
                selected = section,
                onSelect = { section = it }
            )
        }

        selected?.let { product ->
            ModalBottomSheet(
                onDismissRequest = { selected = null },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                EeProductDetails(product)
            }
        }

        recentlyDeleted.takeIf { it.isNotEmpty() }?.let { deleted ->
            EeUndoBar(
                message = if (deleted.size == 1) {
                    "${deleted.first().name} deleted"
                } else {
                    "${deleted.size} products deleted"
                },
                onUndo = {
                    scope.launch {
                        deleted.forEach { repository.restoreProduct(it.id) }
                        recentlyDeleted = emptyList()
                        refreshRecognition()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)
            )
        }
    }
}

/** Which catalogue screen is in front. The Scan experience is never replaced. */
private sealed interface EeRoute {
    data object Tabs : EeRoute
    data class Detail(val productId: String) : EeRoute
    data class Edit(val productId: String?) : EeRoute
}

private const val UNDO_WINDOW_MS = 10_000L
private const val CLOUD_REFRESH_MESSAGE_MS = 6_000L
private const val TAG = "EeElectricEmporium"

@Composable
private fun EeUndoBar(message: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(10.dp),
        color = FanLensColors.Ink,
        contentColor = FanLensColors.Paper,
        shadowElevation = 6.dp
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onUndo) {
                Text("UNDO", color = FanLensColors.BrandRed, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EeCameraPreview(
    recognitionEnabled: Boolean,
    recognitionEngine: ProductRecognitionEngine,
    zoomRatio: Float,
    onRecognition: (RecognitionResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val recognitionEnabledState = rememberUpdatedState(recognitionEnabled)
    val recognitionCallbackState = rememberUpdatedState(onRecognition)
    var boundCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    LaunchedEffect(boundCamera, zoomRatio) {
        boundCamera?.let { camera ->
            val zoomState = camera.cameraInfo.zoomState.value
            val supportedZoom = zoomRatio.coerceIn(
                zoomState?.minZoomRatio ?: 1f,
                zoomState?.maxZoomRatio ?: zoomRatio
            )
            camera.cameraControl.setZoomRatio(supportedZoom)
        }
    }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewContext ->
            PreviewView(previewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // A SurfaceView does not clip to its Compose ancestors, so an
                // over-scaled preview paints over the brand header above it.
                // TextureView clips correctly.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                val cameraProviderFuture = ProcessCameraProvider.getInstance(previewContext)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { useCase ->
                            useCase.setAnalyzer(
                                analysisExecutor,
                                EeFanFrameAnalyzer(
                                    enabled = { recognitionEnabledState.value },
                                    recognitionEngine = recognitionEngine,
                                    onRecognition = { result ->
                                        mainExecutor.execute {
                                            recognitionCallbackState.value(result)
                                        }
                                    }
                                )
                            )
                        }
                    runCatching {
                        provider.unbindAll()
                        boundCamera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        }
    )
}

private class EeFanFrameAnalyzer(
    private val enabled: () -> Boolean,
    private val recognitionEngine: ProductRecognitionEngine,
    private val onRecognition: (RecognitionResult) -> Unit
) : ImageAnalysis.Analyzer {
    private var lastAnalyzedAt = 0L
    private var candidateId: String? = null
    private var candidateFrames = 0
    private var emptyFrames = 0

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled()) return

            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalyzedAt < ANALYSIS_INTERVAL_MS) return
            lastAnalyzedAt = now

            val bitmap = image.toBitmap().rotate(image.imageInfo.rotationDegrees)
            val cropped = bitmap.centerSquare()
            val result = recognitionEngine.recognize(cropped)
            cropped.recycle()
            if (bitmap !== cropped) bitmap.recycle()

            val detectedId = result.detection?.product?.id
            if (detectedId == null) {
                candidateId = null
                candidateFrames = 0
                emptyFrames++
                if (emptyFrames >= REQUIRED_EMPTY_FRAMES) onRecognition(result)
                return
            }

            emptyFrames = 0
            if (candidateId == detectedId) {
                candidateFrames++
            } else {
                candidateId = detectedId
                candidateFrames = 1
            }
            if (candidateFrames >= REQUIRED_MATCH_FRAMES) onRecognition(result)
        } catch (_: Throwable) {
            onRecognition(RecognitionResult(null, "Aim at one product"))
        } finally {
            image.close()
        }
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val rotated = Bitmap.createBitmap(
            this,
            0,
            0,
            width,
            height,
            Matrix().apply { postRotate(degrees.toFloat()) },
            true
        )
        recycle()
        return rotated
    }

    // Shared with reference fingerprinting so both sides are prepared identically.
    private fun Bitmap.centerSquare(): Bitmap = RecognitionPreprocessing.centreSquare(this)

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 650L
        const val REQUIRED_MATCH_FRAMES = 2
        const val REQUIRED_EMPTY_FRAMES = 2
    }
}

@Composable
private fun EeTargetGuide() {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.align(Alignment.Center).size(56.dp)) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = FanLensColors.TrackingBase,
                radius = size.minDimension * .24f,
                center = centre,
                style = Stroke(width = 2.dp.toPx())
            )
            drawLine(
                color = FanLensColors.TrackingBase,
                start = Offset(centre.x - 26.dp.toPx(), centre.y),
                end = Offset(centre.x - 15.dp.toPx(), centre.y),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = FanLensColors.TrackingBase,
                start = Offset(centre.x + 15.dp.toPx(), centre.y),
                end = Offset(centre.x + 26.dp.toPx(), centre.y),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

/** Shown in the bottom stack while nothing is matched, where the result card will appear. */
@Composable
private fun EeScanHint() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = FanLensColors.CameraScrim,
        contentColor = FanLensColors.CameraInk
    ) {
        Text(
            text = "Point at one product",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EeDetectionCard(
    detection: ProductDetection,
    onSelect: (ProductDetection) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(detection) },
        shape = RoundedCornerShape(12.dp),
        color = FanLensColors.Paper,
        contentColor = FanLensColors.Ink,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = detection.product.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (detection.source == MatchSource.Catalogue) {
                        "From a catalogue image · ${detection.product.model}"
                    } else {
                        detection.product.model
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = FanLensColors.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${(detection.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = FanLensColors.BrandRed,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EeZoomControls(
    selectedZoom: Float,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = FanLensColors.CameraScrim,
        contentColor = FanLensColors.CameraInk,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf(1f, 2f, 3f).forEach { zoom ->
                val active = selectedZoom == zoom
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { onZoomSelected(zoom) },
                    shape = CircleShape,
                    color = if (active) FanLensColors.BrandRed else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = FanLensColors.CameraInk
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${zoom.toInt()}×",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EeBrandHeader(cameraAllowed: Boolean, status: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(FanLensColors.Paper)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.electric_emporium_wordmark),
                contentDescription = "Electric Emporium",
                modifier = Modifier.width(176.dp).height(24.dp)
            )
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = FanLensColors.PaperRaised,
                contentColor = FanLensColors.Ink
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (cameraAllowed) FanLensColors.BrandRed
                                else MaterialTheme.colorScheme.error
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "ON-DEVICE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = FanLensColors.InkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EeBottomTabs(selected: EeSection, onSelect: (EeSection) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(FanLensColors.Paper)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = FanLensColors.Rule)
        Row(Modifier.fillMaxWidth()) {
            EeAppTab(
                label = "Scan",
                active = selected == EeSection.Scan,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(EeSection.Scan) }
            )
            EeAppTab(
                label = "Products",
                active = selected == EeSection.Products,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(EeSection.Products) }
            )
            EeAppTab(
                label = "Backup",
                active = selected == EeSection.Backup,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(EeSection.Backup) }
            )
        }
    }
}

@Composable
private fun EeAppTab(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(62.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            color = if (active) FanLensColors.BrandRed else FanLensColors.InkMuted,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .width(if (active) 28.dp else 0.dp)
                .height(2.dp)
                .background(FanLensColors.BrandRed, RoundedCornerShape(1.dp))
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EeProductCatalogue(
    products: List<Product>,
    repository: CatalogRepository,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSelect: (Product) -> Unit,
    onAdd: () -> Unit,
    onDeleteSelected: (List<String>) -> Unit
) {
    // Long-press (or the "Select" button) enters selection mode; a plain tap then
    // toggles a row instead of opening it. Lives entirely here -- the parent only
    // ever hears about it once, as the finished list of ids to delete.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Which shelf to show, "" meaning every product -- purely a view over
    // [products], never persisted; clearing it (or reopening the tab) always
    // goes back to seeing everything.
    var categoryFilter by remember { mutableStateOf("") }
    // Same option-building rule as ProductEditScreen's CategoryField: the
    // standard list plus whatever custom categories are actually in use, so
    // a product filed under something outside CategoryTaxonomy can still be
    // filtered to, not just picked at edit time.
    val categoryOptions = remember(products) {
        (CategoryTaxonomy.STANDARD_CATEGORIES + products.mapNotNull { it.category?.trim()?.takeIf(String::isNotEmpty) })
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }
    val filteredProducts = remember(products, categoryFilter) { filterProductsByCategory(products, categoryFilter) }

    // The list this is checked against can change out from under it (a pull-to-
    // refresh, a cloud pull on the Backup tab) -- drop anything no longer here.
    // Checked against every product, not just the filtered view: changing the
    // category filter must never silently drop a selection made before it.
    LaunchedEffect(products) {
        val liveIds = products.mapTo(mutableSetOf()) { it.id }
        val pruned = selectedIds.filterTo(mutableSetOf()) { it in liveIds }
        if (pruned != selectedIds) selectedIds = pruned
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggle(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    BackHandler(enabled = selectionMode) { exitSelection() }

    // The Add button is docked below the list rather than floating over it: a
    // floating button covers rows whenever the list is short or mid-scroll, and
    // no amount of bottom padding prevents that.
    Column(Modifier.fillMaxSize().background(FanLensColors.Paper)) {
      if (selectionMode) {
          EeSelectionBar(
              count = selectedIds.size,
              onCancel = ::exitSelection,
              onDeleteSelected = {
                  onDeleteSelected(selectedIds.toList())
                  exitSelection()
              }
          )
          HorizontalDivider(color = FanLensColors.Rule)
      }
      PullToRefreshBox(
          isRefreshing = refreshing,
          onRefresh = onRefresh,
          modifier = Modifier.weight(1f).fillMaxWidth()
      ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 24.dp,
                bottom = 24.dp
            )
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Products",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = FanLensColors.Ink
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (products.isEmpty()) {
                                "No products yet. Tap Add product to create the first one."
                            } else if (categoryFilter.isNotBlank() && filteredProducts.isEmpty()) {
                                "No products in “$categoryFilter” yet."
                            } else if (selectionMode) {
                                "Tap products to select them, or press and hold to start."
                            } else {
                                "Tap a product to view its details. Press and hold to select several."
                            },
                            color = FanLensColors.InkMuted
                        )
                        if (products.isNotEmpty() && !selectionMode) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = productListCountLabel(filteredProducts.size, products.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = FanLensColors.InkMuted
                            )
                        }
                    }
                    if (!selectionMode && products.isNotEmpty()) {
                        TextButton(onClick = { selectionMode = true }) {
                            Text("Select", fontWeight = FontWeight.Bold, color = FanLensColors.BrandRed)
                        }
                    }
                }
                if (products.isNotEmpty() && !selectionMode) {
                    Spacer(Modifier.height(12.dp))
                    EeCategoryFilterField(
                        options = categoryOptions,
                        selected = categoryFilter,
                        onSelect = { categoryFilter = it }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            items(filteredProducts, key = { it.id }) { product ->
                EeProductRow(
                    product = product,
                    repository = repository,
                    selectionMode = selectionMode,
                    selected = product.id in selectedIds,
                    onSelect = onSelect,
                    onToggleSelected = { toggle(product.id) },
                    onLongPress = {
                        if (!selectionMode) selectionMode = true
                        toggle(product.id)
                    }
                )
                HorizontalDivider(color = FanLensColors.Rule)
            }
        }
      }

      if (!selectionMode) {
        HorizontalDivider(color = FanLensColors.Rule)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FanLensColors.BrandRed,
                    contentColor = FanLensColors.Paper
                )
            ) {
                Text("Add product", fontWeight = FontWeight.Bold)
            }
        }
      }
    }
}

/**
 * Filters the Products list to one shelf at a time. "All categories" plus
 * whatever CategoryTaxonomy.STANDARD_CATEGORIES and the catalogue's own
 * products actually use, same option-building rule as ProductEditScreen's
 * CategoryField -- but read-only, no "Other…"/free-text mode, since this
 * only ever narrows an existing list, never assigns a category to anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EeCategoryFilterField(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { "All categories" },
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
                text = { Text("All categories") },
                onClick = {
                    onSelect("")
                    expanded = false
                }
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Contextual bar shown in place of the usual list header while selecting. */
@Composable
private fun EeSelectionBar(count: Int, onCancel: () -> Unit, onDeleteSelected: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FanLensColors.PaperRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCancel) {
            Text("Cancel", color = FanLensColors.Ink)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onDeleteSelected,
            enabled = count > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = Color.White
            )
        ) {
            Text("Delete selected ($count)", fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EeProductRow(
    product: Product,
    repository: CatalogRepository,
    selectionMode: Boolean,
    selected: Boolean,
    onSelect: (Product) -> Unit,
    onToggleSelected: () -> Unit,
    onLongPress: () -> Unit
) {
    val thumbnail by produceState<java.io.File?>(initialValue = null, product.id, product.updatedAt) {
        value = repository.coverThumb(product.id)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) FanLensColors.BrandRed.copy(alpha = 0.08f) else Color.Transparent)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelected() else onSelect(product) },
                onLongClick = onLongPress
            )
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelected() },
                colors = CheckboxDefaults.colors(checkedColor = FanLensColors.BrandRed)
            )
            Spacer(Modifier.width(6.dp))
        }
        val thumb = thumbnail
        if (thumb != null) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(FanLensColors.PaperRaised)
            )
        } else {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(FanLensColors.PaperRaised)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = product.name,
                fontWeight = FontWeight.Bold,
                color = FanLensColors.Ink
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = product.model,
                style = MaterialTheme.typography.bodySmall,
                color = FanLensColors.InkMuted
            )
        }
        if (!selectionMode) {
            Text(
                text = "View",
                style = MaterialTheme.typography.labelMedium,
                color = FanLensColors.BrandRed,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EePermissionPanel(onRequestCamera: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Image(
            painter = painterResource(R.drawable.electric_emporium_mark),
            contentDescription = null,
            modifier = Modifier.width(72.dp).height(48.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Allow the camera",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The camera is used to recognise products in your display.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRequestCamera,
            colors = ButtonDefaults.buttonColors(
                containerColor = FanLensColors.BrandRed,
                contentColor = FanLensColors.Paper
            )
        ) { Text("Allow camera") }
    }
}

@Composable
private fun EeProductDetails(product: Product) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.electric_emporium_mark),
            contentDescription = null,
            modifier = Modifier.width(56.dp).height(38.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = product.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = FanLensColors.Ink
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = product.model,
            color = FanLensColors.BrandRed,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = FanLensColors.Rule)
        Spacer(Modifier.height(16.dp))
        Text(
            text = product.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        product.priceLabel?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, fontWeight = FontWeight.Bold, color = FanLensColors.Ink)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Tap outside or press Back to close.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
