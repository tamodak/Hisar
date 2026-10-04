package org.tamodak.hisar.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.google.ai.edge.litert.Environment
import java.io.Closeable
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Outlines people and things in a picture with MobileSAM (Segment Anything with a small TinyViT
 * image encoder). The encoder reads the picture once; then each outline is a quick question to the
 * decoder: "what is in this box, through these points?"
 */
internal class Segmenter(models: File, environment: Environment) : Closeable {

    /** A picture the encoder has read. */
    class Picture(
        val embedding: FloatArray,
        /** Model input pixels per photo pixel. */
        val scale: Float,
    )

    // The encoder's layer norms were adjusted for fp16 (see tools/convert_sam.py); in fp16 its
    // output matched fp32 to a cosine of 0.99995 on a Galaxy S20 FE, in half the time.
    private val encoder = CpuModel(models, environment, "sam_encoder.tflite", fp16 = true)
    private val decoder = CpuModel(models, environment, "sam_decoder.tflite", fp16 = true)
    private val padding = Paint().apply { color = PADDING_COLOR }

    fun read(photo: Bitmap): Picture {
        // Fitted into the top-left corner of the model's square, as SAM was trained.
        val scale = SIZE.toFloat() / max(photo.width, photo.height)
        val square = createBitmap(SIZE, SIZE)
        Canvas(square).apply {
            drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), padding)
            val shrunk = resized(photo, (photo.width * scale).roundToInt(), (photo.height * scale).roundToInt())
            drawBitmap(shrunk, 0f, 0f, null)
        }
        return Picture(encoder.run(rgbFloats(square))[0], scale)
    }

    /**
     * The outline of what lies in [box] (if any) and through [points], as a mask on [grid]. Of the
     * three outlines SAM proposes, the one it rates best.
     */
    fun outline(picture: Picture, grid: Grid, box: RectF?, points: List<PointF>): BooleanArray {
        val coordinates = FloatArray(2 * PROMPTS)
        val labels = FloatArray(PROMPTS) { PADDING_LABEL }
        var n = 0
        fun add(x: Float, y: Float, label: Float) {
            coordinates[2 * n] = x * picture.scale
            coordinates[2 * n + 1] = y * picture.scale
            labels[n++] = label
        }
        if (box != null) {
            add(box.left, box.top, TOP_LEFT_LABEL)
            add(box.right, box.bottom, BOTTOM_RIGHT_LABEL)
        }
        for (point in points.take(PROMPTS - n)) add(point.x, point.y, POINT_LABEL)

        val (masks, scores) = decoder.run(picture.embedding, coordinates, labels)
        val best = scores.indices.maxBy { scores[it] }
        val logits = smoothed(masks, best * MASK_SIZE * MASK_SIZE)
        // Sampled at each grid cell's center, converted to the low-resolution mask's pixels.
        val toMask = picture.scale * MASK_SIZE / SIZE
        return BooleanArray(grid.size) { i ->
            val x = grid.photoX(i % grid.width) * toMask - 0.5f
            val y = grid.photoY(i / grid.width) * toMask - 0.5f
            bilinear(logits, 0, MASK_SIZE, MASK_SIZE, x, y) > 0f
        }
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    /**
     * One of the decoder's 256 x 256 masks, blurred a little: its upsampling layers leave a
     * checkerboard pattern where it is unsure, which would show as dotted edges.
     */
    private fun smoothed(masks: FloatArray, offset: Int): FloatArray {
        val horizontal = FloatArray(MASK_SIZE * MASK_SIZE)
        for (y in 0 until MASK_SIZE) for (x in 0 until MASK_SIZE) {
            var sum = 0f
            for (k in -2..2) sum += KERNEL[k + 2] * masks[offset + y * MASK_SIZE + (x + k).coerceIn(0, MASK_SIZE - 1)]
            horizontal[y * MASK_SIZE + x] = sum
        }
        val result = FloatArray(MASK_SIZE * MASK_SIZE)
        for (y in 0 until MASK_SIZE) for (x in 0 until MASK_SIZE) {
            var sum = 0f
            for (k in -2..2) sum += KERNEL[k + 2] * horizontal[(y + k).coerceIn(0, MASK_SIZE - 1) * MASK_SIZE + x]
            result[y * MASK_SIZE + x] = sum
        }
        return result
    }

    private companion object {
        const val SIZE = 1024
        const val MASK_SIZE = 256

        /** The decoder takes this many prompts (box corners and points), padded with [PADDING_LABEL]. */
        const val PROMPTS = 16
        const val PADDING_LABEL = -1f
        const val POINT_LABEL = 1f
        const val TOP_LEFT_LABEL = 2f
        const val BOTTOM_RIGHT_LABEL = 3f

        /** SAM's average pixel, which its normalization turns into zero. */
        val PADDING_COLOR = Color.rgb(124, 116, 104)

        /** A Gaussian with a standard deviation of one pixel. */
        val KERNEL = FloatArray(5) { exp(-((it - 2) * (it - 2)) / 2f) }.let { k ->
            val total = k.sum()
            FloatArray(5) { k[it] / total }
        }
    }
}
