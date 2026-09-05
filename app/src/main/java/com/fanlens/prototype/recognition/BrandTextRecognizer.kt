package com.fanlens.prototype.recognition

import android.graphics.Bitmap
import android.util.Log
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
            Log.d(TAG, "recognize TIMEOUT after ${TIMEOUT_MS}ms; treating this frame as no text found")
            return emptyList()
        } catch (throwable: Throwable) {
            Log.d(TAG, "recognize FAILED; treating this frame as no text found", throwable)
            return emptyList()
        }

        val detections = text.textBlocks.mapNotNull { block ->
            val body = block.text
            val box = block.boundingBox
            if (body.isBlank() || box == null) return@mapNotNull null
            val area = (box.width().toFloat() * box.height().toFloat()) / frameArea
            BrandTextConflict.DetectedText(body, area)
        }
        // Every block ML Kit found, prominence included, not just the ones that
        // end up mattering to checkForConflict -- this is the one line a real-
        // device test actually needs to see to tell "OCR found nothing usable"
        // apart from "OCR read something, but it didn't parse as any known
        // brand" apart from "OCR read the wrong brand but too small to trust".
        Log.d(TAG, "recognize found ${detections.size} text block(s): " + detections.joinToString { "\"${it.text}\" (area=${"%.4f".format(it.relativeArea)})" })
        return detections
    }

    override fun close() {
        runCatching { recognizer.close() }
    }

    companion object {
        // Distinct, greppable tag -- `adb logcat -s BrandTextTrace` on its own
        // shows every OCR attempt end to end during a real-device test,
        // mirroring CloudSyncManager's own PUSH_TAG for the same reason.
        private const val TAG = "BrandTextTrace"

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
