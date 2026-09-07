package app.masahati.mobile

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class ScanPoint(val x: Double, val y: Double)

object OpenCvDocumentRectifier {
    @Volatile private var initialized = false

    fun isAvailable(): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (!initialized) {
                initialized = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
            }
        }
        return initialized
    }

    fun rectifyIfHelpful(source: Bitmap): Bitmap {
        if (!isAvailable() || source.width < 320 || source.height < 320) return source

        val src = Mat()
        val detection = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val closed = Mat()
        val hierarchy = Mat()
        val kernel = Mat()
        var perspective: Mat? = null
        var warped: Mat? = null

        try {
            Utils.bitmapToMat(source, src)

            val maxSide = max(source.width, source.height)
            val detectionScale = min(1.0, 1280.0 / maxSide.toDouble())
            if (detectionScale < 0.999) {
                Imgproc.resize(
                    src,
                    detection,
                    Size(source.width * detectionScale, source.height * detectionScale),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
                )
            } else {
                src.copyTo(detection)
            }

            Imgproc.cvtColor(detection, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            val median = approximateMedianLuminance(blurred)
            val low = (0.55 * median).coerceIn(35.0, 120.0)
            val high = (1.45 * median).coerceIn(90.0, 230.0)
            Imgproc.Canny(blurred, edges, low, high)

            val k = (min(detection.width(), detection.height()) / 180).coerceIn(3, 9)
            kernel.create(k, k, CvType.CV_8U)
            kernel.setTo(org.opencv.core.Scalar(1.0))
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                closed,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            val imageArea = detection.width().toDouble() * detection.height().toDouble()
            var best: Array<Point>? = null
            var bestScore = Double.NEGATIVE_INFINITY

            contours
                .asSequence()
                .filter { Imgproc.contourArea(it) >= imageArea * 0.18 }
                .sortedByDescending { Imgproc.contourArea(it) }
                .take(24)
                .forEach { contour ->
                    val contour2f = MatOfPoint2f(*contour.toArray())
                    val perimeter = Imgproc.arcLength(contour2f, true)
                    val approx2f = MatOfPoint2f()
                    try {
                        Imgproc.approxPolyDP(contour2f, approx2f, perimeter * 0.018, true)
                        val points = approx2f.toArray()
                        if (points.size != 4) return@forEach

                        val approx = MatOfPoint(*points)
                        try {
                            if (!Imgproc.isContourConvex(approx)) return@forEach
                            val area = abs(Imgproc.contourArea(approx))
                            val coverage = area / imageArea
                            val ordered = order(points.map { ScanPoint(it.x, it.y) })
                            val angleCos = maxCornerCosine(ordered)
                            val minSideRatio = minimumSideRatio(
                                ordered,
                                detection.width().toDouble(),
                                detection.height().toDouble()
                            )
                            if (!shouldRectify(coverage, angleCos, minSideRatio)) return@forEach

                            val borderPenalty = borderTouchPenalty(
                                ordered,
                                detection.width().toDouble(),
                                detection.height().toDouble()
                            )
                            val score =
                                coverage * 120.0 -
                                    angleCos * 35.0 -
                                    borderPenalty * 10.0 +
                                    minSideRatio * 8.0
                            if (score > bestScore) {
                                bestScore = score
                                best = ordered.map { Point(it.x, it.y) }.toTypedArray()
                            }
                        } finally {
                            approx.release()
                        }
                    } finally {
                        contour2f.release()
                        approx2f.release()
                    }
                }

            contours.forEach { it.release() }

            val candidate = best ?: return source
            val orderedDetection = candidate.map { ScanPoint(it.x, it.y) }
            val areaCoverage = polygonArea(orderedDetection) / imageArea
            if (areaCoverage >= 0.93 && borderTouchPenalty(
                    orderedDetection,
                    detection.width().toDouble(),
                    detection.height().toDouble()
                ) < 0.12
            ) {
                // ML Kit already produced a tight crop. Avoid a second destructive crop.
                return source
            }

            val invScale = 1.0 / detectionScale
            val full = orderedDetection.map { ScanPoint(it.x * invScale, it.y * invScale) }
            val tl = full[0]
            val tr = full[1]
            val br = full[2]
            val bl = full[3]

            val widthTop = distance(tl, tr)
            val widthBottom = distance(bl, br)
            val heightLeft = distance(tl, bl)
            val heightRight = distance(tr, br)
            var outWidth = max(widthTop, widthBottom).toInt().coerceAtLeast(120)
            var outHeight = max(heightLeft, heightRight).toInt().coerceAtLeast(120)

            val outMax = max(outWidth, outHeight)
            if (outMax > 2400) {
                val scale = 2400.0 / outMax.toDouble()
                outWidth = (outWidth * scale).toInt().coerceAtLeast(120)
                outHeight = (outHeight * scale).toInt().coerceAtLeast(120)
            }

            val aspect = outWidth.toDouble() / outHeight.toDouble()
            if (aspect !in 0.30..3.30) return source

            val srcPoints = MatOfPoint2f(
                Point(tl.x, tl.y),
                Point(tr.x, tr.y),
                Point(br.x, br.y),
                Point(bl.x, bl.y)
            )
            val dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point((outWidth - 1).toDouble(), 0.0),
                Point((outWidth - 1).toDouble(), (outHeight - 1).toDouble()),
                Point(0.0, (outHeight - 1).toDouble())
            )
            try {
                perspective = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
                warped = Mat()
                Imgproc.warpPerspective(
                    src,
                    warped,
                    perspective,
                    Size(outWidth.toDouble(), outHeight.toDouble()),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE
                )
            } finally {
                srcPoints.release()
                dstPoints.release()
            }

            val resultMat = warped ?: return source
            if (resultMat.empty()) return source
            val result = createBitmap(outWidth, outHeight)
            Utils.matToBitmap(resultMat, result)
            return result
        } catch (_: Throwable) {
            return source
        } finally {
            src.release()
            detection.release()
            gray.release()
            blurred.release()
            edges.release()
            closed.release()
            hierarchy.release()
            kernel.release()
            perspective?.release()
            warped?.release()
        }
    }

    internal fun shouldRectify(
        coverage: Double,
        maxCornerCosine: Double,
        minSideRatio: Double
    ): Boolean =
        coverage in 0.28..0.965 &&
            maxCornerCosine <= 0.42 &&
            minSideRatio >= 0.18

    internal fun order(points: List<ScanPoint>): List<ScanPoint> {
        require(points.size == 4)
        val sumSorted = points.sortedBy { it.x + it.y }
        val diffSorted = points.sortedBy { it.y - it.x }
        val tl = sumSorted.first()
        val br = sumSorted.last()
        val tr = diffSorted.first()
        val bl = diffSorted.last()
        val unique = listOf(tl, tr, br, bl)
        if (unique.distinct().size != 4) {
            val cx = points.sumOf { it.x } / 4.0
            val cy = points.sumOf { it.y } / 4.0
            val around = points.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            val topLeftIndex = around.indices.minBy { around[it].x + around[it].y }
            val rotated = List(4) { around[(topLeftIndex + it) % 4] }
            return if (signedArea(rotated) > 0) rotated else listOf(rotated[0], rotated[3], rotated[2], rotated[1])
        }
        return unique
    }

    private fun approximateMedianLuminance(gray: Mat): Double {
        val rows = gray.rows()
        val cols = gray.cols()
        if (rows <= 0 || cols <= 0) return 128.0
        val samples = ArrayList<Double>(400)
        val stepY = max(1, rows / 20)
        val stepX = max(1, cols / 20)
        var y = stepY / 2
        while (y < rows) {
            var x = stepX / 2
            while (x < cols) {
                gray.get(y, x)?.firstOrNull()?.let(samples::add)
                x += stepX
            }
            y += stepY
        }
        if (samples.isEmpty()) return 128.0
        samples.sort()
        return samples[samples.size / 2]
    }

    private fun maxCornerCosine(points: List<ScanPoint>): Double {
        var maxCos = 0.0
        for (i in 0 until 4) {
            val prev = points[(i + 3) % 4]
            val cur = points[i]
            val next = points[(i + 1) % 4]
            val ax = prev.x - cur.x
            val ay = prev.y - cur.y
            val bx = next.x - cur.x
            val by = next.y - cur.y
            val denom = hypot(ax, ay) * hypot(bx, by)
            if (denom <= 1e-9) return 1.0
            maxCos = max(maxCos, abs((ax * bx + ay * by) / denom))
        }
        return maxCos
    }

    private fun minimumSideRatio(points: List<ScanPoint>, width: Double, height: Double): Double {
        val minDimension = min(width, height).coerceAtLeast(1.0)
        return (0 until 4)
            .minOf { i -> distance(points[i], points[(i + 1) % 4]) / minDimension }
    }

    private fun borderTouchPenalty(points: List<ScanPoint>, width: Double, height: Double): Double {
        val marginX = width * 0.025
        val marginY = height * 0.025
        var touches = 0
        points.forEach {
            if (it.x <= marginX || it.x >= width - marginX || it.y <= marginY || it.y >= height - marginY) {
                touches++
            }
        }
        return touches / 4.0
    }

    private fun polygonArea(points: List<ScanPoint>): Double = abs(signedArea(points))

    private fun signedArea(points: List<ScanPoint>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2.0
    }

    private fun distance(a: ScanPoint, b: ScanPoint): Double = hypot(a.x - b.x, a.y - b.y)
}
