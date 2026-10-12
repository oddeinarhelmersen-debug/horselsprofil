package no.descartes.horsel

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Lager tekst til Equalizer APO (Windows) fra lydprofilen. */
object ApoExport {
    /** Frekvenser kurven skrives ut i. Equalizer APO interpolerer mellom punktene. */
    private val POINTS = doubleArrayOf(
        20.0, 60.0, 100.0, 125.0, 150.0, 180.0, 250.0, 350.0, 500.0, 700.0,
        1000.0, 1500.0, 2000.0, 3000.0, 4000.0, 6000.0, 8000.0, 11300.0, 16000.0, 20000.0
    )

    private fun curve(p: Profile, g: FloatArray): DoubleArray {
        val s = p.musicStrength / 100.0
        return DoubleArray(POINTS.size) { i ->
            val f = POINTS[i]
            (Profile.interp(g, f) * s + p.musicBass * Profile.bassWeight(f)).coerceIn(-15.0, 36.0)
        }
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private fun line(c: DoubleArray) =
        "GraphicEQ: " + POINTS.indices.joinToString("; ") { "${POINTS[it].roundToInt()} ${fmt(c[it])}" }

    fun build(p: Profile): String {
        val l = curve(p, p.eff(0))
        val r = curve(p, p.eff(1))
        val avg = DoubleArray(POINTS.size) { (l[it] + r[it]) / 2.0 }
        val maxG = max(l.maxOrNull() ?: 0.0, r.maxOrNull() ?: 0.0)
        // Demp signalet før forsterkningen så det ikke klipper. Skru opp på forsterkeren i stedet.
        val preamp = -ceil(maxG * 0.6).coerceIn(0.0, 18.0)
        val test = p.tests.getOrNull(p.activeTest)?.name ?: "ingen lagret test"
        val date = SimpleDateFormat("d.M.yyyy HH:mm", Locale("nb", "NO")).format(Date())

        return buildString {
            appendLine("# Hørselsprofil – eksport til Equalizer APO")
            appendLine("# Laget $date fra «$test». Styrke ${p.musicStrength} %, bass +${p.musicBass} dB.")
            appendLine("# Lim inn i C:\\Program Files\\EqualizerAPO\\config\\config.txt (erstatt innholdet).")
            appendLine("# Blokk A er på: samme kurve på begge kanaler, best for høyttalere.")
            appendLine("# For hodetelefoner: sett # foran linjene i blokk A, og fjern # foran linjene i blokk B.")
            appendLine("# Knaser det på høye lyder: gjør Preamp-tallet mer negativt (f.eks. -${(-preamp + 3).roundToInt()}).")
            appendLine()
            appendLine("Preamp: ${fmt(preamp)} dB")
            appendLine()
            appendLine("# --- Blokk A: begge ører (snitt) ---")
            appendLine("Channel: all")
            appendLine(line(avg))
            appendLine()
            appendLine("# --- Blokk B: per øre (hodetelefoner) ---")
            appendLine("# Channel: L")
            appendLine("# " + line(l))
            appendLine("# Channel: R")
            appendLine("# " + line(r))
            appendLine("# Channel: all")
        }
    }
}
