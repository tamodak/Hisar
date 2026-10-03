package org.tamodak.hisar.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Environment
import java.io.Closeable

/**
 * The fast classifier: whether a picture shows a woman's or girl's bare body beyond her face and
 * hands (arms, shoulders, chest, belly, back, legs, feet), or nudity. It looks for the bare body,
 * not for a woman: a bare leg or foot alone counts, since who it belongs to often can't be told.
 *
 * OpenAI's CLIP ViT-B/32 image encoder gives the picture's embedding, and a logistic head trained
 * on 3,711 hand-labelled pictures scores it. Training and measurements: tools/vision. About 30 ms
 * a picture on a Galaxy S20 FE.
 *
 * Not thread-safe: [ImageFilter] calls it from one coroutine at a time.
 */
internal class BodyClassifier(context: Context) : Closeable {

    private val environment = Environment.create()
    // The int8 weights run as fast in fp32 as in fp16, and slightly more accurately (photo-filter).
    private val model = CpuModel(context, environment, MODEL)
    private val head = context.loadVectors(HEAD)

    /** The head's score for [picture]; [COVER] and above means cover it. */
    fun score(picture: Bitmap): Float {
        // The middle square, as CLIP was trained and as the head was measured: fitting the whole
        // picture in with padding did worse (tools/vision/README.md).
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
        private const val MODEL = "vision/clip_vit_b32_int8.tflite"

        /** The head's weights, one per embedding dimension, then its bias (tools/vision). */
        private const val HEAD = "vision/body_head.bin"

        private const val SIZE = 224

        /**
         * Covered from this score: 97 % of the unsafe pictures reach it, and 29 % of the safe ones
         * (out-of-fold, tools/vision/README.md). Those get the outline mask.
         */
        const val COVER = -1.337f

        /**
         * From this score the classifier is sure (95 % of the pictures above it are unsafe): a
         * video's thumbnail takes its video away. 76 % of the unsafe pictures reach it, 3 % of safe.
         */
        const val SURE = 1.075f
    }
}
