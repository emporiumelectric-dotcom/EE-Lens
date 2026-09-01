package com.fanlens.prototype.recognition

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Wraps ML Kit's on-device Text Recognition, turning one [Bitmap] into the
 * list [BrandTextConflict.checkForConflict] expects. Mirrors
 * EmbeddingGenerator's own shape (a thin Closeable wrapper around one
 * Google on-device vision client) rather than introducing a new pattern.
 *
 * Deliberately fails open: any error, or running past [TIMEOUT_MS], returns
 * an empty list rather than throwing or blocking recognition -- consistent
 * with BrandTextConflict's own "missing information is never a conflict"
 * rule. This runs synchronously on the analyzer's dedicated background
 * thread (see FanFrameAnalyzer/EeFanFrameAnalyzer), the same thread
 * EmbeddingGenerator.embed already blocks on, never on the main thread --
 * Tasks.await() throws IllegalStateException if it ever is.
 */
class BrandTextRecognizer private constructor(
    private val recognizer: TextRecognizer
) : Closeable {

    fun recognize(bitmap: Bitmap): List<BrandTextConflict.DetectedText> {
        val frameArea = (bitmap.width.toLong() * bitmap.height.toLong()).toFloat()
        if (frameArea <= 0f) return emptyList()

        val image = InputImage.fromBitmap(bitmap, 0)
        val text = try {
            Tasks.await(recognizer.process(image), TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return emptyList()
        } catch (_: Throwable) {
            return emptyList()
        }

        return text.textBlocks.mapNotNull { block ->
            val body = block.text
            val box = block.boundingBox
            if (body.isBlank() || box == null) return@mapNotNull null
            val area = (box.width().toFloat() * box.height().toFloat()) / frameArea
            BrandTextConflict.DetectedText(body, area)
        }
    }

    override fun close() {
        runCatching { recognizer.close() }
    }

    companion object {
        // Only ever called after a confident embedding match (see
        // OnDeviceProductRecognitionEngine.recognize), not on every frame --
        // there is real budget for this within the existing 650ms per-frame
        // throttle (FanFrameAnalyzer.ANALYSIS_INTERVAL_MS) without needing to
        // shrink it, but this bound still exists so a slow/stuck inference on
        // a real device can never silently stall the camera pipeline; it just
        // degrades to "no text found" for that one frame instead.
        private const val TIMEOUT_MS = 400L

        fun create(): BrandTextRecognizer =
            BrandTextRecognizer(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))
    }
}
