package org.tamodak.hisar.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.google.ai.edge.litert.Environment
import java.io.Closeable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

/**
 * The slow, precise mask for a picture the fast classifier ([BodyClassifier]) covered: the women
 * and girls in it, and body parts that are theirs, painted black following their outlines; the
 * rest of the picture is shown. Ported from the Photo Filter app (photo-filter, commit fc2d646),
 * without its bands for bare men, which Hisar does not cover.
 *
 * 1. [PersonDetector] finds the people.
 * 2. [ClipClassifier] decides, for each one, woman or man.
 * 3. [BodyPartMapper] maps people and body parts anywhere in the picture, including what the
 *    detector misses: hands or feet filling the picture, sketches, anime characters.
 * 4. [Segmenter] outlines each woman, each man, and each body part that no person found explains;
 *    CLIP decides, from the body around such a part, whether it is a woman's. [PoseEstimator]'s
 *    keypoints guide the women's outlines.
 *
 * When unsure it covers: people too small or too many to judge are covered, and when CLIP sees a
 * woman in the picture that none of the above located, it covers everything.
 *
 * Create, use and close an instance on one thread.
 */
internal class OutlineMasker private constructor(
    private val environment: Environment,
    private val detector: PersonDetector,
    private val clip: ClipClassifier,
    private val pose: PoseEstimator,
    private val parts: BodyPartMapper,
    private val segmenter: Segmenter,
) : Closeable {

    enum class Reason { WOMAN, BODY_PART, UNSURE, WHOLE_PICTURE }

    class Result(
        /** Why something was covered: one entry per person or body part. */
        val reasons: List<Reason>,
        val peopleFound: Int,
        /** Share of the photo, from 0 to 1, that is now black. */
        val coveredFraction: Float,
        /** All of it, from start to end. The next four steps run at the same time. */
        val totalTime: Duration,
        /** Finding the people. */
        val findTime: Duration,
        /** Telling women from men. */
        val classifyTime: Duration,
        /** Mapping body parts. */
        val mapTime: Duration,
        /** SAM reading the picture. */
        val readTime: Duration,
        /** Outlining and deciding what to cover. */
        val outlineTime: Duration,
        /** Painting it black. */
        val paintTime: Duration,
    )

    /**
     * Covers what needs covering in [photo], which must be a mutable bitmap, in place. Call it on a
     * dispatcher with threads to spare (Dispatchers.Default): mapping body parts and SAM's reading
     * of the picture don't depend on the people found, so they run alongside finding and judging
     * them, which on a Galaxy S20 FE saves about a third of the time. Not to be called again before
     * it returns: the models are not shared.
     */
    suspend fun filter(photo: Bitmap): Result = coroutineScope {
        val start = TimeSource.Monotonic.markNow()
        val grid = Grid(photo.width, photo.height)
        val mapping = async { measureTimedValue { parts.map(photo, grid) } }
        val reading = async { measureTimedValue { segmenter.read(photo) } }
        val people = measureTimedValue { detector.detect(photo) }
        val judged = measureTimedValue { judge(photo, people.value) }
        val (maps, mapTime) = mapping.await()
        val (picture, readTime) = reading.await()
        val (covered, outlineTime) = measureTimedValue { decide(photo, grid, judged.value, maps, picture) }
        val (coveredFraction, paintTime) = measureTimedValue { paint(photo, grid, covered.mask) }
        Result(
            covered.reasons, people.value.size, coveredFraction, start.elapsedNow(),
            people.duration, judged.duration, mapTime, readTime, outlineTime, paintTime,
        )
    }

    override fun close() {
        detector.close()
        clip.close()
        pose.close()
        parts.close()
        segmenter.close()
        environment.close()
    }

    private enum class Kind { WOMAN, UNSURE, MAN }

    private class Judged(val kind: Kind, val box: RectF)

    private class Covered(val mask: BooleanArray, val reasons: List<Reason>)

    private fun judge(photo: Bitmap, people: List<Person>): List<Judged> {
        val whole = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
        return people.sortedByDescending { it.box.width() * it.box.height() }.withIndex().mapNotNull { (rank, person) ->
            val box = RectF(person.box).apply {
                inset(-MARGIN_X * width(), -MARGIN_Y * height())
                intersect(whole)
            }
            // Past a handful of people, or below a few dozen pixels, there is neither the time nor
            // the detail to judge.
            if (rank >= MAX_JUDGED || person.box.height() < MIN_HEIGHT * photo.height) {
                return@mapNotNull Judged(Kind.UNSURE, box)
            }
            val look = clip.look(photo, box)
            when {
                look.noPerson >= NOT_A_PERSON -> null
                look.woman >= WOMAN -> Judged(Kind.WOMAN, box)
                else -> Judged(Kind.MAN, box)
            }
        }
    }

    private fun decide(
        photo: Bitmap,
        grid: Grid,
        judged: List<Judged>,
        maps: BodyPartMapper.Maps,
        picture: Segmenter.Picture,
    ): Covered {
        val reasons = mutableListOf<Reason>()
        val part = maps.part.atLeast(PART)
        val women = BooleanArray(grid.size)
        val bodyParts = BooleanArray(grid.size)
        /** Men's outlines: body parts found there are theirs. */
        val menOutlines = BooleanArray(grid.size)
        val menBoxes = BooleanArray(grid.size)

        // Men first, so that the women's outlines can leave them out.
        for (man in judged) {
            if (man.kind != Kind.MAN) continue
            menBoxes.orAssign(grid.cells(man.box))
            menOutlines.orAssign(segmenter.outline(picture, grid, man.box, listOf(center(man.box))))
        }

        for (person in judged) {
            if (person.kind != Kind.WOMAN && person.kind != Kind.UNSURE) continue
            val box = person.box
            val ownParts = part and grid.cells(box) andNot menOutlines
            val points = buildList {
                add(center(box))
                for (point in pose.keypoints(photo, box)) {
                    if (point.score >= VISIBLE_POINT && box.contains(point.position.x, point.position.y)) add(point.position)
                }
                addAll(grid.peaks(maps.part, ownParts, 3))
            }
            women.orAssign(segmenter.outline(picture, grid, box, points))
            women.orAssign(ownParts)
            reasons += if (person.kind == Kind.WOMAN) Reason.WOMAN else Reason.UNSURE
        }

        // Body parts that no person found explains: close-ups, drawings, people the detector missed.
        val regions = grid.regions(part andNot women)
        val bodies by lazy { grid.regions(maps.person.atLeast(PERSON)) }
        for (id in 1..regions.count) {
            val area = regions.areas[id]
            if (area < MIN_PART_AREA * grid.size) continue
            val region = regions.cells(id)
            // A man's own face, hands or feet.
            if ((region and menBoxes).cellCount() >= 0.5f * area) continue
            // CLIP judges the whole body the part belongs to, which shows more than the part alone.
            val body = region or (bodies.touching(region) andNot menBoxes)
            val bounds = grid.bounds(body) ?: continue
            if (clip.look(photo, contextAround(bounds, photo)).woman < WOMAN) continue
            // SAM is pointed at the part and at the rest of the body, so that it outlines all of it.
            val points = grid.peaks(maps.part, region, 3) + grid.peaks(maps.person, body andNot region, 2)
            val outline = segmenter.outline(picture, grid, bounds, points)
            // Plus whatever of the body CLIPSeg sees beyond SAM's outline: coarser, but never a hole.
            bodyParts.orAssign(outline or body)
            reasons += Reason.BODY_PART
        }

        var cover = women or (bodyParts andNot menOutlines)
        if (!women.anyCell() && !bodyParts.anyCell() &&
            clip.look(photo, RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())).womanAmongAll >= WOMAN_IN_PICTURE
        ) {
            cover = BooleanArray(grid.size) { true }
            reasons += Reason.WHOLE_PICTURE
        }
        val margin = max(1, (MARGIN * max(grid.width, grid.height)).roundToInt())
        return Covered(grid.dilate(cover, margin), reasons)
    }

    /** [bounds] with some context around it, at least 15 % of the photo's short side across. */
    private fun contextAround(bounds: RectF, photo: Bitmap): RectF {
        val minimum = 0.15f * min(photo.width, photo.height)
        val halfWidth = max(bounds.width(), minimum) * 1.3f / 2
        val halfHeight = max(bounds.height(), minimum) * 1.3f / 2
        return RectF(
            max(0f, bounds.centerX() - halfWidth),
            max(0f, bounds.centerY() - halfHeight),
            min(photo.width.toFloat(), bounds.centerX() + halfWidth),
            min(photo.height.toFloat(), bounds.centerY() + halfHeight),
        )
    }

    private fun center(box: RectF) = PointF(box.centerX(), box.centerY())

    /**
     * Paints the cells of [mask] black, scaled up to the photo with smooth edges, and returns the
     * share of the photo now black.
     */
    private fun paint(photo: Bitmap, grid: Grid, mask: BooleanArray): Float {
        val values = FloatArray(grid.size) { if (mask[it]) 1f else 0f }
        val width = photo.width
        val pixels = IntArray(width * photo.height)
        photo.getPixels(pixels, 0, width, 0, 0, width, photo.height)
        val gridX = FloatArray(width) { (it + 0.5f) * grid.width / width - 0.5f }
        var covered = 0
        for (y in 0 until photo.height) {
            val gridY = (y + 0.5f) * grid.height / photo.height - 0.5f
            for (x in 0 until width) {
                if (bilinear(values, 0, grid.width, grid.height, gridX[x], gridY) >= 0.5f) {
                    pixels[y * width + x] = Color.BLACK
                    covered++
                }
            }
        }
        photo.setPixels(pixels, 0, width, 0, 0, width, photo.height)
        return covered.toFloat() / pixels.size
    }

    private fun warmUp() {
        // First runs allocate memory; do them now rather than on the first photo.
        val blank = createBitmap(64, 64)
        val all = RectF(0f, 0f, 64f, 64f)
        val grid = Grid(64, 64)
        detector.detect(blank)
        clip.look(blank, all)
        pose.keypoints(blank, all)
        parts.map(blank, grid)
        segmenter.outline(segmenter.read(blank), grid, all, emptyList())
    }

    companion object {
        /** A person counts as a woman from this probability up: uncertain cases are covered. */
        private const val WOMAN = 0.3f

        /** The whole picture is covered if CLIP sees a woman in it this surely but none was found. */
        private const val WOMAN_IN_PICTURE = 0.5f

        /** A detection is ignored if CLIP is this sure it shows no person. */
        private const val NOT_A_PERSON = 0.8f

        /** At most this many people are judged (about 30 ms each on a Galaxy S20 FE); others are covered. */
        private const val MAX_JUDGED = 10

        /** People shorter than this share of the photo are covered without judging. */
        private const val MIN_HEIGHT = 0.04f

        /** A person's box is widened this much, as a share of its size, before judging and outlining. */
        private const val MARGIN_X = 0.08f
        private const val MARGIN_Y = 0.05f

        /** CLIPSeg's probability from which a cell counts as a body part. */
        private const val PART = 0.4f

        /** CLIPSeg's probability ("a person") from which a cell counts as part of someone's body. */
        private const val PERSON = 0.3f

        /** Body parts smaller than this share of the picture are left alone. */
        private const val MIN_PART_AREA = 0.003f

        /** Keypoints this sure guide SAM's outlines. */
        private const val VISIBLE_POINT = 0.3f

        /** Covers reach this much (a share of the long side) past the outlines. */
        private const val MARGIN = 0.004f

        /**
         * Loads the models, on the CPU. Takes a few seconds, so call it off the main thread.
         */
        fun create(context: Context): OutlineMasker {
            val opened = mutableListOf<Closeable>()
            fun <T : Closeable> open(model: T) = model.also { opened += it }
            try {
                val environment = Environment.create()
                opened += Closeable { environment.close() }
                val filter = OutlineMasker(
                    environment,
                    open(PersonDetector(context)),
                    open(ClipClassifier(context, environment)),
                    open(PoseEstimator(context)),
                    open(BodyPartMapper(context, environment)),
                    open(Segmenter(context, environment)),
                )
                filter.warmUp()
                return filter
            } catch (e: Throwable) {
                opened.asReversed().forEach { runCatching { it.close() } }
                throw e
            }
        }
    }
}
