package org.tamodak.hisar.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Memory-maps a model stored uncompressed in the APK's assets. */
internal fun Context.loadModel(asset: String): ByteBuffer = assets.openFd(asset).use { file ->
    FileInputStream(file.fileDescriptor).channel.use {
        it.map(FileChannel.MapMode.READ_ONLY, file.startOffset, file.declaredLength)
    }
}

/**
 * An interpreter on the CPU with one thread per core, for the two models [CpuModel] cannot run
 * (their uint8 inputs and the detector's built-in non-max suppression crash it).
 */
internal fun cpuInterpreter(model: ByteBuffer) = Interpreter(
    model,
    Interpreter.Options().setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 8)),
)

/**
 * A model run on the CPU by LiteRT's CompiledModel API, which on a Galaxy S20 FE ran the same
 * models about three times faster than the classic [Interpreter] (CLIP: 30 ms against 111 ms).
 *
 * With [fp16], phones whose cores compute in half precision (ARMv8.2 and later: most phones from
 * 2019 on) run it that way, about twice as fast again. Only models checked to give the same
 * answers in fp16 should ask for it.
 */
internal class CpuModel(
    context: Context,
    environment: Environment,
    asset: String,
    fp16: Boolean = false,
) : Closeable {

    private val model = CompiledModel.create(
        context.assets,
        asset,
        CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(
                CPU_THREADS,
                if (fp16 && HALF_PRECISION) XNNPACK_DEFAULT_FLAGS or XNNPACK_FORCE_FP16 else null,
                null,
            )
        },
        environment,
    )
    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()

    /** Runs the model on [input], one array per input tensor, and returns its outputs in order. */
    fun run(vararg input: FloatArray): List<FloatArray> {
        input.forEachIndexed { i, values -> inputs[i].writeFloat(values) }
        model.run(inputs, outputs)
        return outputs.map { it.readFloat() }
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
    }

    private companion object {
        // From LiteRT's xnnpack_delegate.h: signed and unsigned 8-bit kernels (the defaults), and fp16.
        const val XNNPACK_DEFAULT_FLAGS = 0x1 or 0x2
        const val XNNPACK_FORCE_FP16 = 0x4
    }
}

/**
 * Threads for [CpuModel]: the cores clocked within 80 % of the fastest. Phones pair fast cores
 * with slow ones, and a slow core given an equal share of the work holds the others up: the S20 FE
 * gets its four fast cores, a phone with eight alike gets eight.
 */
private val CPU_THREADS: Int by lazy {
    val speeds = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
        runCatching {
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        }.getOrNull()
    }
    val fastest = speeds.maxOrNull()
    if (fastest == null) Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    else speeds.count { it >= 0.8 * fastest }.coerceIn(1, 8)
}

/** Whether the CPU has half-precision arithmetic (the "asimdhp" feature of ARMv8.2). */
private val HALF_PRECISION: Boolean by lazy {
    runCatching {
        File("/proc/cpuinfo").readLines().any { it.startsWith("Features") && "asimdhp" in it.split(" ", "\t") }
    }.getOrDefault(false)
}

/** Reads an asset of little-endian float32 values, such as precomputed text embeddings. */
internal fun Context.loadVectors(asset: String): FloatArray = assets.open(asset).use { stream ->
    val floats = ByteBuffer.wrap(stream.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    FloatArray(floats.remaining()).also { floats.get(it) }
}

/** Where a region of a photo landed inside a square model input. */
internal class Placement(
    private val scale: Float,
    private val region: RectF,
    private val offsetX: Float,
    private val offsetY: Float,
) {
    /** Converts a point given as a fraction (0..1) of the square into photo coordinates. */
    fun photoX(fraction: Float, size: Int) = (fraction * size - offsetX) / scale + region.left
    fun photoY(fraction: Float, size: Int) = (fraction * size - offsetY) / scale + region.top
}

/**
 * Draws [region] of [photo] into a [size] x [size] square, scaled to fit without distortion, either
 * centered or in the top-left corner, with [background] filling the rest.
 */
internal fun fitInSquare(
    photo: Bitmap,
    region: RectF,
    size: Int,
    background: Int,
    centered: Boolean,
): Pair<Bitmap, Placement> {
    val scale = size / max(region.width(), region.height())
    val width = region.width() * scale
    val height = region.height() * scale
    val offsetX = if (centered) (size - width) / 2 else 0f
    val offsetY = if (centered) (size - height) / 2 else 0f
    val source = shrunkCrop(photo, region, max(width, height))
    val square = createBitmap(size, size)
    Canvas(square).apply {
        drawColor(background)
        drawBitmap(source, null, RectF(offsetX, offsetY, offsetX + width, offsetY + height), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return square to Placement(scale, region, offsetX, offsetY)
}

/**
 * Copies [region] out of [photo], halving it while it is more than twice [targetSize]. Drawing a
 * large image straight down to a small model input would skip most pixels; halving first averages
 * them, like the resizing the models were evaluated with.
 */
private fun shrunkCrop(photo: Bitmap, region: RectF, targetSize: Float): Bitmap {
    val bounds = Rect(
        region.left.roundToInt().coerceIn(0, photo.width - 1),
        region.top.roundToInt().coerceIn(0, photo.height - 1),
        region.right.roundToInt().coerceIn(1, photo.width),
        region.bottom.roundToInt().coerceIn(1, photo.height),
    )
    var image = Bitmap.createBitmap(photo, bounds.left, bounds.top, bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
    while (max(image.width, image.height) > 2 * targetSize) {
        image = image.scale(max(1, image.width / 2), max(1, image.height / 2))
    }
    return image
}

/** Writes [bitmap]'s pixels as RGB bytes (0..255) into [buffer], from its start. */
internal fun putRgbBytes(bitmap: Bitmap, buffer: ByteBuffer) {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val rgb = ByteArray(pixels.size * 3)
    for (i in pixels.indices) {
        rgb[3 * i] = (pixels[i] shr 16).toByte()
        rgb[3 * i + 1] = (pixels[i] shr 8).toByte()
        rgb[3 * i + 2] = pixels[i].toByte()
    }
    buffer.rewind()
    buffer.put(rgb)
    buffer.rewind()
}

/** [bitmap]'s pixels as RGB floats (0..255), row by row. */
internal fun rgbFloats(bitmap: Bitmap): FloatArray {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val rgb = FloatArray(pixels.size * 3)
    for (i in pixels.indices) {
        rgb[3 * i] = (pixels[i] shr 16 and 0xFF).toFloat()
        rgb[3 * i + 1] = (pixels[i] shr 8 and 0xFF).toFloat()
        rgb[3 * i + 2] = (pixels[i] and 0xFF).toFloat()
    }
    return rgb
}

/** The middle square of [photo], resized to [size] x [size]. */
internal fun centerSquare(photo: Bitmap, size: Int): Bitmap {
    val side = min(photo.width, photo.height)
    val square = Bitmap.createBitmap(photo, (photo.width - side) / 2, (photo.height - side) / 2, side, side)
    return resized(square, size, size)
}

/** [photo] resized to [width] x [height], halving it first like [fitInSquare]. */
internal fun resized(photo: Bitmap, width: Int, height: Int): Bitmap {
    var image = photo
    while (image.width > 2 * width && image.height > 2 * height) {
        image = image.scale(max(1, image.width / 2), max(1, image.height / 2))
    }
    return image.scale(width, height)
}
