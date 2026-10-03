package org.tamodak.hisar.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Environment
import java.io.Closeable
import kotlin.math.exp
import kotlin.math.max

/**
 * Maps where people and body parts are in a picture with CLIPSeg (CLIP ViT-B/16 with a small
 * decoder that turns a text prompt into a heat map). It finds what the person detector misses:
 * hands, feet, hair or a face filling the picture, anime characters and sketches.
 *
 * The prompts name women's body parts ("a woman's feet"), but in practice CLIPSeg marks anyone's
 * hands or feet for them, so the maps say where, not whose: [ModestyFilter] asks CLIP that.
 * The prompts' text embeddings are computed offline by tools/make_clipseg_embeddings.py.
 */
internal class BodyPartMapper(context: Context, environment: Environment) : Closeable {

    /** Probabilities (0..1) per grid cell. */
    class Maps(
        /** That the cell shows a person's body or a body part: the most likely of the part prompts. */
        val part: FloatArray,
        /** That the cell is part of a person ("a person"), used to grow a part to the whole body. */
        val person: FloatArray,
    )

    // The vision model computes with int8 weights. Keeping them as int8 only in storage was a
    // little closer to the original, but LiteRT expands such weights to floats when loading, which
    // took 0.55 GB more memory; the covers on 82 test pictures came out the same either way. Both
    // run in fp16 where the phone can, which more than halved the time on a Galaxy S20 FE.
    private val vision = CpuModel(context, environment, "vision/clipseg_vision.tflite", fp16 = true)
    private val decoder = CpuModel(context, environment, "vision/clipseg_decoder.tflite", fp16 = true)
    private val text = context.loadVectors("vision/clipseg_text_embeddings.bin")

    fun map(photo: Bitmap, grid: Grid): Maps {
        // CLIPSeg works on the whole picture squeezed into a square.
        val activations = vision.run(rgbFloats(resized(photo, SIZE, SIZE)))
        val logits = decoder.run(activations[0], activations[1], activations[2], text)[0]
        val cells = SIZE * SIZE
        val part = FloatArray(cells) { i ->
            var best = logits[i]
            for (prompt in 1 until PERSON_PROMPT) best = max(best, logits[prompt * cells + i])
            sigmoid(best)
        }
        val person = FloatArray(cells) { sigmoid(logits[PERSON_PROMPT * cells + it]) }
        return Maps(grid.resample(part, SIZE, SIZE), grid.resample(person, SIZE, SIZE))
    }

    override fun close() {
        vision.close()
        decoder.close()
    }

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))

    private companion object {
        const val SIZE = 352

        /**
         * The prompts, in the order of clipseg_text_embeddings.bin: "a woman", "a girl",
         * "a woman's body", "a woman's face", "a woman's hair", "a woman's hands", "a woman's feet",
         * "a woman's legs", "a human hand", "a human foot", "human legs", "human skin", and last
         * "a person".
         */
        const val PERSON_PROMPT = 12
    }
}
