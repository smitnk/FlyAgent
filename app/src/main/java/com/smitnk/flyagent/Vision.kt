package com.smitnk.flyagent

import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Offline image reading with on-device ML Kit models (bundled, no internet): text (OCR) + object labels. Call off the UI thread. */
object Vision {
    fun describe(bytes: ByteArray): String {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return "(could not decode image)"
        val img = InputImage.fromBitmap(bmp, 0)
        val text = Tasks.await(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)).text.trim()
        val labels = Tasks.await(ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS).process(img))
            .filter { it.confidence > 0.6 }.joinToString { "${it.text} (${(it.confidence * 100).toInt()}%)" }
        return "Image contents (on-device): labels: ${labels.ifEmpty { "none" }}\nText in image:\n${text.ifEmpty { "(no text found)" }}"
    }
}
