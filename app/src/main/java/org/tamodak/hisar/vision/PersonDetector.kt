package org.tamodak.hisar.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A person found in a photo, in photo coordinates. */
internal class Person(val box: RectF, val score: Float)

/**
 * Finds people with EfficientDet-Lite2, trained on COCO. The model includes its own non-max
 * suppression and returns at most 25 objects.
 */
internal class PersonDetector(models: File) : Closeable {

    private val interpreter = cpuInterpreter(models.loadModel("efficientdet_lite2.tflite"))
    private val size = interpreter.getInputTensor(0).shape()[1]
    private val maxObjects = interpreter.getOutputTensor(0).shape()[1]
    private val input = ByteBuffer.allocateDirect(size * size * 3).order(ByteOrder.nativeOrder())
    private val boxes = Array(1) { Array(maxObjects) { FloatArray(4) } }
    private val classes = Array(1) { FloatArray(maxObjects) }
    private val scores = Array(1) { FloatArray(maxObjects) }
    private val count = FloatArray(1)

    fun detect(photo: Bitmap): List<Person> {
        val whole = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
        var found = detectIn(photo, whole)
        val long = max(photo.width, photo.height)
        val short = min(photo.width, photo.height)
        if (found.isEmpty() && long > 1.3f * short) {
            // Fitting a wide or tall photo into the model's square shrinks everyone in it. If that
            // found nobody, look again in overlapping square tiles at full size.
            val tiles = ceil(long.toFloat() / short * 1.25f).toInt()
            val step = (long - short).toFloat() / (tiles - 1)
            found = (0 until tiles).flatMap { i ->
                val start = (i * step).roundToInt().toFloat()
                val tile = if (photo.width > photo.height) RectF(start, 0f, start + short, short.toFloat())
                else RectF(0f, start, short.toFloat(), start + short)
                detectIn(photo, tile)
            }
        }
        return withoutDuplicates(found)
    }

    override fun close() = interpreter.close()

    private fun detectIn(photo: Bitmap, region: RectF): List<Person> {
        // Fitted without distortion into the top-left corner: squashing a wide photo into the square
        // made people too thin to recognize.
        val (square, placement) = fitInSquare(photo, region, size, Color.BLACK, centered = false)
        putRgbBytes(square, input)
        interpreter.runForMultipleInputsOutputs(
            arrayOf(input),
            mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count),
        )
        return (0 until min(count[0].toInt(), maxObjects))
            .filter { classes[0][it].roundToInt() == COCO_PERSON && scores[0][it] >= MIN_SCORE }
            .mapNotNull { i ->
                val (top, left, bottom, right) = boxes[0][i].toList()
                val box = RectF(
                    placement.photoX(left, size), placement.photoY(top, size),
                    placement.photoX(right, size), placement.photoY(bottom, size),
                )
                // Clipped to the region; dropped if it lies only in the padding around the photo.
                if (box.intersect(region)) Person(box, scores[0][i]) else null
            }
    }

    /** Keeps the best of boxes that overlap by half or more (the same person seen in two tiles). */
    private fun withoutDuplicates(people: List<Person>): List<Person> {
        val kept = mutableListOf<Person>()
        for (person in people.sortedByDescending { it.score }) {
            if (kept.none { overlap(it.box, person.box) >= 0.5f }) kept += person
        }
        return kept
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val shared = RectF()
        if (!shared.setIntersect(a, b)) return 0f
        val sharedArea = shared.width() * shared.height()
        return sharedArea / (a.width() * a.height() + b.width() * b.height() - sharedArea)
    }

    private companion object {
        const val COCO_PERSON = 0

        /** Low on purpose: a person missed here is never covered, a false one costs one CLIP look. */
        const val MIN_SCORE = 0.2f
    }
}
