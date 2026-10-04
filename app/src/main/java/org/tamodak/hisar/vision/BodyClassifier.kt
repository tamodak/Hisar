package org.tamodak.hisar.vision

import android.graphics.Bitmap
import com.google.ai.edge.litert.Environment
import java.io.Closeable
import java.io.File

/**
 * The fast classifier: whether a picture shows a woman's or girl's bare body beyond her face and
 * hands (arms, shoulders, chest, belly, back, legs, feet), or nudity. It looks for the bare body,
 * not for a woman: a bare leg or foot alone counts, since who it belongs to often can't be told.
 *
 * OpenAI's CLIP ViT-B/32 image encoder gives the picture's embedding, and a logistic head trained
 * on 5,711 hand-labelled pictures scores it. Training and measurements: tools/vision, README.md.
 *
 * Not thread-safe: [ImageFilter] calls it from one coroutine at a time.
 */
internal class BodyClassifier(models: File) : Closeable {

    private val environment = Environment.create()
    // The int8 weights run as fast in fp32 as in fp16, and slightly more accurately (photo-filter).
    private val model = CpuModel(models, environment, MODEL)
    private val head = models.loadVectors(HEAD)

    /** The head's score for [picture]; [COVER] and above means cover it. */
    fun score(picture: Bitmap): Float {
        // The middle square, as CLIP was trained and as the head was measured: fitting the whole
        // picture in with padding did worse (README.md, picture filter measurements).
        val embedding = model.run(rgbFloats(centerSquare(picture, SIZE)))[0]
        var sum = head[embedding.size]
        for (i in embedding.indices) sum += head[i] * embedding[i]
        return sum
    }

    override fun close() {
        model.close()
        environment.close()
    }

    companion object {
        /** OpenAI's CLIP ViT-B/32 image encoder, int8 weights; the outline mask uses it too. */
        private const val MODEL = "clip_vit_b32_int8.tflite"

        /** The head's weights, one per embedding dimension, then its bias (tools/vision). */
        private const val HEAD = "body_head.bin"

        private const val SIZE = 224

        /**
         * Covered from this score: 97 % of the unsafe labelled pictures reach it, and half of the
         * safe ones, which are weighted towards hard cases (out-of-fold, tools/vision/head.py).
         * Those get the outline mask.
         */
        const val COVER = -1.710f

        /**
         * From this score the classifier is sure (95 % of the labelled pictures above it are
         * unsafe): a video's thumbnail takes its video away. 57 % of unsafe pictures reach it,
         * 3 % of safe ones.
         */
        const val SURE = 1.446f
    }
}
