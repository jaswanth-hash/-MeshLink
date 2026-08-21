package com.meshlink.simulation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas view of the **simulated** topology with optional packet animation.
 * Not a Bluetooth / Nearby radar.
 */
class SimulationGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var nodes: List<SimNode> = emptyList()
    private var links: List<SimLink> = emptyList()
    private var connectedIds: Set<String> = emptySet()
    private var highlightedPath: List<String> = emptyList()
    private var packetProgress: Float = -1f

    private var packetAnimator: ValueAnimator? = null
    private var onPacketFinished: (() -> Unit)? = null

    private val linkUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF78909C.toInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val linkDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0BEC5.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val activeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC8E6C9.toInt()
        style = Paint.Style.FILL
    }
    private val disconnectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFE0B2.toInt()
        style = Paint.Style.FILL
    }
    private val disabledFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCFD8DC.toInt()
        style = Paint.Style.FILL
    }
    private val activeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val disconnectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEF6C00.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val disabledStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF78909C.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212121.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 34f
        isFakeBoldText = true
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6A1B9A.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 26f
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF546E7A.toInt()
        textAlign = Paint.Align.LEFT
        textSize = 22f
    }
    private val packetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6A1B9A.toInt()
        style = Paint.Style.FILL
    }
    private val packetStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    fun updateGraph(
        nodes: List<SimNode>,
        links: List<SimLink>,
        connectedIds: Set<String>,
        highlightedPath: List<String> = emptyList()
    ) {
        this.nodes = nodes
        this.links = links
        this.connectedIds = connectedIds
        this.highlightedPath = highlightedPath
        invalidate()
    }

    fun stopPacketAnimation() {
        packetAnimator?.cancel()
        packetAnimator = null
        packetProgress = -1f
        onPacketFinished = null
        invalidate()
    }

    /**
     * Animates a packet along [highlightedPath]. Calls [onFinished] when done.
     */
    fun animatePacketAlongRoute(
        durationMs: Long = 1200L,
        onFinished: () -> Unit
    ) {
        stopPacketAnimation()
        if (highlightedPath.size < 2) {
            onFinished()
            return
        }
        onPacketFinished = onFinished
        packetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                packetProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    packetProgress = 1f
                    invalidate()
                    val cb = onPacketFinished
                    onPacketFinished = null
                    cb?.invoke()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    onPacketFinished = null
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        stopPacketAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText(
            "SIMULATION ONLY — not Bluetooth / Nearby",
            width / 2f,
            28f,
            bannerPaint
        )
        canvas.drawText(
            "Green=active  Orange=disconnected  Gray=disabled",
            16f,
            height - 16f,
            legendPaint
        )

        if (nodes.isEmpty()) return

        val positions = layoutPositions()
        val routeEdges = highlightedPath.zipWithNext().map { (a, b) ->
            SimLink(a, b).normalized()
        }.toSet()
        val nodeMap = nodes.associateBy { it.id }

        for (link in links) {
            val p1 = positions[link.a] ?: continue
            val p2 = positions[link.b] ?: continue
            val aOk = nodeMap[link.a]?.enabled == true
            val bOk = nodeMap[link.b]?.enabled == true
            val paint = when {
                link.normalized() in routeEdges -> routePaint
                aOk && bOk -> linkUpPaint
                else -> linkDownPaint
            }
            canvas.drawLine(p1.first, p1.second, p2.first, p2.second, paint)
        }

        if (highlightedPath.size >= 2) {
            val path = Path()
            highlightedPath.forEachIndexed { index, id ->
                val point = positions[id] ?: return@forEachIndexed
                if (index == 0) path.moveTo(point.first, point.second)
                else path.lineTo(point.first, point.second)
            }
            canvas.drawPath(path, routePaint)
        }

        val radius = min(width, height) * 0.07f
        for (node in nodes) {
            val point = positions[node.id] ?: continue
            val fill: Paint
            val stroke: Paint
            val label: String
            when {
                !node.enabled -> {
                    fill = disabledFill
                    stroke = disabledStroke
                    label = "${node.id} ✕"
                }
                node.id in connectedIds -> {
                    fill = activeFill
                    stroke = activeStroke
                    label = node.id
                }
                else -> {
                    fill = disconnectedFill
                    stroke = disconnectedStroke
                    label = "${node.id} ○"
                }
            }
            canvas.drawCircle(point.first, point.second, radius, fill)
            canvas.drawCircle(point.first, point.second, radius, stroke)
            canvas.drawText(
                label,
                point.first,
                point.second + textPaint.textSize / 3f,
                textPaint
            )
        }

        if (packetProgress >= 0f && highlightedPath.size >= 2) {
            val point = pointAlongPath(positions, highlightedPath, packetProgress) ?: return
            val pr = radius * 0.45f
            canvas.drawCircle(point.first, point.second, pr, packetPaint)
            canvas.drawCircle(point.first, point.second, pr, packetStroke)
        }
    }

    private fun pointAlongPath(
        positions: Map<String, Pair<Float, Float>>,
        path: List<String>,
        t: Float
    ): Pair<Float, Float>? {
        if (path.size < 2) return positions[path.firstOrNull()]
        val segments = path.zipWithNext().mapNotNull { (a, b) ->
            val p1 = positions[a] ?: return@mapNotNull null
            val p2 = positions[b] ?: return@mapNotNull null
            val dx = p2.first - p1.first
            val dy = p2.second - p1.second
            val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
            Triple(p1, p2, len)
        }
        if (segments.isEmpty()) return null
        val total = segments.sumOf { it.third.toDouble() }.toFloat()
        var remaining = (t.coerceIn(0f, 1f)) * total
        for ((p1, p2, len) in segments) {
            if (remaining <= len) {
                val local = remaining / len
                return (p1.first + (p2.first - p1.first) * local) to
                    (p1.second + (p2.second - p1.second) * local)
            }
            remaining -= len
        }
        return segments.last().second
    }

    private fun layoutPositions(): Map<String, Pair<Float, Float>> {
        val cx = width / 2f
        val cy = height / 2f + 8f
        val radius = min(width, height) * 0.30f
        val count = nodes.size
        if (count == 0) return emptyMap()
        return nodes.mapIndexed { index, node ->
            val angle = (2.0 * Math.PI * index / count) - Math.PI / 2.0
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            node.id to (x to y)
        }.toMap()
    }
}
