package no.descartes.horsel

import android.content.Context
import kotlin.math.ln
import kotlin.math.roundToInt

/** Testfrekvensene i hørselstesten (Hz). */
val FREQS = intArrayOf(250, 500, 1000, 1500, 2000, 3000, 4000, 6000, 8000)

const val MAX_GAIN = 30f

/**
 * Hørselsprofilen. Kanal 0 = venstre øre, kanal 1 = høyre øre.
 * Tersklene er i dBFS målt med EarPods i øret, og forsterkningen i dB per testfrekvens.
 */
class Profile {
    var thrL: FloatArray? = null
    var thrR: FloatArray? = null
    var gL = FloatArray(FREQS.size)
    var gR = FloatArray(FREQS.size)
    var testVolumePct = 0

    var musicOn = false
    var musicStrength = 100
    var musicPerApp = false

    var micGain = 0
    var micOut = 0
    var micStrength = 100
    var micComp = true
    var micNs = false

    fun gains(ch: Int) = if (ch == 0) gL else gR

    /** Lager forsterkning fra tersklene: halvparten av avstanden til det beste punktet (halv-gevinst-regelen). */
    fun gainsFromThresholds() {
        val l = thrL ?: return
        val r = thrR ?: return
        val best = minOf(l.minOrNull() ?: 0f, r.minOrNull() ?: 0f)
        for (i in FREQS.indices) {
            gL[i] = ((l[i] - best) * 0.5f).roundToInt().toFloat().coerceIn(0f, MAX_GAIN)
            gR[i] = ((r[i] - best) * 0.5f).roundToInt().toFloat().coerceIn(0f, MAX_GAIN)
        }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("thrL", thrL?.joinToString(","))
            .putString("thrR", thrR?.joinToString(","))
            .putString("gL", gL.joinToString(","))
            .putString("gR", gR.joinToString(","))
            .putInt("testVolumePct", testVolumePct)
            .putBoolean("musicOn", musicOn)
            .putInt("musicStrength", musicStrength)
            .putBoolean("musicPerApp", musicPerApp)
            .putInt("micGain", micGain)
            .putInt("micOut", micOut)
            .putInt("micStrength", micStrength)
            .putBoolean("micComp", micComp)
            .putBoolean("micNs", micNs)
            .apply()
    }

    companion object {
        private const val PREFS = "horselsprofil"

        private fun parse(s: String?): FloatArray? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
            return if (parts.size == FREQS.size) parts.toFloatArray() else null
        }

        fun load(ctx: Context): Profile {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Profile().apply {
                thrL = parse(sp.getString("thrL", null))
                thrR = parse(sp.getString("thrR", null))
                parse(sp.getString("gL", null))?.let { gL = it }
                parse(sp.getString("gR", null))?.let { gR = it }
                testVolumePct = sp.getInt("testVolumePct", 0)
                musicOn = sp.getBoolean("musicOn", false)
                musicStrength = sp.getInt("musicStrength", 100)
                musicPerApp = sp.getBoolean("musicPerApp", false)
                micGain = sp.getInt("micGain", 0)
                micOut = sp.getInt("micOut", 0)
                micStrength = sp.getInt("micStrength", 100)
                micComp = sp.getBoolean("micComp", true)
                micNs = sp.getBoolean("micNs", false)
            }
        }

        /**
         * Forsterkning (dB) ved vilkårlig frekvens, lineært interpolert på log-frekvens.
         * Under 250 Hz faller den til 0 ved 125 Hz, over 8 kHz til 0 ved 16 kHz,
         * så vi ikke forsterker dur og sus utenfor det som er målt.
         */
        fun interp(g: FloatArray, f: Double): Double {
            val first = FREQS.first().toDouble()
            val last = FREQS.last().toDouble()
            if (f <= first) {
                val k = (ln(f / (first / 2)) / ln(2.0)).coerceIn(0.0, 1.0)
                return g.first() * k
            }
            if (f >= last) {
                val k = (1.0 - ln(f / last) / ln(2.0)).coerceIn(0.0, 1.0)
                return g.last() * k
            }
            for (i in 0 until FREQS.size - 1) {
                val f0 = FREQS[i].toDouble()
                val f1 = FREQS[i + 1].toDouble()
                if (f < f1) {
                    val t = ln(f / f0) / ln(f1 / f0)
                    return g[i] + (g[i + 1] - g[i]) * t
                }
            }
            return g.last().toDouble()
        }
    }
}
