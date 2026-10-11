package no.descartes.horsel

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.ln

/** Viser forsterkningen per frekvens. Audiogram-merking: høyre øre rød ○, venstre øre blå ×. */
class ProfileView(ctx: Context) : View(ctx) {
    var profile: Profile? = null
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.LINE; strokeWidth = 1 * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.MUTED; textSize = 12 * d }
    private val right = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.RIGHT; strokeWidth = 2.5f * d; style = Paint.Style.STROKE }
    private val left = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.LEFT; strokeWidth = 2.5f * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8 * d, 5 * d), 0f)
    }
    private val leftSolid = Paint(left).apply { pathEffect = null }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.CARD; style = Paint.Style.FILL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.55f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val p = profile ?: return
        val ml = 34 * d; val mr = 12 * d; val mt = 10 * d; val mb = 26 * d
        val w = width.toFloat(); val h = height.toFloat()
        val lo = ln(FREQS.first().toFloat()); val hi = ln(FREQS.last().toFloat())
        fun x(f: Int) = ml + (ln(f.toFloat()) - lo) / (hi - lo) * (w - ml - mr)
        fun y(g: Float) = mt + (1f - g / MAX_GAIN) * (h - mt - mb)

        label.textAlign = Paint.Align.RIGHT
        for (g in 0..MAX_GAIN.toInt() step 10) {
            c.drawLine(ml, y(g.toFloat()), w - mr, y(g.toFloat()), grid)
            c.drawText("+$g", ml - 6 * d, y(g.toFloat()) + 4 * d, label)
        }
        label.textAlign = Paint.Align.CENTER
        for (f in FREQS) {
            if (f == 1500 || f == 3000 || f == 6000) continue
            val t = if (f >= 1000) "${f / 1000}k" else "$f"
            c.drawText(t, x(f), h - 8 * d, label)
        }

        fun curve(g: FloatArray, paint: Paint) {
            val path = Path()
            FREQS.forEachIndexed { i, f -> if (i == 0) path.moveTo(x(f), y(g[i])) else path.lineTo(x(f), y(g[i])) }
            c.drawPath(path, paint)
        }
        curve(p.gR, right)
        curve(p.gL, left)
        val r = 6 * d
        FREQS.forEachIndexed { i, f ->
            val cx = x(f); val cy = y(p.gR[i])
            c.drawCircle(cx, cy, r, fill)
            c.drawCircle(cx, cy, r, right)
            val lx = x(f); val ly = y(p.gL[i])
            c.drawLine(lx - r, ly - r, lx + r, ly + r, leftSolid)
            c.drawLine(lx + r, ly - r, lx - r, ly + r, leftSolid)
        }
    }
}
