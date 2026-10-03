package org.tamodak.hisar.vision

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The photo at a reduced resolution, where the filter draws and combines what to cover. The
 * models' own masks are coarser than this anyway (SAM's 256 x 256, CLIPSeg's 352 x 352), and the
 * result is scaled back up to the photo with smooth edges.
 *
 * A mask is a [BooleanArray] of [size] cells, row by row.
 */
internal class Grid(val photoWidth: Int, val photoHeight: Int) {

    private val scale = min(1f, MAX_SIZE.toFloat() / max(photoWidth, photoHeight))
    val width = max(1, (photoWidth * scale).roundToInt())
    val height = max(1, (photoHeight * scale).roundToInt())
    val size get() = width * height

    /** Photo coordinates of the center of a cell. */
    fun photoX(x: Int) = (x + 0.5f) * photoWidth / width
    fun photoY(y: Int) = (y + 0.5f) * photoHeight / height

    /** The cells whose centers lie in [area], given in photo coordinates. */
    fun cells(area: RectF): BooleanArray {
        val mask = BooleanArray(size)
        val x0 = max(0, ceil(area.left * width / photoWidth - 0.5f).toInt())
        val x1 = min(width - 1, floor(area.right * width / photoWidth - 0.5f).toInt())
        val y0 = max(0, ceil(area.top * height / photoHeight - 0.5f).toInt())
        val y1 = min(height - 1, floor(area.bottom * height / photoHeight - 0.5f).toInt())
        for (y in y0..y1) mask.fill(true, y * width + x0, y * width + x1 + 1)
        return mask
    }

    /** The smallest rectangle, in photo coordinates, around the cells of [mask]; null if empty. */
    fun bounds(mask: BooleanArray): RectF? {
        var x0 = width
        var y0 = height
        var x1 = -1
        var y1 = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (mask[y * width + x]) {
                x0 = min(x0, x); x1 = max(x1, x)
                y0 = min(y0, y); y1 = max(y1, y)
            }
        }
        if (x1 < 0) return null
        return RectF(
            x0.toFloat() * photoWidth / width,
            y0.toFloat() * photoHeight / height,
            (x1 + 1f) * photoWidth / width,
            (y1 + 1f) * photoHeight / height,
        )
    }

    /**
     * Samples a [mapWidth] x [mapHeight] map that covers the whole photo, stretched to it, at every
     * cell, interpolating bilinearly.
     */
    fun resample(map: FloatArray, mapWidth: Int, mapHeight: Int, offset: Int = 0): FloatArray =
        FloatArray(size) { i ->
            bilinear(
                map, offset, mapWidth, mapHeight,
                (i % width + 0.5f) * mapWidth / width - 0.5f,
                (i / width + 0.5f) * mapHeight / height - 0.5f,
            )
        }

    /**
     * Up to [count] points (photo coordinates) where [heat] is highest inside [region], kept at
     * least 8 % of the photo's long side apart so that they mark different parts of it.
     */
    fun peaks(heat: FloatArray, region: BooleanArray, count: Int): List<PointF> {
        val candidates = region.indices.filter { region[it] }.sortedByDescending { heat[it] }.take(5000)
        val spacing = SPREAD * max(width, height)
        val picked = mutableListOf<Int>()
        for (i in candidates) {
            if (picked.size >= count) break
            val x = i % width
            val y = i / width
            if (picked.all { j -> square(x - j % width) + square(y - j / width) >= spacing * spacing }) picked += i
        }
        return picked.map { PointF(photoX(it % width), photoY(it / width)) }
    }

    /** Numbers the 8-connected regions of [mask]. */
    fun regions(mask: BooleanArray): Regions {
        val label = IntArray(size)
        val areas = mutableListOf(0)
        val stack = IntArray(size)
        for (start in 0 until size) {
            if (!mask[start] || label[start] != 0) continue
            val id = areas.size
            var area = 0
            var top = 0
            stack[top++] = start
            label[start] = id
            while (top > 0) {
                val i = stack[--top]
                area++
                val x = i % width
                val y = i / width
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val n = ny * width + nx
                    if (mask[n] && label[n] == 0) {
                        label[n] = id
                        stack[top++] = n
                    }
                }
            }
            areas += area
        }
        return Regions(label, areas.toIntArray())
    }

    /** [mask] grown by [radius] cells in every direction. */
    fun dilate(mask: BooleanArray, radius: Int): BooleanArray {
        val grown = BooleanArray(size)
        // A disc, as offsets per row.
        val reach = IntArray(2 * radius + 1) { dy ->
            val d = dy - radius
            kotlin.math.sqrt((radius * radius - d * d).toFloat() + 0.5f).toInt()
        }
        for (y in 0 until height) for (x in 0 until width) {
            if (!mask[y * width + x]) continue
            for (dy in -radius..radius) {
                val ny = y + dy
                if (ny < 0 || ny >= height) continue
                val r = reach[dy + radius]
                grown.fill(true, ny * width + max(0, x - r), ny * width + min(width - 1, x + r) + 1)
            }
        }
        return grown
    }

    private fun square(v: Int) = v * v

    companion object {
        /** The grid's long side, in cells. */
        const val MAX_SIZE = 512

        /** How far apart [peaks] are kept, as a share of the long side. */
        private const val SPREAD = 0.08f
    }
}

/** Samples [map] (row by row, [mapWidth] x [mapHeight], starting at [offset]) at (x, y), clamped to its edges. */
internal fun bilinear(map: FloatArray, offset: Int, mapWidth: Int, mapHeight: Int, x: Float, y: Float): Float {
    val cx = x.coerceIn(0f, mapWidth - 1f)
    val cy = y.coerceIn(0f, mapHeight - 1f)
    val x0 = cx.toInt()
    val y0 = cy.toInt()
    val x1 = min(x0 + 1, mapWidth - 1)
    val y1 = min(y0 + 1, mapHeight - 1)
    val fx = cx - x0
    val fy = cy - y0
    val top = map[offset + y0 * mapWidth + x0] * (1 - fx) + map[offset + y0 * mapWidth + x1] * fx
    val bottom = map[offset + y1 * mapWidth + x0] * (1 - fx) + map[offset + y1 * mapWidth + x1] * fx
    return top * (1 - fy) + bottom * fy
}

/** The regions of a mask: [label] gives each cell's region (1, 2, ...; 0 outside), [areas] each region's size in cells. */
internal class Regions(val label: IntArray, val areas: IntArray) {
    val count get() = areas.size - 1

    fun cells(id: Int) = BooleanArray(label.size) { label[it] == id }

    /** The cells of every region that shares a cell with [mask]. */
    fun touching(mask: BooleanArray): BooleanArray {
        val ids = BooleanArray(areas.size)
        for (i in label.indices) if (mask[i]) ids[label[i]] = true
        ids[0] = false
        return BooleanArray(label.size) { ids[label[it]] }
    }
}

internal infix fun BooleanArray.or(other: BooleanArray) = BooleanArray(size) { this[it] || other[it] }
internal infix fun BooleanArray.and(other: BooleanArray) = BooleanArray(size) { this[it] && other[it] }
internal infix fun BooleanArray.andNot(other: BooleanArray) = BooleanArray(size) { this[it] && !other[it] }
internal fun BooleanArray.orAssign(other: BooleanArray) {
    for (i in indices) if (other[i]) this[i] = true
}
internal fun BooleanArray.cellCount() = count { it }
internal fun BooleanArray.anyCell() = any { it }
internal fun FloatArray.atLeast(threshold: Float) = BooleanArray(size) { this[it] >= threshold }
