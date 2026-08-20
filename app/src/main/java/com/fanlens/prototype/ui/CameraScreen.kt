package com.fanlens.prototype.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fanlens.prototype.model.Product
import com.fanlens.prototype.model.ProductDetection
import com.fanlens.prototype.model.RecognitionResult
import com.fanlens.prototype.recognition.ProductRecognitionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    cameraAllowed: Boolean,
    recognitionEngine: ProductRecognitionEngine,
    onRequestCamera: () -> Unit
) {
    var detection by remember { mutableStateOf<ProductDetection?>(null) }
    var selected by remember { mutableStateOf<Product?>(null) }
    var recognitionReady by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Preparing six local fans…") }

    LaunchedEffect(recognitionEngine) {
        runCatching {
            withContext(Dispatchers.Default) { recognitionEngine.prepare() }
        }.onSuccess {
            recognitionReady = true
            status = "Aim at one fan"
        }.onFailure {
            recognitionReady = false
            status = "Recognition could not start"
        }
    }

    BackHandler(enabled = selected != null) { selected = null }

    Box(Modifier.fillMaxSize().background(FanLensColors.Backdrop)) {
        if (cameraAllowed) {
            CameraPreview(
                recognitionEnabled = recognitionReady,
                recognitionEngine = recognitionEngine,
                onRecognition = { result ->
                    detection = result.detection
                    status = result.status
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PermissionPanel(onRequestCamera)
        }

        if (cameraAllowed && selected == null) {
            if (detection == null) TargetGuide()
            detection?.let { current ->
                DetectionOverlay(
                    detections = listOf(current),
                    onSelect = { selected = it.product }
                )
            }
        }

        StatusHeader(
            cameraAllowed = cameraAllowed,
            status = if (cameraAllowed) status else "Camera access needed"
        )

        selected?.let { product ->
            ModalBottomSheet(
                onDismissRequest = { selected = null },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                ProductDetails(product)
            }
        }
    }
}

@Composable
private fun CameraPreview(
    recognitionEnabled: Boolean,
    recognitionEngine: ProductRecognitionEngine,
    onRecognition: (RecognitionResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val recognitionEnabledState = rememberUpdatedState(recognitionEnabled)
    val recognitionCallbackState = rememberUpdatedState(onRecognition)

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewContext ->
            PreviewView(previewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
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
                                FanFrameAnalyzer(
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
                        provider.bindToLifecycle(
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

private class FanFrameAnalyzer(
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
            onRecognition(RecognitionResult(null, "Aim at one fan"))
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

    private fun Bitmap.centerSquare(): Bitmap {
        val side = (minOf(width, height) * .92f).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
    }

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 650L
        const val REQUIRED_MATCH_FRAMES = 2
        const val REQUIRED_EMPTY_FRAMES = 2
    }
}

@Composable
private fun TargetGuide() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(maxWidth * .76f, maxHeight * .58f)
                .border(1.dp, FanLensColors.Guide, RoundedCornerShape(18.dp))
        )
    }
}

@Composable
private fun DetectionOverlay(
    detections: List<ProductDetection>,
    onSelect: (ProductDetection) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        detections.forEach { detection ->
            val boxWidth = maxWidth * (detection.bounds.right - detection.bounds.left)
            val boxHeight = maxHeight * (detection.bounds.bottom - detection.bounds.top)
            Box(
                Modifier
                    .offset(maxWidth * detection.bounds.left, maxHeight * detection.bounds.top)
                    .size(boxWidth, boxHeight)
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(y = 18.dp)
                        .clickable { onSelect(detection) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = detection.product.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${(detection.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(cameraAllowed: Boolean, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FanLensColors.HeaderScrim)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (cameraAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("FanLens", fontWeight = FontWeight.Bold, color = FanLensColors.OnSurface)
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = FanLensColors.OnSurfaceMuted
            )
        }
        Text(
            "ON-DEVICE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PermissionPanel(onRequestCamera: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text("Show the camera", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "FanLens needs camera access to place product names over the display.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRequestCamera,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) { Text("Allow camera") }
    }
}

@Composable
private fun ProductDetails(product: Product) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        Text(product.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(product.model, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(product.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        product.priceLabel?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap outside or press Back to return to all products.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
