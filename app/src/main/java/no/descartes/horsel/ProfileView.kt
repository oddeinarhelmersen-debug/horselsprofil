package no.descartes.horsel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/** Felles tegnehjelp for diagrammene: log-frekvens vannrett, dB loddrett. */
abstract class FreqChart(ctx: Context) : View(ctx) {
    protected val d = resources.displayMetrics.density
    protected val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.LINE; strokeWidth = 1 * d }
    protected val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.MUTED; strokeWidth = 1.5f * d }
    protected val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.MUTED; textSize = 12 * d }
    protected val ml get() = 38 * d
    protected val mr get() = 14 * d
    protected val mt get() = 12 * d
    protected val mb get() = 26 * d
    protected var yMin = 0f
    protected var yMax = 30f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.55f).toInt())
    }

    private val lo get() = ln(FREQS.first().toFloat() / 1.15f)
    private val hi get() = ln(FREQS.last().toFloat() * 1.15f)
    protected fun x(f: Int) = ml + (ln(f.toFloat()) - lo) / (hi - lo) * (width - ml - mr)
    protected fun y(g: Float) = mt + (1f - (g - yMin) / (yMax - yMin)) * (height - mt - mb)
    protected fun gFromY(py: Float) = yMin + (1f - (py - mt) / (height - mt - mb)) * (yMax - yMin)

    protected fun drawAxes(c: Canvas, step: Int) {
        label.textAlign = Paint.Align.RIGHT
        var g = yMin.toInt()
        while (g <= yMax.toInt()) {
            c.drawLine(ml, y(g.toFloat()), width - mr, y(g.toFloat()), if (g == 0) zero else grid)
            c.drawText(if (g > 0) "+$g" else "$g", ml - 6 * d, y(g.toFloat()) + 4 * d, label)
            g += step
        }
        label.textAlign = Paint.Align.CENTER
        for (f in FREQS) {
            if (f == 1500 || f == 3000 || f == 6000) continue
            c.drawText(if (f >= 1000) "${f / 1000}k" else "$f", x(f), height - 8 * d, label)
        }
    }
}

/** Resultatet: samlet forsterkning per øre. Audiogram-merking: høyre øre rød ○, venstre øre blå ×. */
class ProfileView(ctx: Context) : FreqChart(ctx) {
    var profile: Profile? = null
        set(v) { field = v; invalidate() }

    private val right = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.RIGHT; strokeWidth = 2.5f * d; style = Paint.Style.STROKE }
    private val left = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.LEFT; strokeWidth = 2.5f * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8 * d, 5 * d), 0f)
    }
    private val leftSolid = Paint(left).apply { pathEffect = null }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.CARD; style = Paint.Style.FILL }

    override fun onDraw(c: Canvas) {
        val p = profile ?: return
        val l = p.eff(0); val r = p.eff(1)
        val all = l + r
        yMin = minOf(0f, floor((all.minOrNull() ?: 0f) / 10f) * 10f)
        yMax = maxOf(30f, ceil((all.maxOrNull() ?: 0f) / 10f) * 10f)
        drawAxes(c, 10)
        fun curve(g: FloatArray, paint: Paint) {
            val path = Path()
            FREQS.forEachIndexed { i, f -> if (i == 0) path.moveTo(x(f), y(g[i])) else path.lineTo(x(f), y(g[i])) }
            c.drawPath(path, paint)
        }
        curve(r, right)
        curve(l, left)
        val rr = 6 * d
        FREQS.forEachIndexed { i, f ->
            val cx = x(f)
            c.drawCircle(cx, y(r[i]), rr, fill)
            c.drawCircle(cx, y(r[i]), rr, right)
            val ly = y(l[i])
            c.drawLine(cx - rr, ly - rr, cx + rr, ly + rr, leftSolid)
            c.drawLine(cx + rr, ly - rr, cx - rr, ly + rr, leftSolid)
        }
    }
}

/**
 * Felles justeringskurve for begge ørene. Dra et punkt opp eller ned med fingeren.
 * Dra langs streken under diagrammet (ikke på et punkt) for å flytte hele kurven.
 */
class AdjustView(ctx: Context) : FreqChart(ctx) {
    var profile: Profile? = null
        set(v) { field = v; invalidate() }
    var onChange: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.ACCENT; strokeWidth = 3 * d; style = Paint.Style.STROKE }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.ACCENT; style = Paint.Style.FILL }
    private val knobActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.WARN; style = Paint.Style.FILL }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.FG; textSize = 14 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    private var active = -1        // punktet som dras, eller -2 = hele kurven
    private var startY = 0f
    private var startAdj = FloatArray(FREQS.size)

    init { yMin = -ADJ_RANGE; yMax = ADJ_RANGE }

    override fun onDraw(c: Canvas) {
        val p = profile ?: return
        drawAxes(c, 5)
        val path = Path()
        FREQS.forEachIndexed { i, f -> if (i == 0) path.moveTo(x(f), y(p.adj[i])) else path.lineTo(x(f), y(p.adj[i])) }
        c.drawPath(path, line)
        FREQS.forEachIndexed { i, f ->
            val big = i == active || active == -2
            c.drawCircle(x(f), y(p.adj[i]), (if (big) 11 else 9) * d, if (big) knobActive else knob)
            if (i == active) {
                val v = p.adj[i].roundToInt()
                c.drawText(if (v > 0) "+$v" else "$v", x(f), y(p.adj[i]) - 16 * d, value)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = profile ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // Nærmeste punkt vannrett; er fingeren langt fra punktet loddrett, flyttes hele kurven.
                var best = 0; var bd = Float.MAX_VALUE
                FREQS.forEachIndexed { i, f -> val dd = abs(x(f) - e.x); if (dd < bd) { bd = dd; best = i } }
                active = if (abs(y(p.adj[best]) - e.y) < 40 * d) best else -2
                startY = e.y
                startAdj = p.adj.copyOf()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dg = gFromY(e.y) - gFromY(startY)
                if (active >= 0) {
                    p.adj[active] = (startAdj[active] + dg).roundToInt().toFloat().coerceIn(-ADJ_RANGE, ADJ_RANGE)
                } else if (active == -2) {
                    for (i in p.adj.indices) p.adj[i] = (startAdj[i] + dg).roundToInt().toFloat().coerceIn(-ADJ_RANGE, ADJ_RANGE)
                }
                invalidate()
                onChange?.invoke()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                active = -1
                invalidate()
                onCommit?.invoke()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
