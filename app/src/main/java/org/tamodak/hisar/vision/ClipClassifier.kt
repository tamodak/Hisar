package org.tamodak.hisar.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.ai.edge.litert.Environment
import java.io.Closeable
import java.io.File
import kotlin.math.exp

/**
 * Tells what a region of a photo shows using OpenAI's CLIP ViT-B/32 image encoder, by comparing its
 * embedding with text embeddings of descriptions such as "a pencil sketch of a woman". CLIP learned
 * from hundreds of millions of captioned images, which is what lets it handle photos, paintings,
 * drawings and anime alike.
 *
 * The text embeddings are computed once, offline, by tools/make_text_embeddings.py.
 */
internal class ClipClassifier(models: File, environment: Environment) : Closeable {

    /** Probabilities for one region. */
    class Look(
        /** That the person is a woman or girl rather than a man or boy. */
        val woman: Float,
        /** That the region shows a woman, compared with a man or no person at all. */
        val womanAmongAll: Float,
        /** That the region shows no person at all, compared with a woman or a man. */
        val noPerson: Float,
        /** That a man's body between the navel and the knees is bare rather than covered. */
        val bare: Float,
    )

    // The int8 weights run as fast in fp32 as in fp16, and slightly more accurately.
    private val model = CpuModel(models, environment, "clip_vit_b32_int8.tflite")
    private val text = models.loadVectors("clip_text_embeddings.bin").let { floats ->
        val dimensions = floats.size / TEXT_CLASSES
        Array(TEXT_CLASSES) { floats.copyOfRange(it * dimensions, (it + 1) * dimensions) }
    }

    fun look(photo: Bitmap, region: RectF): Look {
        val (square, _) = fitInSquare(photo, region, SIZE, CLIP_MEAN_COLOR, centered = true)
        val embedding = model.run(rgbFloats(square))[0]
        val similarity = FloatArray(TEXT_CLASSES) { dot(embedding, text[it]) }
        val all = softmax(similarity[WOMAN], similarity[MAN], similarity[NO_PERSON])
        return Look(
            woman = sigmoid(similarity[WOMAN] - similarity[MAN]),
            womanAmongAll = all[0],
            noPerson = all[2],
            bare = sigmoid(similarity[BARE] - similarity[COVERED]),
        )
    }

    override fun close() = model.close()

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    // CLIP's similarities are cosines; it was trained to sharpen them by a factor of 100.
    private fun sigmoid(difference: Float) = 1f / (1f + exp(-LOGIT_SCALE * difference))

    private fun softmax(vararg similarities: Float): FloatArray {
        val highest = similarities.max()
        val e = FloatArray(similarities.size) { exp(LOGIT_SCALE * (similarities[it] - highest)) }
        val total = e.sum()
        return FloatArray(e.size) { e[it] / total }
    }

    private companion object {
        // Order of the vectors in clip_text_embeddings.bin (tools/prompts.py, CLASSES).
        const val WOMAN = 0
        const val MAN = 1
        const val NO_PERSON = 2
        const val BARE = 3
        const val COVERED = 4
        const val TEXT_CLASSES = 5

        const val LOGIT_SCALE = 100f

        /** The model's input size. */
        const val SIZE = 224

        /** CLIP's average pixel; padding with it disturbs the image least. */
        val CLIP_MEAN_COLOR = Color.rgb(123, 117, 104)
    }
}
