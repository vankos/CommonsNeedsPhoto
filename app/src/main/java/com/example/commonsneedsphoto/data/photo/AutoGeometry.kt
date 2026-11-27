package com.example.commonsneedsphoto.data.photo

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Locale

enum class UprightMode {
    /** Rotation only (auto straighten). */
    LEVEL,

    /** Roll + pitch: converging verticals become parallel. */
    VERTICAL,

    /** Roll + pitch + yaw (+ shear): verticals and horizontals. */
    FULL,
}

/**
 * Frame-preserving automatic geometry, modelled as a virtual camera rotation:
 *
 *     H = S · K · R · K⁻¹   (centred pixel coordinates, origin = image centre, y down)
 *     R = Ry(yaw) · Rx(pitch) · Rz(roll),  K = diag(f, f, 1),  S = [[1,0,0],[-shear,1,0],[0,0,1]]
 *
 * Stored as angles, so the same value renders the 1280 px preview and the
 * 4096 px output. roll > 0 rotates the picture clockwise on screen, the same
 * sign as android.graphics.Matrix.postRotate(degrees).
 */
data class AutoGeometry(
    val mode: UprightMode,
    val roll: Double,
    val pitch: Double,
    val yaw: Double,
    val shear: Double,
    val focalRatio: Double,
) {
    fun centredMatrix(width: Int, height: Int): DoubleArray {
        val f = focalRatio * max(width, height)
        val k = doubleArrayOf(f, 0.0, 0.0, 0.0, f, 0.0, 0.0, 0.0, 1.0)
        val kInv = doubleArrayOf(1 / f, 0.0, 0.0, 0.0, 1 / f, 0.0, 0.0, 0.0, 1.0)
        val s = doubleArrayOf(1.0, 0.0, 0.0, -shear, 1.0, 0.0, 0.0, 0.0, 1.0)
        return Mat3.mul(s, Mat3.mul(k, Mat3.mul(Mat3.rotation(roll, pitch, yaw), kInv)))
    }

    fun layout(width: Int, height: Int): UprightLayout? =
        UprightFraming.layout(centredMatrix(width, height), width, height)
}

/** Where the corrected photo lands for one bitmap size. */
class UprightLayout(
    /** Source pixel to canvas pixel, row-major, for android.graphics.Matrix.setValues. */
    val canvasMatrix: DoubleArray,
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** Outline of the warped photo on the canvas: TL, TR, BR, BL as x, y pairs. */
    val frameQuad: DoubleArray,
    /** Largest rectangle with the photo's aspect ratio and no empty corners, normalised to the canvas. */
    val constrainedCrop: CropRect,
    /** Fraction of the source pixels that stay inside [constrainedCrop]. */
    val sourceKept: Double,
    /** Photo area / canvas area. */
    val cover: Double,
    /** Largest / smallest local scale of the warp, corners and centre. */
    val anisotropy: Double,
) {
    /** Source pixel to output pixel when the output is [crop] of the canvas. */
    fun matrixFor(crop: CropRect): DoubleArray {
        val t = doubleArrayOf(
            1.0, 0.0, -crop.left.toDouble() * canvasWidth,
            0.0, 1.0, -crop.top.toDouble() * canvasHeight,
            0.0, 0.0, 1.0,
        )
        return Mat3.mul(t, canvasMatrix)
    }

    fun outputSize(crop: CropRect): Pair<Int, Int> = Pair(
        (crop.width * canvasWidth).toInt().coerceAtLeast(1),
        (crop.height * canvasHeight).toInt().coerceAtLeast(1),
    )
}

sealed interface UprightResult {
    data class Corrected(val geometry: AutoGeometry, val layout: UprightLayout, val log: List<String>) : UprightResult
    data class AlreadyStraight(val log: List<String>) : UprightResult
    data class NotFound(val reason: Reason, val log: List<String>) : UprightResult

    enum class Reason { NO_LINES, TOO_DISTORTED }
}

/** Row-major 3x3 matrices and 3-vectors. */
internal object Mat3 {
    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        }
        return r
    }

    fun mulVec(m: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    fun rotZ(t: Double) = doubleArrayOf(cos(t), -sin(t), 0.0, sin(t), cos(t), 0.0, 0.0, 0.0, 1.0)
    fun rotX(t: Double) = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cos(t), -sin(t), 0.0, sin(t), cos(t))
    fun rotY(t: Double) = doubleArrayOf(cos(t), 0.0, sin(t), 0.0, 1.0, 0.0, -sin(t), 0.0, cos(t))
    fun rotation(roll: Double, pitch: Double, yaw: Double) = mul(rotY(yaw), mul(rotX(pitch), rotZ(roll)))

    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    fun norm(a: DoubleArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])

    fun inverse(a: DoubleArray): DoubleArray {
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        return doubleArrayOf(
            c00 / det, (a[2] * a[7] - a[1] * a[8]) / det, (a[1] * a[5] - a[2] * a[4]) / det,
            c01 / det, (a[0] * a[8] - a[2] * a[6]) / det, (a[2] * a[3] - a[0] * a[5]) / det,
            c02 / det, (a[1] * a[6] - a[0] * a[7]) / det, (a[0] * a[4] - a[1] * a[3]) / det,
        )
    }

    /** Maps (x, y); returns null when the point crosses the horizon (w <= 0). */
    fun map(m: DoubleArray, x: Double, y: Double): DoubleArray? {
        val w = m[6] * x + m[7] * y + m[8]
        if (w <= 1e-9) return null
        return doubleArrayOf((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    /** Eigenvector of the smallest eigenvalue of a symmetric matrix (cyclic Jacobi). */
    fun smallestEigenvector(sym: DoubleArray): DoubleArray {
        val a = sym.copyOf()
        val v = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(50) {
            val off = a[1] * a[1] + a[2] * a[2] + a[5] * a[5]
            if (off < 1e-30) return@repeat
            for ((p, q) in arrayOf(0 to 1, 0 to 2, 1 to 2)) {
                val apq = a[p * 3 + q]
                if (abs(apq) < 1e-300) continue
                val theta = (a[q * 3 + q] - a[p * 3 + p]) / (2 * apq)
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until 3) {
                    val akp = a[k * 3 + p]
                    val akq = a[k * 3 + q]
                    a[k * 3 + p] = c * akp - s * akq
                    a[k * 3 + q] = s * akp + c * akq
                }
                for (k in 0 until 3) {
                    val apk = a[p * 3 + k]
                    val aqk = a[q * 3 + k]
                    a[p * 3 + k] = c * apk - s * aqk
                    a[q * 3 + k] = s * apk + c * aqk
                }
                for (k in 0 until 3) {
                    val vkp = v[k * 3 + p]
                    val vkq = v[k * 3 + q]
                    v[k * 3 + p] = c * vkp - s * vkq
                    v[k * 3 + q] = s * vkp + c * vkq
                }
            }
        }
        var best = 0
        for (i in 1 until 3) if (a[i * 4] < a[best * 4]) best = i
        return doubleArrayOf(v[best], v[3 + best], v[6 + best])
    }
}

object UprightFraming {

    private const val KEEP_SAMPLES = 100
    private const val MAX_CANVAS_GROWTH = 1.6

    fun layout(hc: DoubleArray, width: Int, height: Int): UprightLayout? {
        val w = width.toDouble()
        val h = height.toDouble()
        val corners = doubleArrayOf(-w / 2, -h / 2, w / 2, -h / 2, w / 2, h / 2, -w / 2, h / 2)
        val quad = DoubleArray(8)
        for (i in 0 until 4) {
            val p = Mat3.map(hc, corners[2 * i], corners[2 * i + 1]) ?: return null
            quad[2 * i] = p[0]
            quad[2 * i + 1] = p[1]
        }
        val area = polygonArea(quad)
        if (area <= 1e-9) return null
        // Area-preserving scale: the corrected photo has as many pixels as the source.
        val scale = sqrt(w * h / area)
        for (i in 0 until 8) quad[i] *= scale
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (i in 0 until 4) {
            minX = min(minX, quad[2 * i]); maxX = max(maxX, quad[2 * i])
            minY = min(minY, quad[2 * i + 1]); maxY = max(maxY, quad[2 * i + 1])
        }
        val canvasW = ceil(maxX - minX - 1e-6).toInt()
        val canvasH = ceil(maxY - minY - 1e-6).toInt()
        if (canvasW > MAX_CANVAS_GROWTH * w || canvasH > MAX_CANVAS_GROWTH * h) return null
        val rect = largestAspectRectInQuad(quad, w / h) ?: return null

        val toCanvas = doubleArrayOf(scale, 0.0, -minX, 0.0, scale, -minY, 0.0, 0.0, 1.0)
        val fromSource = doubleArrayOf(1.0, 0.0, -w / 2, 0.0, 1.0, -h / 2, 0.0, 0.0, 1.0)
        val canvasMatrix = Mat3.mul(toCanvas, Mat3.mul(hc, fromSource))
        for (i in 0 until 4) {
            quad[2 * i] -= minX
            quad[2 * i + 1] -= minY
        }
        val crop = CropRect(
            ((rect[0] - minX) / canvasW).toFloat().coerceIn(0f, 1f),
            ((rect[1] - minY) / canvasH).toFloat().coerceIn(0f, 1f),
            ((rect[2] - minX) / canvasW).toFloat().coerceIn(0f, 1f),
            ((rect[3] - minY) / canvasH).toFloat().coerceIn(0f, 1f),
        )
        var inside = 0
        for (iy in 0 until KEEP_SAMPLES) for (ix in 0 until KEEP_SAMPLES) {
            val p = Mat3.map(canvasMatrix, (ix + 0.5) / KEEP_SAMPLES * w, (iy + 0.5) / KEEP_SAMPLES * h) ?: continue
            if (p[0] >= rect[0] - minX && p[0] <= rect[2] - minX && p[1] >= rect[1] - minY && p[1] <= rect[3] - minY) {
                inside++
            }
        }
        val scales = DoubleArray(5)
        val probes = doubleArrayOf(-w / 2, -h / 2, w / 2, -h / 2, w / 2, h / 2, -w / 2, h / 2, 0.0, 0.0)
        for (i in 0 until 5) scales[i] = localScale(hc, probes[2 * i], probes[2 * i + 1])
        return UprightLayout(
            canvasMatrix = canvasMatrix,
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            frameQuad = quad,
            constrainedCrop = crop,
            sourceKept = inside.toDouble() / (KEEP_SAMPLES * KEEP_SAMPLES),
            cover = w * h / ((maxX - minX) * (maxY - minY)),
            anisotropy = scales.max() / scales.min(),
        )
    }

    /**
     * Largest axis-aligned rectangle with width / height = [aspect] inside a
     * convex quad (x, y pairs). Linear programme in (cx, cy, k), half sizes
     * (k·aspect/2, k/2); the optimum is a vertex where 3 edge constraints are
     * tight, so all 4 triples are enumerated. Ties (a whole optimal segment)
     * are averaged, which centres the rectangle. Returns left, top, right, bottom.
     */
    fun largestAspectRectInQuad(quad: DoubleArray, aspect: Double): DoubleArray? {
        var area2 = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area2 += quad[2 * i] * quad[2 * j + 1] - quad[2 * j] * quad[2 * i + 1]
        }
        val sign = if (area2 > 0) 1.0 else -1.0
        val cons = Array(4) { i ->
            val j = (i + 1) % 4
            val ex = quad[2 * j] - quad[2 * i]
            val ey = quad[2 * j + 1] - quad[2 * i + 1]
            val nx = ey * sign
            val ny = -ex * sign
            doubleArrayOf(nx, ny, (abs(nx) * aspect + abs(ny)) / 2, nx * quad[2 * i] + ny * quad[2 * i + 1])
        }
        val solutions = ArrayList<DoubleArray>()
        for (skip in 0 until 4) {
            val t = (0 until 4).filter { it != skip }.map { cons[it] }
            val det = det3(t[0][0], t[0][1], t[0][2], t[1][0], t[1][1], t[1][2], t[2][0], t[2][1], t[2][2])
            if (abs(det) < 1e-12) continue
            val cx = det3(t[0][3], t[0][1], t[0][2], t[1][3], t[1][1], t[1][2], t[2][3], t[2][1], t[2][2]) / det
            val cy = det3(t[0][0], t[0][3], t[0][2], t[1][0], t[1][3], t[1][2], t[2][0], t[2][3], t[2][2]) / det
            val k = det3(t[0][0], t[0][1], t[0][3], t[1][0], t[1][1], t[1][3], t[2][0], t[2][1], t[2][3]) / det
            if (k <= 0) continue
            val feasible = cons.all { c -> c[0] * cx + c[1] * cy + c[2] * k <= c[3] + 1e-6 * (abs(c[3]) + 1) }
            if (feasible) solutions.add(doubleArrayOf(k, cx, cy))
        }
        if (solutions.isEmpty()) return null
        val kMax = solutions.maxOf { it[0] }
        val tied = solutions.filter { it[0] >= kMax * (1 - 1e-6) }
        val cx = tied.sumOf { it[1] } / tied.size
        val cy = tied.sumOf { it[2] } / tied.size
        return doubleArrayOf(cx - kMax * aspect / 2, cy - kMax / 2, cx + kMax * aspect / 2, cy + kMax / 2)
    }

    private fun det3(
        a: Double, b: Double, c: Double,
        d: Double, e: Double, f: Double,
        g: Double, h: Double, i: Double,
    ) = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)

    private fun polygonArea(q: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
        }
        return abs(s) / 2
    }

    /** sqrt(|det J|) of the projective map at (x, y). */
    private fun localScale(m: DoubleArray, x: Double, y: Double): Double {
        val z = m[6] * x + m[7] * y + m[8]
        val u = (m[0] * x + m[1] * y + m[2]) / z
        val v = (m[3] * x + m[4] * y + m[5]) / z
        val j00 = (m[0] - u * m[6]) / z
        val j01 = (m[1] - u * m[7]) / z
        val j10 = (m[3] - v * m[6]) / z
        val j11 = (m[4] - v * m[7]) / z
        return sqrt(abs(j00 * j11 - j01 * j10))
    }
}

/**
 * Line segments -> vanishing directions -> camera rotation -> the mildest
 * correction that passes the quality gates (FULL, VERTICAL, damped, LEVEL).
 */
object UprightEstimator {

    const val DEFAULT_FOCAL_RATIO = 0.70
    private val FAMILY_BAND = Math.toRadians(30.0)
    private val INLIER = Math.toRadians(1.5)
    private const val MAX_HYPOTHESES = 60
    private val MAX_ROLL = Math.toRadians(20.0)
    private val MAX_PITCH = Math.toRadians(20.0)
    private val MAX_YAW = Math.toRadians(20.0)
    private val MAX_DETECT_TILT = Math.toRadians(45.0)
    private val MIN_PITCH = Math.toRadians(1.5)
    private val MIN_YAW = Math.toRadians(5.0)
    private val MIN_ROLL = Math.toRadians(0.2)
    private const val FAMILY_MIN_INLIERS = 3
    private const val FAMILY_MIN_LENGTH = 0.60
    private const val FAMILY_MIN_SPREAD = 0.15
    private const val YAW_MIN_INLIERS = 4
    private const val YAW_MIN_LENGTH = 1.0
    private const val HORIZON_MIN_LENGTH = 0.45
    private val ROLL_CONFLICT = Math.toRadians(8.0)
    private val ROLL_AGREE = Math.toRadians(1.0)
    private val MAX_SHEAR = Math.toRadians(3.0)
    private const val MAX_ANISOTROPY = 3.0
    private const val MIN_COVER_REL = 0.75
    private const val MIN_KEEP_REL = 0.70
    private val MAX_H_RESIDUAL = Math.toRadians(1.5)
    private const val MIN_IMPROVEMENT = 0.70
    private const val MIN_IMPROVEMENT_LEVEL = 0.85
    private val DAMPING = doubleArrayOf(1.0, 0.75, 0.5)

    private class Family(val direction: DoubleArray, val inliers: List<LineSegment>) {
        val length: Double = inliers.sumOf { it.length }
    }

    private class Candidate(
        val name: String,
        val mode: UprightMode,
        val roll: Double,
        val pitch: Double,
        val yaw: Double,
        val fixShear: Boolean,
        val damped: Boolean,
    )

    fun estimate(
        segments: List<LineSegment>,
        width: Int,
        height: Int,
        focalRatio: Double = DEFAULT_FOCAL_RATIO,
    ): UprightResult {
        val log = ArrayList<String>()
        val longSide = max(width, height).toDouble()
        val f = focalRatio * longSide
        val cx = width / 2.0
        val cy = height / 2.0
        val vertical = segments.filter { abs(it.angleFromVertical()) <= FAMILY_BAND }
        val horizontal = segments.filter {
            abs(it.angleFromVertical()) > FAMILY_BAND && abs(it.angleFromHorizontal()) <= FAMILY_BAND
        }
        // Accept vertical VPs up to 45° of tilt: beyond that the VP can sit inside the
        // frame (|f·cot p| < h/2 at p ≈ 54°) and the correction is not meaningful.
        val v = vanishingDirection(vertical, cx, cy, f) { d ->
            val dd = if (d[1] > 0) d else negate(d)
            abs(atan2(dd[0], dd[1])) <= MAX_ROLL * 1.3 && abs(atan2(-dd[2], hypot(dd[0], dd[1]))) <= MAX_DETECT_TILT
        }
        // Same 45° limit for horizontals: a VP inside the frame (corridor depth lines) is not a "horizontal".
        val hz = vanishingDirection(horizontal, cx, cy, f) { d ->
            val dd = if (d[0] > 0) d else negate(d)
            abs(atan2(dd[1], dd[0])) <= MAX_ROLL * 1.3 && abs(atan2(dd[2], hypot(dd[0], dd[1]))) <= MAX_DETECT_TILT
        }
        val vOk = v != null && reliable(v, true, width, longSide, FAMILY_MIN_INLIERS, FAMILY_MIN_LENGTH)
        val hOk = hz != null && reliable(hz, false, height, longSide, FAMILY_MIN_INLIERS, FAMILY_MIN_LENGTH)
        val hYawOk = hz != null && reliable(hz, false, height, longSide, YAW_MIN_INLIERS, YAW_MIN_LENGTH)
        log += "segments ${segments.size} (V ${vertical.size}, H ${horizontal.size}); " +
            "V inliers ${v?.inliers?.size ?: 0} ok=$vOk, H inliers ${hz?.inliers?.size ?: 0} ok=$hOk yawOk=$hYawOk"
        log += "V ${familyInfo(v, true, width, longSide)}; H ${familyInfo(hz, false, height, longSide)}"

        var rollV: Double? = null
        var rollH: Double? = null
        if (vOk) {
            val dv = upward(v!!.direction)
            rollV = atan2(dv[0], dv[1])
        }
        if (hOk) {
            rollH = -dominantAngle(hz!!.inliers, horizontal = true)!!
        } else if (horizontal.isNotEmpty()) {
            val longest = horizontal.maxBy { it.length }
            if (longest.length >= HORIZON_MIN_LENGTH * width) {
                rollH = -longest.angleFromHorizontal()
                log += "single horizon line"
            }
        }
        log += "roll from verticals ${deg(rollV)}, from horizontals ${deg(rollH)}"

        var trustV = vOk
        if (vOk && rollH != null && abs(rollV!! - rollH) > ROLL_CONFLICT) {
            val hLength = if (hOk) hz!!.length else 0.0
            if (hLength > v!!.length) {
                trustV = false
                log += "verticals disagree with the horizon: ignored"
            }
        }

        val candidates = ArrayList<Candidate>()
        // True when the photo needs more than a rotation; then "nothing to rotate" must not read as "already straight".
        var perspectiveWanted = false
        if (!vOk) {
            // Verticals that converge past the detection limit (VP near or inside the frame):
            // the photo is far from upright, so "already straight" would be wrong.
            val steep = vanishingDirection(vertical, cx, cy, f) { d ->
                val dd = if (d[1] > 0) d else negate(d)
                abs(atan2(dd[0], dd[1])) <= MAX_ROLL * 1.3
            }
            if (steep != null && reliable(steep, true, width, longSide, FAMILY_MIN_INLIERS, FAMILY_MIN_LENGTH)) {
                perspectiveWanted = true
                log += "vertical VP beyond ${Math.toDegrees(MAX_DETECT_TILT).toInt()}° of tilt"
            }
        }
        if (trustV) {
            val angles = anglesFromDirections(v!!.direction, if (hOk) hz!!.direction else null)
            val (roll, pitch, yaw) = Triple(angles[0], angles[1], angles[2])
            log += "raw roll ${deg(roll)} pitch ${deg(pitch)} yaw ${deg(yaw)}; " +
                "Vv ${vpText(v.direction, f, cx, cy)}; Vh ${if (hOk) vpText(hz!!.direction, f, cx, cy) else "-"}"
            val usePitch = abs(pitch) >= MIN_PITCH
            val useYaw = hYawOk && abs(yaw) >= MIN_YAW && abs(yaw) <= MAX_YAW
            perspectiveWanted = perspectiveWanted || usePitch || (hYawOk && abs(yaw) >= MIN_YAW)
            if (usePitch || useYaw) {
                for (k in DAMPING) {
                    if (abs(pitch * k) > MAX_PITCH) continue
                    val suffix = if (k == 1.0) "" else "@$k"
                    val p = if (usePitch) pitch * k else 0.0
                    if (useYaw) {
                        candidates += Candidate("FULL$suffix", UprightMode.FULL, roll, p, yaw * k, hOk, k < 1.0)
                    }
                    if (usePitch) {
                        candidates += Candidate("VERTICAL$suffix", UprightMode.VERTICAL, roll, p, 0.0, hOk, k < 1.0)
                    }
                }
            }
            var levelRoll = roll
            if (rollH != null) {
                val wv = v.length
                val wh = if (hOk) hz!!.length else width.toDouble()
                if (abs(rollH - roll) <= ROLL_AGREE) {
                    levelRoll = (roll * wv + rollH * wh) / (wv + wh)
                } else if (wh > wv) {
                    levelRoll = rollH
                }
            }
            candidates += Candidate("LEVEL", UprightMode.LEVEL, levelRoll, 0.0, 0.0, false, false)
        } else if (rollH != null) {
            candidates += Candidate("LEVEL", UprightMode.LEVEL, rollH, 0.0, 0.0, false, false)
        }
        if (candidates.isEmpty()) {
            val reason = if (perspectiveWanted) UprightResult.Reason.TOO_DISTORTED else UprightResult.Reason.NO_LINES
            return UprightResult.NotFound(reason, log)
        }

        val levelRoll = abs(candidates.last().roll)
        val ratio = max(width.toDouble() / height, height.toDouble() / width)
        val levelLinear = 1 / (cos(levelRoll) + ratio * sin(levelRoll))
        val levelKeep = levelLinear * levelLinear
        val levelCover = 1 / ((cos(levelRoll) + width.toDouble() / height * sin(levelRoll)) *
            (cos(levelRoll) + height.toDouble() / width * sin(levelRoll)))
        val vInl = if (vOk) v!!.inliers else emptyList()
        val hInl = if (hOk) hz!!.inliers else emptyList()
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val before = deviationScore(identity, vInl, hInl, cx, cy)

        for (c in candidates) {
            if (abs(c.roll) > MAX_ROLL || abs(c.pitch) > MAX_PITCH) {
                log += "${c.name}: angles out of range"
                continue
            }
            var shear = 0.0
            if (c.fixShear && hOk) {
                var d2 = Mat3.mulVec(Mat3.rotation(c.roll, c.pitch, c.yaw), hz!!.direction)
                if (d2[0] < 0) d2 = negate(d2)
                shear = d2[1] / d2[0]
                if (abs(atan(shear)) > MAX_SHEAR) shear = 0.0
            }
            val geometry = AutoGeometry(c.mode, c.roll, c.pitch, c.yaw, shear, focalRatio)
            val hc = geometry.centredMatrix(width, height)
            val layout = UprightFraming.layout(hc, width, height)
            if (layout == null) {
                log += "${c.name}: degenerate"
                continue
            }
            val after = deviationScore(hc, vInl, hInl, cx, cy)
            val hResidual = if (hOk && c.mode != UprightMode.LEVEL) {
                abs(dominantAngle(hInl.map { mapSegment(hc, it, cx, cy) }, horizontal = true) ?: 0.0)
            } else {
                0.0
            }
            val ok = if (c.mode == UprightMode.LEVEL) {
                after <= MIN_IMPROVEMENT_LEVEL * before
            } else {
                after <= MIN_IMPROVEMENT * before &&
                    layout.anisotropy <= MAX_ANISOTROPY &&
                    layout.cover >= MIN_COVER_REL * levelCover &&
                    layout.sourceKept >= MIN_KEEP_REL * levelKeep &&
                    hResidual <= MAX_H_RESIDUAL * (if (c.damped) 2.0 else 1.0)
            }
            log += String.format(Locale.ROOT, 
                "%s: r %.2f p %.2f y %.2f sh %.2f | keep %.3f (level %.3f) cover %.3f aniso %.2f hres %.2f dev %.2f->%.2f %s",
                c.name, Math.toDegrees(c.roll), Math.toDegrees(c.pitch), Math.toDegrees(c.yaw),
                Math.toDegrees(atan(shear)), layout.sourceKept, levelKeep, layout.cover, layout.anisotropy,
                Math.toDegrees(hResidual), before, after, if (ok) "OK" else "rejected",
            )
            if (c.mode == UprightMode.LEVEL && abs(c.roll) < MIN_ROLL) {
                // Nothing to rotate. If a perspective fix was wanted but failed the gates, say so.
                return if (perspectiveWanted) {
                    UprightResult.NotFound(UprightResult.Reason.TOO_DISTORTED, log)
                } else {
                    UprightResult.AlreadyStraight(log)
                }
            }
            if (ok) return UprightResult.Corrected(geometry, layout, log)
        }
        return UprightResult.NotFound(UprightResult.Reason.TOO_DISTORTED, log)
    }

    // ---------------------------------------------------------------- VP estimation

    private fun vanishingDirection(
        segments: List<LineSegment>,
        cx: Double,
        cy: Double,
        f: Double,
        accept: (DoubleArray) -> Boolean,
    ): Family? {
        if (segments.size < 2) return null
        val sorted = segments.sortedByDescending { it.length }
        val normals = sorted.map { interpretationNormal(it, cx, cy, f) }
        val hypotheses = min(sorted.size, MAX_HYPOTHESES)
        var best: DoubleArray? = null
        var bestScore = 0.0
        for (i in 0 until hypotheses) for (j in i + 1 until hypotheses) {
            val d = Mat3.cross(normals[i], normals[j])
            val n = Mat3.norm(d)
            if (n < 1e-9) continue
            for (k in 0 until 3) d[k] /= n
            if (!accept(d)) continue
            var score = 0.0
            for (s in sorted) {
                val e = angleError(s, d, cx, cy, f)
                if (e < INLIER) score += s.length * (1 - (e / INLIER) * (e / INLIER))
            }
            if (score > bestScore) {
                bestScore = score
                best = d
            }
        }
        var d = best ?: return null
        repeat(3) {
            val idx = sorted.indices.filter { angleError(sorted[it], d, cx, cy, f) < INLIER }
            if (idx.size < 2) return@repeat
            val m = DoubleArray(9)
            for (k in idx) {
                val n = normals[k]
                val w = sorted[k].length
                for (a in 0 until 3) for (b in 0 until 3) m[a * 3 + b] += w * n[a] * n[b]
            }
            val e = Mat3.smallestEigenvector(m)
            d = if (e[0] * d[0] + e[1] * d[1] + e[2] * d[2] >= 0) e else negate(e)
        }
        return Family(d, sorted.filter { angleError(it, d, cx, cy, f) < INLIER })
    }

    private fun interpretationNormal(s: LineSegment, cx: Double, cy: Double, f: Double): DoubleArray {
        val n = Mat3.cross(
            doubleArrayOf((s.x1 - cx) / f, (s.y1 - cy) / f, 1.0),
            doubleArrayOf((s.x2 - cx) / f, (s.y2 - cy) / f, 1.0),
        )
        val len = Mat3.norm(n)
        return doubleArrayOf(n[0] / len, n[1] / len, n[2] / len)
    }

    /** Image angle between a segment and the line from its midpoint to the vanishing point. */
    private fun angleError(s: LineSegment, d: DoubleArray, cx: Double, cy: Double, f: Double): Double {
        val ux = s.x2 - s.x1
        val uy = s.y2 - s.y1
        val tx: Double
        val ty: Double
        if (abs(d[2]) > 1e-9) {
            tx = f * d[0] / d[2] + cx - s.midX
            ty = f * d[1] / d[2] + cy - s.midY
        } else {
            tx = d[0]
            ty = d[1]
        }
        return atan2(abs(ux * ty - uy * tx), abs(ux * tx + uy * ty))
    }

    private fun reliable(fam: Family, vertical: Boolean, extent: Int, longSide: Double, minCount: Int, minLength: Double): Boolean {
        if (fam.inliers.size < minCount || fam.length < minLength * longSide) return false
        val mids = fam.inliers.map { if (vertical) it.midX else it.midY }
        return (mids.max() - mids.min()) / extent >= FAMILY_MIN_SPREAD
    }

    private fun familyInfo(fam: Family?, vertical: Boolean, extent: Int, longSide: Double): String {
        if (fam == null) return "none"
        val mids = fam.inliers.map { if (vertical) it.midX else it.midY }
        val spread = if (mids.isEmpty()) 0.0 else (mids.max() - mids.min()) / extent
        return String.format(
            Locale.ROOT,
            "inliers=%d length=%.2fL spread=%.2f",
            fam.inliers.size,
            fam.length / longSide,
            spread,
        )
    }

    // ----------------------------------------------------------------- angles

    /** roll, pitch, yaw that send the vertical (and horizontal) vanishing direction to infinity. */
    internal fun anglesFromDirections(dvIn: DoubleArray, dh: DoubleArray?): DoubleArray {
        val dv = upward(dvIn)
        val roll = atan2(dv[0], dv[1])
        val d1 = Mat3.mulVec(Mat3.rotZ(roll), dv)
        val pitch = atan2(-d1[2], d1[1])
        var yaw = 0.0
        if (dh != null) {
            var d2 = Mat3.mulVec(Mat3.rotX(pitch), Mat3.mulVec(Mat3.rotZ(roll), dh))
            if (d2[0] < 0) d2 = negate(d2)
            yaw = atan2(d2[2], d2[0])
        }
        return doubleArrayOf(roll, pitch, yaw)
    }

    /**
     * Length-weighted mode of segment angles: 0.1° grid over ±15°, triangular
     * ±0.75° kernel, refined by the weighted mean inside the window. Radians.
     */
    internal fun dominantAngle(segments: List<LineSegment>, horizontal: Boolean): Double? {
        if (segments.isEmpty()) return null
        val a = DoubleArray(segments.size) {
            Math.toDegrees(if (horizontal) segments[it].angleFromHorizontal() else segments[it].angleFromVertical())
        }
        val window = 0.75
        var best = 0.0
        var bestScore = -1.0
        for (step in 0..300) {
            val c = -15.0 + step * 0.1
            var score = 0.0
            for (i in a.indices) score += max(0.0, 1 - abs(a[i] - c) / window) * segments[i].length
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        var sum = 0.0
        var weight = 0.0
        for (i in a.indices) {
            if (abs(a[i] - best) <= window) {
                sum += a[i] * segments[i].length
                weight += segments[i].length
            }
        }
        return Math.toRadians(if (weight > 0) sum / weight else best)
    }

    /** Length-weighted mean |deviation| in degrees of the inliers after [hc]: the "do no harm" check. */
    private fun deviationScore(hc: DoubleArray, v: List<LineSegment>, h: List<LineSegment>, cx: Double, cy: Double): Double {
        var total = 0.0
        var weight = 0.0
        for (s in v) {
            total += abs(Math.toDegrees(mapSegment(hc, s, cx, cy).angleFromVertical())) * s.length
            weight += s.length
        }
        for (s in h) {
            total += abs(Math.toDegrees(mapSegment(hc, s, cx, cy).angleFromHorizontal())) * s.length
            weight += s.length
        }
        return if (weight > 0) total / weight else 0.0
    }

    private fun mapSegment(hc: DoubleArray, s: LineSegment, cx: Double, cy: Double): LineSegment {
        val a = Mat3.map(hc, s.x1 - cx, s.y1 - cy) ?: doubleArrayOf(0.0, 0.0)
        val b = Mat3.map(hc, s.x2 - cx, s.y2 - cy) ?: doubleArrayOf(0.0, 0.0)
        return LineSegment(a[0], a[1], b[0], b[1], s.length)
    }

    private fun upward(d: DoubleArray) = if (d[1] > 0) d else negate(d)
    private fun negate(d: DoubleArray) = doubleArrayOf(-d[0], -d[1], -d[2])
    private fun deg(r: Double?) = if (r == null) "-" else String.format(Locale.ROOT, "%.3f", Math.toDegrees(r))
    private fun vpText(d: DoubleArray, f: Double, cx: Double, cy: Double): String {
        val dd = if (d[2] < 0) negate(d) else d
        if (abs(dd[2]) < 1e-12) return "infinity"
        return String.format(Locale.ROOT, "(%.1f, %.1f)", f * dd[0] / dd[2] + cx, f * dd[1] / dd[2] + cy)
    }
}
