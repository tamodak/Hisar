package org.tamodak.hisar.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A body point in photo coordinates, with how sure the model is that it is visible (0..1). */
internal class Keypoint(val position: PointF, val score: Float)

/** Finds the 17 COCO body keypoints of the one person in a region, with MoveNet SinglePose Lightning. */
internal class PoseEstimator(models: File) : Closeable {

    private val interpreter = cpuInterpreter(models.loadModel("movenet_lightning_int8.tflite"))
    private val size = interpreter.getInputTensor(0).shape()[1]
    private val input = ByteBuffer.allocateDirect(size * size * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(1) { Array(KEYPOINTS) { FloatArray(3) } } }

    fun keypoints(photo: Bitmap, region: RectF): List<Keypoint> {
        val (square, placement) = fitInSquare(photo, region, size, Color.BLACK, centered = true)
        putRgbBytes(square, input)
        interpreter.run(input, output)
        // Each keypoint is (y, x, score), as fractions of the square.
        return output[0][0].map { (y, x, score) ->
            Keypoint(PointF(placement.photoX(x, size), placement.photoY(y, size)), score)
        }
    }

    override fun close() = interpreter.close()

    companion object {
        const val KEYPOINTS = 17
        const val LEFT_SHOULDER = 5
        const val RIGHT_SHOULDER = 6
        const val LEFT_HIP = 11
        const val RIGHT_HIP = 12
        const val LEFT_KNEE = 13
        const val RIGHT_KNEE = 14
    }
}
