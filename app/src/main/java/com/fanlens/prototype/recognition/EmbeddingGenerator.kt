package com.fanlens.prototype.recognition

import android.content.Context
import android.graphics.Bitmap
import com.fanlens.prototype.util.Hashing
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable

/**
 * Turns one image into one fingerprint.
 *
 * Vectors are L2-normalised here, once, so that every later comparison is a plain
 * dot product. [modelVersion] is the hash of the .tflite asset: change the model
 * file and every stored fingerprint is recognisably from a different generation.
 */
class EmbeddingGenerator private constructor(
    private val embedder: ImageEmbedder,
    val modelId: String,
    val modelVersion: String
) : Closeable {

    /** Fingerprint width, learned from the first successful embedding. */
    @Volatile
    var dim: Int = 0
        private set

    fun embed(bitmap: Bitmap): FloatArray? {
        val image = BitmapImageBuilder(bitmap).build()
        val raw = try {
            embedder.embed(image)
                .embeddingResult()
                .embeddings()
                .firstOrNull()
                ?.floatEmbedding()
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }

        if (raw == null || raw.isEmpty()) return null
        if (dim == 0) dim = raw.size
        return VectorCodec.l2Normalize(raw)
    }

    override fun close() {
        runCatching { embedder.close() }
    }

    companion object {
        const val MODEL_PATH = "models/mobilenet_v3_small.tflite"
        const val MODEL_ID = "mobilenet_v3_small"

        fun create(context: Context, assetPath: String = MODEL_PATH): EmbeddingGenerator {
            val options = ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(assetPath)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .build()

            // The preprocessing is part of the version: change how images are
            // prepared and every stored fingerprint must be regenerated.
            val assetHash = context.assets.open(assetPath).use(Hashing::sha256)
            return EmbeddingGenerator(
                embedder = ImageEmbedder.createFromOptions(context, options),
                modelId = MODEL_ID,
                modelVersion = "$assetHash/${RecognitionPreprocessing.VERSION}"
            )
        }
    }
}
