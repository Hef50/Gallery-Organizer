package com.galleryorganizer.data.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

data class SuggestedLabel(val label: String, val confidence: Float)

data class ImageAnalysis(
    val labels: List<SuggestedLabel> = emptyList(),
    val text: String? = null,
)

/**
 * Looks at one image. An interface so the auto-tagger's logic — thresholds, ordering,
 * what never gets suggested — is testable on the JVM without ML Kit or a device.
 */
interface ImageAnalyzer {
    suspend fun analyze(uri: Uri, minConfidence: Float): ImageAnalysis?
    fun close() {}
}

/**
 * The real one: ML Kit's bundled image labeler and Latin text recogniser.
 *
 * Both models are **bundled in the APK**, not downloaded — the app has no `INTERNET`
 * permission and never will. That costs a few MB of APK and buys analysis that works on a
 * plane, on day one, with no Play Services dependency at runtime.
 */
class MlKitImageAnalyzer(private val context: Context) : ImageAnalyzer {

    private val labeler by lazy {
        // The threshold is applied here as well as downstream so ML Kit can discard weak
        // candidates before they are ever materialised.
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(MIN_MODEL_CONFIDENCE).build(),
        )
    }

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun analyze(uri: Uri, minConfidence: Float): ImageAnalysis? {
        // A 200 MP original decoded at full size is a ~800 MB bitmap. Labels are stable at
        // around a thousand pixels, and OCR needs enough resolution to resolve glyphs but
        // nothing like the full frame.
        val bitmap = decodeScaled(uri, MAX_ANALYSIS_EDGE) ?: return null
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val labels = runCatching {
                Tasks.await(labeler.process(image), TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull().orEmpty().mapNotNull {
                if (it.confidence >= minConfidence) SuggestedLabel(it.text, it.confidence) else null
            }

            val text = runCatching {
                Tasks.await(recognizer.process(image), TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }

            ImageAnalysis(labels, text)
        } finally {
            bitmap.recycle()
        }
    }

    /** Two-pass decode: read the bounds, pick a sample size, then decode. */
    private fun decodeScaled(uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / sample > maxEdge) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()

    override fun close() {
        runCatching { labeler.close() }
        runCatching { recognizer.close() }
    }

    private companion object {
        const val MAX_ANALYSIS_EDGE = 1_280
        const val MIN_MODEL_CONFIDENCE = 0.5f
        const val TASK_TIMEOUT_SECONDS = 20L
    }
}
